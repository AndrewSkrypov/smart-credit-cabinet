"""Synthetic production-profile Analytics check on a guarded, job-owned CI daemon.

The Valkey check is an authenticated CLI probe in Analytics' network namespace,
using the running container's configuration. It is not a JVM-client assertion.
No Dev host, manifest, environment file or persistent generation is accessed.
"""

import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import secrets
import signal
import stat
import subprocess
import tempfile
import time
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[2]
STACK = ("docker-compose.yml", "deploy/compose.yaml", "deploy/compose.dev-alpha.yaml")
PRIVATE_NETWORKS = {"alpha-stream", "alpha-store", "alpha-read", "alpha-cache"}
PROJECT_LABEL = "com.docker.compose.project"
JOB_LABEL = "ci.alpha.projection.job"
IMAGE_PROJECT_LABEL = "ci.alpha.projection.project"
ID = re.compile(r"[0-9a-f]{64}")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")
SHA = re.compile(r"[0-9a-f]{40}")


class ProjectionError(RuntimeError):
    """Only constant operation names and error categories may leave the fixture."""


class InterruptedProjection(ProjectionError):
    pass


def job_project(job_id):
    if not re.fullmatch(r"[0-9]{1,24}", job_id):
        raise ProjectionError("invalid_job_id")
    return f"cabinet-alpha-projection-{job_id}"


def endpoint_from_env(values):
    endpoint = values.get("CABINET_CI_DOCKER_HOST", "")
    if not endpoint.startswith("unix:///") or values.get("DOCKER_CONTEXT"):
        raise ProjectionError("isolated_unix_endpoint_required")
    if values.get("DOCKER_HOST", endpoint) != endpoint:
        raise ProjectionError("conflicting_docker_endpoint")
    path = Path(endpoint.removeprefix("unix://"))
    if not stat.S_ISSOCK(path.stat().st_mode):
        raise ProjectionError("isolated_unix_socket_required")
    return "unix://" + str(path.resolve())


def checked(operation, command, env, *, stdin=None, timeout=60):
    try:
        result = subprocess.run(command, input=stdin, capture_output=True, text=True,
                                cwd=ROOT, env=env, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise ProjectionError(f"{operation}: timeout") from None
    except OSError:
        raise ProjectionError(f"{operation}: unavailable") from None
    if result.returncode:
        raise ProjectionError(f"{operation}: failed")
    return result.stdout.strip()


def synthetic_env(project, endpoint, revision):
    # Do not inherit operator credentials, Docker contexts or Compose overrides.
    env = {"PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"), "LC_ALL": "C",
           "CABINET_CI_DOCKER_HOST": endpoint, "DOCKER_HOST": endpoint}
    for path in STACK:
        for key in re.findall(r"\$\{([A-Z_0-9]+):\?", (ROOT / path).read_text()):
            env[key] = secrets.token_hex(32)
    env.update({
        "GATEWAY_CONFIG": str(ROOT / "docker/gateway/nginx.conf"),
        "CABINET_DOMAIN": "alpha.example.invalid", "CABINET_RELEASE": revision,
        "CABINET_IMAGE_ALPHA_ANALYTICS": f"{project}:analytics",
        "CABINET_IMAGE_POSTGRES": f"{project}:postgres",
        "CABINET_ALPHA_DEV_GENERATION": "g" + sha256(project.encode()).hexdigest()[:16],
        "CABINET_ALPHA_DEV_TOPIC": f"{project}.status",
        "CABINET_ALPHA_DEV_GROUP": f"{project}.consumer",
        "CABINET_ALPHA_DEV_DLQ_TOPIC": f"{project}.status.dlq",
        "CABINET_ALPHA_DEV_READ_ENABLED": "false",
        "CABINET_ALPHA_DEV_KAFKA_IMAGE": f"{project}:kafka",
    })
    return env


def check_wiring(config, env):
    services = config["services"]
    analytics, valkey = services["analytics"], services["valkey"]
    settings = analytics["environment"]
    attached = {name for name, service in services.items() if "alpha-cache" in service.get("networks", {})}
    if (settings.get("SPRING_PROFILES_ACTIVE") != "prod"
            or str(settings.get("CABINET_VALKEY_ENABLED")).lower() != "true"
            or settings.get("CABINET_VALKEY_HOST") != "valkey"
            or settings.get("CABINET_VALKEY_PASSWORD") != env["VALKEY_PASSWORD"]
            or valkey["environment"].get("VALKEY_PASSWORD") != env["VALKEY_PASSWORD"]
            or set(analytics["networks"]) != PRIVATE_NETWORKS
            or set(valkey["networks"]) != {"cabinet", "alpha-cache"}
            or attached != {"analytics", "valkey"}
            or analytics["depends_on"]["valkey"]["condition"] != "service_healthy"
            or not all(config["networks"][key].get("internal") is True for key in PRIVATE_NETWORKS)
            or any(services[key].get("ports") for key in ("analytics", "analytics-db", "analytics-kafka", "valkey"))
            or services["credit"]["environment"]["ANALYTICS_STATUS_READ_ENABLED"] not in ("false", False)):
        raise ProjectionError("private_production_wiring_required")


