"""Private Dev-only Analytics wiring and generation bootstrap."""
from dataclasses import dataclass
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile


PROJECT = "cabinet-dev"
PARTITIONS = 3
SERVICES = ("analytics", "analytics-db", "analytics-kafka")
SECRET_KEYS = (
    "CABINET_ALPHA_DEV_PG_ADMIN_PASSWORD",
    "CABINET_ALPHA_DEV_PG_OWNER_PASSWORD",
    "CABINET_ALPHA_DEV_PG_APP_PASSWORD",
    "CABINET_ALPHA_DEV_READ_TOKEN",
)
OPS_DIR = Path(__file__).resolve().parents[1] / "services/analytics/ops"
KAFKA_IMAGE_FILE = Path(__file__).resolve().parents[1] / "ci/alpha/kafka-image.txt"


@dataclass(frozen=True)
class AlphaDev:
    generation: str
    read_enabled: bool

    @classmethod
    def from_config(cls, project, local, values, release):
        enabled = values.get("CABINET_ALPHA_DEV_ENABLED", "false")
        read = values.get("CABINET_ALPHA_DEV_READ_ENABLED", "false")
        if enabled not in {"true", "false"} or read not in {"true", "false"}:
            raise ValueError("Переключатели пилота Dev должны содержать true или false")
        if enabled == "false":
            if read == "true":
                raise ValueError("Чтение из Analytics требует CABINET_ALPHA_DEV_ENABLED=true")
            return None
        if local or project != PROJECT:
            raise ValueError("Пилот Analytics допускается только в защищённом cabinet-dev")
        # Previous manifests can still be used to back up the base stack.
        if not release or not release.alpha_dev:
            return None
        generation = values.get("CABINET_ALPHA_DEV_GENERATION", "")
        if not re.fullmatch(r"g[0-9a-f]{16}", generation):
            raise ValueError("Поколение Dev требует g и 16 строчных шестнадцатеричных символов")
        secrets = [values.get(key, "") for key in SECRET_KEYS]
        for key, secret in zip(SECRET_KEYS, secrets):
            if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", secret) or secret.startswith("dev-") or "CHANGE_ME" in secret:
                raise ValueError(f"Нужен отдельный случайный {key}")
        other_values = {value for key, value in values.items() if key not in SECRET_KEYS}
        if len(set(secrets)) != len(secrets) or other_values.intersection(secrets):
            raise ValueError("Для пилота Dev нужны отдельные секреты")
        return cls(generation, read == "true")

    @property
    def topic(self):
        return f"alpha.dev.credit.application-status-changed.v1.{self.generation}"

    @property
    def dlq(self):
        return f"{self.topic}.dlq"

    @property
    def group(self):
        return f"alpha-dev-analytics-{self.generation}"

    @property
    def kafka_image(self):
        image = KAFKA_IMAGE_FILE.read_text(encoding="utf-8").strip()
        if not re.fullmatch(r"apache/kafka:[0-9.]+@sha256:[0-9a-f]{64}", image):
            raise ValueError("Требуется закреплённый образ Apache Kafka")
        return image

    def environment(self, release):
        return {
            "CABINET_ALPHA_DEV_KAFKA_IMAGE": self.kafka_image,
            "CABINET_ALPHA_DEV_TOPIC": self.topic,
            "CABINET_ALPHA_DEV_GROUP": self.group,
            "CABINET_ALPHA_DEV_DLQ_TOPIC": self.dlq,
            "CABINET_ALPHA_DEV_READ_ENABLED": str(self.read_enabled).lower(),
            "CABINET_IMAGE_ALPHA_ANALYTICS": release.alpha_dev["image"],
        }

    def state(self):
        return {"generation": self.generation, "read_enabled": self.read_enabled}


def kafka(deployment, tool, *args):
    return deployment.compose(
        "exec", "-T", "analytics-kafka", f"/opt/kafka/bin/{tool}.sh",
        "--bootstrap-server", "analytics-kafka:9092", *args, capture=True,
    )


