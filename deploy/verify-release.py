#!/usr/bin/env python3
"""Verify the running Compose images and the frontend served over HTTPS."""
import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
import re
import ssl
import subprocess
from urllib.error import URLError
from urllib.request import HTTPSHandler, HTTPRedirectHandler, build_opener

from common import Deployment
from registry_release import APP_SERVICES, REVISION


IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")
ENTRY = re.compile(r"""<script\b[^>]*\bsrc=["'](/assets/index-[\w-]+\.js)["']""", re.ASCII)
FRONTEND_ROOT = "/usr/share/nginx/html"


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


def verify(deployment, expected_revision):
    if not deployment.local and deployment.values["CABINET_RELEASE"] != expected_revision:
        raise ValueError("CABINET_RELEASE отличается от ожидаемой ревизии")
    origin = deployment.values["PUBLIC_CABINET_URL"].rstrip("/")
    services = (*APP_SERVICES, "postgres") if deployment.release else APP_SERVICES
    if deployment.release and "clamav" in deployment.release.images:
        services = (*services, "clamav")
    containers = {service: deployment.container(service) for service in services}
    images = {}
    for service, container in containers.items():
        running, image_id = docker(
            "inspect", "--type", "container", "--format", "{{.State.Running}} {{.Image}}", container,
        ).decode("ascii").strip().split()
        if running != "true" or not IMAGE_ID.fullmatch(image_id):
            raise RuntimeError(f"Работающий образ {service} не подтверждён")
        revision = docker(
            "image", "inspect", "--format", '{{index .Config.Labels "org.opencontainers.image.revision"}}', image_id,
        ).decode("ascii").strip()
        if deployment.release and deployment.release.local_image_id(service) != image_id:
            raise RuntimeError(f"Работающий образ {service} отличается от digest выпуска")
        if revision != expected_revision:
            raise RuntimeError(f"OCI revision работающего образа {service} отличается от ожидаемой")
        images[service] = {"container": container, "image_id": image_id, "revision": revision}
        if deployment.release:
            images[service]["registry_ref"] = deployment.release.images[service]

    frontend = containers["frontend"]
    entry = frontend_entry(docker("exec", frontend, "cat", FRONTEND_ROOT + "/index.html"))
    image_hash = sha256(docker("exec", frontend, "cat", FRONTEND_ROOT + entry)).hexdigest()
    ca = str(deployment.gateway_config.parent / "local-ca.crt") if deployment.local else None
    opener = build_opener(NoRedirect(), HTTPSHandler(context=ssl.create_default_context(cafile=ca)))
    public_entry = frontend_entry(fetch(opener, origin, "/", 1024 * 1024))
    if public_entry != entry or sha256(fetch(opener, origin, entry, 16 * 1024 * 1024)).hexdigest() != image_hash:
        raise RuntimeError("JavaScript по HTTPS отличается от работающего контейнера frontend")
    if any(deployment.container(service) != container for service, container in containers.items()):
        raise RuntimeError("Состав контейнеров изменился во время проверки")
    return {
        "passed": True,
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "project": deployment.project,
        "profiles": sorted(deployment.profiles),
        "expected_revision": expected_revision,
        "images": images,
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
