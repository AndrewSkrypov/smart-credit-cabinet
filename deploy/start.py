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
parser.add_argument("--release-manifest")
parser.add_argument("--check-only", action="store_true")
parser.add_argument("--host-memory-gib", type=int, default=8)
args = parser.parse_args()
if args.host_memory_gib < 4:
    parser.error("Укажите объём RAM сервера в GiB, минимум 4")
if args.release_manifest and not args.no_build:
    parser.error("--release-manifest требует --no-build")
deployment = Deployment(args.project, args.env_file, args.local, args.release_manifest)
subprocess.run([sys.executable, str(ROOT / "deploy/render-gateway.py"), "--caddy-ip", deployment.values.get("CADDY_EDGE_IP", "172.30.40.2"),
                "--output", str(deployment.gateway_config)], check=True)
config = deployment.config()
active = deployment.active_services(config)
assert not active["gateway"].get("ports"), "Gateway не должен иметь опубликованных портов"
assert all(
    not service.get("ports") or (name == "grafana" and all(port.get("host_ip") == "127.0.0.1" for port in service["ports"]))
    for name, service in active.items() if name != "caddy"
), "Наружу публикуется только Caddy. Grafana может слушать локальный адрес."
budget = sum(int(service["mem_limit"]) for service in active.values())
assert budget <= (args.host_memory_gib - 2) * 1024**3, "Лимиты приложений должны оставить 2 GiB для ОС и Docker"
print(f"Проверены {len(active)} компонентов, сумма лимитов {budget / 1024**2:.0f} MiB, секреты не выводятся")
if not args.check_only:
    if deployment.release:
        deployment.release.pull_and_verify(active)
    elif not args.no_build:
        deployment.assert_build_revision()
    if "ollama-pull" in active:
        deployment.compose("up", "-d", "--wait", "--wait-timeout", "90", "ollama")
        deployment.compose("run", "--rm", "--no-deps", "ollama-pull")
    # Stop existing writers before bootstrap changes ownership and the owners migrate.
    deployment.compose("stop", "caddy", "identity", "credit", "engagement")
    arguments = ["up", "-d", "--wait", "--wait-timeout", "300", "--no-build" if args.no_build else "--build"]
    deployment.compose(*arguments, *[name for name in active if name not in {"caddy", "ollama-pull"}])
    count = deployment.block_demo_accounts()
    deployment.compose("up", "-d", "--no-deps", "caddy")
    print(f"Стенд запущен; до открытия HTTPS дополнительно заблокировано старых демо-учёток: {count}")
