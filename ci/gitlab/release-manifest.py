"""Validate the release artifact and optionally emit its Helm values."""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "deploy"))
from registry_release import RegistryRelease

SERVICES = {"identity", "credit", "engagement", "external-sim", "frontend", "postgres", "swagger"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("revision")
    parser.add_argument("repository")
    parser.add_argument("--helm-values", type=Path)
    args = parser.parse_args()
    try:
        manifest = RegistryRelease(args.manifest)
    except (OSError, ValueError):
        parser.error("invalid release manifest")
    if not re.fullmatch(r"[a-f0-9]{40}", args.revision) or manifest.revision != args.revision:
        parser.error("release revision does not match checkout")
    images = manifest.images
    if not SERVICES <= set(images) <= (SERVICES | {"ssh-bridge", "clamav", "documents"}):
        parser.error("release images have unexpected service names")
    for service, ref in images.items():
        prefix = re.escape(f"{args.repository}/{service}@sha256:")
        if not isinstance(ref, str) or not re.fullmatch(prefix + r"[a-f0-9]{64}", ref):
            parser.error(f"invalid image digest or repository for {service}")
    if args.helm_values:
        args.helm_values.write_text(json.dumps({"release": {"revision": args.revision}, "images": images}) + "\n")


if __name__ == "__main__":
    main()
