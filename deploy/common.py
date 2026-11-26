"""Shared scoped deployment configuration; never print the expanded compose environment."""
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse

from alpha_dev import AlphaDev
from registry_release import RegistryRelease

ROOT = Path(__file__).resolve().parent.parent
DEMO_PROJECT = "cabinet-dev-demo"
DEMO_DOMAIN = "demo.dev.credit-cabinet.ru"


class Deployment:
    def __init__(self, project, env_file="deploy/.env", local=False, release_manifest=None, *, env_contents=None):
        if not re.fullmatch(r"cabinet-(?:dev|prod)[a-z0-9-]{0,50}", project):
            raise ValueError("Имя проекта должно начинаться с cabinet-dev или cabinet-prod")
        self.project = project
        self.local = local
        self.path = Path(env_file).resolve()
        self.values = {}
        if env_contents is None:
            env_contents = self.path.read_bytes()
        for line in env_contents.decode("utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            match = re.fullmatch(r"([A-Z_][A-Z_0-9]*)=([^\r\n]*)", line)
            if not match:
                raise ValueError("Ожидается KEY=value без кавычек; используйте configure.py")
            self.values[match[1]] = match[2]
        self.release = RegistryRelease(release_manifest) if release_manifest else None
        if self.release:
            self.values["CABINET_RELEASE"] = self.release.revision
        if project.startswith("cabinet-dev") and not self.release:
            raise ValueError("Dev-проект требует manifest выпуска")
        secret_keys = ["POSTGRES_PASSWORD", "JWT_SECRET", "SIM_API_KEY", "IDENTITY_LINK_KEY", "VALKEY_PASSWORD"]
        database_secrets = [service + suffix for service in ["IDENTITY", "CREDIT", "ENGAGEMENT"]
                            for suffix in ["_DB_PASSWORD", "_MIGRATION_PASSWORD"]]
        secret_keys.extend(database_secrets)
        for key, minimum in [("POSTGRES_PASSWORD", 24), ("JWT_SECRET", 48), ("SIM_API_KEY", 24), ("IDENTITY_LINK_KEY", 48), ("VALKEY_PASSWORD", 32)]:
            value = self.values.get(key, "")
            if len(value) < minimum or not re.fullmatch(r"[A-Za-z0-9_-]+", value) or "CHANGE_ME" in value or value.startswith("dev-"):
                raise ValueError(f"Нужен отдельный случайный {key}; значение не выводится")
        for key in database_secrets:
            value = self.values.get(key, "")
            if len(value) < 32 or not re.fullmatch(r"[A-Za-z0-9_-]+", value) or value.startswith("dev-") or "CHANGE_ME" in value:
                raise ValueError(f"Нужен отдельный случайный {key}; добавьте роли через upgrade-database-env.py")
        if len({self.values[key] for key in secret_keys}) != len(secret_keys):
            raise ValueError("Для БД, JWT, симуляторов, одноразовых ссылок и Valkey нужны разные секреты")
        backend = self.values.setdefault("STORAGE_BACKEND", "local")
        enabled = self.values.setdefault("STORAGE_ENABLED", "false")
        if backend not in {"local", "s3"} or enabled not in {"true", "false"}:
            raise ValueError("Ожидаются STORAGE_BACKEND=local|s3 и STORAGE_ENABLED=true|false")
        self.profiles = {"prod"}
        optional = {profile.strip() for profile in self.values.get("CABINET_OPTIONAL_PROFILES", "").split(",") if profile.strip()}
        if not optional <= {"ai", "monitoring"}:
            raise ValueError("CABINET_OPTIONAL_PROFILES поддерживает ai,monitoring")
        self.profiles.update(optional)
        demo_edge = self.values.get("CABINET_DEV_PUBLIC_DEMO_EDGE", "false")
        if demo_edge not in {"true", "false"}:
            raise ValueError("CABINET_DEV_PUBLIC_DEMO_EDGE должен быть true или false")
        if demo_edge == "true":
            if local or project != "cabinet-dev" or self.values.get("CABINET_DOMAIN") != "dev.credit-cabinet.ru":
                raise ValueError("Маршрут публичного демо доступен только обычному HTTPS Dev")
            if self.values.get("CABINET_GRAFANA_CADDYFILE"):
                raise ValueError("Маршрут публичного демо требует Caddyfile из выбранного выпуска")
        isolated_demo = self.values.get("CABINET_ISOLATED_DEMO", "false")
        if isolated_demo not in {"true", "false"} or (isolated_demo == "true") != (project == DEMO_PROJECT):
            raise ValueError("Для cabinet-dev-demo нужен явный CABINET_ISOLATED_DEMO=true; в других проектах он запрещён")
        self.isolated_demo = isolated_demo == "true"
        if self.isolated_demo:
            if local or optional or backend != "local" or enabled != "false":
                raise ValueError("Публичное демо требует изолированные локальные данные без дополнительных профилей")
            if self.values.get("CABINET_DOMAIN") != DEMO_DOMAIN:
                raise ValueError("Недопустимый домен публичного демо")
            if self.values.get("EDGE_SUBNET") != "172.30.41.0/28":
                raise ValueError("Для публичного демо нужна отдельная EDGE_SUBNET=172.30.41.0/28")

        ollama_ssh = self.values.get("CABINET_DEV_OLLAMA_SSH", "false")
        if ollama_ssh not in {"true", "false"}:
            raise ValueError("CABINET_DEV_OLLAMA_SSH должен быть true или false")
        self.ollama_ssh = ollama_ssh == "true"
        if self.ollama_ssh:
            if local or project != "cabinet-dev" or "ai" in self.profiles:
                raise ValueError("SSH bridge доступен только cabinet-dev без локального профиля ai")
            if not self.release or "ssh-bridge" not in self.release.images:
                raise ValueError("Для SSH bridge нужен digest образа в manifest выпуска")
        grafana_https = self.values.get("CABINET_GRAFANA_HTTPS", "false")
        if grafana_https not in {"true", "false"}:
            raise ValueError("CABINET_GRAFANA_HTTPS должен быть true или false")
        if grafana_https == "true":
            if local or project != "cabinet-dev" or "monitoring" not in self.profiles:
                raise ValueError("Для CABINET_GRAFANA_HTTPS нужен cabinet-dev с профилем monitoring")
            password = self.values.get("GRAFANA_PASSWORD", "")
            if len(password) < 16 or password == "local-monitoring-change-me":
                raise ValueError("Для публичной Grafana нужен отдельный GRAFANA_PASSWORD")
        if backend == "s3" or enabled == "true":
            self.profiles.add("storage")
            keys = ["S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_ADMIN_ACCESS_KEY", "S3_ADMIN_SECRET_KEY"]
            for key in keys:
                value = self.values.get(key, "")
                if len(value) < 24 or not re.fullmatch(r"[A-Za-z0-9_-]+", value) or "CHANGE_ME" in value or value.startswith("dev-"):
                    raise ValueError(f"Нужен отдельный случайный {key}; значение не выводится")
            all_keys = keys + secret_keys
            if len({self.values[key] for key in all_keys}) != len(all_keys):
                raise ValueError("Для S3, оператора и остальных служб нужны разные ключи")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", self.values.get("IDENTITY_LINK_KEY_VERSION", "1")):
            raise ValueError("Неверная версия ключа одноразовых ссылок")
        if self.values.get("DEMO_ACCOUNTS") != "false" or self.values.get("SIM_CONTROL_ENABLED") != "false":
            raise ValueError("Производственный запуск требует отключённых демо-учёток и пульта")
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", self.values.get("CABINET_RELEASE", "")):
            raise ValueError("Неверная метка выпуска")
        if not re.fullmatch(r"[A-Za-z0-9.-]+", self.values.get("CABINET_DOMAIN", "")):
            raise ValueError("Неверный домен")
        origin = urlparse(self.values.get("PUBLIC_CABINET_URL", ""))
        if origin.scheme != "https" or origin.hostname != self.values["CABINET_DOMAIN"].lower() or origin.username or origin.password or origin.query or origin.fragment or origin.path not in {"", "/"}:
            raise ValueError("PUBLIC_CABINET_URL должен быть HTTPS origin домена кабинета")
        subnet = ipaddress.IPv4Network(self.values.get("EDGE_SUBNET", "172.30.40.0/28"))
        address = ipaddress.IPv4Address(self.values.get("CADDY_EDGE_IP", "172.30.40.2"))
        if not subnet.is_private or address not in subnet or address in [subnet.network_address, subnet.broadcast_address]:
            raise ValueError("Caddy должен иметь отдельный внутренний адрес в EDGE_SUBNET")
        gateway = ipaddress.IPv4Address(self.values.get("GATEWAY_EDGE_IP", "172.30.40.3"))
        if gateway not in subnet or gateway in [address, subnet.network_address, subnet.broadcast_address]:
            raise ValueError("Gateway должен иметь другой адрес внутри EDGE_SUBNET")
        if local and self.values["CABINET_DOMAIN"] != "localhost":
            raise ValueError("Локальная проверка допускает только localhost и внутренний CA")
        self.alpha_dev = AlphaDev.from_config(project, local, self.values, self.release)
        self.environment = {**os.environ, **self.values, "COMPOSE_PROFILES": ""}
        if "ai" in self.profiles:
            self.environment.setdefault("ASSISTANT_MODEL", "qwen2.5:1.5b")
        if "monitoring" in self.profiles:
            self.environment["CABINET_OTLP_ENABLED"] = "true"
            self.environment["CABINET_LOG_FILE"] = "/var/log/cabinet/${HOSTNAME}/${spring.application.name}.jsonl"
        self.gateway_config = ROOT / "deploy/runtime" / project / "gateway.conf"
        self.environment["GATEWAY_CONFIG"] = str(self.gateway_config)
        self.environment["SOURCE_REVISION"] = self.values["CABINET_RELEASE"]
        self.command = ["docker", "compose", "--env-file", str(self.path), "-p", project,
                        "-f", str(ROOT / "docker-compose.yml"), "-f", str(ROOT / "deploy/compose.yaml")]
        if self.release:
            self.environment.update(self.release.environment())
            self.command.extend(["-f", str(ROOT / "deploy/compose.registry.yaml")])
        if self.alpha_dev:
            self.environment.update(self.alpha_dev.environment(self.release))
            self.command.extend(["-f", str(ROOT / "deploy/compose.dev-alpha.yaml")])
        if local:
            self.command.extend(["-f", str(ROOT / "deploy/compose.local.yaml")])
        if "monitoring" in self.profiles:
            self.command.extend(["-f", str(ROOT / "monitoring/compose.ops.yaml")])
            if project == "cabinet-dev" and self.values.get("CABINET_ALERTMANAGER_PAGING_DIR"):
                self.command.extend(["-f", str(ROOT / "monitoring/compose.paging.yml")])
        if grafana_https == "true":
            self.command.extend(["-f", str(ROOT / "monitoring/compose.grafana-https.yml")])
        if self.isolated_demo:
            self.command.extend(["-f", str(ROOT / "deploy/compose.demo.yaml")])
        if demo_edge == "true":
            self.command.extend(["-f", str(ROOT / "deploy/compose.dev-demo-edge.yaml")])
        if self.ollama_ssh:
            self.command.extend(["-f", str(ROOT / "deploy/compose.ollama-ssh.yaml")])
        for profile in sorted(self.profiles):
            self.command.extend(["--profile", profile])

    def compose(self, *arguments, capture=False):
        result = subprocess.run([*self.command, *arguments], cwd=ROOT, env=self.environment,
                                text=True, encoding="utf-8", capture_output=capture or self.release is not None)
        if result.returncode:
            # Expanded configuration and command errors can contain credentials, so callers get only the stage.
            raise RuntimeError(f"Compose {arguments[0]} не завершён, код {result.returncode}")
        return result.stdout if capture else None

    def container(self, service, include_stopped=False):
        result = subprocess.run(["docker", "ps", "-aq" if include_stopped else "-q", "--filter", f"label=com.docker.compose.project={self.project}",
                                 "--filter", f"label=com.docker.compose.service={service}"], capture_output=True, text=True, check=True)
        identifiers = result.stdout.split()
        if len(identifiers) != 1:
            raise ValueError(f"Ожидается один работающий контейнер {service} в выбранном проекте")
        return identifiers[0]

    def sql(self, database, statement):
        result = subprocess.run(["docker", "exec", "-i", self.container("postgres"), "psql", "-U", self.values.get("POSTGRES_USER", "cabinet"),
                                 "-d", database, "-v", "ON_ERROR_STOP=1", "-q", "-tA"], input=statement,
                                text=True, encoding="utf-8", capture_output=True)
        if result.returncode:
            raise RuntimeError("Операция PostgreSQL не завершена; проверьте выбранную БД и права оператора")
        return result.stdout.strip()

    def config(self):
        return json.loads(self.compose("config", "--format", "json", capture=True))

    def active_services(self, config=None):
        config = config if config is not None else self.config()
        return {name: service for name, service in config["services"].items()
                if not service.get("profiles") or self.profiles.intersection(service["profiles"])}

    def assert_build_revision(self):
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()
        if revision != self.values["CABINET_RELEASE"] or dirty:
            raise ValueError("Для сборки нужен чистый checkout ровно CABINET_RELEASE; тег другого кода не создаётся")

    def block_demo_accounts(self):
        return int(self.sql("identity", """
            with blocked as (update users set status='BLOCKED', credentials_version=credentials_version+1
                where lower(email) like '%@demo.test' and status <> 'BLOCKED' returning id)
            select count(*) from blocked;
        """))