class Docker:
    def __init__(self, endpoint, config, env):
        self.command = ["docker", "--config", str(config), "--host", endpoint]
        self.env = env

    def run(self, operation, *args, **options):
        return checked(operation, [*self.command, *args], self.env, **options)

    def guard(self):
        labels = json.loads(self.run("engine_guard", "info", "--format", "{{json .Labels}}"))
        if not isinstance(labels, list) or "cabinet-ci=true" not in labels:
            raise ProjectionError("cabinet_ci_engine_required")

    def inspect(self, kind, reference, template):
        return json.loads(self.run("object_inspection", kind, "inspect", "--format", template, reference))


def cleanup_owned(docker, project, job_id):
    """Use exact job labels/IDs; never prune, remove other projects, or delete base images."""
    docker.guard()
    errors = []
    for kind in ("container", "network", "volume", "image"):
        image = kind == "image"
        project_key = IMAGE_PROJECT_LABEL if image else PROJECT_LABEL
        args = [kind, "ls", "--quiet", "--filter", f"label={project_key}={project}"]
        if kind == "container":
            args += ["--all", "--no-trunc"]
        elif kind == "network":
            args += ["--no-trunc"]
        elif image:
            args += ["--no-trunc", "--filter", f"label={JOB_LABEL}={job_id}"]
        try:
            references = set(docker.run("cleanup_discovery", *args).splitlines())
            for reference in sorted(references):
                valid = IMAGE_ID.fullmatch(reference) if image else ID.fullmatch(reference) if kind != "volume" else (
                    re.fullmatch(re.escape(project) + r"[-_][a-zA-Z0-9_.-]+", reference)
                )
                if not valid:
                    raise ProjectionError("cleanup_scope_mismatch")
                template = "{{json .Config.Labels}}" if kind in {"container", "image"} else "{{json .Labels}}"
                labels = docker.inspect(kind, reference, template)
                if not isinstance(labels, dict) or labels.get(project_key) != project or (image and labels.get(JOB_LABEL) != job_id):
                    raise ProjectionError("cleanup_scope_mismatch")
                options = ["--force", "--volumes"] if kind == "container" else ["--no-prune"] if image else []
                docker.run("owned_cleanup", kind, "rm", *options, reference, timeout=35)
        except (ProjectionError, ValueError):
            errors.append(kind)
    if errors:
        raise ProjectionError("owned_cleanup_incomplete")


def jar_evidence(revision):
    artifacts = ROOT / "ci-artifacts/alpha-analytics"
    recorded = (artifacts / "source-revision.txt").read_text().strip()
    jar = ROOT / "services/analytics/build/libs/app.jar"
    digest = sha256(jar.read_bytes()).hexdigest()
    if (recorded != revision or (artifacts / "app.jar").read_bytes() != jar.read_bytes()
            or (artifacts / "app.jar.sha256").read_text().strip() != f"{digest}  app.jar"):
        raise ProjectionError("verified_jar_mismatch")
    return digest


def image_evidence(docker, reference, revision):
    image = docker.inspect("image", reference,
                           '{"id":{{json .Id}},"revision":{{json (index .Config.Labels "org.opencontainers.image.revision")}}}')
    if not IMAGE_ID.fullmatch(image.get("id", "")) or image.get("revision") != revision:
        raise ProjectionError("image_source_mismatch")
    return image


def container_id(docker, compose, service):
    value = checked("container_identity", [*compose, "ps", "--all", "--quiet", service], docker.env)
    if not ID.fullmatch(value):
        raise ProjectionError("container_identity_mismatch")
    return value


def environment_values(docker, container):
    values = docker.inspect("container", container, "{{json .Config.Env}}")
    if not isinstance(values, list) or any(not isinstance(value, str) or "=" not in value for value in values):
        raise ProjectionError("runtime_configuration_invalid")
    return dict(value.split("=", 1) for value in values)


