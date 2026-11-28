"""Read and verify the immutable GitLab image set used by the deployment."""
from hashlib import sha256
import json
from pathlib import Path
import re
import subprocess

APP_SERVICES = ("identity", "credit", "engagement", "external-sim", "frontend")
IMAGE_COMPONENTS = (*APP_SERVICES, "postgres", "swagger")
RUNTIME_COMPONENTS = {"catalog-grpc": "catalog", "assessment": "assessment",
                      "servicing": "servicing", "documents": "documents"}
OPTIONAL_IMAGES = {"ssh-bridge", "clamav", "mcp", *RUNTIME_COMPONENTS}
RUNTIME_MODES = {"disabled", "local", "remote"}
CONFIG_DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
REVISION = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")
KAFKA_UPSTREAM_IMAGE = re.compile(r"apache/kafka:[0-9.]+@sha256:[0-9a-f]{64}")
MCP_BASE_IMAGE = re.compile(r"node:[0-9]+\.[0-9]+\.[0-9]+-bookworm-slim@sha256:[0-9a-f]{64}")
KAFKA_IMAGE_FILE = Path(__file__).resolve().parents[1] / "ci/alpha/kafka-image.txt"
IMAGE = re.compile(r"[a-z0-9.-]+(?::[0-9]{1,5})?(?:/[a-z0-9][a-z0-9._-]*)+@sha256:[0-9a-f]{64}")
SERVICE_COMPONENTS = {
    **{name: name for name in (*APP_SERVICES, "postgres")},
    "swagger-ui": "swagger",
    "cabinet-mcp": "mcp",
    "clamav": "clamav",
    "ollama-ssh-bridge": "ssh-bridge",
    "db-init": "postgres",
    "identity-migrate": "identity",
    "credit-migrate": "credit",
    "engagement-migrate": "engagement",
    **{name: name for name in RUNTIME_COMPONENTS},
    "assessment-db": "postgres",
    "assessment-kafka": "kafka",
    "assessment-migrate": "assessment",
    "servicing-db": "postgres",
    "servicing-migrate": "servicing",
    "documents-db": "postgres",
    "documents-migrate": "documents",
    "documents-cutover": "documents",
    "documents-probe": "documents",
    "analytics-db": "postgres",
    "analytics-kafka": "kafka",
    "analytics": "analytics",
    "analytics-migrate": "analytics",
}


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Повторяющийся ключ в manifest выпуска")
        result[key] = value
    return result


