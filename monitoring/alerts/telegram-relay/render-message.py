#!/usr/bin/env python3
"""Stage a message-only update to the protected native Alertmanager config."""
import argparse
import os
from pathlib import Path

import yaml


def render(existing, environment):
    config = yaml.safe_load(existing)
    name = f"telegram-{environment}"
    receivers = [item for item in config["receivers"] if item.get("name") == name]
    if len(receivers) != 1 or len(receivers[0].get("telegram_configs", [])) != 1:
        raise ValueError(f"Expected one Telegram integration in {name}")
    telegram = receivers[0]["telegram_configs"][0]
    telegram["parse_mode"] = "HTML"
    telegram["message"] = Path(__file__).with_name("message.tmpl").read_text().rstrip("\n")
    return yaml.safe_dump(config, sort_keys=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", choices=("dev", "prod"), required=True)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        if (args.input.stat().st_mode & 0o077) not in (0, 0o040):
            parser.error("Alertmanager config requires a protected input file")
        result = render(args.input.read_text(), args.environment)
        with os.fdopen(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as output:
            output.write(result)
    except (KeyError, TypeError, ValueError, OSError, yaml.YAMLError):
        parser.exit(1, "Unable to stage a protected Telegram-only format update.\n")


if __name__ == "__main__":
    main()
