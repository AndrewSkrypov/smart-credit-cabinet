#!/usr/bin/env python3
"""Create a private environment file without echoing generated credentials."""
import argparse
import os
from pathlib import Path
import re
import secrets
import subprocess

parser = argparse.ArgumentParser(description="Создать новые секреты развёртывания; существующий файл не перезаписывается")
parser.add_argument("--local", action="store_true", help="Только локальная проверка с внутренним CA Caddy")
parser.add_argument("--domain")
parser.add_argument("--email")
parser.add_argument("--output", default="deploy/.env")
parser.add_argument("--storage", choices=["local", "s3"], default="local")
args = parser.parse_args()
if not args.local and (not args.domain or not args.email):
    parser.error("Для сервера задайте --domain и --email владельца")
domain = "localhost" if args.local else args.domain
email = "operator@demo.test" if args.local else args.email
if not re.fullmatch(r"[a-zA-Z0-9.-]+", domain) or not re.fullmatch(r"[a-zA-Z0-9_.+@-]+", email):
    parser.error("Домен/почта содержат неподдерживаемые символы")
root = Path(__file__).resolve().parent.parent
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
values = {"CABINET_DOMAIN": domain, "ACME_EMAIL": email, "CABINET_RELEASE": revision, "POSTGRES_USER": "cabinet",
          "POSTGRES_PASSWORD": secrets.token_urlsafe(32), "JWT_SECRET": secrets.token_urlsafe(48), "SIM_API_KEY": secrets.token_urlsafe(32),
          "IDENTITY_LINK_KEY": secrets.token_urlsafe(48), "IDENTITY_LINK_KEY_VERSION": "1",
          "VALKEY_PASSWORD": secrets.token_urlsafe(32),
          "PUBLIC_CABINET_URL": "https://" + domain,
          "DEMO_ACCOUNTS": "false", "SIM_CONTROL_ENABLED": "false", "EDGE_SUBNET": "172.30.40.0/28", "CADDY_EDGE_IP": "172.30.40.2",
          "GATEWAY_EDGE_IP": "172.30.40.3"}
values.update(STORAGE_BACKEND=args.storage, STORAGE_ENABLED="true" if args.storage == "s3" else "false",
              S3_ACCESS_KEY=secrets.token_urlsafe(24), S3_SECRET_KEY=secrets.token_urlsafe(32),
              S3_ADMIN_ACCESS_KEY=secrets.token_urlsafe(24), S3_ADMIN_SECRET_KEY=secrets.token_urlsafe(32))
for service in ["IDENTITY", "CREDIT", "ENGAGEMENT"]:
    values[service + "_DB_PASSWORD"] = secrets.token_urlsafe(32)
    values[service + "_MIGRATION_PASSWORD"] = secrets.token_urlsafe(32)
if args.local:
    values.update(HTTP_PORT="18080", HTTPS_PORT="18443", HTTP_BIND="127.0.0.1", HTTPS_BIND="127.0.0.1", PUBLIC_CABINET_URL="https://localhost:18443")
target = Path(args.output).resolve()
target.parent.mkdir(parents=True, exist_ok=True)
descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
    stream.write("".join(f"{key}={value}\n" for key, value in values.items()))
print(f"Закрытый файл настроек создан: {target}. Значения секретов не выводятся.")
