import unittest

from configure import AUDIT_PATH, KUBERNETES_SECRETS, VaultError, configure, gitlab_role, policies


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
            return {"data": data}
        if path == "sys/audit/pump-file":
            self.audit["pump-file/"] = payload
        elif path == "sys/mounts/pump":
            self.mounts["pump/"] = payload
        elif path == "sys/auth/pump-gitlab":
            self.auth["pump-gitlab/"] = payload
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
