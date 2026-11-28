"""Verify active Kubernetes images and frontend bytes over the public HTTPS URL."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener

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


def verify_mcp_routes(origin, opener, fetch):
    resource = origin + "/api/mcp"
    scope = "cabinet:mcp:read"
    a = json.loads(fetch("/.well-known/oauth-authorization-server", 8192))
    b = json.loads(fetch("/.well-known/oauth-protected-resource/api/mcp", 8192))
    if (a.get("issuer") != origin or a.get("authorization_endpoint") != origin + "/api/mcp/oauth/authorize"
            or a.get("token_endpoint") != origin + "/api/mcp/oauth/token"
            or a.get("revocation_endpoint") != origin + "/api/mcp/oauth/revoke"
            or a.get("scopes_supported") != [scope] or "S256" not in a.get("code_challenge_methods_supported", [])
            or b.get("resource") != resource or b.get("authorization_servers") != [origin]
            or b.get("scopes_supported") != [scope]):
        raise SystemExit("MCP OAuth metadata does not match the selected public origin and read scope")

    for path, status in (("/api/mcp", 401), ("/api/mcp/oauth/other", 404),
                         ("/internal/mcp/authorize", 404), ("/internal/mcp/redeem", 404),
                         ("/internal/mcp/credit/priorities", 404)):
        request = Request(origin + path, data=b"{}", headers={"Content-Type": "application/json"}, method="POST")
        try:
            with opener.open(request, timeout=15):
                raise SystemExit(f"MCP route {path} unexpectedly accepted an unauthenticated request")
        except HTTPError as error:
            with error:
                if error.code != status:
                    raise SystemExit(f"MCP route {path} expected HTTP {status}, received HTTP {error.code}") from None
                if status == 401 and 'resource_metadata="' + origin + '/.well-known/oauth-protected-resource/api/mcp"' not in (
                    error.headers.get("WWW-Authenticate", "")
                ):
                    raise SystemExit("MCP bearer challenge is missing the exact protected resource metadata URL") from None


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
    mcp_modes = set()
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
            if name == "identity":
                mcp_options = [item.get("value") for item in matching[0].get("env", [])
                               if item["name"] == "CABINET_MCP_OAUTH_ENABLED"]
                if mcp_options not in ([], ["false"], ["true"]):
                    raise SystemExit("identity: invalid MCP activation mode")
                mcp_modes.add(mcp_options == ["true"])
                if mcp_options == ["true"]:
                    sidecar = [item for item in pod["status"].get("containerStatuses", [])
                               if item["name"] == "mcp-identity-tls" and item["ready"] and item.get("imageID")]
                    if len(sidecar) != 1:
                        raise SystemExit("identity: MCP TLS sidecar is not ready")
            if name == "credit" and args.catalog_mode:
                mode = [e.get("value") for e in matching[0].get("env", []) if e["name"] == "CABINET_CATALOG_MODE"]
                if mode != [args.catalog_mode]:
                    raise SystemExit("credit: Pod is configured for a different Catalog mode")
                credit_pods.append((metadata["name"], matching[0]["name"]))
            if name == "frontend":
                frontend_pods.append((metadata["name"], matching[0]["name"]))
        pod_counts[name] = len(active)

    if len(mcp_modes) != 1:
        raise SystemExit("identity: inconsistent MCP activation mode")
    mcp_enabled = True in mcp_modes
    if mcp_enabled:
        if not (release.mcp or {}).get("config_digest"):
            raise SystemExit("The immutable release has no MCP OCI provenance")
        selector = "app.kubernetes.io/instance=cabinet,app.kubernetes.io/name=cabinet-mcp"
        pods = json.loads(subprocess.check_output([*kubectl, "get", "pods", "-l", selector, "-o", "json"]))["items"]
        active = [pod for pod in pods if not pod["metadata"].get("deletionTimestamp")]
        if len(active) < 2 or any(
            pod["metadata"].get("annotations", {}).get("cabinet.release/revision") != release.revision
            or [c["image"] for c in pod["spec"]["containers"] if c["name"] == "cabinet-mcp"] != [release.images["mcp"]]
            or not any(
                c["name"] == "cabinet-mcp" and c["ready"] and c.get("imageID")
                and catalog_image_matches(release.images["mcp"], release.mcp["config_digest"], c["imageID"])
                for c in pod["status"].get("containerStatuses", [])
            )
            for pod in active
        ):
            raise SystemExit("MCP: active Pods are not ready with the expected release image")
        pod_counts["cabinet-mcp"] = len(active)
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

    if mcp_enabled:
        verify_mcp_routes(args.url.rstrip("/"), opener, fetch)
        report["mcpPublicReadiness"] = True
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
