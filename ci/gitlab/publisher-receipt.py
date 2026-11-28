#!/usr/bin/env python3
"""Pin the first protected-main publisher's two artifacts inside one pipeline."""

import argparse
from base64 import b64decode, b64encode
from dataclasses import dataclass
from hashlib import sha256
from http.client import HTTPException
from importlib import import_module
import io
import json
import os
from pathlib import Path
import ssl
import subprocess
from tempfile import TemporaryDirectory
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import HTTPSHandler, Request, build_opener


packages = import_module("backend-packages")
release = import_module("release-evidence")
GITLAB = "https://gitlab.credit-cabinet.ru"
PROJECT_PATH = "pump/smart-credit-cabinet"
PACKAGE = "cabinet-release-ci"
FILE = "publisher-receipt.json"
PUBLISHER = "main-release-images"
VERIFIERS = {
    "main-release-activation": ("production", "4", "prepare"),
    "main-dev-deploy": ("dev", "2", "start"),
    "main-prod-deploy": ("production", "4", "start"),
}
ARTIFACT_MAX = 64 * 1024
BUNDLE_MAX = 192 * 1024
MISSING = 3


class ReceiptError(Exception):
    pass


def json_object(raw):
    data = json.loads(raw, object_pairs_hook=packages.unique_object)
    if not isinstance(data, dict):
        raise ReceiptError("Invalid publisher receipt document")
    return data


@dataclass(frozen=True)
class Receipt:
    manifest: bytes
    evidence: bytes

    def encode(self):
        return json.dumps({"format": 1, "release": b64encode(self.manifest).decode("ascii"),
                           "evidence": b64encode(self.evidence).decode("ascii")},
                          separators=(",", ":"), sort_keys=True).encode("ascii")

    @classmethod
    def decode(cls, raw):
        data = json_object(raw)
        if set(data) != {"format", "release", "evidence"} or type(data["format"]) is not int or data["format"] != 1:
            raise ReceiptError("Unexpected publisher receipt envelope")
        return cls(b64decode(data["release"], validate=True), b64decode(data["evidence"], validate=True))

    def validate(self, expected):
        if not all(0 < len(raw) <= ARTIFACT_MAX for raw in (self.manifest, self.evidence)):
            raise ReceiptError("Empty or oversized publisher receipt artifact")
        manifest = json_object(self.manifest)
        if manifest.get("revision") != expected["revision"]:
            raise ReceiptError("Release manifest does not match this source revision")
        evidence = json_object(self.evidence)
        publisher = evidence.pop("publisher_job", None)
        if (not isinstance(publisher, str) or not packages.NUMBER.fullmatch(publisher)
                or type(evidence.get("format")) is not int
                or evidence != expected | {"manifest_sha256": sha256(self.manifest).hexdigest()}):
            raise ReceiptError("Publisher receipt does not match this pipeline and manifest")
        return publisher


def authorize(action, env):
    if (env.get("CI_SERVER_URL") != GITLAB or env.get("CI_API_V4_URL") != f"{GITLAB}/api/v4"
            or env.get("CI_PROJECT_ID") != "1" or env.get("CI_PROJECT_PATH") != PROJECT_PATH
            or env.get("CI_COMMIT_REF_NAME") != "main" or env.get("CI_DEBUG_TRACE", "").lower() == "true"):
        raise ReceiptError("Publisher receipt requires the approved GitLab project and protected main job")
    name = env.get("CI_JOB_NAME")
    if action == "verify":
        scope = env.get("CI_ENVIRONMENT_NAME"), env.get("CI_RUNNER_ID"), env.get("CI_ENVIRONMENT_ACTION")
        if scope != VERIFIERS.get(name):
            raise ReceiptError("Only the approved main release jobs can verify a publisher receipt")
    elif name != PUBLISHER:
        raise ReceiptError("Only the main release publisher can restore or save a publisher receipt")