def valkey_namespace_probe(docker, analytics, valkey, project, env):
    actual = environment_values(docker, analytics)
    cache = environment_values(docker, valkey)
    if (actual.get("SPRING_PROFILES_ACTIVE") != "prod" or actual.get("CABINET_VALKEY_ENABLED") != "true"
            or actual.get("CABINET_VALKEY_HOST") != "valkey"
            or actual.get("CABINET_VALKEY_PORT", "6379") != "6379"
            or actual.get("CABINET_VALKEY_PASSWORD") != env["VALKEY_PASSWORD"]
            or cache.get("VALKEY_PASSWORD") != actual.get("CABINET_VALKEY_PASSWORD")):
        raise ProjectionError("runtime_valkey_configuration_mismatch")
    image = docker.inspect("container", valkey, "{{json .Image}}")
    if not IMAGE_ID.fullmatch(image):
        raise ProjectionError("valkey_image_identity_mismatch")
    probe_env = {**docker.env, "VALKEYCLI_AUTH": actual["CABINET_VALKEY_PASSWORD"],
                 "PROBE_HOST": actual["CABINET_VALKEY_HOST"], "PROBE_PORT": actual.get("CABINET_VALKEY_PORT", "6379")}
    # Password is inherited by name, never a command argument or receipt field.
    script = ('test "$(valkey-cli --no-auth-warning --raw -h "$PROBE_HOST" -p "$PROBE_PORT" ping)" = PONG; '
              'unset VALKEYCLI_AUTH; reply=$(valkey-cli --raw -h "$PROBE_HOST" -p "$PROBE_PORT" ping); '
              'case "$reply" in NOAUTH*) ;; *) exit 1;; esac')
    checked("authenticated_namespace_probe", [*docker.command, "run", "--rm", "--name", f"{project}-valkey-probe",
            "--network", f"container:{analytics}", "--label", f"{PROJECT_LABEL}={project}",
            "--env", "VALKEYCLI_AUTH", "--env", "PROBE_HOST", "--env", "PROBE_PORT",
            "--entrypoint", "/bin/sh", image, "-eu", "-c", script], probe_env, timeout=25)
    return {"kind": "authenticated_cli_in_analytics_network_namespace", "analytics_container_id": analytics,
            "valkey_container_id": valkey, "probe_image_id": image,
            "host": actual["CABINET_VALKEY_HOST"], "port": int(actual.get("CABINET_VALKEY_PORT", "6379")),
            "credentials_match_running_valkey": True,
            "authenticated_ping": True, "unauthenticated_denied": True, "jvm_client_assertion": False}


