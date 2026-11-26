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
SHA256 = re.compile(r"[0-9a-f]{64}")
IMAGE = re.compile(r"[a-z0-9.-]+(?::[0-9]{1,5})?(?:/[a-z0-9][a-z0-9._-]*)+@sha256:[0-9a-f]{64}")
SERVICE_COMPONENTS = {
    **{name: name for name in (*APP_SERVICES, "postgres")},
    "swagger-ui": "swagger",
    "clamav": "clamav",
    "ollama-ssh-bridge": "ssh-bridge",
    "db-init": "postgres",
    "identity-migrate": "identity",
    "credit-migrate": "credit",
    "engagement-migrate": "engagement",
    "analytics-db": "postgres",
    "analytics": "analytics",
    "analytics-migrate": "analytics",
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
        repositories = {image.rsplit("/", 1)[0] for image in self.images.values()}
        if len(repositories) != 1:
            raise ValueError("Образы выпуска должны находиться в одном проекте Registry")
        self.alpha_dev = None
        if "alpha_dev" in release:
            alpha = release["alpha_dev"]
            repository, = repositories
            if (not isinstance(alpha, dict) or set(alpha) != {"image", "jar_sha256"}
                    or not isinstance(alpha["image"], str)
                    or not re.fullmatch(re.escape(repository) + r"/analytics@sha256:[0-9a-f]{64}", alpha["image"])
                    or not isinstance(alpha["jar_sha256"], str) or not SHA256.fullmatch(alpha["jar_sha256"])):
                raise ValueError("Некорректный digest образа или SHA256 JAR Analytics в manifest")
            self.alpha_dev = alpha
        self.checksum = sha256(raw).hexdigest()

    def environment(self):
        return {
            "CABINET_RELEASE": self.revision,
            **{"CABINET_IMAGE_" + component.upper().replace("-", "_"): self.images[component]
               for component in self.images},
        }

    def pull_and_verify(self, services):
        components = {SERVICE_COMPONENTS[name] for name in services if name in SERVICE_COMPONENTS}
        images = self.images if not self.alpha_dev else {**self.images, "analytics": self.alpha_dev["image"]}
        for name in sorted(components.intersection(images)):
            image = images[name]
            # Do not print command output. A configured credential helper can include sensitive errors.
            result = subprocess.run(["docker", "pull", image], capture_output=True)
            if result.returncode:
                raise RuntimeError(f"Registry: не скачан образ {name} по digest")
            self.local_image_id(name)

    def local_image_id(self, name):
        labels = '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
        if name == "analytics" and self.alpha_dev:
            image = self.alpha_dev["image"]
            labels += ' {{index .Config.Labels "org.opencontainers.image.jar.sha256"}}'
            expected = [self.revision, self.alpha_dev["jar_sha256"]]
        else:
            image = self.images[name]
            expected = [self.revision]
        result = subprocess.run(
            ["docker", "image", "inspect", "--format", labels, image], capture_output=True, text=True,
        )
        fields = result.stdout.split()
        if (result.returncode or not fields or not re.fullmatch(r"sha256:[0-9a-f]{64}", fields[0])
                or fields[1:] != expected):
            raise RuntimeError(f"OCI provenance образа {name} не совпадает с manifest")
        return fields[0]
