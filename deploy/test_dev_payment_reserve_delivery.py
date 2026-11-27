"""Dev simulation selection is versioned, project-bound and independent of demo accounts."""
from contextlib import ExitStack
from hashlib import sha256
import json
import os
from pathlib import Path
import runpy
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import yaml

import common
import dev_payment_reserve as policy

ROOT = Path(__file__).resolve().parents[1]
REVISION = '1' * 40


class ComposeLoader(yaml.SafeLoader):
    pass


ComposeLoader.add_constructor('!override', lambda loader, node: loader.construct_sequence(node))


def environment(domain='dev.credit-cabinet.ru'):
    keys = ['POSTGRES_PASSWORD', 'JWT_SECRET', 'SIM_API_KEY', 'IDENTITY_LINK_KEY', 'VALKEY_PASSWORD']
    keys += [service + suffix for service in ('IDENTITY', 'CREDIT', 'ENGAGEMENT')
             for suffix in ('_DB_PASSWORD', '_MIGRATION_PASSWORD')]
    values = {key: sha256(key.encode()).hexdigest() for key in keys}
    values.update(CABINET_DOMAIN=domain, PUBLIC_CABINET_URL='https://' + domain,
                  CABINET_RELEASE=REVISION, DEMO_ACCOUNTS='false', SIM_CONTROL_ENABLED='false')
    return values


