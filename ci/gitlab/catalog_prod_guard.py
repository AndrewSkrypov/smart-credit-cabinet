"""Decide when a Helm upgrade can safely revert its workload revision."""
import argparse
import json
from pathlib import Path


def settings(catalog):
    enabled = catalog.get("enabled", False)
    maintenance = catalog.get("maintenance", False)
    mode = catalog.get("creditMode", "local")
    if type(enabled) is not bool or type(maintenance) is not bool or mode not in ("local", "remote"):
        raise ValueError("Invalid Catalog rollout settings")
    if mode == "remote" and not enabled:
        raise ValueError("Remote Credit requires Catalog")
    return enabled, mode, maintenance


def database_mode(credit, catalog, *, enabled):
    if (credit, catalog) == ("f", "t"):
        return "remote"
    if (credit, catalog) == ("t", "f"):
        return "local"
    if not enabled and credit in {"absent", "unfenced", "t"} and catalog in {"absent", "unfenced", "f"}:
        return "local"
    raise ValueError("PostgreSQL did not confirm a single Catalog writer")


def upgrade_strategy(current, desired, status, flags, *, opening=None):
    enabled, mode, maintenance = settings(current.get("catalog", {}))
    want_enabled, want_mode, want_maintenance = settings(desired)
    if status in {"pending-install", "pending-upgrade", "pending-rollback", "uninstalling"}:
        raise ValueError("Resolve the unfinished Helm operation with the gateway closed")
    if opening is not None:
        pending = {
            "revision": desired.get("revision"), "mode": want_mode,
            "enabled": "true" if want_enabled else "false",
        }
        if want_maintenance:
            if mode != want_mode:
                raise ValueError("Restage the current Catalog mode before changing the writer again")
        elif (opening != pending or status not in {"deployed", "failed"}
              or (enabled, mode) != (want_enabled, want_mode)
              or current.get("release", {}).get("revision") != desired.get("revision")):
            raise ValueError("Retry the marked gateway opening or restage the current mode in maintenance")
    if want_mode != mode and not (
        current.get("app", {}).get("enabled") is True and enabled and want_enabled and maintenance and want_maintenance
    ):
        raise ValueError("Catalog owner changes require both runtimes enabled in a separate maintenance step")
    if database_mode(*flags, enabled=enabled) != want_mode:
        raise ValueError("Apply and verify the Catalog history transfer before starting the requested Credit mode")

    if want_mode != mode:
        return "switch"
    if want_maintenance:
        return "hold"
    if opening is not None:
        return "open"
    if maintenance:
        if (status != "deployed" or want_enabled != enabled
                or current.get("release", {}).get("revision") != desired.get("revision")):
            raise ValueError("Stage the same verified Helm revision in maintenance before reopening the gateway")
        return "open"
    if status in {"deployed", "missing"}:
        return "automatic"
    return "wait"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("current", type=Path)
    parser.add_argument("desired", type=Path)
    parser.add_argument("status")
    parser.add_argument("credit", choices=("absent", "unfenced", "f", "t"))
    parser.add_argument("catalog", choices=("absent", "unfenced", "f", "t"))
    parser.add_argument("--opening", type=Path)
    args = parser.parse_args()
    try:
        current = json.loads(args.current.read_text())
        desired = json.loads(args.desired.read_text())
        marker = args.opening.read_text().strip() if args.opening else ""
        opening = json.loads(marker)["data"] if marker else None
        if marker and not isinstance(opening, dict):
            raise ValueError("Invalid gateway opening marker")
        strategy = upgrade_strategy(current, desired, args.status, (args.credit, args.catalog), opening=opening)
        enabled, mode, _ = settings(desired)
    except (TypeError, ValueError, AttributeError, KeyError, OSError) as error:
        parser.exit(1, f"Catalog rollout blocked. {error}\n")
    print(strategy, mode, "true" if enabled else "false")


if __name__ == "__main__":
    main()
