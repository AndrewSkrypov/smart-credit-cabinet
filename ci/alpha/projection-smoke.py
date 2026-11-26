"""Verify Dev Alpha Compose wiring and ingest one event in an isolated CI project."""

import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[2]
STACK = ("docker-compose.yml", "deploy/compose.yaml", "deploy/compose.dev-alpha.yaml")
PRIVATE_NETWORKS = {"alpha-stream", "alpha-store", "alpha-read"}


def checked(label, command, env, *, stdin=None, timeout=60):
    result = subprocess.run(command, input=stdin, capture_output=True, text=True,
                            cwd=ROOT, env=env, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{label} failed with exit {result.returncode}")
    return result.stdout.strip()


def synthetic_env(project):
    env = {key: value for key, value in os.environ.items() if not key.startswith(
           ("CABINET_", "COMPOSE_", "ALPHA_", "DB_"))}
    # Override all required interpolation values, including those inherited from the runner.
    for path in STACK:
        for key in re.findall(r"\$\{([A-Z_0-9]+):\?", (ROOT / path).read_text()):
            env[key] = secrets.token_hex(32)
    env.update({
        "GATEWAY_CONFIG": str(ROOT / "docker/gateway/nginx.conf"),
        "CABINET_DOMAIN": "alpha.example.invalid",
        "CABINET_RELEASE": "a" * 40,
        "CABINET_IMAGE_ALPHA_ANALYTICS": f"{project}:local",
        "CABINET_IMAGE_POSTGRES": (ROOT / "docker/postgres/Dockerfile").read_text().split("FROM ", 1)[1].split()[0],
        "CABINET_ALPHA_DEV_GENERATION": "g" + secrets.token_hex(8),
        "CABINET_ALPHA_DEV_TOPIC": f"{project}.status",
        "CABINET_ALPHA_DEV_GROUP": f"{project}.consumer",
        "CABINET_ALPHA_DEV_DLQ_TOPIC": f"{project}.status.dlq",
        "CABINET_ALPHA_DEV_READ_ENABLED": "false",
        "CABINET_ALPHA_DEV_KAFKA_IMAGE": f"{project}:kafka",
    })
    return env


def check_wiring(config, env):
    services = config["services"]
    analytics = services["analytics"]
    settings = analytics["environment"]
    valkey = services["valkey"]
    if (settings.get("SPRING_PROFILES_ACTIVE") != "prod"
            or str(settings.get("CABINET_VALKEY_ENABLED")).lower() != "true"
            or settings.get("CABINET_VALKEY_HOST") != "valkey"
            or settings.get("CABINET_VALKEY_PASSWORD") != env["VALKEY_PASSWORD"]
            or valkey["environment"].get("VALKEY_PASSWORD") != env["VALKEY_PASSWORD"]
            or not {"cabinet", *PRIVATE_NETWORKS} <= analytics["networks"].keys()
            or "cabinet" not in valkey["networks"]
            or analytics["depends_on"]["valkey"]["condition"] != "service_healthy"
            or not all(config["networks"][key].get("internal") for key in PRIVATE_NETWORKS)
            or any(services[key].get("ports") for key in ("analytics", "analytics-db", "analytics-kafka"))
            or services["credit"]["environment"]["ANALYTICS_STATUS_READ_ENABLED"] not in ("false", False)):
        raise RuntimeError("Dev Alpha Compose did not supply production Valkey or violated private shadow routing")
    print("compose_wiring\tpassed")


def run_projection(compose, project, env):
    def run(label, *args, **options):
        return checked(label, [*compose, *args], env, **options)

    def kafka(tool, *args, **options):
        return run(tool, "exec", "-T", "analytics-kafka", f"/opt/kafka/bin/{tool}.sh",
                   "--bootstrap-server", "analytics-kafka:9092", *args, **options)

    def psql(role, *args, **options):
        command = (
            'case "$1" in owner) export PGPASSWORD="$ALPHA_PILOT_PG_OWNER_PASSWORD";; '
            'app) export PGPASSWORD="$ALPHA_PILOT_PG_APP_PASSWORD";; *) exit 2;; esac; '
            'role="$1"; shift; exec psql -X -w -h 127.0.0.1 -U "analytics_$role" '
            '-d analytics -v ON_ERROR_STOP=1 "$@"'
        )
        return run("Analytics SQL", "exec", "-T", "analytics-db", "sh", "-eu", "-c",
                   command, "psql", role, *args, **options)

    topic = env["CABINET_ALPHA_DEV_TOPIC"]
    image = env["CABINET_IMAGE_ALPHA_ANALYTICS"]
    kafka_image = env["CABINET_ALPHA_DEV_KAFKA_IMAGE"]
    kafka_pin = (ROOT / "ci/alpha/kafka-image.txt").read_text().strip()
    if not re.fullmatch(r"apache/kafka:[A-Za-z0-9._-]+@sha256:[a-f0-9]{64}", kafka_pin):
        raise RuntimeError("Current Kafka recipe is missing its immutable upstream pin")
    revision = checked("source revision", ["git", "rev-parse", "HEAD"], env)
    if env.get("CI_COMMIT_SHA", revision) != revision:
        raise RuntimeError("Analytics smoke checkout must match CI_COMMIT_SHA")
    endpoint = env.get("DOCKER_HOST")
    if env.get("DOCKER_CONTEXT") or not endpoint:
        endpoint = checked("Docker endpoint", ["docker", "context", "inspect", "--format",
                                               "{{.Endpoints.docker.Host}}"], env)
    if not endpoint.startswith("unix:///"):
        raise RuntimeError("Analytics smoke requires a local Docker daemon")
    built_images = []
    try:
        checked("Analytics release image", ["docker", "build", "--platform", "linux/amd64", "--tag", image,
                "--build-arg", "SERVICE=analytics", "--build-arg", "JAR_SOURCE=prebuilt",
                "--build-arg", f"SOURCE_REVISION={revision}", "-f", "docker/service/Dockerfile", "."],
                env, timeout=360)
        built_images.append(image)
        kafka_refs = (f"mirror.gcr.io/{kafka_pin}", kafka_pin)
        for kafka_base in kafka_refs:
            try:
                checked("pinned Kafka base", ["docker", "pull", "--platform", "linux/amd64", kafka_base],
                        env, timeout=75)
                break
            except (RuntimeError, subprocess.TimeoutExpired):
                if kafka_base == kafka_refs[-1]:
                    raise
        checked("patched Kafka image", ["docker", "build", "--platform", "linux/amd64", "--tag", kafka_image,
                "--build-arg", f"UPSTREAM_KAFKA_IMAGE={kafka_base}", "-f", "ci/alpha/Kafka.Dockerfile", "ci/alpha"],
                env, timeout=120)
        built_images.append(kafka_image)
        actual_base = checked("Kafka image base label", ["docker", "image", "inspect", "--format",
                              '{{index .Config.Labels "org.opencontainers.image.base.name"}}', kafka_image], env)
        if actual_base != kafka_base:
            raise RuntimeError("Built Kafka does not carry the expected immutable base label")
        run("synthetic PostgreSQL image", "pull", "analytics-db", "--policy", "missing", timeout=150)
        run("private dependencies", "up", "-d", "--wait", "--wait-timeout", "150",
            "valkey", "analytics-db", "analytics-kafka", timeout=180)
        run("Analytics migrations", "run", "--rm", "--no-deps", "analytics-migrate")
        for name in (topic, env["CABINET_ALPHA_DEV_DLQ_TOPIC"]):
            kafka("kafka-topics", "--create", "--topic", name, "--partitions", "3", "--replication-factor", "1")
        empty_snapshot = "application_id,application_version,status\n"
        psql("owner", "-q", "-v", f"generation={topic}",
             "-v", f"snapshot_sha256={sha256(empty_snapshot.encode()).hexdigest()}",
             "-f", "/alpha/import-status-snapshot.sql", stdin=empty_snapshot)
        run("Analytics production profile health", "up", "-d", "--no-deps", "--wait", "--wait-timeout", "90",
            "analytics", timeout=105)
        application_id, event_id = str(uuid4()), str(uuid4())
        synthetic = {"applicationId": application_id, "eventId": event_id, "applicationVersion": 1,
                     "status": "SUBMITTED", "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")}
        kafka("kafka-console-producer", "--topic", topic, "--reader-property", "parse.key=true",
              "--reader-property", "key.separator=:", stdin=f"{application_id}:{json.dumps(synthetic)}\n")
        query = f"""SELECT version.application_version || ':' || version.status
                    FROM application_status_projection version
                    JOIN application_status_receipts receipt USING (application_id, application_version, status)
                    WHERE version.application_id = '{application_id}' AND receipt.event_id = '{event_id}'"""
        deadline = time.monotonic() + 45
        while psql("app", "-qtA", "-c", query, timeout=8) != "1:SUBMITTED":
            if time.monotonic() >= deadline:
                raise RuntimeError("Kafka event did not produce matching SQL receipt and projection")
            time.sleep(2)
        print(f"revision\t{revision}\nproject\t{project}\nanalytics_prod_startup\tpassed\n"
              "synthetic_kafka_sql_receipt_and_projection\tpassed")
    finally:
        try:
            run("isolated cleanup", "down", "--volumes", timeout=75)
        finally:
            if built_images:
                checked("synthetic image cleanup", ["docker", "image", "rm", *built_images], env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config-only", action="store_true")
    args = parser.parse_args()
    project = "cabinet-alpha-projection-" + secrets.token_hex(8)
    env = synthetic_env(project)
    compose = ["docker", "compose", "--env-file", "/dev/null", "-p", project, "--profile", "prod"]
    for name in STACK:
        compose.extend(("-f", name))
    try:
        config = json.loads(checked("synthetic Dev Compose render", [*compose, "config", "--format", "json"], env))
        check_wiring(config, env)
        if not args.config_only:
            run_projection(compose, project, env)
    except (RuntimeError, OSError, subprocess.TimeoutExpired, ValueError, KeyError) as exc:
        print(str(exc) if type(exc) is RuntimeError else type(exc).__name__, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