def fetch(url, registry):
    try:
        response = registry.request(url)
    except HTTPError as error:
        with error:
            if error.code == 404:
                return None
            location = error.headers.get("Location")
            if error.code not in {302, 303, 307, 308} or not location:
                raise
            target = urljoin(url, location)
            packages.https_url(target)
        # The object store's signed URL authenticates the GET without a GitLab credential.
        response = registry.open_request(Request(target), timeout=30)
    with response:
        if response.status != 200:
            raise ReceiptError("Unexpected publisher receipt download status")
        raw = response.read(BUNDLE_MAX + 1)
    if not raw or len(raw) > BUNDLE_MAX:
        raise ReceiptError("Empty or oversized publisher receipt bundle")
    return raw


def read_artifact(path):
    if not path.is_file():
        raise ReceiptError("Missing publisher receipt artifact")
    with path.open("rb") as stream:
        return stream.read(ARTIFACT_MAX + 1)


def handle(action, root, context, expected, job, registry):
    manifest = packages.workspace_path(root, release.MANIFEST)
    evidence = packages.workspace_path(root, release.EVIDENCE)
    url = f"{context.api}/projects/{context.project}/packages/generic/{PACKAGE}/{context.revision}-{context.pipeline}/{FILE}"

    if action == "save":
        current = Receipt(read_artifact(manifest), read_artifact(evidence))
        if current.validate(expected) != job:
            raise ReceiptError("Only the original publisher attempt can save this receipt")
        raw = current.encode()
        existing = fetch(url, registry)
        if existing is None:
            with registry.request(url, data=io.BytesIO(raw), size=len(raw)) as response:
                if response.status != 201:
                    raise ReceiptError("Unexpected publisher receipt upload status")
            existing = fetch(url, registry)
        if existing != raw:
            raise ReceiptError("Canonical receipt differs from this publisher attempt")
        return 0

    raw = fetch(url, registry)
    if raw is None:
        if action == "restore":
            return MISSING
        raise ReceiptError("The pipeline has no canonical publisher receipt")

    original = Receipt.decode(raw)
    original.validate(expected)
    if action == "restore":
        with TemporaryDirectory(prefix=".publisher-receipt-", dir=root) as directory:
            staged_manifest = Path(directory) / manifest.name
            staged_evidence = Path(directory) / evidence.name
            staged_manifest.write_bytes(original.manifest)
            staged_evidence.write_bytes(original.evidence)
            staged_manifest.replace(manifest)
            staged_evidence.replace(evidence)
    elif Receipt(read_artifact(manifest), read_artifact(evidence)) != original:
        raise ReceiptError("Local release artifacts differ from the canonical publisher receipt")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("restore", "save", "verify"))
    action = parser.parse_args().action
    try:
        authorize(action, os.environ)
        context = packages.Context.from_environment(os.environ)
        expected, job = release.context()
        tls = ssl.create_default_context()
        if ca := os.environ.get("CI_SERVER_TLS_CA_FILE"):
            tls.load_verify_locations(cafile=ca)
        registry = packages.Registry(os.environ["CI_JOB_TOKEN"],
                                     build_opener(packages.NoRedirect(), HTTPSHandler(context=tls)).open)
        result = handle(action, Path.cwd().resolve(), context, expected, job, registry)
    except (ReceiptError, packages.TransferError, release.ReleaseError) as error:
        parser.exit(1, f"Publisher receipt {action}: {error}\n")
    except HTTPError as error:
        with error:
            parser.exit(1, f"Publisher receipt {action}: HTTP {error.code}\n")
    except (OSError, KeyError, ValueError, TypeError, URLError, HTTPException, subprocess.SubprocessError):
        parser.exit(1, f"Publisher receipt {action} failed to read the CI context, artifacts or transport\n")
    if result == 0:
        print(f"Publisher receipt {action}: exact release artifacts verified")
    return result


if __name__ == "__main__":
    raise SystemExit(main())
