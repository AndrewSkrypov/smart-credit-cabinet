from hashlib import sha256
from importlib import import_module
from io import BytesIO
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock
from urllib.error import HTTPError


receipt = import_module("publisher-receipt")
TOKEN = "fixture-job-token"
API = f"{receipt.GITLAB}/api/v4"
REVISION = "a" * 40
CONTEXT = receipt.packages.Context(API, "1", "100", REVISION)
EXPECTED = {"format": 1, "project": "1", "pipeline": "100", "revision": REVISION,
            "tree": "b" * 40, "source": "push", "ref": "refs/heads/main"}
URL = f"{API}/projects/1/packages/generic/{receipt.PACKAGE}/{REVISION}-100/{receipt.FILE}"


class Response(BytesIO):
    def __init__(self, raw=b"", *, status=200):
        super().__init__(raw)
        self.status = status


def http_error(url, code, headers=None):
    return HTTPError(url, code, "fixture", headers or {}, BytesIO())


class Storage:
    def __init__(self):
        self.bundles = {}
        self.puts = 0
        self.rewrite = False

    def open(self, request, *, timeout):
        assert request.get_header("Job-token") == TOKEN
        assert timeout <= 120
        url = request.full_url
        if request.method == "PUT":
            self.puts += 1
            if url in self.bundles:
                raise http_error(url, 409)
            raw = request.data.read()
            assert request.get_header("Content-length") == str(len(raw))
            self.bundles[url] = raw + b" " if self.rewrite else raw
            return Response(status=201)
        if url not in self.bundles:
            raise http_error(url, 404)
        return Response(self.bundles[url])


class PublisherReceiptTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.storage = Storage()
        self.registry = receipt.packages.Registry(TOKEN, self.storage.open)
        self.manifest = self.root / "release.json"
        self.evidence = self.root / "release-evidence.json"

    def write_artifacts(self, job="27", digest="f"):
        self.manifest.write_bytes(json.dumps({"revision": REVISION, "images": {
            "identity": "registry.example.invalid/identity@sha256:" + digest * 64,
        }}, indent=2).encode() + b"\n")
        self.evidence.write_bytes(json.dumps(EXPECTED | {
            "publisher_job": job, "manifest_sha256": sha256(self.manifest.read_bytes()).hexdigest(),
        }, sort_keys=True).encode() + b"\n")
        return self.manifest.read_bytes(), self.evidence.read_bytes()

    def run_action(self, action, job="27", *, registry=None, expected=EXPECTED):
        return receipt.handle(action, self.root, CONTEXT, expected, job, registry or self.registry)

    def test_retry_restores_first_attempt_bytes_and_both_deployments_verify_them(self):
        self.assertEqual(self.run_action("restore"), receipt.MISSING)
        original = self.write_artifacts()
        self.assertEqual(self.run_action("save"), 0)
        self.assertEqual(self.run_action("save"), 0)
        self.assertEqual(self.storage.puts, 1)
        self.assertEqual(set(self.storage.bundles), {URL})
        self.assertNotIn(TOKEN.encode(), self.storage.bundles[URL])
        self.write_artifacts(job="28", digest="d")
        self.assertEqual(self.run_action("restore", "28"), 0)
        self.assertEqual((self.manifest.read_bytes(), self.evidence.read_bytes()), original)
        for job in ("30", "31"):
            self.assertEqual(self.run_action("verify", job), 0)
        self.write_artifacts(job="28", digest="d")
        with self.assertRaises(receipt.ReceiptError):
            self.run_action("save", "28")
        with self.assertRaises(receipt.ReceiptError):
            self.run_action("verify", "30")
        self.assertEqual(self.storage.puts, 1)

    def test_refuses_wrong_context_hash_and_oversized_registry_response(self):
        original_manifest, original_evidence = self.write_artifacts()
        self.run_action("save")
        with self.assertRaises(receipt.ReceiptError):
            self.run_action("restore", expected=EXPECTED | {"pipeline": "111"})
        self.manifest.write_bytes(original_manifest + b" ")
        with self.assertRaises(receipt.ReceiptError):
            self.run_action("save")
        corrupt = receipt.Receipt(original_manifest + b" ", original_evidence).encode()
        self.storage.bundles[URL] = corrupt
        with self.assertRaises(receipt.ReceiptError):
            self.run_action("restore")
        self.storage.bundles[URL] = b"x" * (receipt.BUNDLE_MAX + 1)
        with self.assertRaises(receipt.ReceiptError):
            self.run_action("restore")
        self.assertEqual(self.evidence.read_bytes(), original_evidence)
        self.assertEqual(self.storage.puts, 1)

    def test_upload_must_round_trip_identical_bytes_and_rejects_symlinks(self):
        self.write_artifacts()
        self.storage.rewrite = True
        with self.assertRaisesRegex(receipt.ReceiptError, "differs"):
            self.run_action("save")
        self.evidence.unlink()
        self.evidence.symlink_to(self.manifest)
        with self.assertRaises(receipt.packages.TransferError):
            self.run_action("restore")

    def test_only_registry_404_is_missing_and_token_is_removed_on_download_redirect(self):
        object_url = "https://objects.example.invalid/signed?signature=fixture"
        opener = Mock(side_effect=[http_error(URL, 302, {"Location": object_url}), http_error(object_url, 404)])
        client = receipt.packages.Registry(TOKEN, opener)
        with self.assertRaises(HTTPError) as failure:
            self.run_action("restore", registry=client)
        failure.exception.close()
        first, redirected = [call.args[0] for call in opener.call_args_list]
        self.assertEqual(first.get_header("Job-token"), TOKEN)
        self.assertEqual(redirected.full_url, object_url)
        self.assertIsNone(redirected.get_header("Job-token"))
        opener = Mock(side_effect=http_error(URL, 403))
        with self.assertRaises(HTTPError) as denied:
            self.run_action("restore", registry=receipt.packages.Registry(TOKEN, opener))
        denied.exception.close()

    def test_upload_redirect_fails_without_reading_or_writing_its_target(self):
        self.write_artifacts()
        opener = Mock(side_effect=[http_error(URL, 404),
                                  http_error(URL, 307, {"Location": "https://external.invalid/upload"})])
        with self.assertRaises(HTTPError) as failure:
            self.run_action("save", registry=receipt.packages.Registry(TOKEN, opener))
        failure.exception.close()
        self.assertEqual([call.args[0].full_url for call in opener.call_args_list], [URL, URL])

    def test_only_exact_project_ref_and_job_may_use_its_operation(self):
        env = {"CI_SERVER_URL": receipt.GITLAB, "CI_API_V4_URL": API, "CI_PROJECT_ID": "1",
               "CI_PROJECT_PATH": receipt.PROJECT_PATH, "CI_COMMIT_REF_NAME": "main", "CI_JOB_NAME": receipt.PUBLISHER}
        receipt.authorize("restore", env)
        receipt.authorize("save", env)
        prod = env | {"CI_JOB_NAME": "main-prod-deploy", "CI_ENVIRONMENT_NAME": "production",
                      "CI_ENVIRONMENT_ACTION": "start", "CI_RUNNER_ID": "4"}
        receipt.authorize("verify", prod)
        activation = prod | {"CI_JOB_NAME": "main-release-activation", "CI_ENVIRONMENT_ACTION": "prepare"}
        receipt.authorize("verify", activation)
        for mismatch in ({"CI_ENVIRONMENT_ACTION": "start"}, {"CI_RUNNER_ID": "2"}, {"CI_ENVIRONMENT_NAME": "dev"}):
            with self.subTest(mismatch=mismatch), self.assertRaises(receipt.ReceiptError):
                receipt.authorize("verify", activation | mismatch)
        for override in ({"CI_DEBUG_TRACE": "TRUE"}, {"CI_COMMIT_REF_NAME": "ref"}, {"CI_RUNNER_ID": "5"},
                         {"CI_PROJECT_ID": "2"}, {"CI_ENVIRONMENT_ACTION": "prepare"},
                         {"CI_SERVER_URL": "https://other.invalid", "CI_API_V4_URL": "https://other.invalid/api/v4"}):
            with self.subTest(override=override), self.assertRaises(receipt.ReceiptError):
                receipt.authorize("verify", prod | override)
        with self.assertRaises(receipt.ReceiptError):
            receipt.authorize("save", prod)


if __name__ == "__main__":
    unittest.main()
