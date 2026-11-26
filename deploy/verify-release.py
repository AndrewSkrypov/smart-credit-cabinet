#!/usr/bin/env python3
"""Verify the running Compose images and the frontend served over HTTPS."""
import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
import re
import ssl
import subprocess
from urllib.error import HTTPError, URLError
from urllib.request import HTTPSHandler, HTTPRedirectHandler, build_opener

from alpha_dev import analytics_owner
from common import Deployment
from registry_release import APP_SERVICES, REVISION, SERVICE_COMPONENTS


IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")
ENTRY = re.compile(r"""<script\b[^>]*\bsrc=["'](/assets/index-[\w-]+\.js)["']""", re.ASCII)
FRONTEND_ROOT = "/usr/share/nginx/html"
ALPHA_ROUTE = "/internal/v1/analytics/current-statuses"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def docker(*args):
    try:
        return subprocess.run(
            ["docker", *args], capture_output=True, check=True, timeout=15,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError(f"Docker {args[0]} не завершён") from None


def frontend_entry(index):
    entries = ENTRY.findall(index.decode("utf-8"))
    if len(entries) != 1:
        raise ValueError("В index.html ожидается один входной JavaScript-файл сборки кабинета")
    return entries[0]


def fetch(opener, origin, path, limit):
    try:
        with opener.open(origin + path, timeout=15) as response:
            if response.status != 200:
                raise RuntimeError(f"HTTPS вернул статус {response.status} для {path}")
            body = response.read(limit + 1)
    except URLError:
        raise RuntimeError(f"Проверка HTTPS не прошла для {path}") from None
    if len(body) > limit:
        raise RuntimeError(f"Размер ответа HTTPS превышает лимит для {path}")
    return body


def verify_alpha(deployment, origin, ca, containers):
    alpha = deployment.alpha_dev
    kafka = containers["analytics-kafka"]
    running, image_id = docker(
        "inspect", "--type", "container", "--format", "{{.State.Running}} {{.Image}}", kafka,
    ).decode("ascii").strip().split()
    expected_id = docker("image", "inspect", "--format", "{{.Id}}", alpha.kafka_image).decode("ascii").strip()
    if running != "true" or not IMAGE_ID.fullmatch(image_id) or image_id != expected_id:
        raise RuntimeError("Работающий Kafka отличается от закреплённого digest")

    route = "http://analytics:8080" + ALPHA_ROUTE
    common = ["curl", "-sS", "--max-time", "3", "-o", "/dev/null", "-w", "%{http_code}"]
    denied = docker("exec", containers["credit"], *common, route).decode("ascii")
    authorized = docker(
        "exec", containers["credit"], "sh", "-eu", "-c",
        'exec curl -sS --max-time 3 -o /dev/null -w "%{http_code}" '
        '-H "X-Analytics-Read-Token: $ANALYTICS_STATUS_READ_TOKEN" "$1"', "analytics", route,
    ).decode("ascii")
    if denied != "401" or authorized not in {"200", "503"}:
        raise RuntimeError("Внутренний HTTP Analytics не подтвердил проверку токена")
    try:
        fetch(opener(ca), origin.rstrip("/"), ALPHA_ROUTE, 1024)
    except HTTPError as error:
        if error.code != 404:
            raise RuntimeError("Внешний gateway не подтвердил закрытие пути Analytics") from None
    else:
        raise RuntimeError("Внешний gateway допускает доступ к пути Analytics")

    marker = analytics_owner(
        deployment, "-qtA", "-c",
        "SELECT generation || ' ' || snapshot_sha256 FROM application_status_generation WHERE singleton",
    ).split()
    if len(marker) != 2 or marker[0] != alpha.topic or not re.fullmatch(r"[0-9a-f]{64}", marker[1]):
        raise RuntimeError("Маркер снимка Analytics не соответствует поколению")
    if alpha.read_enabled and authorized != "200":
        raise RuntimeError("Analytics пока не подтверждает готовность для активного чтения")
    return {
        "topic": alpha.topic, "dlq_topic": alpha.dlq, "consumer_group": alpha.group,
        "read_enabled": alpha.read_enabled, "snapshot_sha256": marker[1],
        "http_with_token": int(authorized), "http_without_token": int(denied), "public_http": 404,
        "kafka": {"container": kafka, "image_id": image_id, "registry_ref": alpha.kafka_image},
        "jar_sha256": deployment.release.alpha_dev["jar_sha256"],
    }


def verify(deployment, expected_revision):
    if not deployment.local and deployment.values["CABINET_RELEASE"] != expected_revision:
        raise ValueError("CABINET_RELEASE отличается от ожидаемой ревизии")
    origin = deployment.values["PUBLIC_CABINET_URL"].rstrip("/")
    services = (*APP_SERVICES, "postgres") if deployment.release else APP_SERVICES
    if deployment.release and "clamav" in deployment.release.images:
        services = (*services, "clamav")
    if deployment.ollama_ssh:
        services = (*services, "ollama-ssh-bridge")
    containers = {service: deployment.container(service) for service in services}
    images = {}
    if deployment.alpha_dev:
        containers.update({name: deployment.container(name) for name in ("analytics", "analytics-db", "analytics-kafka")})
    for service, container in containers.items():
        if service == "analytics-kafka":
            continue
        running, image_id = docker(
            "inspect", "--type", "container", "--format", "{{.State.Running}} {{.Image}}", container,
        ).decode("ascii").strip().split()
        if running != "true" or not IMAGE_ID.fullmatch(image_id):
            raise RuntimeError(f"Работающий образ {service} не подтверждён")
        revision = docker(
            "image", "inspect", "--format", '{{index .Config.Labels "org.opencontainers.image.revision"}}', image_id,
        ).decode("ascii").strip()
        component = SERVICE_COMPONENTS[service]
        if deployment.release and deployment.release.local_image_id(component) != image_id:
            raise RuntimeError(f"Работающий образ {service} отличается от digest выпуска")
        if revision != expected_revision:
            raise RuntimeError(f"OCI revision работающего образа {service} отличается от ожидаемой")
        images[service] = {"container": container, "image_id": image_id, "revision": revision}
        if deployment.release:
            images[service]["registry_ref"] = (
                deployment.release.alpha_dev["image"] if component == "analytics" else deployment.release.images[component]
            )

    frontend = containers["frontend"]
    entry = frontend_entry(docker("exec", frontend, "cat", FRONTEND_ROOT + "/index.html"))
    image_hash = sha256(docker("exec", frontend, "cat", FRONTEND_ROOT + entry)).hexdigest()
    ca = str(deployment.gateway_config.parent / "local-ca.crt") if deployment.local else None
    opener = build_opener(NoRedirect(), HTTPSHandler(context=ssl.create_default_context(cafile=ca)))
    public_entry = frontend_entry(fetch(opener, origin, "/", 1024 * 1024))
    if public_entry != entry or sha256(fetch(opener, origin, entry, 16 * 1024 * 1024)).hexdigest() != image_hash:
        raise RuntimeError("JavaScript по HTTPS отличается от работающего контейнера frontend")
    alpha_report = verify_alpha(deployment, origin, ca, containers) if deployment.alpha_dev else None
    if any(deployment.container(service) != container for service, container in containers.items()):
        raise RuntimeError("Состав контейнеров изменился во время проверки")
    return {
        "passed": True,
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "project": deployment.project,
        "profiles": sorted(deployment.profiles),
        "expected_revision": expected_revision,
        "images": images,
        **({"alpha_dev": alpha_report} if alpha_report else {}),
        "https": {"origin": origin, "status": 200, "entry": entry, "sha256": image_hash},
    }


def main():
    parser = argparse.ArgumentParser(description="Сверить работающий выпуск Compose и JavaScript, который отдаётся по HTTPS")
    parser.add_argument("--project", default="cabinet-prod")
    parser.add_argument("--env-file", default="deploy/.env")
    parser.add_argument("--expected-revision", required=True)
    parser.add_argument("--release-manifest")
    parser.add_argument("--local", action="store_true", help="Использовать локальный CA Caddy, подготовленный deploy/verify.py")
    args = parser.parse_args()
    if not REVISION.fullmatch(args.expected_revision):
        parser.error("--expected-revision должен содержать 40 строчных шестнадцатеричных символов")
    try:
        result = verify(Deployment(args.project, args.env_file, args.local, args.release_manifest), args.expected_revision)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Проверка выпуска не прошла. {error}\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