def run_projection(docker, compose, project, revision, env, receipt):
    def run(operation, *args, **options):
        return checked(operation, [*compose, *args], env, **options)

    def kafka(tool, *args, **options):
        return run("kafka_operation", "exec", "-T", "analytics-kafka", f"/opt/kafka/bin/{tool}.sh",
                   "--bootstrap-server", "analytics-kafka:9092", *args, **options)

    def psql(role, *args, **options):
        script = ('case "$1" in owner) export PGPASSWORD="$ALPHA_PILOT_PG_OWNER_PASSWORD";; '
                  'app) export PGPASSWORD="$ALPHA_PILOT_PG_APP_PASSWORD";; *) exit 2;; esac; '
                  'role="$1"; shift; exec psql -X -w -h 127.0.0.1 -U "analytics_$role" '
                  '-d analytics -v ON_ERROR_STOP=1 "$@"')
        return run("analytics_sql", "exec", "-T", "analytics-db", "sh", "-eu", "-c", script, "psql", role, *args, **options)

    digest = jar_evidence(revision)
    labels = ["--label", f"{IMAGE_PROJECT_LABEL}={project}", "--label", f"{JOB_LABEL}={os.environ['CI_JOB_ID']}",
              "--label", f"org.opencontainers.image.revision={revision}"]
    image = env["CABINET_IMAGE_ALPHA_ANALYTICS"]
    docker.run("analytics_image_build", "build", "--platform", "linux/amd64", "--tag", image, *labels,
               "--build-arg", "SERVICE=analytics", "--build-arg", "JAR_SOURCE=prebuilt",
               "--build-arg", f"SOURCE_REVISION={revision}", "-f", "docker/service/Dockerfile", ".", timeout=360)
    analytics_image = image_evidence(docker, image, revision)
    receipt["analytics"] = {**analytics_image, "jar_sha256": digest, "jar_source": "prebuilt"}
    pin = (ROOT / "ci/alpha/kafka-image.txt").read_text().strip()
    if not re.fullmatch(r"apache/kafka:[A-Za-z0-9._-]+@sha256:[a-f0-9]{64}", pin):
        raise ProjectionError("immutable_kafka_pin_required")
    docker.run("pinned_kafka_pull", "pull", "--platform", "linux/amd64", pin, timeout=150)
    upstream = docker.inspect("image", pin, '{"id":{{json .Id}},"os":{{json .Os}},"arch":{{json .Architecture}},"digests":{{json .RepoDigests}}}')
    repository = pin.split("@", 1)[0].rsplit(":", 1)[0]
    if (not IMAGE_ID.fullmatch(upstream.get("id", "")) or upstream["os"] != "linux" or upstream["arch"] != "amd64"
            or f"{repository}@{pin.split('@', 1)[1]}" not in upstream["digests"]):
        raise ProjectionError("kafka_upstream_identity_mismatch")
    kafka_image = env["CABINET_ALPHA_DEV_KAFKA_IMAGE"]
    docker.run("patched_kafka_build", "build", "--platform", "linux/amd64", "--tag", kafka_image, *labels,
               "--build-arg", f"UPSTREAM_KAFKA_IMAGE={pin}", "-f", "ci/alpha/Kafka.Dockerfile", "ci/alpha", timeout=180)
    built_kafka = image_evidence(docker, kafka_image, revision)
    if docker.inspect("image", kafka_image, '{{json (index .Config.Labels "org.opencontainers.image.base.name")}}') != pin:
        raise ProjectionError("patched_kafka_base_mismatch")
    receipt["kafka"] = {**built_kafka, "upstream_ref": pin, "upstream_image_id": upstream["id"]}
    docker.run("patched_postgres_build", "build", "--platform", "linux/amd64", "--tag", env["CABINET_IMAGE_POSTGRES"],
               *labels, "-f", "docker/postgres/Dockerfile", ".", timeout=180)
    run("private_dependencies", "up", "-d", "--wait", "--wait-timeout", "150",
        "valkey", "analytics-db", "analytics-kafka", timeout=180)
    run("analytics_migrations", "run", "--rm", "--no-deps", "analytics-migrate", timeout=90)
    topic = env["CABINET_ALPHA_DEV_TOPIC"]
    for name in (topic, env["CABINET_ALPHA_DEV_DLQ_TOPIC"]):
        kafka("kafka-topics", "--create", "--topic", name, "--partitions", "3", "--replication-factor", "1")
    snapshot = "application_id,application_version,status\n"
    psql("owner", "-q", "-v", f"generation={topic}", "-v", f"snapshot_sha256={sha256(snapshot.encode()).hexdigest()}",
         "-f", "/alpha/import-status-snapshot.sql", stdin=snapshot)
    run("analytics_prod_startup", "up", "-d", "--no-deps", "--wait", "--wait-timeout", "90", "analytics", timeout=105)
    analytics = container_id(docker, compose, "analytics")
    actual_image = docker.inspect("container", analytics, "{{json .Image}}")
    if actual_image != analytics_image["id"]:
        raise ProjectionError("running_analytics_image_mismatch")
    actual_networks = docker.inspect("container", analytics, "{{json .NetworkSettings.Networks}}")
    if set(actual_networks) != {f"{project}_{name}" for name in PRIVATE_NETWORKS}:
        raise ProjectionError("running_analytics_network_mismatch")
    receipt["analytics"]["container_id"] = analytics
    receipt["analytics"]["networks"] = sorted(actual_networks)
    broker = container_id(docker, compose, "analytics-kafka")
    if docker.inspect("container", broker, "{{json .Image}}") != built_kafka["id"]:
        raise ProjectionError("running_kafka_image_mismatch")
    run("running_kafka_patch", "exec", "-T", "analytics-kafka", "apk", "info", "--exists", "brotli-libs=1.2.1-r0")
    if "-XX:-UseBASE64Intrinsics" not in environment_values(docker, broker).get("KAFKA_OPTS", "").split():
        raise ProjectionError("running_kafka_jvm_mitigation_missing")
    receipt["kafka"].update(container_id=broker, patched_brotli_libs="1.2.1-r0", jvm_mitigation_enabled=True)
    receipt["valkey_namespace_probe"] = valkey_namespace_probe(docker, analytics, container_id(docker, compose, "valkey"), project, env)
    application_id, event_id = str(uuid4()), str(uuid4())
    event = {"applicationId": application_id, "eventId": event_id, "applicationVersion": 1,
             "status": "SUBMITTED", "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")}
    kafka("kafka-console-producer", "--topic", topic, "--reader-property", "parse.key=true",
          "--reader-property", "key.separator=:", stdin=f"{application_id}:{json.dumps(event)}\n")
    query = f"""SELECT v.application_version || ':' || v.status
                FROM application_status_projection v
                JOIN application_status_receipts r USING (application_id, application_version, status)
                WHERE v.application_id = '{application_id}' AND r.event_id = '{event_id}'"""
    deadline = time.monotonic() + 45
    while psql("app", "-qtA", "-c", query, timeout=8) != "1:SUBMITTED":
        if time.monotonic() >= deadline:
            raise ProjectionError("kafka_sql_receipt_timeout")
        time.sleep(2)
    receipt["kafka_sql"] = {"application_id": application_id, "event_id": event_id, "topic": topic,
                            "application_version": 1, "status": "SUBMITTED", "matching_receipt_and_projection": True}
    receipt["analytics_prod_startup"] = True


