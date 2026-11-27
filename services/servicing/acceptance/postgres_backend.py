"""Job-owned PostgreSQL adapter; no default Docker socket and no existing DB attachment."""
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

POSTGRES_IMAGE = "postgres:16.15-alpine@sha256:deb28ee471183cb0c303113dac9b2433c51eeeeb678def7af9a9ec5820367815"
COMPONENT = "servicing-acceptance"


def tool_environment(source, home, inherit_proxy=False):
    result = {key: source[key] for key in ("PATH", "LANG", "LC_ALL", "TZ", "TMPDIR", "TMP", "TEMP", "SSL_CERT_FILE", "SSL_CERT_DIR") if key in source}
    result.update(HOME=str(home), LANG=result.get("LANG", "C.UTF-8"))
    if inherit_proxy:
        for key in ("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy"):
            if key in source:
                result[key] = source[key]
    return result


class CiPostgres:
    def __init__(self, image, artifacts, env, source=None):
        source = os.environ if source is None else source
        if source.get("CI") != "true" or not re.fullmatch(r"[0-9]+", source.get("CI_JOB_ID", "")) or not re.fullmatch(r"[0-9]+", source.get("CI_PROJECT_ID", "")):
            raise RuntimeError("CI PostgreSQL requires explicit numeric job/project identity")
        self.socket = source.get("CABINET_CI_DOCKER_HOST", "")
        if not self.socket.startswith("unix:///") or not Path(self.socket[7:]).is_socket():
            raise RuntimeError("CI PostgreSQL requires an explicit cabinet-ci Unix socket")
        if image != POSTGRES_IMAGE:
            raise RuntimeError("CI PostgreSQL image must be the reviewed PostgreSQL16.15 digest")
        self.image, self.artifacts, self.env = image, artifacts, env
        self.labels = {"cabinet-ci.job": source["CI_JOB_ID"], "cabinet-ci.project": source["CI_PROJECT_ID"], "cabinet-ci.component": COMPONENT}
        self.container = None
        labels = json.loads(self.docker("info", "--format", "{{json .Labels}}"))
        if "cabinet-ci=true" not in (labels or []):
            raise RuntimeError("Docker daemon is not labelled cabinet-ci=true")

    def docker(self, *arguments, env=None):
        result = subprocess.run(["docker", "--host", self.socket, *arguments], env=env or self.env, text=True,
                                capture_output=True, check=True, close_fds=True)
        return result.stdout.strip()

    def start(self, user, password):
        env = self.env.copy()
        env["POSTGRES_PASSWORD"] = password
        arguments = ["run", "--detach", "--name", "cabinet-ci-" + self.labels["cabinet-ci.job"] + "-servicing-" + uuid.uuid4().hex[:8],
                     "--memory", "384m", "--publish", "127.0.0.1::5432"]
        for key, value in self.labels.items():
            arguments.extend(["--label", key + "=" + value])
        arguments.extend(["--env", "POSTGRES_USER=" + user, "--env", "POSTGRES_PASSWORD", "--env", "POSTGRES_DB=postgres",
                          "--env", "POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256", self.image,
                          "-c", "shared_buffers=16MB", "-c", "max_connections=40", "-c", "password_encryption=scram-sha-256"])
        container = self.docker(*arguments, env=env)
        if not re.fullmatch(r"[a-f0-9]{64}", container):
            raise RuntimeError("Docker did not return an exact owned container ID")
        self.container = container
        (self.artifacts / "ci-resource.json").write_text(json.dumps({"containerId": container, "labels": self.labels, "image": self.image}))
        address = self.docker("port", container, "5432/tcp")
        if not re.fullmatch(r"127\.0\.0\.1:[0-9]+", address):
            raise RuntimeError("Owned PostgreSQL must publish only one loopback port")
        return int(address.rsplit(":", 1)[1])

    def remove(self, container):
        if not re.fullmatch(r"[a-f0-9]{12,64}", container):
            raise RuntimeError("Invalid owned container ID")
        labels = json.loads(self.docker("inspect", "--format", "{{json .Config.Labels}}", container))
        if any(labels.get(key) != value for key, value in self.labels.items()):
            raise RuntimeError("Refusing cleanup of a container outside this job/project/component")
        self.docker("rm", "--force", "--volumes", container)

    def close(self):
        if self.container:
            self.remove(self.container)
            self.container = None

    def cleanup_job(self):
        arguments = ["ps", "--all", "--quiet", "--no-trunc"]
        for key, value in self.labels.items():
            arguments.extend(["--filter", "label=" + key + "=" + value])
        for container in self.docker(*arguments).splitlines():
            self.remove(container)
