"""Scope demo creation and reset to an explicitly named local synthetic project."""
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent


class DemoEnvironment:
    def __init__(self, project, port=18082, ttl="PT30M"):
        if not re.fullmatch(r"cabinet-demo(?:-[a-z0-9]+)*", project) or not 1024 <= port <= 65535:
            raise ValueError("Нужен отдельный cabinet-demo* проект и непривилегированный локальный порт")
        self.project = project
        self.port = port
        self.environment = {**os.environ, "GATEWAY_PORT": str(port), "DEMO_HISTORY_TTL": ttl, "DEMO_ACCOUNTS": "true", "SIM_CONTROL_ENABLED": "true",
                            "POSTGRES_USER": "cabinet", "POSTGRES_PASSWORD": "cabinet", "JWT_SECRET": "dev-only-insecure-secret-change-me-32bytes+",
                            "SIM_API_KEY": "dev-sim-api-key", "COMPOSE_PROFILES": ""}
        self.command = ["docker", "compose", "-p", project, "-f", str(ROOT / "docker-compose.yml"), "-f", str(ROOT / "scripts/demo-history.compose.yaml")]

    def compose(self, *arguments):
        subprocess.run([*self.command, *arguments], cwd=ROOT, env=self.environment, check=True)

    def assert_demo(self):
        identifiers = subprocess.check_output(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + self.project], text=True).split()
        for identifier in identifiers:
            info = json.loads(subprocess.check_output(["docker", "inspect", identifier], text=True))[0]
            service = info["Config"]["Labels"].get("com.docker.compose.service")
            values = dict(value.split("=", 1) for value in info["Config"]["Env"] if "=" in value)
            if service == "identity" and values.get("DEMO_ACCOUNTS") != "true":
                raise ValueError("Это не демо-стенд: сброс и изменение конфигурации запрещены")
            if service == "external-sim" and values.get("SIM_CONTROL_ENABLED") != "true":
                raise ValueError("Пульт выключен: проект не принимается за выделенный демо-стенд")

    def images_from(self, prefix):
        if not re.fullmatch(r"[a-z0-9][a-z0-9_.-]{2,80}", prefix):
            raise ValueError("Некорректный префикс готовых образов")
        for service in ["identity", "credit", "engagement", "external-sim", "frontend"]:
            subprocess.run(["docker", "tag", prefix + "-" + service, self.project + "-" + service], check=True)
