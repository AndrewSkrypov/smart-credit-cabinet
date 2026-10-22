"""Shared scoped deployment configuration; never print the expanded compose environment."""
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent


class Deployment:
    def __init__(self, project, env_file="deploy/.env", local=False):
        if not re.fullmatch(r"cabinet-prod[a-z0-9-]{0,50}", project):
            raise ValueError("Имя производственного проекта должно начинаться с cabinet-prod")
        self.project = project
        self.local = local
        self.path = Path(env_file).resolve()
        self.values = {}
        for line in self.path.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            match = re.fullmatch(r"([A-Z_][A-Z_0-9]*)=([^\r\n]*)", line)
            if not match:
                raise ValueError("Ожидается KEY=value без кавычек; используйте configure.py")
            self.values[match[1]] = match[2]
        for key, minimum in [("POSTGRES_PASSWORD", 24), ("JWT_SECRET", 48), ("SIM_API_KEY", 24)]:
            value = self.values.get(key, "")
            if len(value) < minimum or not re.fullmatch(r"[A-Za-z0-9_-]+", value) or "CHANGE_ME" in value:
                raise ValueError(f"Нужен отдельный случайный {key}; значение не выводится")
        if len({self.values[key] for key in ["POSTGRES_PASSWORD", "JWT_SECRET", "SIM_API_KEY"]}) != 3:
            raise ValueError("Для БД, JWT и симуляторов нужны разные секреты")
        if self.values.get("DEMO_ACCOUNTS") != "false" or self.values.get("SIM_CONTROL_ENABLED") != "false":
            raise ValueError("Производственный запуск требует отключённых демо-учёток и пульта")
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", self.values.get("CABINET_RELEASE", "")):
            raise ValueError("Неверная метка выпуска")
        if not re.fullmatch(r"[A-Za-z0-9.-]+", self.values.get("CABINET_DOMAIN", "")):
            raise ValueError("Неверный домен")
        subnet = ipaddress.IPv4Network(self.values.get("EDGE_SUBNET", "172.30.40.0/28"))
        address = ipaddress.IPv4Address(self.values.get("CADDY_EDGE_IP", "172.30.40.2"))
        if not subnet.is_private or address not in subnet or address in [subnet.network_address, subnet.broadcast_address]:
            raise ValueError("Caddy должен иметь отдельный внутренний адрес в EDGE_SUBNET")
        gateway = ipaddress.IPv4Address(self.values.get("GATEWAY_EDGE_IP", "172.30.40.3"))
        if gateway not in subnet or gateway in [address, subnet.network_address, subnet.broadcast_address]:
            raise ValueError("Gateway должен иметь другой адрес внутри EDGE_SUBNET")
        if local and self.values["CABINET_DOMAIN"] != "localhost":
            raise ValueError("Локальная проверка допускает только localhost и внутренний CA")
        self.environment = {**os.environ, **self.values, "COMPOSE_PROFILES": ""}
        self.gateway_config = ROOT / "deploy/runtime" / project / "gateway.conf"
        self.environment["GATEWAY_CONFIG"] = str(self.gateway_config)
        self.environment["SOURCE_REVISION"] = self.values["CABINET_RELEASE"]
        self.command = ["docker", "compose", "--env-file", str(self.path), "-p", project,
                        "-f", str(ROOT / "docker-compose.yml"), "-f", str(ROOT / "deploy/compose.yaml")]
        if local:
            self.command.extend(["-f", str(ROOT / "deploy/compose.local.yaml")])
        self.command.extend(["--profile", "prod"])

    def compose(self, *arguments, capture=False):
        result = subprocess.run([*self.command, *arguments], cwd=ROOT, env=self.environment,
                                text=True, encoding="utf-8", capture_output=capture)
        if result.returncode:
            # Expanded configuration and command errors can contain credentials, so callers get only the stage.
            raise RuntimeError(f"Compose {arguments[0]} не завершён, код {result.returncode}")
        return result.stdout if capture else None

    def container(self, service):
        result = subprocess.run(["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={self.project}",
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