def interrupted(signum, _frame):
    raise InterruptedProjection("terminated" if signum == signal.SIGTERM else "interrupted")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cleanup-only", action="store_true")
    parser.add_argument("--receipt", type=Path, default=ROOT / "ci-artifacts/alpha-projection.json")
    args = parser.parse_args()
    job_id = os.environ.get("CI_JOB_ID", "")
    receipt = {"schema_version": 1, "passed": False, "cleanup_passed": False}
    guarded = False
    status = 1
    try:
        project = job_project(job_id)
        endpoint = endpoint_from_env(os.environ)
        revision = checked("source_revision", ["git", "rev-parse", "HEAD"], {"PATH": os.environ.get("PATH", "")})
        if not SHA.fullmatch(revision) or os.environ.get("CI_COMMIT_SHA", revision) != revision:
            raise ProjectionError("source_revision_mismatch")
        if not args.cleanup_only and checked("clean_source", ["git", "status", "--porcelain", "--untracked-files=all"],
                                              {"PATH": os.environ.get("PATH", "")}):
            raise ProjectionError("clean_checkout_required")
        tree = checked("source_tree", ["git", "rev-parse", "HEAD^{tree}"], {"PATH": os.environ.get("PATH", "")})
        receipt.update(source_commit=revision, source_tree=tree, project=project, job_id=job_id)
        with tempfile.TemporaryDirectory(prefix="alpha-projection-") as directory:
            env = synthetic_env(project, endpoint, revision)
            docker = Docker(endpoint, directory, env)
            docker.guard()
            guarded = True
            receipt["daemon_guard"] = {"endpoint_kind": "unix", "engine_label": "cabinet-ci=true"}
            signal.signal(signal.SIGTERM, interrupted)
            signal.signal(signal.SIGINT, interrupted)
            try:
                if not args.cleanup_only:
                    # Override the production volume name so the synthetic run cannot
                    # address a Dev generation, including during an interrupted cleanup.
                    overlay = Path(directory) / "scope.json"
                    overlay.write_text(json.dumps({"volumes": {"alpha-analytics-data": {"name": f"{project}-analytics-data"}}}))
                    compose = [*docker.command, "compose", "--env-file", "/dev/null", "-p", project, "--profile", "prod"]
                    for name in (*STACK, str(overlay)):
                        compose.extend(("-f", name))
                    config = json.loads(checked("compose_render", [*compose, "config", "--format", "json"], env))
                    check_wiring(config, env)
                    receipt["private_compose_wiring"] = True
                    run_projection(docker, compose, project, revision, env, receipt)
                receipt["passed"] = True
                status = 0
            finally:
                cleanup_owned(docker, project, job_id)
                receipt["cleanup_passed"] = True
    except InterruptedProjection:
        receipt["error_category"] = "interrupted"
        status = 143
    except ProjectionError as error:
        receipt["error_category"] = "verification_failed" if guarded else "preflight_failed"
        # ProjectionError messages are closed codes from this module, never
        # subprocess diagnostics, user input, exception messages or credentials.
        receipt["failure_code"] = str(error)
    except Exception:
        # Neither command stdout/stderr, environment values nor exception text
        # are published.
        receipt["error_category"] = "verification_failed" if guarded else "preflight_failed"
    if not receipt["cleanup_passed"]:
        receipt["passed"] = False
        status = 1
    if not args.cleanup_only:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n")
    print("Alpha projection fixture passed" if status == 0 else "Alpha projection fixture failed")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
