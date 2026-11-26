"""Read and verify the immutable GitLab image set used by the deployment."""
from hashlib import sha256
import json
from pathlib import Path
import re
import subprocess

APP_SERVICES = ("identity", "credit", "engagement", "external-sim", "frontend")
IMAGE_COMPONENTS = (*APP_SERVICES, "postgres", "swagger")
OPTIONAL_IMAGES = {"ssh-bridge", "clamav"}
REVISION = re.compile(r"[0-9a-f]{40}")
IMAGE = re.compile(r"[a-z0-9.-]+(?::[0-9]{1,5})?(?:/[a-z0-9][a-z0-9._-]*)+@sha256:[0-9a-f]{64}")
SERVICE_COMPONENTS = {
    **{name: name for name in (*APP_SERVICES, "postgres")},
    "swagger-ui": "swagger",
    "clamav": "clamav",
    "db-init": "postgres",
    "identity-migrate": "identity",
    "credit-migrate": "credit",
    "engagement-migrate": "engagement",
}


class RegistryRelease:
    def __init__(self, path):
        self.path = Path(path).resolve()
        raw = self.path.read_bytes()
        release = json.loads(raw)
        if not isinstance(release, dict):
            raise ValueError("Ожидается JSON manifest выпуска")
        self.revision = release.get("revision")
        self.images = release.get("images")
        if not isinstance(self.revision, str) or not REVISION.fullmatch(self.revision):
            raise ValueError("Нужна полная Git-ревизия выпуска")
        required = set(IMAGE_COMPONENTS)
        if not isinstance(self.images, dict) or not required <= set(self.images) <= (required | OPTIONAL_IMAGES):
            raise ValueError("Manifest должен содержать полный набор образов выпуска")
        for name, image in self.images.items():
            if not isinstance(image, str) or not IMAGE.fullmatch(image):
                raise ValueError(f"Нужен полный OCI digest образа {name}")
        if len({image.rsplit("/", 1)[0] for image in self.images.values()}) != 1:
            raise ValueError("Образы выпуска должны находиться в одном проекте Registry")
        self.checksum = sha256(raw).hexdigest()

    def environment(self):
        components = (*IMAGE_COMPONENTS, "clamav") if "clamav" in self.images else IMAGE_COMPONENTS
        return {
            "CABINET_RELEASE": self.revision,
            **{"CABINET_IMAGE_" + component.upper().replace("-", "_"): self.images[component]
               for component in components},
        }

    def pull_and_verify(self, services):
        components = {SERVICE_COMPONENTS[name] for name in services if name in SERVICE_COMPONENTS}
        for name in sorted(components.intersection(self.images)):
            image = self.images[name]
            # Do not print command output. A configured credential helper can include sensitive errors.
            result = subprocess.run(["docker", "pull", image], capture_output=True)
            if result.returncode:
                raise RuntimeError(f"Registry: не скачан образ {name} по digest")
            self.local_image_id(name)

    def local_image_id(self, name):
        result = subprocess.run(
            ["docker", "image", "inspect", "--format",
             '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}}', self.images[name]],
            capture_output=True, text=True,
        )
        fields = result.stdout.split()
        if (result.returncode or len(fields) != 2 or not re.fullmatch(r"sha256:[0-9a-f]{64}", fields[0])
                or fields[1] != self.revision):
            raise RuntimeError(f"OCI revision образа {name} не совпадает с manifest")
        return fields[0]
