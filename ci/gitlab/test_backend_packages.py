import importlib.util
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock
from urllib.error import HTTPError


spec = importlib.util.spec_from_file_location("backend_packages", Path(__file__).with_name("backend-packages.py"))
packages = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = packages
spec.loader.exec_module(packages)

API = "https://gitlab.example.invalid/api/v4"
ENV = {"CI_SERVER_URL": "https://gitlab.example.invalid", "CI_API_V4_URL": API,
       "CI_PROJECT_ID": "1", "CI_PIPELINE_ID": "96", "CI_COMMIT_SHA": "a" * 40}
TOKEN = "test-job-token"


class Response(BytesIO):
    def __init__(self, body=b"", *, status=200, headers=None):
        super().__init__(body)
        self.status = status
        self.headers = headers or {}


class MemoryRegistry:
    def __init__(self):
        self.files = {}
        self.puts = []
        self.fail_on = None
        self.omit_checksum = False

    def open(self, request, *, timeout):
        assert request.get_header("Job-token") == TOKEN
        url = request.full_url
        if request.method == "PUT":
            if self.fail_on and url.endswith(self.fail_on):
                raise HTTPError(url, 413, "fixture rejects upload", {}, BytesIO())
            data = request.data.read()
            assert request.get_header("Content-length") == str(len(data))
            self.files[url] = data
            self.puts.append(url)
            return Response(status=201)
        if url not in self.files:
            raise HTTPError(url, 404, "fixture file absent", {}, BytesIO())
        data = self.files[url]
        headers = {"Content-Length": str(len(data))}
        if not self.omit_checksum:
            headers["X-Checksum-SHA256"] = sha256(data).hexdigest()
        return Response(data, headers=headers)


