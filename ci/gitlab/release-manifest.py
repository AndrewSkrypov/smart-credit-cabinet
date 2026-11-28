"""Validate the release artifact and optionally emit its Helm values."""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "deploy"))
from registry_release import IMAGE_COMPONENTS, OPTIONAL_IMAGES, RegistryRelease

SERVICES = set(IMAGE_COMPONENTS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("revision")
    parser.add_argument("repository")
    parser.add_argument("--helm-values", type=Path)
    parser.add_argument("--require-catalog", action="store_true")
    parser.add_argument("--require-runtimes", action="store_true")
    parser.add_argument("--require-mcp", action="store_true")
    args = parser.parse_args()
    try:
        manifest = RegistryRelease(args.manifest)
    except (OSError, ValueError):
        parser.error("invalid release manifest")
    if not re.fullmatch(r"[a-f0-9]{40}", args.revision) or manifest.revision != args.revision:
        parser.error("release revision does not match checkout")
    if args.require_catalog and not (manifest.catalog or {}).get("config_digest"):
        parser.error("a new release requires Catalog image provenance")
    if args.require_runtimes and not manifest.has_all_runtimes:
        parser.error("a new release requires all four runtime images and Kafka provenance")
    if args.require_mcp and not manifest.mcp:
        parser.error("a new release requires the MCP image and its OCI provenance")
    images = manifest.images
    if not SERVICES <= set(images) <= (SERVICES | OPTIONAL_IMAGES):
        parser.error("release images have unexpected service names")
    for service, ref in images.items():
        prefix = re.escape(f"{args.repository}/{service}@sha256:")
        if not isinstance(ref, str) or not re.fullmatch(prefix + r"[a-f0-9]{64}", ref):
            parser.error(f"invalid image digest or repository for {service}")
    if args.helm_values:
        helm_images = dict(images)
        if manifest.assessment:
            helm_images["assessment-kafka"] = manifest.kafka_image
        args.helm_values.write_text(json.dumps({"release": {"revision": args.revision}, "images": helm_images}) + "\n")


if __name__ == "__main__":
    main()
