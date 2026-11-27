#!/usr/bin/env python3
"""Validate the production boundary and start its explicitly selected local Docker project."""
import argparse
import subprocess
import sys
from alpha_dev import bootstrap, prepare
from catalog import check_tls
from common import Deployment, ROOT, deploy_lock
import runtime_attempts
from runtime_backup import resume_alpha_stores, verify_alpha_catchup
from runtime_delivery_support import read_record, write_record
from runtime_rollout import MIGRATIONS, prepare_credit, prepare_private, stop_writers, verify_private

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
with deploy_lock(args.env_file, args.project):
    deployment = Deployment(args.project, args.env_file, args.local, args.release_manifest)
    attempt = None
    if (deployment.release and deployment.release.has_all_runtimes and (deployment.runtime_modes or deployment.alpha_dev)
            and args.project == "cabinet-dev" and not args.check_only):
        attempt = runtime_attempts.load(deployment.path.parent, deployment.release)
        if read_record(deployment.path.parent / "pending.json") != attempt["target"]:
            raise RuntimeError("Предварительная запись Dev не совпадает с попыткой общего выпуска")
    if deployment.catalog_enabled:
        check_tls(deployment.catalog_state / "tls")
    elif (deployment.catalog_state / "authority.json").exists():
        parser.error("Catalog уже введён. Для возврата используйте проверенный локальный режим.")
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
        if deployment.ollama_ssh:
            # Remove the old bridge before changing the engagement network namespace.
            deployment.compose("rm", "--stop", "--force", "ollama-ssh-bridge")
        previous_alpha = attempt["alpha"]["frozen"] if attempt and attempt.get("alpha") else None
        arguments = ["up", "-d", "--wait", "--wait-timeout", "300", "--no-build" if args.no_build else "--build"]
        if deployment.documents:
            deployment.documents.preflight(deployment)
        if previous_alpha:
            resume_alpha_stores(deployment, attempt, arguments)
        if deployment.runtime_modes:
            # Prepare independent backends only after the previous ingress and every writer are stopped.
            if not previous_alpha:
                stop_writers(deployment)
            prepare_credit(deployment, arguments, storage_ready=bool(previous_alpha))
            if "seaweedfs" in active:
                deployment.compose(*arguments, "--no-deps", "seaweedfs")
        expected_marker = previous_alpha["checkpoint"]["marker"] if previous_alpha else None
        alpha_bootstrap = deployment.alpha_dev is not None and prepare(
            deployment, arguments, expected_marker, storage_ready=bool(previous_alpha),
        )
        if not deployment.runtime_modes and not previous_alpha:
            deployment.compose("stop", "caddy", "identity", "credit", "engagement")
        if alpha_bootstrap:
            bootstrap(deployment, arguments)
        excluded = {"caddy", "ollama-pull"}
        if deployment.runtime_modes:
            excluded.update(MIGRATIONS)
            receipt = prepare_private(deployment, arguments)
            # The owner grants were checked by the hooks. Do not implicitly rerun db-init here.
            arguments.append("--no-deps")
        deployment.compose(*arguments, *[name for name in active if name not in excluded])
        if deployment.runtime_modes:
            verify_private(deployment, receipt)
        if previous_alpha:
            proof = verify_alpha_catchup(deployment, previous_alpha)
            ready = deployment.path.parent / "runtime-attempts" / (attempt["attempt"] + "-alpha-ready.json")
            observed = read_record(ready)
            if observed is None:
                write_record(ready, {**proof, "manifest_sha256": deployment.release.checksum}, immutable=True)
            elif observed.get("manifest_sha256") != deployment.release.checksum or observed.get("caught_up") is not True:
                raise RuntimeError("Изменилась квитанция приёмки Alpha")
        count = 0 if deployment.isolated_demo else deployment.block_demo_accounts()
        if args.project.startswith("cabinet-dev-restore-"):
            print("Изолированные runtime подтверждены; HTTPS остаётся закрытым")
        else:
            deployment.compose("up", "-d", "--no-deps", "caddy")
            print(f"Стенд запущен; до открытия HTTPS дополнительно заблокировано старых демо-учёток: {count}")
