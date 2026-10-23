import importlib.util
import os
from pathlib import Path
import stat
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("upgrade_database_env", Path(__file__).resolve().parents[2] / "deploy/upgrade-database-env.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DatabaseEnvironmentTest(unittest.TestCase):
    def test_upgrade_preserves_every_existing_key_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target, repeated = [Path(directory) / name for name in ("old.env", "new.env", "repeat.env")]
            original = "# operator settings\nJWT_SECRET=" + "j" * 64 + "\nPOSTGRES_PASSWORD=" + "p" * 48 + "\nCUSTOM_SETTING=keep\n"
            source.write_text(original, encoding="utf-8")
            self.assertEqual(module.upgrade(source, target), 6)
            if os.name == "posix":
                self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
            self.assertEqual(source.read_text(encoding="utf-8"), original)
            self.assertTrue(target.read_text(encoding="utf-8").startswith(original))
            self.assertEqual(module.upgrade(target, repeated), 0)
            self.assertEqual(target.read_bytes(), repeated.read_bytes())
            with self.assertRaises(FileExistsError):
                module.upgrade(source, target)

    def test_reused_owner_and_runtime_credentials_are_rejected_before_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = [Path(directory) / name for name in ("old.env", "new.env")]
            source.write_text("IDENTITY_DB_PASSWORD=" + "a" * 40 + "\nIDENTITY_MIGRATION_PASSWORD=" + "a" * 40 + "\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                module.upgrade(source, target)
            self.assertFalse(target.exists())