class RegistryRelease:
    def __init__(self, path, *, allow_legacy_alpha=False):
        self.path = Path(path).resolve()
        raw = self.path.read_bytes()
        release = json.loads(raw, object_pairs_hook=unique_fields)
        if not isinstance(release, dict) or set(release) - {"revision", "images", "alpha_dev", "runtime_oci", "mcp", *RUNTIME_COMPONENTS.values()}:
            raise ValueError("Ожидается JSON manifest выпуска")
        self.revision = release.get("revision")
        self.images = release.get("images")
        if not isinstance(self.revision, str) or not REVISION.fullmatch(self.revision):
            raise ValueError("Нужна полная Git-ревизия выпуска")
        required = set(IMAGE_COMPONENTS)
        if not isinstance(self.images, dict) or not required <= set(self.images) <= (required | OPTIONAL_IMAGES):
            raise ValueError("Manifest должен содержать полный набор образов выпуска")
        for name, image in self.images.items():
            if (not isinstance(image, str) or not IMAGE.fullmatch(image)
                    or not image.rsplit("/", 1)[-1].startswith(name + "@")):
                raise ValueError(f"Нужен полный OCI digest образа {name}")
        repositories = {image.rsplit("/", 1)[0] for image in self.images.values()}
        if len(repositories) != 1:
            raise ValueError("Образы выпуска должны находиться в одном проекте Registry")
        repository, = repositories
        self.mcp = None
        if "mcp" in self.images or "mcp" in release:
            mcp = release.get("mcp")
            if ("mcp" not in self.images or not isinstance(mcp, dict) or set(mcp) != {"config_digest", "base_image"}
                    or not isinstance(mcp["config_digest"], str) or not CONFIG_DIGEST.fullmatch(mcp["config_digest"])
                    or not isinstance(mcp["base_image"], str) or not MCP_BASE_IMAGE.fullmatch(mcp["base_image"])):
                raise ValueError("Для MCP нужны OCI config digest и закреплённый базовый образ Node.js")
            self.mcp = mcp
        self.runtime_oci = release.get("runtime_oci", {})
        if "runtime_oci" in release and (
            not isinstance(self.runtime_oci, dict)
            or set(self.runtime_oci) != {"kafka", *RUNTIME_COMPONENTS}
            or any(not isinstance(value, str) or not CONFIG_DIGEST.fullmatch(value) for value in self.runtime_oci.values())
        ):
            raise ValueError("Ожидаются digest OCI-конфигураций четырёх runtime и Kafka")
        self.has_all_runtimes = "runtime_oci" in release
        self.runtime = {}
        for component, key in RUNTIME_COMPONENTS.items():
            descriptor = release.get(key)
            legacy_documents = component == "documents" and key not in release and not self.has_all_runtimes
            if not self.has_all_runtimes and key not in release and (component not in self.images or legacy_documents):
                continue
            keys = {"jar_sha256", "dev_mode"}
            if key == "assessment":
                keys.add("kafka_image")
            if key == "catalog" and (self.has_all_runtimes or isinstance(descriptor, dict) and "config_digest" in descriptor):
                keys.add("config_digest")
            if (component not in self.images or not isinstance(descriptor, dict) or set(descriptor) != keys
                    or not isinstance(descriptor["jar_sha256"], str) or not SHA256.fullmatch(descriptor["jar_sha256"])
                    or not isinstance(descriptor["dev_mode"], str) or descriptor["dev_mode"] not in RUNTIME_MODES):
                raise ValueError(f"Некорректный digest, SHA256 JAR или режим {component} в manifest")
            if key == "catalog" and "config_digest" in descriptor:
                digest = descriptor["config_digest"]
                if (not isinstance(digest, str) or not CONFIG_DIGEST.fullmatch(digest)
                        or self.has_all_runtimes and digest != self.runtime_oci[component]):
                    raise ValueError("Некорректный digest OCI-конфигурации Catalog")
            if key == "assessment" and (
                not isinstance(descriptor["kafka_image"], str)
                or not re.fullmatch(re.escape(repository) + r"/kafka@sha256:[0-9a-f]{64}", descriptor["kafka_image"])
            ):
                raise ValueError("Для Assessment нужен digest Kafka из проекта выпуска")
            self.runtime[component] = descriptor

        self.catalog = self.runtime.get("catalog-grpc")
        self.assessment = self.runtime.get("assessment")
        self.servicing = self.runtime.get("servicing")
        self.documents = self.runtime.get("documents")
        self.alpha_dev = None
        self.legacy_alpha = False
        if "alpha_dev" in release:
            alpha = release["alpha_dev"]
            repository, = repositories
            if (not isinstance(alpha, dict) or set(alpha) != {"image", "jar_sha256", "kafka_image"}
                    or not isinstance(alpha["image"], str)
                    or not re.fullmatch(re.escape(repository) + r"/analytics@sha256:[0-9a-f]{64}", alpha["image"])
                    or not isinstance(alpha["jar_sha256"], str) or not SHA256.fullmatch(alpha["jar_sha256"])
                    or not isinstance(alpha["kafka_image"], str)):
                raise ValueError("Некорректный digest образа Analytics, Kafka или SHA256 JAR в manifest")
            if not re.fullmatch(re.escape(repository) + r"/kafka@sha256:[0-9a-f]{64}", alpha["kafka_image"]):
                if not allow_legacy_alpha or not KAFKA_UPSTREAM_IMAGE.fullmatch(alpha["kafka_image"]):
                    raise ValueError("Нужен digest образа Kafka из проекта Registry выпуска")
                self.legacy_alpha = True
            if self.assessment and self.assessment["kafka_image"] != alpha["kafka_image"]:
                raise ValueError("Assessment и Analytics должны использовать один образ Kafka выпуска")
            self.alpha_dev = alpha
        self.checksum = sha256(raw).hexdigest()

    @property
    def kafka_image(self):
        if self.assessment:
            return self.assessment["kafka_image"]
        if not self.alpha_dev or self.legacy_alpha:
            raise ValueError("Для запуска Kafka нужен образ из проекта Registry выпуска")
        return self.alpha_dev["kafka_image"]

    @property
    def kafka_upstream(self):
        image = KAFKA_IMAGE_FILE.read_text(encoding="utf-8").strip()
        if not KAFKA_UPSTREAM_IMAGE.fullmatch(image):
            raise ValueError("Нужен закреплённый upstream образ Apache Kafka")
        return image

    def environment(self):
        return {
            "CABINET_RELEASE": self.revision,
            **{"CABINET_IMAGE_" + component.upper().replace("-", "_"): self.images[component]
               for component in self.images},
        }

    def image_for(self, component):
        if component == "analytics" and self.alpha_dev:
            return self.alpha_dev["image"]
        if component == "kafka":
            return self.kafka_image
        try:
            return self.images[component]
        except KeyError:
            raise ValueError(f"Для {component} нужен образ из manifest выпуска") from None

    def pull_and_verify(self, services):
        components = {SERVICE_COMPONENTS[name] for name in services if name in SERVICE_COMPONENTS}
        optional_upstream = {"clamav"} - self.images.keys()
        references = {name: self.image_for(name) for name in sorted(components - optional_upstream)}
        for name, image in references.items():
            # Do not print command output. A configured credential helper can include sensitive errors.
            result = subprocess.run(["docker", "pull", image], capture_output=True)
            if result.returncode:
                raise RuntimeError(f"Registry: не скачан образ {name} по digest")
            self.local_image_id(name)

    def local_image_id(self, name):
        labels = '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
        expected = [self.revision]
        image = self.image_for(name)
        if name == "analytics" and self.alpha_dev:
            labels += ' {{index .Config.Labels "org.opencontainers.image.jar.sha256"}}'
            expected.append(self.alpha_dev["jar_sha256"])
        elif name in self.runtime:
            labels += ' {{index .Config.Labels "org.opencontainers.image.jar.sha256"}}'
            expected.append(self.runtime[name]["jar_sha256"])
        elif name == "kafka":
            labels += ' {{index .Config.Labels "org.opencontainers.image.base.name"}}'
            expected.append(self.kafka_upstream)
        elif name == "mcp" and self.mcp:
            labels += ' {{index .Config.Labels "org.opencontainers.image.base.name"}}'
            expected.append(self.mcp["base_image"])
        result = subprocess.run(
            ["docker", "image", "inspect", "--format", labels, image], capture_output=True, text=True,
        )
        fields = result.stdout.split()
        digest = self.runtime_oci.get(name) or self.runtime.get(name, {}).get("config_digest")
        if name == "mcp" and self.mcp:
            digest = self.mcp["config_digest"]
        if digest and (not fields or fields[0] != digest):
            raise RuntimeError(f"Digest OCI-конфигурации {name} не совпадает с manifest")
        if (result.returncode or not fields or not re.fullmatch(r"sha256:[0-9a-f]{64}", fields[0])
                or fields[1:] != expected):
            raise RuntimeError(f"OCI provenance образа {name} не совпадает с manifest")
        return fields[0]
