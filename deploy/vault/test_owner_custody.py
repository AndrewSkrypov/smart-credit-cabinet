import importlib.util
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


source = Path(__file__).with_name("owner-custody.py")
spec = importlib.util.spec_from_file_location("owner_custody", source)
owner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owner)


class MemoryKeychain:
    def __init__(self, fail=False):
        self.data = {}
        self.fail = fail

    def find(self, name):
        return self.data.get(name)

    def add(self, name, value):
        if self.fail:
            raise owner.CustodyError("synthetic custody failure")
        self.data[name] = value

    def probe(self):
        pass


class FreshVault:
    def __init__(self):
        self.initialized = False
        self.progress = 0
        self.init_writes = 0

    def request(self, method, path, payload=None):
        if (method, path) == ("GET", "sys/init"):
            return {"initialized": self.initialized}
        if (method, path) == ("PUT", "sys/init"):
            self.init_writes += 1
            self.initialized = True
            assert payload == {"secret_shares": 3, "secret_threshold": 2}
            return {"keys_base64": [key * 28 for key in "abc"], "root_token": "synthetic-root-token"}
        if (method, path) == ("PUT", "sys/unseal"):
            self.progress += 1
        return {"initialized": self.initialized, "storage_type": "raft", "sealed": self.progress < 2,
                "progress": self.progress, "t": 2}


class CustodyTest(unittest.TestCase):
    @patch.object(owner, "require_owner_directory")
    def test_initialize_once_and_unseal_with_off_vm_custody(self, _):
        with tempfile.TemporaryDirectory() as directory:
            vault, keychain = FreshVault(), MemoryKeychain()
            owner.initialize(vault, keychain, "test", Path(directory))
            self.assertIn("test/shamir", keychain.data)
            self.assertFalse(list(Path(directory).iterdir()))
            with self.assertRaisesRegex(owner.CustodyError, "already exists"):
                owner.initialize(vault, keychain, "test", Path(directory))
            self.assertEqual(vault.init_writes, 1)
            owner.unseal(vault, keychain, "test")
            self.assertEqual(vault.progress, 2)

    @patch.object(owner, "require_owner_directory")
    def test_custody_failure_leaves_private_emergency_receipt_and_refuses_retry(self, _):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            vault, keychain = FreshVault(), MemoryKeychain(fail=True)
            with self.assertRaisesRegex(owner.CustodyError, "emergency file"):
                owner.initialize(vault, keychain, "test", path)
            emergency = path / "vault-init-emergency.json"
            self.assertEqual(stat.S_IMODE(emergency.lstat().st_mode), 0o600)
            self.assertFalse((path / "vault-init.lock").exists())
            with self.assertRaisesRegex(owner.CustodyError, "already exists"):
                owner.initialize(vault, keychain, "test", path)
            self.assertEqual(vault.init_writes, 1)

    def test_unseal_refuses_to_join_ongoing_attempt(self):
        vault = FreshVault()
        vault.initialized = True
        vault.progress = 1
        with self.assertRaisesRegex(owner.CustodyError, "already started"):
            owner.unseal(vault, MemoryKeychain(), "test")
