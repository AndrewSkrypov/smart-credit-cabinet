#!/usr/bin/env python3
"""Validate the production boundary and start its explicitly selected local Docker project."""
import argparse
import subprocess
import sys
from common import Deployment, ROOT

parser = argparse.ArgumentParser(description="Проверить настройки и запустить prod-профиль")
parser.add_argument("--project", default="cabinet-prod")
parser.add_argument("--env-file", default="deploy/.env")
parser.add_argument("--local", action="store_true")
parser.add_argument("--no-build", action="store_true")
parser.add_argument("--check-only", action="store_true")
args = parser.parse_args()
deployment = Deployment(args.project, args.env_file, args.local)
subprocess.run([sys.executable, str(ROOT / "deploy/render-gateway.py"), "--caddy-ip", deployment.values.get("CADDY_EDGE_IP", "172.30.40.2"),
                "--output", str(deployment.gateway_config)], check=True)
config = deployment.config()
active = deployment.active_services(config)
assert not active["gateway"].get("ports"), "Gateway не должен иметь опубликованных портов"
assert all(not service.get("ports") for name, service in active.items() if name != "caddy"), "Наружу публикуется только Caddy"
budget = sum(int(service["mem_limit"]) for service in active.values())
assert budget <= 6 * 1024**3, "Лимиты приложений превышают 6 GiB; оставьте 2 GiB для ОС/Docker на VPS 8 GiB"
print(f"Проверены {len(active)} компонентов, сумма лимитов {budget / 1024**2:.0f} MiB, секреты не выводятся")
if not args.check_only:
    if not args.no_build:
        deployment.assert_build_revision()
    deployment.compose("stop", "caddy")
    arguments = ["up", "-d", "--wait", "--wait-timeout", "300", "--no-build" if args.no_build else "--build"]
    deployment.compose(*arguments, *[name for name in active if name != "caddy"])
    count = deployment.block_demo_accounts()
    deployment.compose("up", "-d", "--no-deps", "caddy")
    print(f"Стенд запущен; до открытия HTTPS дополнительно заблокировано старых демо-учёток: {count}")