class BackendPackageTest(unittest.TestCase):
    def setUp(self):
        scratch = TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.source = Path(scratch.name).resolve() / "source"
        self.target = self.source.with_name("target")
        self.context = packages.Context.from_environment(ENV)
        self.memory = MemoryRegistry()
        self.registry = packages.Registry(TOKEN, self.memory.open)
        self.jars = {}
        for path, name in packages.FILES.items():
            artifact = self.source / path
            artifact.parent.mkdir(parents=True)
            data = b"PK\0\xffopaque-tested-bytes\n" + name.encode()
            artifact.write_bytes(data)
            self.jars[path] = data
        sha_file = self.source / packages.CHECKSUMS
        sha_file.parent.mkdir()
        sha_file.write_text("".join(f"{sha256(data).hexdigest()}  {path}\n" for path, data in self.jars.items()))

    def copy_receipt(self):
        for path in (packages.CHECKSUMS, packages.RECEIPT):
            target = self.target / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((self.source / path).read_bytes())

    def test_exact_tested_bytes_round_trip_with_full_version_and_no_token_in_receipt(self):
        packages.upload(self.source, self.context, "1787", self.registry)
        self.assertEqual(len(self.memory.puts), 8)
        version = "a" * 40 + "-96-1787"
        self.assertEqual(set(self.memory.puts), {f"{API}/projects/1/packages/generic/{packages.PACKAGE}/{version}/{file}"
                                                for file in packages.FILES.values()})
        self.assertNotIn(TOKEN, (self.source / packages.RECEIPT).read_text())
        self.copy_receipt()
        packages.download(self.target, self.context, self.registry)
        self.assertEqual({path: (self.target / path).read_bytes() for path in packages.FILES}, self.jars)

    def test_partial_upload_leaves_no_receipt_and_retry_uses_new_job_version(self):
        self.memory.fail_on = "engagement.jar"
        with self.assertRaises(HTTPError) as failure:
            packages.upload(self.source, self.context, "1787", self.registry)
        failure.exception.close()
        self.assertGreater(len(self.memory.puts), 0)
        self.assertFalse((self.source / packages.RECEIPT).exists())
        published = set(self.memory.puts)
        self.memory.fail_on = None
        packages.upload(self.source, self.context, "1788", self.registry)
        self.assertEqual(len(set(self.memory.puts) - published), 8)
        self.assertEqual(json.loads((self.source / packages.RECEIPT).read_bytes())["producer_job"], "1788")
        with self.assertRaisesRegex(packages.TransferError, "already exists"):
            packages.upload(self.source, self.context, "1788", self.registry)
        self.assertFalse((self.source / packages.RECEIPT).exists())

    def test_download_stream_checks_hash_and_leaves_workspace_untouched_on_partial_failure(self):
        packages.upload(self.source, self.context, "1787", self.registry)
        self.copy_receipt()
        self.memory.omit_checksum = True
        corrupted_url = next(url for url in self.memory.files if url.endswith("servicing.jar"))
        self.memory.files[corrupted_url] = b"X" + self.memory.files[corrupted_url][1:]
        path = self.target / next(iter(packages.FILES))
        path.parent.mkdir(parents=True)
        path.write_bytes(b"keep the existing checkout on failure")
        with self.assertRaisesRegex(packages.TransferError, "bytes differ"):
            packages.download(self.target, self.context, self.registry)
        self.assertEqual(path.read_bytes(), b"keep the existing checkout on failure")
        self.assertEqual(list(self.target.glob("services/*/build/libs/app.jar")), [path])

    def test_rejects_other_origin_project_pipeline_revision_and_version_before_fetch(self):
        packages.upload(self.source, self.context, "1787", self.registry)
        self.copy_receipt()
        path = self.target / packages.RECEIPT
        original = json.loads(path.read_bytes())
        client = Mock()
        for name, value in {"api": "https://other.invalid/api/v4", "project": "2", "pipeline": "1",
                            "revision": "b" * 40, "version": "latest", "producer_job": "../1788"}.items():
            with self.subTest(name=name):
                path.write_text(json.dumps({**original, name: value}))
                with self.assertRaises(packages.TransferError):
                    packages.download(self.target, self.context, client)
        client.verify.assert_not_called()
        with self.assertRaisesRegex(packages.TransferError, "current CI server"):
            packages.Context.from_environment({**ENV, "CI_API_V4_URL": "https://other.invalid/api/v4"})

    def test_rejects_wrong_local_bytes_extra_inventory_paths_and_symlinks_before_upload(self):
        client = Mock()
        path = self.source / next(iter(packages.FILES))
        original = path.read_bytes()
        path.write_bytes(b"changed after tests")
        with self.assertRaisesRegex(packages.TransferError, "no longer matches"):
            packages.upload(self.source, self.context, "1787", client)
        path.write_bytes(original)
        extra = self.source / "services/unknown/build/libs/app.jar"
        extra.parent.mkdir(parents=True)
        extra.write_bytes(b"extra jar")
        with self.assertRaisesRegex(packages.TransferError, "Unexpected backend app.jar"):
            packages.upload(self.source, self.context, "1787", client)
        extra.unlink()
        path.unlink()
        path.symlink_to(self.source / list(packages.FILES)[1])
        with self.assertRaisesRegex(packages.TransferError, "symbolic link"):
            packages.upload(self.source, self.context, "1787", client)
        client.publish.assert_not_called()

    def test_receipt_inventory_and_checksum_file_cannot_select_arbitrary_paths(self):
        packages.upload(self.source, self.context, "1787", self.registry)
        self.copy_receipt()
        receipt = self.target / packages.RECEIPT
        original = receipt.read_text()
        files = json.loads(original)["files"]
        unexpected = "../../unrelated.jar"
        receipt.write_text(original.replace(next(iter(files)), unexpected, 1))
        with self.assertRaisesRegex(packages.TransferError, "exactly the eight"):
            packages.download(self.target, self.context, Mock())
        receipt.write_text(original)
        check = self.target / packages.CHECKSUMS
        first, *rest = check.read_text().splitlines()
        check.write_text("\n".join([first, first, *rest]))
        with self.assertRaisesRegex(packages.TransferError, "duplicate"):
            packages.download(self.target, self.context, Mock())

    def test_direct_object_store_redirect_never_carries_job_token(self):
        origin = f"{API}/projects/1/packages/generic/{packages.PACKAGE}/version/assessment.jar"
        signed = "https://objects.example.invalid/download?signature=fixture"
        opener = Mock(side_effect=[HTTPError(origin, 302, "redirect", {"Location": signed}, BytesIO()), Response(b"jar")])
        registry = packages.Registry(TOKEN, opener)
        with registry.fetch(origin) as result:
            self.assertEqual(result.read(), b"jar")
        first, redirected = [call.args[0] for call in opener.call_args_list]
        self.assertEqual(first.get_header("Job-token"), TOKEN)
        self.assertEqual(redirected.full_url, signed)
        self.assertIsNone(redirected.get_header("Job-token"))

    def test_upload_rejects_inconsistent_registry_checksum_or_bytes(self):
        local = self.source / next(iter(packages.FILES))
        data = local.read_bytes()
        metadata = {"size": len(data), "sha256": sha256(data).hexdigest()}
        url = f"{API}/projects/1/packages/generic/{packages.PACKAGE}/version/assessment.jar"
        unexpected = b"X" + data[1:]
        for headers in ({"Content-Length": str(len(data)), "X-Checksum-SHA256": sha256(unexpected).hexdigest()}, {}):
            with self.subTest(headers=headers):
                opener = Mock(side_effect=[HTTPError(url, 404, "absent", {}, BytesIO()), Response(status=201),
                                           Response(unexpected, headers=headers)])
                with self.assertRaisesRegex(packages.TransferError, "differs|differ"):
                    packages.Registry(TOKEN, opener).publish(url, local, metadata)


if __name__ == "__main__":
    unittest.main()
