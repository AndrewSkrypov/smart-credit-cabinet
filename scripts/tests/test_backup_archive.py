import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("backup_support", Path(__file__).resolve().parents[2] / "deploy/backup_support.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BackupArchiveTest(unittest.TestCase):
    def archive(self, directory, names, link=False):
        path = Path(directory) / "documents.tar.gz"
        with tarfile.open(path, "w:gz") as stream:
            for name in names:
                entry = tarfile.TarInfo(name)
                if link:
                    entry.type = tarfile.SYMTYPE
                    entry.linkname = "/etc/passwd"
                    stream.addfile(entry)
                else:
                    entry.size = 3
                    stream.addfile(entry, io.BytesIO(b"pdf"))
        return path

    def test_parent_and_absolute_paths_are_rejected_before_extraction(self):
        for name in ["../outside", "/absolute"]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(AssertionError):
                    module.document_inventory(self.archive(directory, [name]))

    def test_symlinks_cannot_escape_the_documents_volume(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(AssertionError):
                module.document_inventory(self.archive(directory, ["innocent"], link=True))

    def test_duplicate_paths_are_not_silently_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(AssertionError):
                module.document_inventory(self.archive(directory, ["same.pdf", "same.pdf"]))
