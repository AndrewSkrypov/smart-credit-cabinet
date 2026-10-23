import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("maintenance", Path(__file__).resolve().parents[2] / "deploy/maintenance.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MaintenanceCheckpointTest(unittest.TestCase):
    def test_interruption_before_publication_preserves_previous_report(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "transfer.json"
            module.private_checkpoint(path, {"copied": 1})
            with patch.object(module.os, "replace", side_effect=InterruptedError):
                with self.assertRaises(InterruptedError):
                    module.private_checkpoint(path, {"copied": 2})
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {"copied": 1})
            self.assertEqual(list(Path(directory).iterdir()), [path])
            module.private_checkpoint(path, {"copied": 2})
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {"copied": 2})
