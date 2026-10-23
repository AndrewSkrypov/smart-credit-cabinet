#!/usr/bin/env python3
"""Add independent database secrets to a new private env file, preserving the source."""
import argparse
import os
from pathlib import Path
import re
import secrets


def upgrade(source, target):
    original = Path(source).read_text(encoding="utf-8")
    values = {}
    for line in original.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"([A-Z_][A-Z_0-9]*)=([^\r\n]*)", line)
        if not match or match[1] in values:
            raise ValueError("Ожидаются уникальные KEY=value; исходный файл не изменён")
        values[match[1]] = match[2]
    keys = [service + suffix for service in ["IDENTITY", "CREDIT", "ENGAGEMENT"]
            for suffix in ["_DB_PASSWORD", "_MIGRATION_PASSWORD"]]
    additions = {}
    for key in keys:
        if key in values:
            value = values[key]
            if len(value) < 32 or not re.fullmatch(r"[A-Za-z0-9_-]+", value) or value.startswith("dev-") or "CHANGE_ME" in value:
                raise ValueError("Существующий секрет роли не подходит; значение не выводится")
        else:
            additions[key] = secrets.token_urlsafe(32)
    combined = {**values, **additions}
    protected = keys + [key for key in ["POSTGRES_PASSWORD", "JWT_SECRET", "SIM_API_KEY", "IDENTITY_LINK_KEY", "VALKEY_PASSWORD",
                                        "S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_ADMIN_ACCESS_KEY", "S3_ADMIN_SECRET_KEY"] if combined.get(key)]
    if len({combined[key] for key in protected}) != len(protected):
        raise ValueError("Секреты ролей и остальных служб должны различаться")
    descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        stream.write(original.rstrip("\r\n") + "\n")
        stream.write("".join(f"{key}={value}\n" for key, value in additions.items()))
    return len(additions)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Добавить пароли ролей в новый env; старый файл сохраняется")
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    count = upgrade(args.env_file, args.output)
    print(f"Создан новый закрытый env; добавлено секретов: {count}. Исходный файл и его ключи сохранены.")
