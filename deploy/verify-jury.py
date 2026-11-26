#!/usr/bin/env python3
"""Check three real roles against a jury endpoint with a private credentials file."""
import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit

from jury import read_credentials, verify


def main():
    parser = argparse.ArgumentParser(description="Проверить роли и мандат жюри по HTTPS")
    parser.add_argument("--url", required=True)
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--ca", help="CA для изолированного локального стенда")
    args = parser.parse_args()
    origin = urlsplit(args.url)
    if (origin.scheme != "https" or not origin.hostname or origin.username or origin.password
            or origin.path not in ("", "/") or origin.query or origin.fragment):
        parser.error("--url должен быть HTTPS origin")
    try:
        checks = verify(args.url.rstrip("/"), read_credentials(args.credentials, allow_custom_emails=True), args.ca)
    except Exception as error:
        # Transport exceptions may include private data. Sanitized check failures are safe.
        message = str(error) if type(error) is RuntimeError else type(error).__name__
        parser.exit(1, f"Проверка жюри не прошла. {message}\n")
    print(json.dumps({"passed": True, "checks": checks}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
