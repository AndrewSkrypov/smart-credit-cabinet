"""Exercise Catalog rollout planning and the flags passed to Helm."""
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from catalog_prod_guard import upgrade_strategy

ROOT = Path(__file__).resolve().parents[2]
REVISION = "1" * 40
BASE = ("identity", "credit", "engagement", "external-sim", "frontend", "postgres", "swagger", "catalog-grpc")


def values(mode, maintenance, *, enabled=True):
    return {"creditMode": mode, "maintenance": maintenance, "enabled": enabled, "revision": REVISION}


def current(mode, maintenance, *, enabled=True):
    return {"app": {"enabled": True}, "catalog": values(mode, maintenance, enabled=enabled),
            "release": {"revision": REVISION}}


def load_verifier():
    spec = importlib.util.spec_from_file_location("verify_prod", ROOT / "ci/gitlab/verify-prod.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class GuardTest(unittest.TestCase):
    def test_unchanged_owner_allows_automatic_rollback(self):
        self.assertEqual(upgrade_strategy(current("local", False), values("local", False), "deployed", ("t", "f")),
                         "automatic")
        self.assertEqual(upgrade_strategy(current("remote", False), values("remote", False), "deployed", ("f", "t")),
                         "automatic")
        self.assertEqual(upgrade_strategy(current("remote", False), values("remote", False), "failed", ("f", "t")),
                         "wait")
        self.assertEqual(upgrade_strategy({}, values("local", False, enabled=False), "missing", ("absent", "absent")),
                         "automatic")

    def test_owner_changes_require_maintenance_and_verified_transfer(self):
        for source, target, flags in (("local", "remote", ("f", "t")), ("remote", "local", ("t", "f"))):
            self.assertEqual(upgrade_strategy(current(source, True), values(target, True), "deployed", flags), "switch")
            for old, new in ((False, False), (False, True), (True, False)):
                with self.assertRaisesRegex(ValueError, "maintenance"):
                    upgrade_strategy(current(source, old), values(target, new), "deployed", flags)
            with self.assertRaisesRegex(ValueError, "history transfer"):
                upgrade_strategy(current(source, True), values(target, True), "deployed", flags[::-1])

    def test_database_mismatch_and_dual_writers_block_rollback(self):
        with self.assertRaisesRegex(ValueError, "history transfer"):
            upgrade_strategy(current("local", False), values("local", False), "deployed", ("f", "t"))
        for flags in (("f", "f"), ("t", "t")):
            with self.assertRaisesRegex(ValueError, "single Catalog writer"):
                upgrade_strategy(current("local", False), values("local", False), "deployed", flags)

    def test_gateway_reopens_only_after_same_staged_revision_succeeds(self):
        self.assertEqual(upgrade_strategy(current("local", False), values("local", True), "deployed", ("t", "f")),
                         "hold")
        self.assertEqual(upgrade_strategy(current("remote", True), values("remote", False), "deployed", ("f", "t")),
                         "open")
        with self.assertRaisesRegex(ValueError, "reopening"):
            upgrade_strategy(current("remote", True), values("remote", False), "failed", ("f", "t"))
        with self.assertRaisesRegex(ValueError, "reopening"):
            upgrade_strategy(current("remote", True), {**values("remote", False), "revision": "2" * 40},
                             "deployed", ("f", "t"))

    def test_retry_uses_the_opening_marker_after_helm_maintenance_is_false(self):
        marker = {"revision": REVISION, "mode": "remote", "enabled": "true"}
        for status in ("deployed", "failed"):
            self.assertEqual(upgrade_strategy(current("remote", False), values("remote", False), status,
                                              ("f", "t"), opening=marker), "open")
        with self.assertRaisesRegex(ValueError, "marked gateway"):
            upgrade_strategy(current("remote", False), {**values("remote", False), "revision": "2" * 40},
                             "deployed", ("f", "t"), opening=marker)
        self.assertEqual(upgrade_strategy(current("remote", False), values("remote", True), "deployed",
                                          ("f", "t"), opening=marker), "hold")

    def test_production_role_can_scale_only_gateway(self):
        source = (ROOT / "ci/gitlab/prod-rbac.yaml").read_text()
        role = next(doc for doc in source.split("\n---\n") if "\nkind: Role\n" in doc)
        rules = re.split(r"(?m)^  - apiGroups: ", role)
        scale = [rule for rule in rules if "resources: [deployments/scale]" in rule]
        self.assertEqual(len(scale), 1)
        self.assertTrue(scale[0].startswith("[apps]\n"))
        self.assertIn("resourceNames: [gateway]", scale[0])
        self.assertIn("verbs: [get, patch, update]", scale[0])

    def test_catalog_oci_image_id_uses_the_pinned_manifest_or_verified_config(self):
        verifier = load_verifier()
        image = "registry.example.invalid/project/catalog-grpc@sha256:" + "a" * 64
        config_digest = "sha256:" + "b" * 64
        self.assertTrue(verifier.catalog_image_matches(image, config_digest, "docker-pullable://" + image))
        self.assertTrue(verifier.catalog_image_matches(image, config_digest, "containerd://" + config_digest))
        self.assertFalse(verifier.catalog_image_matches(image, config_digest, "sha256:" + "c" * 64))
        self.assertFalse(verifier.catalog_image_matches(image, config_digest, image.replace("catalog-grpc", "credit")))

    def test_catalog_report_requires_runtime_mtls_beyond_pod_readiness(self):
        verifier = load_verifier()
        images = {name: "registry.example.invalid/project/" + name + "@sha256:" + "a" * 64 for name in BASE}
        config_digest = "sha256:" + "c" * 64
        release = SimpleNamespace(revision=REVISION, images=images,
                                  catalog={"config_digest": config_digest, "jar_sha256": "b" * 64})

        def kubectl(args):
            name = args[args.index("-l") + 1].rsplit("=", 1)[1]
            component = verifier.WORKLOAD_IMAGES.get(name, name)
            image_id = config_digest if name == "catalog-grpc" else images[component]
            env = [{"name": "CABINET_CATALOG_MODE", "value": "remote"}] if name == "credit" else []
            pod = {
                "metadata": {"annotations": {"cabinet.release/revision": REVISION}, "name": name + "-pod"},
                "spec": {"containers": [{"name": name, "image": images[component], "env": env}]},
                "status": {"containerStatuses": [{"name": name, "ready": True, "imageID": image_id}]},
            }
            return json.dumps({"items": [pod]})

        args = ["verify-prod", "release.json", "https://mock.invalid", "--context", "mock", "--namespace", "cabinet-prod",
                "--catalog-mode", "remote", "--internal-only"]
        with (patch.object(sys, "argv", args), patch.object(verifier, "RegistryRelease", return_value=release),
              patch.object(verifier.subprocess, "check_output", side_effect=kubectl),
              patch.object(verifier, "verify_catalog_deployment") as deployment,
              patch.object(verifier, "probe_mtls") as probe, patch("builtins.print") as output):
            probe.side_effect = RuntimeError("mTLS failed")
            with self.assertRaisesRegex(RuntimeError, "mTLS failed"):
                verifier.main()
            output.assert_not_called()
            probe.side_effect = None
            verifier.main()
            self.assertEqual(deployment.call_count, 2)
            self.assertEqual(probe.call_args.args[0][-5:], ["-i", "credit-pod", "-c", "credit", "--"])
            self.assertEqual(probe.call_args.args[1], "remote")
            self.assertTrue(json.loads(output.call_args.args[0])["catalog"]["mtls"])


class DeployScriptTest(unittest.TestCase):
    def deploy(self, current_values, desired, flags, version, *, fail=False, history_mismatch=False):
        (result, args, _, _, _), = self.deploy_runs(
            current_values, desired, flags, version, {"MOCK_FAILED": str(int(fail)),
                                                     "MOCK_HISTORY_MISMATCH": str(int(history_mismatch))},
        )
        return result, args

    def deploy_runs(self, current_values, desired, flags, version, *runs):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "ci").symlink_to(ROOT / "ci", target_is_directory=True)
            (root / "services").symlink_to(ROOT / "services", target_is_directory=True)
            (root / "release.json").write_text(json.dumps({
                "revision": REVISION,
                "images": {name: "registry.example.invalid/project/" + name + "@sha256:" + "a" * 64 for name in BASE},
                "catalog": {"dev_mode": "local", "jar_sha256": "b" * 64, "config_digest": "sha256:" + "c" * 64},
            }))
            credentials = root / "jury.json"
            credentials.touch()
            log = root / "helm-upgrade.json"
            events = root / "events"
            state = root / "helm-state.json"
            state.write_text(json.dumps({"status": "deployed", "values": current_values}))
            gateway = root / "gateway-replicas"
            gateway.write_text("0" if current_values.get("catalog", {}).get("maintenance") else "2")
            marker = root / "opening-marker.json"
            kubectl = root / "kubectl"
            kubectl.write_text(f"""#!{sys.executable}
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
events = Path(os.environ['MOCK_EVENTS'])
gateway = Path(os.environ['MOCK_GATEWAY'])
marker = Path(os.environ['MOCK_MARKER'])

def note(event):
    with events.open('a') as stream:
        print(event, file=stream)

if 'namespace' in args:
    print('test-uid')
elif 'auth' in args:
    verb = args[args.index('can-i') + 1]
    assert 'deployment/gateway' in args and '--subresource=scale' in args
    note('auth-' + verb)
    raise SystemExit(int(os.environ.get('MOCK_SCALE_FORBIDDEN', '0')))
elif 'configmap' in args:
    assert 'cabinet-catalog-gateway-opening' in args
    if 'get' in args and marker.exists():
        print(marker.read_text())
    elif 'create' in args:
        assert not marker.exists()
        data = dict(arg.split('=', 2)[1:] for arg in args if arg.startswith('--from-literal='))
        marker.write_text(json.dumps(dict(data=data)))
        note('marker-create')
    elif 'delete' in args:
        marker.unlink()
        note('marker-delete')
elif 'scale' in args:
    assert 'deployment/gateway' in args and '--replicas=0' in args
    gateway.write_text('0')
    note('scale-closed')
elif 'exec' in args:
    source = sys.stdin.read()
    if args[-1] == 'history':
        assert "Alternative references a missing product revision" in source
        if os.environ.get('MOCK_HISTORY_MISMATCH') == '1':
            print('Catalog history differs', file=sys.stderr)
            raise SystemExit(1)
    else:
        print(os.environ['MOCK_FLAGS'])
elif 'deployment' in args:
    print(gateway.read_text() if 'gateway' in args else 0)
elif 'pods' not in args:
    raise SystemExit(1)
""")
            helm = root / "helm"
            helm.write_text(f"""#!{sys.executable}
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
cmd = args[0]
state = Path(os.environ['MOCK_STATE'])
previous = json.loads(state.read_text())
if previous['status'] == 'pending-upgrade':
    with Path(os.environ['MOCK_EVENTS']).open('a') as stream:
        print('helm-' + cmd, file=stream)
if cmd == 'upgrade' and os.environ.get('MOCK_SCALE_OWNERSHIP'):
    assert os.environ['MOCK_VERSION'].startswith('v4.')
    if '--server-side=false' not in args:
        print('SSA conflict with kubectl-scale on spec.replicas', file=sys.stderr)
        raise SystemExit(1)
if cmd == 'template':
    print('    ' + os.environ['MOCK_DESIRED'])
elif cmd == 'list':
    print(json.dumps([dict(status=previous['status'])]))
elif cmd == 'get':
    print(json.dumps(previous['values']))
elif cmd == 'version':
    print(os.environ['MOCK_VERSION'])
elif cmd == 'upgrade' and '--dry-run=server' not in args:
    Path(os.environ['MOCK_UPGRADE']).write_text(json.dumps(args))
    with Path(os.environ['MOCK_EVENTS']).open('a') as stream:
        print('upgrade', file=stream)
    desired = json.loads(os.environ['MOCK_DESIRED'])
    failed = int(os.environ.get('MOCK_FAILED', '0'))
    Path(os.environ['MOCK_GATEWAY']).write_text('0' if desired['maintenance'] else '2')
    next_values = dict(app=dict(enabled=True), catalog=desired, release=dict(revision=desired['revision']))
    state.write_text(json.dumps(dict(status='failed' if failed else 'deployed', values=next_values)))
    raise SystemExit(failed)
elif cmd not in ('lint', 'upgrade'):
    raise SystemExit(1)
""")
            python = root / "python3"
            python.write_text(f"""#!{sys.executable}
import os, sys
from pathlib import Path
args = sys.argv[1:]
if args[0] not in ('ci/gitlab/verify-prod.py', 'deploy/verify-jury.py'):
    os.execv(sys.executable, [sys.executable, *args])
kind = 'jury' if args[0] == 'deploy/verify-jury.py' else ('internal' if '--internal-only' in args else 'public')
with Path(os.environ['MOCK_EVENTS']).open('a') as stream:
    print(kind, file=stream)
raise SystemExit(int(os.environ.get('MOCK_FAIL_' + kind.upper(), '0')))
""")
            for executable in (kubectl, helm, python):
                executable.chmod(0o700)
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}",
                       CI_COMMIT_SHA=REVISION, CI_REGISTRY_IMAGE="registry.example.invalid/project",
                       CABINET_PROD_KUBECONFIG="/mock/config", CABINET_PROD_CONTEXT="mock", CABINET_PROD_VALUES="/mock/values",
                       CABINET_PROD_NAMESPACE="cabinet-prod", CABINET_PROD_NAMESPACE_UID="test-uid",
                       CABINET_PROD_JURY_CREDENTIALS=str(credentials), CABINET_PROD_URL="https://mock.invalid",
                       MOCK_DESIRED=json.dumps(desired), MOCK_FLAGS="\n".join(flags), MOCK_VERSION=version,
                       MOCK_UPGRADE=str(log), MOCK_EVENTS=str(events), MOCK_STATE=str(state),
                       MOCK_GATEWAY=str(gateway), MOCK_MARKER=str(marker))
            results = []
            for overrides in runs:
                if overrides.get("MOCK_INTERRUPTED_OPEN"):
                    state.write_text(json.dumps({"status": "pending-upgrade", "values": current("remote", False)}))
                    marker.write_text(json.dumps({"data": {"revision": REVISION, "mode": "remote", "enabled": "true"}}))
                    gateway.write_text("2")
                log.unlink(missing_ok=True)
                events.write_text("")
                result = subprocess.run(["bash", "ci/gitlab/deploy-prod.sh"], text=True, cwd=root,
                                        capture_output=True, env={**env, **overrides})
                args = json.loads(log.read_text()) if log.exists() else []
                results.append((result, args, events.read_text().splitlines(), marker.exists(), gateway.read_text()))
            return results

    def test_same_owner_passes_version_specific_rollback_flag(self):
        for version, flag in (("v3.19.0", "--atomic"), ("v4.3.0", "--rollback-on-failure")):
            with self.subTest(version=version):
                result, args = self.deploy(current("local", False), values("local", False), ("t", "f"), version)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(flag, args)
                self.assertNotIn("--server-side=false", args)

    def test_failed_owner_changes_keep_gateway_in_maintenance_without_automatic_rollback(self):
        for source, target, flags in (("local", "remote", ("f", "t")), ("remote", "local", ("t", "f"))):
            with self.subTest(source=source):
                result, args = self.deploy(current(source, True), values(target, True), flags, "v4.3.0", fail=True)
                self.assertEqual(result.returncode, 1)
                self.assertIn("maintenance remains closed", result.stderr)
                self.assertIn("--wait", args)
                self.assertIn("--server-side=false", args)
                self.assertNotIn("--atomic", args)
                self.assertNotIn("--rollback-on-failure", args)

    def test_failed_public_opening_persists_across_checked_retries(self):
        first, preflight_failure, repeated_failure, accepted = self.deploy_runs(
            current("remote", True), values("remote", False), ("f", "t"), "v4.3.0",
            {"MOCK_FAIL_PUBLIC": "1"}, {"MOCK_FAIL_INTERNAL": "1"}, {"MOCK_FAIL_JURY": "1"}, {},
        )
        expected_preflight = ["auth-get", "auth-patch", "auth-update", "internal"]
        for run in (first, preflight_failure, repeated_failure):
            result, args, events, marked, replicas = run
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertTrue(marked)
            self.assertEqual(replicas, "0")
            self.assertEqual(events[:4], expected_preflight)
            self.assertNotIn("--rollback-on-failure", args)
            self.assertNotIn("--atomic", args)
        self.assertEqual(first[2][4:], ["marker-create", "upgrade", "public", "scale-closed"])
        self.assertEqual(preflight_failure[1], [])
        self.assertEqual(preflight_failure[2], expected_preflight)
        self.assertEqual(repeated_failure[2][4:], ["upgrade", "public", "jury", "scale-closed"])
        result, args, events, marked, replicas = accepted
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--wait", args)
        self.assertEqual(events, expected_preflight + ["upgrade", "public", "jury", "marker-delete"])
        self.assertFalse(marked)
        self.assertEqual(replicas, "2")

    def test_opening_without_gateway_scale_permission_stops_before_helm(self):
        (result, args, events, marked, replicas), = self.deploy_runs(
            current("remote", True), values("remote", False), ("f", "t"), "v4.3.0",
            {"MOCK_SCALE_FORBIDDEN": "1"},
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("gateway scale permissions", result.stderr)
        self.assertEqual(args, [])
        self.assertEqual(events, ["auth-get"])
        self.assertFalse(marked)
        self.assertEqual(replicas, "0")

    def test_failed_helm_opening_retries_without_automatic_rollback(self):
        failed, retry = self.deploy_runs(
            current("remote", True), values("remote", False), ("f", "t"), "v3.19.0",
            {"MOCK_FAILED": "1"}, {},
        )
        self.assertEqual(failed[0].returncode, 1, failed[0].stderr)
        self.assertEqual(failed[2][-3:], ["marker-create", "upgrade", "scale-closed"])
        self.assertTrue(failed[3])
        self.assertEqual(retry[0].returncode, 0, retry[0].stderr)
        self.assertIn("--wait", retry[1])
        self.assertNotIn("--atomic", retry[1])
        self.assertNotIn("--server-side=false", retry[1])
        self.assertEqual(retry[2][3:], ["internal", "upgrade", "public", "jury", "marker-delete"])

    def test_interrupted_opening_closes_gateway_before_pending_helm_guard(self):
        (result, args, events, marked, replicas), = self.deploy_runs(
            current("remote", True), values("remote", False), ("f", "t"), "v4.3.0",
            {"MOCK_INTERRUPTED_OPEN": "1"},
        )
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("unfinished Helm operation", result.stderr)
        self.assertEqual(events, ["scale-closed", "helm-lint", "helm-template", "helm-list", "helm-get"])
        self.assertEqual(args, [])
        self.assertTrue(marked)
        self.assertEqual(replicas, "0")

    def test_helm4_reopening_patches_replicas_after_external_scale(self):
        failed, retry = self.deploy_runs(
            current("remote", True), values("remote", False), ("f", "t"), "v4.3.0",
            {"MOCK_FAIL_PUBLIC": "1"}, {"MOCK_SCALE_OWNERSHIP": "1"},
        )
        self.assertIn("scale-closed", failed[2])
        self.assertEqual(failed[4], "0")
        self.assertEqual(retry[0].returncode, 0, retry[0].stderr)
        self.assertIn("--server-side=false", retry[1])
        self.assertNotIn("--force-conflicts", retry[1])
        self.assertEqual(retry[4], "2")
        self.assertFalse(retry[3])

    def test_missing_reverse_transfer_stops_before_upgrade(self):
        result, args = self.deploy(current("remote", True), values("local", True), ("f", "t"), "v4.3.0")
        self.assertEqual(result.returncode, 1)
        self.assertIn("history transfer", result.stderr)
        self.assertEqual(args, [])

        result, args = self.deploy(current("remote", True), values("local", True), ("t", "f"), "v4.3.0",
                                   history_mismatch=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Catalog history differs", result.stderr)
        self.assertEqual(args, [])


if __name__ == "__main__":
    unittest.main()
