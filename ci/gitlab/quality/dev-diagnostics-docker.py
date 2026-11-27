"""Exercise the Dev diagnostic Go templates against the cabinet-ci Docker API."""

from contextlib import ExitStack
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
from unittest.mock import patch
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "deploy"))
import dev_diagnostics as diagnostics


def main():
    socket = os.environ.get("CABINET_CI_DOCKER_HOST", "")
    job_id = os.environ.get("CI_JOB_ID", "")
    if not socket.startswith("unix:///") or not re.fullmatch(r"[0-9]{1,24}", job_id):
        raise RuntimeError("Docker diagnostic fixture requires a cabinet-ci Unix socket and a numeric job ID")
    socket_path = Path(socket.removeprefix("unix://"))
    if not stat.S_ISSOCK(socket_path.stat().st_mode):
        raise RuntimeError("Docker diagnostic fixture requires a cabinet-ci socket")
    endpoint = f"unix://{socket_path.resolve()}"
    project = f"cabinet-diagnostics-{job_id}-{uuid4().hex[:12]}"
    image = f"{project}:fixture"

    with tempfile.TemporaryDirectory() as directory, ExitStack() as cleanup:
        context = Path(directory) / "build"
        context.mkdir()
        config = Path(directory) / "docker"
        config.mkdir()

        def docker(*args, timeout=20):
            operation = ".".join(args[:2]) if args[0] in {"image", "container", "network"} else args[0]
            command = ["docker", "--config", str(config), "--host", endpoint, *args]
            try:
                result = subprocess.run(command, env={"PATH": "/usr/local/bin:/usr/bin:/bin", "LC_ALL": "C"},
                                        stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)
            except (OSError, subprocess.TimeoutExpired):
                raise RuntimeError(f"Docker fixture {operation} did not complete") from None
            if result.returncode:
                raise RuntimeError(f"Docker fixture {operation} returned {result.returncode}")
            return result.stdout.strip()

        labels = json.loads(docker("info", "--format", "{{json .Labels}}"))
        if not isinstance(labels, list) or "cabinet-ci=true" not in labels:
            raise RuntimeError("Docker diagnostic fixture requires a cabinet-ci engine")

        (context / "Dockerfile").write_text('FROM scratch\nCOPY fixture /fixture\nCMD ["/fixture"]\n')
        (context / "fixture").write_text(project + "\n")
        docker("build", "--quiet", "--network", "none", "--tag", image, str(context), timeout=90)
        image_id = docker("image", "inspect", "--format", "{{.Id}}", image)
        if not diagnostics.IMAGE_ID.fullmatch(image_id):
            raise RuntimeError("Invalid Docker fixture image ID")
        cleanup.callback(docker, "image", "rm", "--no-prune", image_id)
        if json.loads(docker("image", "inspect", "--format", "{{json .Config.Labels}}", image_id)) not in (None, {}):
            raise RuntimeError("Scratch diagnostic fixture unexpectedly has image labels")

        project_label = f"com.docker.compose.project={project}"
        name = f"{project}_default"
        network_id = docker("network", "create", "--internal", "--label", project_label,
                            "--label", "com.docker.compose.network=default", name)
        if not diagnostics.ID.fullmatch(network_id):
            raise RuntimeError("Invalid Docker fixture network ID")
        cleanup.callback(docker, "network", "rm", network_id)

        container_id = docker("container", "create", "--name", f"{project}-analytics", "--network", name,
                              "--label", project_label, "--label", "com.docker.compose.service=analytics",
                              "--label", "com.docker.compose.oneoff=False", image_id)
        if not diagnostics.ID.fullmatch(container_id):
            raise RuntimeError("Invalid Docker fixture container ID")
        cleanup.callback(docker, "container", "rm", container_id)

        with patch.object(diagnostics, "PROJECT", project), patch.object(diagnostics, "DOCKER_SOCKET", endpoint):
            if diagnostics.inspect("image", [image_id], diagnostics.IMAGE_FIELDS) != [
                {"id": image_id, "revision": None}
            ]:
                raise RuntimeError("Image without labels did not produce a null revision")
            report = diagnostics.collect("registry.example.invalid/cabinet/project", set())
        container, = report["containers"]
        network, = report["networks"]
        if (container["image_id"] != image_id or container["oci_revision"] != "unclassified"
                or container["state"] != "created" or container["health"] is not None
                or container["labels"] != {"project": project, "service": "analytics", "oneoff": "False"}
                or network["name"] != name or network["internal"] is not True or report["error_counts"]):
            raise RuntimeError("Docker fixture diagnostic report did not match the created objects")
    print("Dev diagnostic Docker formats passed on an unlabeled image, created container and internal network")


if __name__ == "__main__":
    main()
