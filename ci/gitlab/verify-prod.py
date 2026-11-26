"""Verify active Kubernetes images and frontend bytes over the public HTTPS URL."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import urlparse

from urllib.request import HTTPRedirectHandler, build_opener

WORKLOAD_IMAGES = {
    "identity": "identity", "credit": "credit", "engagement": "engagement",
    "external-sim": "external-sim", "frontend": "frontend", "swagger-ui": "swagger",
    "postgres": "postgres",
}
STATEFUL_WITHOUT_RELEASE_ANNOTATION = {"postgres", "clamav"}
ENTRY = re.compile(rb"""<script\b[^>]*\bsrc=["'](/assets/index-[\w-]+\.js)["']""")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("url")
    parser.add_argument("--context", required=True)
    parser.add_argument("--namespace", required=True)
    args = parser.parse_args()
    url = urlparse(args.url)
    if url.scheme != "https" or not url.hostname or url.path not in ("", "/") or url.query or url.fragment or url.username:
        parser.error("provide the public HTTPS origin")
    manifest = json.loads(args.manifest.read_text())
    kubectl = ["kubectl", "--context", args.context, "--namespace", args.namespace, "--request-timeout=20s"]
    frontend_pods = []
    pod_counts = {}
    workloads = dict(WORKLOAD_IMAGES)
    if "clamav" in manifest["images"]:
        workloads["clamav"] = "clamav"
    for name, component in sorted(workloads.items()):
        selector = f"app.kubernetes.io/instance=cabinet,app.kubernetes.io/name={name}"
        pods = json.loads(subprocess.check_output([*kubectl, "get", "pods", "-l", selector, "-o", "json"]))["items"]
        active = [pod for pod in pods if not pod["metadata"].get("deletionTimestamp")]
        if not active:
            raise SystemExit(f"{name}: active Pods missing")
        for pod in active:
            metadata = pod["metadata"]
            if (
                name not in STATEFUL_WITHOUT_RELEASE_ANNOTATION
                and metadata.get("annotations", {}).get("cabinet.release/revision") != manifest["revision"]
            ):
                raise SystemExit(f"{name}: running Pod has a different revision")
            expected = manifest["images"][component]
            matching = [c for c in pod["spec"]["containers"] if c["image"] == expected]
            if len(matching) != 1 or not any(
                c["name"] == matching[0]["name"] and c["ready"] and c.get("imageID")
                for c in pod["status"].get("containerStatuses", [])
            ):
                raise SystemExit(f"{name}: active Pod is not ready with the expected digest")
            if name == "frontend":
                frontend_pods.append((metadata["name"], matching[0]["name"]))
        pod_counts[name] = len(active)

    opener = build_opener(NoRedirect())

    def fetch(path, limit):
        with opener.open(args.url.rstrip("/") + path, timeout=15) as response:
            result = response.read(limit + 1)
            if response.status != 200 or len(result) > limit:
                raise SystemExit(f"Invalid HTTPS response at {path}")
            return result

    fetch("/healthz", 1024)
    entries = ENTRY.findall(fetch("/", 1024 * 1024))
    if len(entries) != 1:
        raise SystemExit("HTTPS index is missing its frontend entry")
    entry = entries[0].decode("ascii")
    asset_hash = hashlib.sha256(fetch(entry, 16 * 1024 * 1024)).hexdigest()
    for pod, container in frontend_pods:
        asset = subprocess.check_output([*kubectl, "exec", pod, "-c", container, "--", "cat", "/usr/share/nginx/html" + entry], timeout=30)
        if hashlib.sha256(asset).hexdigest() != asset_hash:
            raise SystemExit(f"{pod}: frontend does not match public HTTPS bytes")
    print(json.dumps({"revision": manifest["revision"], "readyPods": pod_counts, "assetSHA256": asset_hash, "url": args.url}))


if __name__ == "__main__":
    main()
