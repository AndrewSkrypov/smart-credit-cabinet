"""Verify active Kubernetes images and frontend bytes over the public HTTPS URL."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

from urllib.request import HTTPRedirectHandler, build_opener

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "deploy"))
from catalog import probe_mtls
from registry_release import RegistryRelease

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


def catalog_image_matches(expected, config_digest, observed):
    image_id = re.sub(r"^(docker-pullable|docker|containerd|cri-o)://", "", observed)
    return image_id in {expected, expected.rsplit("@", 1)[1], config_digest}


def verify_catalog_deployment(kubectl, release):
    deployment = json.loads(subprocess.check_output([*kubectl, "get", "deployment", "catalog-grpc", "-o", "json"]))
    metadata, spec, status = (deployment[key] for key in ("metadata", "spec", "status"))
    replicas = spec["replicas"]
    replicas_ready = all(status.get(key) == replicas for key in ("updatedReplicas", "readyReplicas", "availableReplicas"))
    template = spec["template"]
    images = [container["image"] for container in template["spec"]["containers"] if container["name"] == "catalog-grpc"]
    if (replicas < 1 or not replicas_ready or status.get("observedGeneration", 0) < metadata["generation"]
            or metadata.get("annotations", {}).get("cabinet.release/revision") != release.revision
            or images != [release.images["catalog-grpc"]]):
        raise SystemExit("catalog-grpc: Deployment has not reached the expected release and image")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("url")
    parser.add_argument("--context", required=True)
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--catalog-mode", choices=("local", "remote"))
    parser.add_argument("--internal-only", action="store_true")
    args = parser.parse_args()
    url = urlparse(args.url)
    if url.scheme != "https" or not url.hostname or url.path not in ("", "/") or url.query or url.fragment or url.username:
        parser.error("provide the public HTTPS origin")
    release = RegistryRelease(args.manifest)
    kubectl = ["kubectl", "--context", args.context, "--namespace", args.namespace, "--request-timeout=20s"]
    frontend_pods = []
    credit_pods = []
    pod_counts = {}
    workloads = dict(WORKLOAD_IMAGES)
    if args.catalog_mode:
        if not (release.catalog or {}).get("config_digest"):
            raise SystemExit("Catalog requires OCI provenance from the protected release job")
        verify_catalog_deployment(kubectl, release)
        workloads["catalog-grpc"] = "catalog-grpc"
    if "clamav" in release.images:
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
                and metadata.get("annotations", {}).get("cabinet.release/revision") != release.revision
            ):
                raise SystemExit(f"{name}: running Pod has a different revision")
            expected = release.images[component]
            matching = [c for c in pod["spec"]["containers"] if c["image"] == expected]
            running = [c for c in pod["status"].get("containerStatuses", [])
                       if len(matching) == 1 and c["name"] == matching[0]["name"] and c["ready"] and c.get("imageID")]
            if len(running) != 1:
                raise SystemExit(f"{name}: active Pod is not ready with the expected digest")
            if name == "catalog-grpc" and not catalog_image_matches(expected, release.catalog["config_digest"], running[0]["imageID"]):
                raise SystemExit("catalog-grpc: runtime imageID does not match the release OCI manifest or config")
            if name == "credit" and args.catalog_mode:
                mode = [e.get("value") for e in matching[0].get("env", []) if e["name"] == "CABINET_CATALOG_MODE"]
                if mode != [args.catalog_mode]:
                    raise SystemExit("credit: Pod is configured for a different Catalog mode")
                credit_pods.append((metadata["name"], matching[0]["name"]))
            if name == "frontend":
                frontend_pods.append((metadata["name"], matching[0]["name"]))
        pod_counts[name] = len(active)

    report = {"revision": release.revision, "readyPods": pod_counts}
    if args.catalog_mode:
        for pod, container in credit_pods:
            probe_mtls([*kubectl, "exec", "-i", pod, "-c", container, "--"], args.catalog_mode)
        report["catalog"] = {
            "mode": args.catalog_mode, "registry_ref": release.images["catalog-grpc"],
            "oci_config_digest": release.catalog["config_digest"], "jar_sha256": release.catalog["jar_sha256"],
            "mtls": True,
        }
    if args.internal_only:
        print(json.dumps(report))
        return

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
    print(json.dumps({**report, "assetSHA256": asset_hash, "url": args.url}))


if __name__ == "__main__":
    main()
