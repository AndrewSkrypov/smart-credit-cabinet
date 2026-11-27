#!/usr/bin/env python3
"""Transfer the tested backend JARs through this GitLab project's package registry."""

import argparse
from dataclasses import dataclass
from hashlib import sha256
from http.client import HTTPException
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
from tempfile import NamedTemporaryFile, TemporaryDirectory
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, HTTPSHandler, Request, build_opener


PACKAGE = "cabinet-backend-ci"
CHECKSUMS = Path("ci-artifacts/backend.sha256")
RECEIPT = Path("ci-artifacts/backend-package.json")
FILES = {
    f"services/{service}/build/libs/app.jar": f"{service}.jar"
    for service in ("assessment", "catalog-grpc", "credit", "documents", "engagement", "external-sim", "identity", "servicing")
}
HEX = re.compile(r"[0-9a-f]{64}")
REVISION = re.compile(r"[0-9a-f]{40}")
NUMBER = re.compile(r"[1-9][0-9]{0,18}")
SMALL_FILE = 8192
BLOCK = 1024 * 1024


class TransferError(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None


def https_url(url):
    parts = urlsplit(url)
    if (parts.scheme != "https" or not parts.hostname or parts.username is not None
            or parts.password is not None or parts.fragment or any(char.isspace() for char in url)):
        raise TransferError("GitLab package transport requires a credential-free HTTPS URL")
    return parts


@dataclass(frozen=True)
class Context:
    api: str
    project: str
    pipeline: str
    revision: str

    @classmethod
    def from_environment(cls, env):
        server = env["CI_SERVER_URL"].rstrip("/")
        api = env["CI_API_V4_URL"].rstrip("/")
        if https_url(server).query or api != server + "/api/v4":
            raise TransferError("GitLab API does not match the current CI server")
        project, pipeline, revision = (env[name] for name in ("CI_PROJECT_ID", "CI_PIPELINE_ID", "CI_COMMIT_SHA"))
        if not NUMBER.fullmatch(project) or not NUMBER.fullmatch(pipeline) or not REVISION.fullmatch(revision):
            raise TransferError("Invalid GitLab project, pipeline or source revision")
        return cls(api, project, pipeline, revision)

    def provenance(self, job):
        if not isinstance(job, str) or not NUMBER.fullmatch(job):
            raise TransferError("Invalid backend producer job ID")
        return {"format": 1, "api": self.api, "project": self.project, "pipeline": self.pipeline,
                "revision": self.revision, "producer_job": job, "package": PACKAGE,
                "version": f"{self.revision}-{self.pipeline}-{job}"}

    def url(self, version, path):
        return f"{self.api}/projects/{self.project}/packages/generic/{PACKAGE}/{version}/{FILES[path]}"


def workspace_path(root, path):
    target = root / path
    if target.resolve() != target or target.is_symlink():
        raise TransferError("Backend artifact path crosses a symbolic link")
    return target


def small_file(root, path):
    target = workspace_path(root, path)
    if not target.is_file() or target.stat().st_size > SMALL_FILE:
        raise TransferError("Missing or oversized backend artifact metadata")
    return target.read_text(encoding="utf-8")


def checksums(root):
    values = {}
    for line in small_file(root, CHECKSUMS).splitlines():
        checksum, delimiter, path = line.partition("  ")
        if not delimiter or not HEX.fullmatch(checksum) or path not in FILES or path in values:
            raise TransferError("Unexpected path, digest or duplicate in the backend checksum inventory")
        values[path] = checksum
    if values.keys() != FILES.keys():
        raise TransferError("Backend checksum inventory must contain all eight JARs")
    return values


def unique_object(pairs):
    result = dict(pairs)
    if len(result) != len(pairs):
        raise TransferError("Duplicate backend package receipt field")
    return result


def read_receipt(root, context, expected):
    receipt = json.loads(small_file(root, RECEIPT), object_pairs_hook=unique_object)
    if not isinstance(receipt, dict):
        raise TransferError("Invalid backend package receipt")
    files = receipt.pop("files", None)
    if receipt != context.provenance(receipt.get("producer_job")) or type(receipt["format"]) is not int:
        raise TransferError("Backend receipt does not belong to this GitLab project, pipeline and source revision")
    if not isinstance(files, dict) or files.keys() != FILES.keys():
        raise TransferError("Backend receipt must name exactly the eight expected JAR paths")
    for path, metadata in files.items():
        if (not isinstance(metadata, dict) or set(metadata) != {"sha256", "size"} or metadata["sha256"] != expected[path]
                or type(metadata["size"]) is not int or metadata["size"] <= 0):
            raise TransferError("Backend receipt disagrees with the tested JAR checksum inventory")
    return receipt["version"], files


def stream_digest(source, size, destination=None):
    result = sha256()
    remaining = size
    while block := source.read(min(BLOCK, remaining + 1)):
        remaining -= len(block)
        if remaining < 0:
            raise TransferError("Backend JAR exceeds its recorded size")
        result.update(block)
        if destination is not None:
            destination.write(block)
    if remaining:
        raise TransferError("Backend JAR is shorter than its recorded size")
    return result.hexdigest()


class Registry:
    def __init__(self, token, open_request):
        if not token or token != token.strip() or any(char.isspace() for char in token):
            raise TransferError("A GitLab CI job token is required")
        self.token = token
        self.open_request = open_request

    def request(self, url, *, data=None, size=None):
        headers = {"JOB-TOKEN": self.token}
        if data is not None:
            headers.update({"Content-Type": "application/octet-stream", "Content-Length": str(size)})
        return self.open_request(Request(url, data=data, headers=headers, method="PUT" if data is not None else "GET"), timeout=120)

    def fetch(self, url):
        try:
            return self.request(url)
        except HTTPError as error:
            with error:
                location = error.headers.get("Location")
                if error.code not in {302, 303, 307, 308} or not location:
                    raise
                target = urljoin(url, location)
                https_url(target)
            # A direct object-store download uses its signed URL, without the GitLab credential.
            return self.open_request(Request(target), timeout=120)

    def publish(self, url, path, metadata):
        try:
            with self.request(url):
                pass
        except HTTPError as error:
            with error:
                if error.code != 404:
                    raise
        else:
            raise TransferError("Backend package already exists for this producer job")
        with path.open("rb") as source, self.request(url, data=source, size=metadata["size"]) as response:
            if response.status != 201:
                raise TransferError("GitLab package upload did not return HTTP 201")
        self.verify(url, metadata)

    def verify(self, url, metadata, destination=None):
        with self.fetch(url) as response:
            if response.status != 200:
                raise TransferError("GitLab package download did not return HTTP 200")
            checksum = response.headers.get("X-Checksum-SHA256")
            length = response.headers.get("Content-Length")
            if ((checksum is not None and checksum != metadata["sha256"])
                    or (length is not None and length != str(metadata["size"]))):
                raise TransferError("GitLab package checksum or size differs from the tested JAR")
            if destination is not None or checksum is None or length is None:
                if stream_digest(response, metadata["size"], destination) != metadata["sha256"]:
                    raise TransferError("GitLab package bytes differ from the tested JAR")


def check_workspace_inventory(root):
    if {str(file.relative_to(root)) for file in root.glob("services/*/build/libs/app.jar")} - FILES.keys():
        raise TransferError("Unexpected backend app.jar in the workspace")


def upload(root, context, job, registry):
    receipt_file = workspace_path(root, RECEIPT)
    receipt_file.unlink(missing_ok=True)
    receipt = context.provenance(job)
    check_workspace_inventory(root)
    files = {}
    for path, checksum in checksums(root).items():
        artifact = workspace_path(root, path)
        if not artifact.is_file() or not (size := artifact.stat().st_size):
            raise TransferError("A tested backend JAR is missing or empty")
        with artifact.open("rb") as source:
            if stream_digest(source, size) != checksum:
                raise TransferError("A tested backend JAR no longer matches backend.sha256")
        files[path] = {"size": size, "sha256": checksum}
    for path, metadata in files.items():
        registry.publish(context.url(receipt["version"], path), workspace_path(root, path), metadata)
    with NamedTemporaryFile(mode="w", encoding="utf-8", dir=receipt_file.parent) as temporary:
        json.dump({**receipt, "files": files}, temporary, sort_keys=True)
        temporary.flush()
        os.link(temporary.name, receipt_file)


def download(root, context, registry):
    version, files = read_receipt(root, context, checksums(root))
    check_workspace_inventory(root)
    destinations = {path: workspace_path(root, path) for path in files}
    with TemporaryDirectory(prefix=".backend-package-", dir=root) as scratch:
        staged = Path(scratch)
        for path, metadata in files.items():
            with (staged / FILES[path]).open("wb") as destination:
                registry.verify(context.url(version, path), metadata, destination)
        for path, destination in destinations.items():
            destination.parent.mkdir(parents=True, exist_ok=True)
            (staged / FILES[path]).replace(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("upload", "download"))
    action = parser.parse_args().action
    try:
        context = Context.from_environment(os.environ)
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL).strip()
        if head != context.revision:
            raise TransferError("Workspace checkout differs from the GitLab source revision")
        tls = ssl.create_default_context()
        if ca := os.environ.get("CI_SERVER_TLS_CA_FILE"):
            tls.load_verify_locations(cafile=ca)
        registry = Registry(os.environ["CI_JOB_TOKEN"], build_opener(NoRedirect(), HTTPSHandler(context=tls)).open)
        if action == "upload":
            if os.environ["CI_JOB_NAME"] != "backend":
                raise TransferError("Only the backend job publishes the tested JARs")
            upload(Path.cwd().resolve(), context, os.environ["CI_JOB_ID"], registry)
        else:
            download(Path.cwd().resolve(), context, registry)
    except TransferError as error:
        parser.exit(1, f"Backend package {action}: {error}\n")
    except HTTPError as error:
        with error:
            parser.exit(1, f"Backend package {action}: GitLab HTTP {error.code}\n")
    except (OSError, URLError, HTTPException, KeyError, ValueError, subprocess.SubprocessError):
        parser.exit(1, f"Backend package {action} failed to read the CI context, metadata or transport\n")
    print(f"Backend package {action}: verified eight tested JARs")


if __name__ == "__main__":
    main()
