"""Shared scoped deployment configuration; never print the expanded compose environment."""
from contextlib import contextmanager
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
from urllib.parse import urlparse

from alpha_dev import AlphaDev
from assessment_dev import AssessmentDev, OVERLAY as ASSESSMENT_OVERLAY
from documents_dev import DocumentsDev
from servicing_dev import ServicingDev, OVERLAY as SERVICING_OVERLAY
from catalog import requested_mode, state_path, validate_secrets
from dev_diagnostics import error_category
import dev_payment_reserve
import mcp_dev
from registry_release import RegistryRelease

ROOT = Path(__file__).resolve().parent.parent
DEMO_PROJECT = "cabinet-dev-demo"
DEMO_DOMAIN = "demo.dev.credit-cabinet.ru"


class Deployment:
    def __init__(self, project, env_file="deploy/.env", local=False, release_manifest=None, *,
                 env_contents=None, open_hooks=True, historical_backup=False):
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
        dev_payment_reserve.validate_overrides(self.values, os.environ)
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
        self.dev_payment_reserve_simulation = dev_payment_reserve.enabled(
            project, local, self.values, self.release, historical_backup,
        )
        self.catalog_mode = requested_mode(project, local, self.release)
        self.catalog_enabled = self.catalog_mode != "disabled"
        self.catalog_state = state_path(self.path, project)
        if self.catalog_enabled:
            validate_secrets(self.values)
        elif open_hooks and project == "cabinet-dev" and (self.catalog_state / "authority.json").exists():
            raise RuntimeError("После ввода Catalog нужен manifest с режимом local или remote")
        self.mcp = mcp_dev.enabled(project, local, self.values, self.release, historical_backup)
        self.alpha_dev = AlphaDev.from_config(project, local, self.values, self.release)
        self.environment = {**os.environ, **self.values, "COMPOSE_PROFILES": ""}
        # Both values are controller-owned. The reviewed Dev overlay alone sets
        # the effective container flag; ambient CI/shell values cannot select it.
        self.environment[dev_payment_reserve.FLAG] = "false"
        self.environment[dev_payment_reserve.CONTEXT] = project
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
        if self.dev_payment_reserve_simulation:
            self.command.extend(["-f", str(ROOT / dev_payment_reserve.OVERLAY)])
        if self.alpha_dev:
            self.environment.update(self.alpha_dev.environment(self.release, project))
            self.command.extend(["-f", str(ROOT / "deploy/compose.dev-alpha.yaml")])
        if self.catalog_enabled:
            self.environment.update(
                CABINET_CATALOG_MODE="local",
                CABINET_CATALOG_TLS_DIR=str(self.catalog_state / "tls"),
            )
            self.command.extend(["-f", str(ROOT / "deploy/compose.catalog.yaml")])
        for name, hook in (("assessment", AssessmentDev), ("servicing", ServicingDev), ("documents", DocumentsDev)):
            descriptor = getattr(self.release, name, None)
            bound_to_source = descriptor is not None and descriptor["dev_mode"] != "disabled"
            setattr(self, name, hook.open(self) if open_hooks and (bound_to_source or not historical_backup) else None)
        for hook, overlay in (
            (self.assessment, ASSESSMENT_OVERLAY),
            (self.servicing, SERVICING_OVERLAY),
            (self.documents, ROOT / "deploy/compose.documents.yaml"),
        ):
            if hook is not None:
                self.environment.update(hook.compose_environment())
                self.command.extend(["-f", str(overlay)])
        self.runtime_modes = {"catalog": self.catalog_mode} if self.catalog_enabled else {}
        if self.release and not local and (project == "cabinet-dev" or project.startswith("cabinet-dev-restore-")):
            for name in ("assessment", "servicing", "documents"):
                descriptor = getattr(self.release, name)
                if descriptor is not None and descriptor["dev_mode"] != "disabled":
                    if open_hooks and getattr(self, name) is None:
                        raise RuntimeError(f"Отсутствует hook включённого runtime {name}")
                    self.runtime_modes[name] = descriptor["dev_mode"]
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
        if self.mcp:
            self.environment.update(mcp_dev.environment(self.path, self.values))
            self.command.extend(["-f", str(ROOT / mcp_dev.OVERLAY)])
        for profile in sorted(self.profiles):
            self.command.extend(["--profile", profile])

    def compose(self, *arguments, capture=False):
        result = subprocess.run([*self.command, *arguments], cwd=ROOT, env=self.environment,
                                text=True, encoding="utf-8", capture_output=capture or self.release is not None)
        if result.returncode:
            # Expanded configuration and command errors can contain credentials, so callers get only the stage.
            category = error_category(result.stderr)
            raise RuntimeError(f"Compose {arguments[0]} не завершён, код {result.returncode}, категория {category}")
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

@contextmanager
def deploy_lock(env_file, project):
    """Share the protected project lock with subprocesses started by dev-release."""
    if project != "cabinet-dev" and not project.startswith("cabinet-dev-restore-"):
        yield None
        return
    lock_name = "deploy.lock" if project == "cabinet-dev" else project + ".lock"
    path = Path(env_file).absolute().parent / lock_name
    inherited = os.environ.get("CABINET_DEPLOY_LOCK_FD")
    if inherited is not None:
        fd = int(inherited)
        if not os.path.samestat(path.lstat(), os.fstat(fd)):
            raise RuntimeError("Дочерний процесс утратил блокировку проекта Dev")
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield fd
        return
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "r+") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.getuid():
            raise ValueError("Блокировка проекта Dev должна принадлежать оператору и иметь права 0600")
        fcntl.flock(stream, fcntl.LOCK_EX)
        yield stream.fileno()


def lock_environment(fd):
    if fd is None:
        return {}
    return {"pass_fds": (fd,), "env": {**os.environ, "CABINET_DEPLOY_LOCK_FD": str(fd)}}


def replace_env(path, original, contents):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".cabinet.env.", delete=False) as stream:
            temporary = Path(stream.name)
            created = os.fstat(stream.fileno())
            if (created.st_uid, created.st_gid) != (original.st_uid, original.st_gid):
                os.fchown(stream.fileno(), original.st_uid, original.st_gid)
            os.fchmod(stream.fileno(), stat.S_IMODE(original.st_mode))
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            current = path.lstat()
            if not os.path.samestat(current, original) or current.st_ctime_ns != original.st_ctime_ns:
                raise RuntimeError("cabinet.env изменился во время выкладки. Повторите запуск.")
            temporary.replace(path)
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