def analytics_owner(deployment, *args, stdin=None):
    command = [
        "docker", "exec", "-i", deployment.container("analytics-db"), "sh", "-eu", "-c",
        'export PGPASSWORD="$ALPHA_PILOT_PG_OWNER_PASSWORD"; '
        'exec psql -X -w -h 127.0.0.1 -U analytics_owner -d analytics -v ON_ERROR_STOP=1 "$@"',
        "psql", *args,
    ]
    result = subprocess.run(
        command, capture_output=True,
        **({"input": b""} if stdin is None else {"stdin": stdin}),
    )
    if result.returncode:
        raise RuntimeError("Вызов psql для владельца Analytics не завершён")
    return result.stdout.decode("utf-8").strip()


def prepare(deployment, up):
    """Migrate the private database and verify topic existence before pausing Credit."""
    alpha = deployment.alpha_dev
    for file in ("export-status-snapshot.sql", "import-status-snapshot.sql"):
        if not (OPS_DIR / file).is_file():
            raise ValueError("В выпуске отсутствуют SQL-файлы bootstrap Analytics")
    deployment.compose("stop", "analytics")
    deployment.compose(*up, "analytics-db", "analytics-kafka")
    deployment.compose("run", "--rm", "--no-deps", "analytics-migrate")
    marker = analytics_owner(deployment, "-qtA", "-c",
                             "SELECT generation FROM application_status_generation WHERE singleton")
    if marker and marker != alpha.topic:
        raise RuntimeError("База Analytics привязана к другому поколению")

    topics = set(kafka(deployment, "kafka-topics", "--list").splitlines())
    for topic in (alpha.topic, alpha.dlq):
        if topic not in topics:
            if marker:
                raise RuntimeError("Kafka потеряла topic с текущим поколением Analytics")
            kafka(deployment, "kafka-topics", "--create", "--topic", topic,
                  "--partitions", str(PARTITIONS), "--replication-factor", "1")
        description = kafka(deployment, "kafka-topics", "--describe", "--topic", topic)
        if not re.search(rf"\bPartitionCount:\s*{PARTITIONS}\b.*\bReplicationFactor:\s*1\b", description):
            raise RuntimeError("Параметры topic Analytics отличаются от ожидаемых")
        if not marker:
            offsets = kafka(deployment, "kafka-get-offsets", "--topic", topic, "--time", "-1")
            expected = {f"{topic}:{partition}:0" for partition in range(PARTITIONS)}
            if set(offsets.splitlines()) != expected:
                raise RuntimeError("Для bootstrap нужны пустые topic нового поколения")
    return not marker


def bootstrap(deployment, up):
    """Load a consistent snapshot while the protected deploy holds the lock and writers are stopped."""
    alpha = deployment.alpha_dev
    deployment.compose(*up, "postgres")
    for service in ("db-init", "credit-migrate"):
        deployment.compose("run", "--rm", "--no-deps", service)
    # The new publisher must be able to isolate restored records by generation.
    deployment.sql("credit", "SELECT stream_generation FROM application_status_outbox LIMIT 0")
    with tempfile.TemporaryFile() as snapshot:
        with (OPS_DIR / "export-status-snapshot.sql").open("rb") as export:
            result = subprocess.run(
                ["docker", "exec", "-i", deployment.container("postgres"), "psql", "-X", "-w", "-q",
                 "-U", deployment.values.get("POSTGRES_USER", "cabinet"), "-d", "credit", "-v", "ON_ERROR_STOP=1"],
                stdin=export, stdout=snapshot, stderr=subprocess.DEVNULL,
            )
        if result.returncode:
            raise RuntimeError("Согласованный снимок статусов Credit не получен")
        snapshot.seek(0)
        digest = hashlib.file_digest(snapshot, "sha256").hexdigest()
        snapshot.seek(0)
        analytics_owner(deployment, "-q", "-v", f"generation={alpha.topic}", "-v", f"snapshot_sha256={digest}",
                        "-f", "/alpha/import-status-snapshot.sql", stdin=snapshot)
    print(f"Загружен снимок поколения {alpha.topic}, SHA-256 {digest}")
