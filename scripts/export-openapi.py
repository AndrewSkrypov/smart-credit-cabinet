#!/usr/bin/env python3
"""Export the four live service contracts or compare them with the saved YAML."""
import argparse
import copy
import difflib
import json
import os
from pathlib import Path
import sys
import urllib.request
from urllib.parse import urlsplit, urlunsplit

import yaml

ROOT = Path(__file__).resolve().parent.parent
SERVICES = ("identity", "credit", "engagement", "external-sim")
METHODS = {"get", "post", "put", "patch", "delete", "head", "options"}


def canonical(document):
    result = copy.deepcopy(document)
    # Only Spring's request-derived origins vary between equivalent test environments.
    for server in result.get("servers", []):
        if server.get("description") == "Generated server url":
            parts = urlsplit(server["url"])
            server["url"] = urlunsplit(("", "", parts.path or "/", parts.query, parts.fragment))
            server["description"] = "Адрес сервиса или шлюза; /ext/ доступен только во внутренней сети"
    return result


def validate(document, service):
    if not document.get("openapi", "").startswith("3.") or not document.get("paths"):
        raise ValueError(f"{service}: нет корректного OpenAPI 3 и операций")
    operations = 0
    for path, item in document["paths"].items():
        if path.startswith("/internal/"):
            raise ValueError(f"{service}: внутренний путь попал в публичную спецификацию: {path}")
        for method, operation in item.items():
            if method not in METHODS:
                continue
            operations += 1
            if not operation.get("summary"):
                raise ValueError(f"{service}: нет описания {method.upper()} {path}")
    if "bearerAuth" not in document.get("components", {}).get("securitySchemes", {}):
        raise ValueError(f"{service}: не описана схема Bearer JWT")
    return operations


def main():
    parser = argparse.ArgumentParser(description="Выгрузить OpenAPI из стенда или проверить расхождения с contracts/openapi")
    parser.add_argument("--base-url", default=os.environ.get("CABINET_URL", "http://localhost:8080"))
    parser.add_argument("--output", type=Path, default=ROOT / "contracts" / "openapi")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    failed = False
    if not args.check:
        args.output.mkdir(parents=True, exist_ok=True)
    for service in SERVICES:
        with urllib.request.urlopen(f"{args.base_url.rstrip('/')}/v3/api-docs/{service}", timeout=30) as response:
            live = canonical(json.load(response))
        count = validate(live, service)
        target = args.output / f"{service}.yaml"
        if args.check:
            if not target.is_file():
                print(f"Нет спецификации {target}", file=sys.stderr)
                failed = True
                continue
            saved = yaml.safe_load(target.read_text(encoding="utf-8"))
            if saved != live:
                failed = True
                left = json.dumps(saved, ensure_ascii=False, sort_keys=True, indent=2).splitlines()
                right = json.dumps(live, ensure_ascii=False, sort_keys=True, indent=2).splitlines()
                print("\n".join(difflib.unified_diff(left, right, fromfile=str(target), tofile=f"live/{service}")), file=sys.stderr)
            else:
                print(f"{service}: {count} операций, расхождений нет")
        else:
            target.write_text(yaml.safe_dump(live, allow_unicode=True, sort_keys=True, width=120), encoding="utf-8", newline="\n")
            print(f"{service}: {count} операций → {target}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
