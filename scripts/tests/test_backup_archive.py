import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import Mock
import json

spec = importlib.util.spec_from_file_location("backup_support", Path(__file__).resolve().parents[2] / "deploy/backup_support.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BackupArchiveTest(unittest.TestCase):
    def test_generated_s3_files_are_included_when_client_uploads_are_only_local(self):
        deployment = Mock()
        deployment.sql.side_effect = ["1", "1", '{"LOCAL": 2, "S3": 3}']
        counts = module.storage_backend_counts(deployment)
        self.assertEqual(counts, {"LOCAL": 2, "S3": 3})
        self.assertIn("union all select storage_backend from generated_artifacts", deployment.sql.call_args.args[1])
        manifest = self.manifest()
        manifest["storage"]["backends"] = counts
        with self.assertRaises(AssertionError):
            module.backup_archives(manifest)

    def test_inactive_storage_volume_is_resolved_without_starting_it(self):
        deployment = Mock(project="cabinet-prod-test")
        deployment.config.return_value = {"volumes": {"cabinet-documents": {"name": "cabinet-prod-test_cabinet-documents"}}}
        deployment.compose.return_value = json.dumps({"volumes": {"cabinet-seaweedfs": {"name": "cabinet-prod-test_cabinet-seaweedfs"}}})
        self.assertEqual(module.storage_volume(deployment, "cabinet-seaweedfs"), "cabinet-prod-test_cabinet-seaweedfs")
        deployment.compose.assert_called_once_with("--profile", "storage", "config", "--format", "json", capture=True)
        deployment.compose.return_value = json.dumps({"volumes": {"cabinet-seaweedfs": {"name": "another-project_data"}}})
        with self.assertRaises(AssertionError):
            module.storage_volume(deployment, "cabinet-seaweedfs")

    def manifest(self, version=2, s3=False):
        files = {name: {} for name in ["identity.dump", "credit.dump", "engagement.dump", "documents.tar.gz"]}
        result = {"format_version": version, "backup_complete": True, "documents": {"old.pdf": {}}, "files": files}
        if version == 2:
            result["storage"] = {"backends": {"LOCAL": 1, "S3": int(s3)}, "s3_volume_included": s3}
            if s3:
                result["storage"]["seaweedfs_files"] = {"volume.dat": {}}
                files["seaweedfs.tar.gz"] = {}
        return result

    def test_legacy_local_backup_remains_readable(self):
        self.assertEqual(set(module.backup_archives(self.manifest(version=1))), {"documents.tar.gz"})

    def test_s3_backup_requires_both_volumes(self):
        manifest = self.manifest(s3=True)
        self.assertEqual(set(module.backup_archives(manifest)), {"documents.tar.gz", "seaweedfs.tar.gz"})
        del manifest["files"]["seaweedfs.tar.gz"]
        with self.assertRaises(AssertionError):
            module.backup_archives(manifest)

    def test_s3_rows_cannot_claim_local_only_backup(self):
        manifest = self.manifest()
        manifest["storage"]["backends"]["S3"] = 1
        with self.assertRaises(AssertionError):
            module.backup_archives(manifest)

    def test_unexpected_archive_path_is_rejected(self):
        manifest = self.manifest()
        manifest["files"]["../outside.tar.gz"] = {}
        with self.assertRaises(AssertionError):
            module.backup_archives(manifest)

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
