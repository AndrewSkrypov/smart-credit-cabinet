import argparse
from pathlib import Path
import unittest

from configure import (
    AUDIT_PATH, KUBERNETES_SECRETS, VaultError, configure, dev_approle_source, gitlab_role, policies, render_policy,
)


ORIGIN = "https://vault.pump.internal:8200"


class FakeVault:
    def __init__(self):
        self.audit = {}
        self.mounts = {}
        self.auth = {}
        self.settings = {}
        self.calls = []

    def request(self, method, path, payload=None):
        self.calls.append((method, path))
        if method == "GET":
            data = {"sys/audit": self.audit, "sys/mounts": self.mounts,
                    "sys/auth": self.auth}.get(path, self.settings.get(path, {}))
            if path == "auth/pump-dev-approle/role/cabinet-dev" and data:
                data = {**data, "token_bound_cidrs": [cidr.removesuffix("/32") for cidr in data["token_bound_cidrs"]]}
            return {"data": data}
        if path == "sys/audit/pump-file":
            self.audit["pump-file/"] = payload
        elif path == "sys/mounts/pump":
            self.mounts["pump/"] = payload
        elif path.startswith("sys/auth/"):
            self.auth[path.removeprefix("sys/auth/") + "/"] = payload
        else:
            self.settings[path] = payload
        return {}


class ConfigureTest(unittest.TestCase):
    def test_install_orders_audit_and_retains_existing_mounts_on_retry(self):
        vault = FakeVault()
        configure(vault, ORIGIN)
        configure(vault, ORIGIN)
        self.assertEqual(vault.calls[:2], [("GET", "sys/audit"), ("POST", "sys/audit/pump-file")])
        self.assertEqual(vault.audit["pump-file/"]["options"]["file_path"], AUDIT_PATH)
        for path in ("sys/audit/pump-file", "sys/mounts/pump", "sys/auth/pump-gitlab"):
            self.assertEqual(vault.calls.count(("POST", path)), 1)
        self.assertEqual(vault.settings["pump/config"], {"cas_required": True})
        self.assertNotIn("pump-dev-approle/", vault.auth)

    def test_fails_closed_for_wrong_mount_or_wrong_audit_path(self):
        vault = FakeVault()
        vault.mounts["pump/"] = {"type": "kv", "options": {"version": "1"}}
        with self.assertRaisesRegex(VaultError, "not KV v2"):
            configure(vault, ORIGIN)
        self.assertNotIn(("POST", "pump/config"), vault.calls)
        vault = FakeVault()
        vault.audit["pump-file/"] = {"type": "file", "options": {"file_path": "/tmp/audit.log"}}
        with self.assertRaisesRegex(VaultError, "audit device"):
            configure(vault, ORIGIN)
        self.assertEqual(vault.calls, [("GET", "sys/audit")])

    def test_ci_roles_pin_project_ref_environment_and_runner(self):
        for env, runner, name in (("dev", 2, "pump-ci-dev"), ("production", 4, "pump-ci-prod")):
            role = gitlab_role(ORIGIN, env, runner)
            self.assertEqual(role["bound_audiences"], [ORIGIN])
            self.assertEqual(role["bound_claims"]["job_project_id"], "1")
            self.assertEqual(role["bound_claims"]["ref_path"], "refs/heads/feat/final-platform-release")
            self.assertEqual(role["bound_claims"]["ref_protected"], "true")
            self.assertEqual(role["bound_claims"]["runner_id"], runner)
            self.assertEqual(role["bound_claims"]["environment"], env)
            self.assertEqual(role["bound_claims"]["environment_action"], "start")
            self.assertEqual(role["token_policies"], [name])
            self.assertTrue(role["token_no_default_policy"])
            self.assertEqual(role["token_explicit_max_ttl"], 180)

    def test_readers_and_initial_seeders_have_exact_paths(self):
        rules = policies()
        self.assertEqual(rules["pump-ci-dev"]["pump/data/dev/ci-deploy"], ["read"])
        self.assertNotIn("pump/data/prod/ci-deploy", rules["pump-ci-dev"])
        self.assertEqual(rules["pump-seed-ci-dev"]["pump/data/dev/ci-deploy"], ["create", "read"])
        self.assertEqual(set(k for k in rules["pump-prod-kubernetes"] if k.startswith("pump/")),
                         {f"pump/data/prod/kubernetes/{name}" for name in KUBERNETES_SECRETS})
        self.assertNotIn("sys/storage/raft/snapshot-force", rules["pump-raft-snapshot"])
        self.assertFalse(any("*" in path or "list" in actions or "delete" in actions
                             for policy in rules.values() for path, actions in policy.items()))

    def test_standalone_ci_policy_matches_provisioned_policy(self):
        directory = Path(__file__).resolve().parents[2] / "ci" / "gitlab" / "vault"
        rules = policies()
        for name, profile in (("pump-ci-dev", "dev"), ("pump-ci-prod", "production")):
            with self.subTest(profile=profile):
                standalone = (directory / f"{profile}.hcl").read_text()
                self.assertEqual(standalone.split(), render_policy(rules[name]).split())

    def test_dev_approle_pins_one_egress_and_accepts_equivalent_readback(self):
        vault = FakeVault()
        source = "85.92.111.168/32"
        configure(vault, ORIGIN, source)
        configure(vault, ORIGIN, source)
        self.assertEqual(vault.calls.count(("POST", "sys/auth/pump-dev-approle")), 1)
        role = vault.settings["auth/pump-dev-approle/role/cabinet-dev"]
        self.assertEqual(role["token_policies"], ["pump-dev-compose"])
        self.assertTrue(role["token_no_default_policy"])
        self.assertEqual(role["secret_id_num_uses"], 1)
        self.assertEqual(role["secret_id_ttl"], 300)
        self.assertEqual(role["token_explicit_max_ttl"], 180)
        self.assertEqual(role["secret_id_bound_cidrs"], [source])
        self.assertEqual(role["token_bound_cidrs"], [source])

    def test_dev_approle_rejects_private_or_broad_source(self):
        for unsafe_source in ("85.92.111.0/24", "10.129.0.33", "0.0.0.0/0", "2001:db8::1"):
            with self.subTest(source=unsafe_source), self.assertRaises(argparse.ArgumentTypeError):
                dev_approle_source(unsafe_source)