class DevReserveConfiguration(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.dict(os.environ, {}, clear=True))
        self.run = self.stack.enter_context(patch('subprocess.run', side_effect=AssertionError('no external processes')))
        self.release = SimpleNamespace(revision=REVISION, alpha_dev=None,
                                       assessment=None, servicing=None, documents=None,
                                       environment=lambda: {})
        self.stack.enter_context(patch.object(common, 'RegistryRelease', return_value=self.release))
        self.stack.enter_context(patch.object(common, 'requested_mode', return_value='disabled'))
        self.hooks = [self.stack.enter_context(patch.object(hook, 'open', return_value=None))
                      for hook in (common.AssessmentDev, common.ServicingDev, common.DocumentsDev)]

    def deployment(self, project='cabinet-dev', *, local=False, values=None, release=True, historical_backup=False):
        values = environment('localhost' if local else 'dev.credit-cabinet.ru') if values is None else values
        body = ''.join(f'{key}={value}\n' for key, value in values.items()).encode()
        return common.Deployment(project, self.root / 'synthetic.env', local=local,
                                 release_manifest=self.root / 'release.json' if release else None,
                                 env_contents=body, historical_backup=historical_backup)

    def overlay_selected(self, deployment):
        return str(ROOT / policy.OVERLAY) in deployment.command

    def test_default_false_without_the_exact_versioned_dev_scope(self):
        for project, local, release, historical, domain in (
            ('cabinet-prod', False, None, False, 'credit.example.invalid'),
            ('cabinet-dev-restore-123', False, object(), False, 'dev.credit-cabinet.ru'),
            ('cabinet-dev-demo', False, object(), False, 'demo.dev.credit-cabinet.ru'),
            ('cabinet-dev-other', False, object(), False, 'dev.credit-cabinet.ru'),
            ('cabinet-dev', True, object(), False, 'localhost'),
            ('cabinet-dev', False, None, False, 'dev.credit-cabinet.ru'),
            ('cabinet-dev', False, object(), True, 'dev.credit-cabinet.ru'),
            ('cabinet-dev', False, object(), False, 'elsewhere.example.invalid'),
        ):
            with self.subTest(project=project, local=local, historical=historical, domain=domain):
                self.assertFalse(policy.enabled(project, local, environment(domain), release, historical))

    def test_only_exact_normal_dev_selects_literal_overlay_without_env_changes(self):
        values = environment()
        self.assertNotIn(policy.FLAG, values)
        before = dict(values)
        deployed = self.deployment(values=values)
        self.assertTrue(deployed.dev_payment_reserve_simulation)
        self.assertTrue(self.overlay_selected(deployed))
        self.assertEqual(values, before)
        self.assertEqual(deployed.environment[policy.FLAG], 'false')
        self.assertEqual(deployed.environment[policy.CONTEXT], 'cabinet-dev')
        self.assertEqual(deployed.values['DEMO_ACCOUNTS'], 'false')
        self.assertEqual(deployed.values['SIM_CONTROL_ENABLED'], 'false')
        self.assertEqual(deployed.command[deployed.command.index('-p') + 1], 'cabinet-dev')
        self.run.assert_not_called()

    def test_canonical_origin_with_optional_trailing_slash_is_bound(self):
        values = environment()
        values['PUBLIC_CABINET_URL'] += '/'
        self.assertTrue(self.overlay_selected(self.deployment(values=values)))
        for origin in ('http://dev.credit-cabinet.ru', 'https://foreign.example.invalid',
                       'https://dev.credit-cabinet.ru/path', 'https://dev.credit-cabinet.ru?enabled=1'):
            with self.subTest(origin=origin):
                self.assertFalse(policy.enabled('cabinet-dev', False, {**values, 'PUBLIC_CABINET_URL': origin}, object()))

    def test_prod_local_foreign_dev_and_historical_config_do_not_select_overlay(self):
        for project, local, historical in (('cabinet-prod', False, False),
                                          ('cabinet-dev-restore-123', False, False),
                                          ('cabinet-dev-other', False, False),
                                          ('cabinet-dev', True, False),
                                          ('cabinet-dev', False, True)):
            with self.subTest(project=project, local=local, historical=historical):
                deployed = self.deployment(project, local=local, historical_backup=historical)
                self.assertFalse(deployed.dev_payment_reserve_simulation)
                self.assertFalse(self.overlay_selected(deployed))
                self.assertEqual(deployed.environment[policy.FLAG], 'false')

    def test_envfile_enable_attempts_fail_before_any_runtime_hook(self):
        for project in ('cabinet-dev', 'cabinet-prod', 'cabinet-dev-restore-123', 'cabinet-dev-other', 'cabinet-dev-demo'):
            with self.subTest(project=project), self.assertRaisesRegex(ValueError, 'не ENV/CI'):
                self.deployment(project, values={**environment(), policy.FLAG: 'true'})
        for hook in self.hooks:
            hook.assert_not_called()
        self.run.assert_not_called()

    def test_shell_ci_enable_attempt_is_rejected_even_if_envfile_says_false(self):
        for project in ('cabinet-dev', 'cabinet-prod', 'cabinet-dev-other'):
            with self.subTest(project=project), patch.dict(os.environ, {policy.FLAG: 'true'}), \
                    self.assertRaisesRegex(ValueError, 'не ENV/CI'):
                self.deployment(project, values={**environment(), policy.FLAG: 'false'})
        for hook in self.hooks:
            hook.assert_not_called()
        self.run.assert_not_called()

    def test_context_cannot_be_injected_by_envfile_or_shell(self):
        for context in ('cabinet-dev', 'cabinet-prod', 'cabinet-dev-restore-1'):
            with self.subTest(context=context), self.assertRaisesRegex(ValueError, 'контроллером'):
                self.deployment(values={**environment(), policy.CONTEXT: context})
            with self.subTest(shell=context), patch.dict(os.environ, {policy.CONTEXT: context}), \
                    self.assertRaisesRegex(ValueError, 'контроллером'):
                self.deployment('cabinet-prod')
        self.run.assert_not_called()

    def test_nonboolean_flags_and_changed_demo_controls_are_rejected(self):
        for invalid in ('1', 'TRUE', '', 'yes'):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, 'true или false'):
                self.deployment(values={**environment(), policy.FLAG: invalid})
        for key in ('DEMO_ACCOUNTS', 'SIM_CONTROL_ENABLED'):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'отключённых'):
                self.deployment(values={**environment(), key: 'true'})

    def test_yaml_defaults_and_versioned_permission_are_literals(self):
        base = yaml.safe_load((ROOT / 'docker-compose.yml').read_text())
        prod = yaml.load((ROOT / 'deploy/compose.yaml').read_text(), Loader=ComposeLoader)
        dev = yaml.safe_load((ROOT / policy.OVERLAY).read_text())
        for configuration in (base, prod):
            self.assertEqual(configuration['services']['credit']['environment'][policy.FLAG], 'false')
        permission = dev['services']['credit']['environment']
        self.assertEqual(permission, {policy.FLAG: 'true', policy.CONTEXT: 'cabinet-dev', 'DEMO_ACCOUNTS': 'false'})
        self.assertNotIn('${', (ROOT / policy.OVERLAY).read_text())
        self.assertEqual(set(dev['services']), {'credit'})
        self.assertEqual(base['services']['credit']['environment'][policy.CONTEXT], '')
        self.assertEqual(prod['services']['credit']['environment'][policy.CONTEXT], 'cabinet-prod')
        demo = yaml.safe_load((ROOT / 'deploy/compose.demo.yaml').read_text())['services']['credit']['environment']
        self.assertEqual(demo[policy.FLAG], 'false')
        self.assertEqual(demo[policy.CONTEXT], 'cabinet-dev-demo')
        self.assertEqual(demo['DEMO_ACCOUNTS'], 'true')

    def test_environment_generator_keeps_flag_and_demo_controls_off_without_enable_option(self):
        output = self.root / 'generated.env'
        with patch.object(sys, 'argv', ['configure.py', '--domain', 'credit.example.invalid', '--output', str(output)]), \
                patch('subprocess.check_output', return_value=REVISION), patch('builtins.print'):
            runpy.run_path(str(ROOT / 'deploy/configure.py'), run_name='__main__')
        values = dict(line.split('=', 1) for line in output.read_text().splitlines())
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        for key in (policy.FLAG, 'DEMO_ACCOUNTS', 'SIM_CONTROL_ENABLED'):
            self.assertEqual(values[key], 'false')
        self.assertNotIn(policy.CONTEXT, values)


if __name__ == '__main__':
    unittest.main()
