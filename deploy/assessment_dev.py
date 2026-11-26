"""Private Assessment provisioning and the readiness fence for the shared rollout."""

from dataclasses import dataclass
from functools import partial
from hashlib import sha256
import base64
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
from tempfile import NamedTemporaryFile

from registry_release import IMAGE, REVISION, SHA256, KAFKA_IMAGE_FILE, KAFKA_UPSTREAM_IMAGE

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "deploy/compose.assessment.yaml"
FILES = ROOT / "helm/cabinet/files/assessment"
SERVICES = ("assessment-db", "assessment-kafka", "assessment")
PASSWORDS = ("ASSESSMENT_ADMIN_PASSWORD", "ASSESSMENT_MIGRATION_PASSWORD", "ASSESSMENT_DB_PASSWORD")
STATE_KEYS = (*PASSWORDS, "ASSESSMENT_GENERATION", "ASSESSMENT_CLUSTER_ID")
MODES = {"disabled", "local", "remote"}
GENERATION = re.compile(r"g[0-9a-f]{16}")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
BROKER = "assessment-kafka:9092"
VOLUMES = ("assessment-db-data", "assessment-kafka-data")

CREDIT_OPEN = """
select (select count(*) from credit_assessment_cycle c join applications a on a.id=c.application_id
          where c.request is not null and c.closed_at is null and a.status in ('ENRICHMENT','SCORING'))
     + (select count(*) from assessment_exchange_outbox where sent_at is null);
"""
ASSESSMENT_OPEN = """
select (select count(*) from assessment_execution where completed_at is null)
     + (select count(*) from assessment_exchange_outbox where sent_at is null);
"""
CREDIT_SNAPSHOT = """
select json_build_object('cycles', (select count(*) from credit_assessment_cycle),
 'closed', (select count(*) from credit_assessment_cycle where closed_at is not null),
 'outbox', (select count(*) from assessment_exchange_outbox),
 'sent', (select count(*) from assessment_exchange_outbox where sent_at is not null));
"""
ASSESSMENT_SNAPSHOT = """
select json_build_object('executions', (select count(*) from assessment_execution),
 'completed', (select count(*) from assessment_execution where completed_at is not null),
 'outbox', (select count(*) from assessment_exchange_outbox),
 'sent', (select count(*) from assessment_exchange_outbox where sent_at is not null));
"""


def valid(pattern, value):
    return isinstance(value, str) and pattern.fullmatch(value) is not None


@dataclass(frozen=True)
class Descriptor:
    revision: str
    manifest_sha256: str
    mode: str
    app_image: str
    jar_sha256: str
    kafka_image: str
    kafka_upstream: str

    @classmethod
    def from_release(cls, release):
        if release is None:
            return None
        metadata = getattr(release, "assessment", None)
        if metadata is None and "assessment" not in release.images:
            return None
        required = {"jar_sha256", "dev_mode", "kafka_image"}
        image = release.images.get("assessment")
        credit = release.images.get("credit")
        if (not isinstance(metadata, dict) or set(metadata) != required
                or not valid(REVISION, release.revision) or not valid(SHA256, release.checksum)
                or not valid(SHA256, metadata["jar_sha256"])
                or not isinstance(metadata["dev_mode"], str) or metadata["dev_mode"] not in MODES
                or not valid(IMAGE, image) or not valid(IMAGE, credit)):
            raise ValueError("Некорректный descriptor выпуска Assessment")
        repository = credit.rsplit("/", 1)[0]
        kafka_image = metadata["kafka_image"]
        for name, value in (("credit", credit), ("assessment", image), ("kafka", kafka_image)):
            if not valid(IMAGE, value) or value.rsplit("@", 1)[0] != f"{repository}/{name}":
                raise ValueError("Для Assessment нужны digest образов одного проекта Registry")
        upstream = KAFKA_IMAGE_FILE.read_text(encoding="ascii").strip()
        if not valid(KAFKA_UPSTREAM_IMAGE, upstream):
            raise ValueError("Ожидается закреплённый upstream образ Kafka")
        return cls(release.revision, release.checksum, metadata["dev_mode"], image,
                   metadata["jar_sha256"], kafka_image, upstream)

    def image_labels(self):
        revision = "org.opencontainers.image.revision"
        return {
            "assessment": (self.app_image, {
                revision: self.revision, "org.opencontainers.image.jar.sha256": self.jar_sha256,
            }),
            "assessment-kafka": (self.kafka_image, {
                revision: self.revision, "org.opencontainers.image.base.name": self.kafka_upstream,
            }),
        }


def private_directory(path):
    path.mkdir(mode=0o700, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o700 or info.st_uid != os.getuid():
        raise ValueError("Каталог состояния Assessment должен принадлежать оператору и иметь права 0700")


def read_private(path):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return None
    with os.fdopen(fd, "r", encoding="ascii") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.getuid():
            raise ValueError("Файл состояния Assessment должен принадлежать оператору и иметь права 0600")
        return stream.read()


def write_private(path, text):
    temporary = None
    try:
        with NamedTemporaryFile(mode="w", encoding="ascii", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            os.fchmod(stream.fileno(), 0o600)
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(path)
        fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    finally:
        if temporary:
            temporary.unlink(missing_ok=True)


def read_record(path):
    text = read_private(path)
    if text is None:
        return None
    record = json.loads(text)
    if not isinstance(record, dict) or not record:
        raise ValueError("Некорректная запись состояния Assessment")
    return record


def write_record(path, data):
    write_private(path, json.dumps(data, sort_keys=True) + "\n")


def load_secrets(path, other):
    data = read_private(path)
    if data is None:
        values = {key: secrets.token_hex(32) for key in PASSWORDS}
        values["ASSESSMENT_GENERATION"] = "g" + secrets.token_hex(8)
        values["ASSESSMENT_CLUSTER_ID"] = base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("=")
        write_private(path, "".join(f"{key}={value}\n" for key, value in values.items()))
        data = read_private(path)
    values = {}
    for line in data.splitlines():
        name, divider, value = line.partition("=")
        if not divider or name in values:
            raise ValueError("Некорректный формат приватного Assessment ENV")
        values[name] = value
    if (set(values) != set(STATE_KEYS) or not valid(GENERATION, values["ASSESSMENT_GENERATION"])
            or not re.fullmatch(r"[A-Za-z0-9_-]{21}[AQgw]", values["ASSESSMENT_CLUSTER_ID"])):
        raise ValueError("Некорректное поколение или cluster ID Assessment")
    passwords = {values[key] for key in PASSWORDS}
    if (len(passwords) != len(PASSWORDS) or any(not valid(SHA256, value) for value in passwords)
            or passwords.intersection(other.values())):
        raise ValueError("Assessment требует отдельные автоматически созданные пароли")
    return values


def run(*args, stdin=None):
    result = subprocess.run(args, input=stdin, text=True, encoding="utf-8", capture_output=True, timeout=80)
    if result.returncode:
        raise RuntimeError("Проверка Assessment завершилась ошибкой")
    return result.stdout.strip()


def one_json(*args):
    parsed = json.loads(run(*args))
    if not isinstance(parsed, list) or len(parsed) != 1 or not isinstance(parsed[0], dict):
        raise RuntimeError("Ожидается один объект Docker для Assessment")
    return parsed[0]


def verify_image(reference, required_labels):
    image = one_json("docker", "image", "inspect", reference)
    labels = image.get("Config", {}).get("Labels") or {}
    if (not valid(DIGEST, image.get("Id")) or reference not in (image.get("RepoDigests") or [])
            or any(labels.get(key) != value for key, value in required_labels.items())):
        raise RuntimeError("OCI provenance Assessment не совпадает с manifest")
    return image["Id"]


@dataclass(frozen=True)
class AssessmentDev:
    deployment: object
    descriptor: Descriptor
    state: Path
    secrets: dict

    @classmethod
    def open(cls, deployment):
        if deployment.local or not re.fullmatch(r"cabinet-dev(?:-restore-[a-z0-9-]+)?", deployment.project):
            return None
        descriptor = Descriptor.from_release(deployment.release)
        name = "assessment" if deployment.project == "cabinet-dev" else f"assessment-{deployment.project}"
        state = Path(deployment.path).parent / name
        if descriptor is None or descriptor.mode == "disabled":
            if os.path.lexists(state):
                raise RuntimeError("Подготовленный Assessment требует сохранения инфраструктуры в режиме local")
            return None
        private_directory(state)
        secret_path = state / "assessment.env"
        if not os.path.lexists(secret_path) and any(state.iterdir()):
            raise RuntimeError("Отсутствует приватный Assessment ENV для существующего состояния")
        return cls(deployment, descriptor, state, load_secrets(secret_path, deployment.values))

    @property
    def generation(self):
        return self.secrets["ASSESSMENT_GENERATION"]

    @property
    def topics(self):
        prefix = f"alpha.dev.assessment.v1.{self.generation}"
        return {name: f"{prefix}.{name}" for name in ("commands", "results")}

    @property
    def groups(self):
        prefix = f"alpha.dev.{self.generation}.assessment.v1"
        return {"commands": f"{prefix}.executor", "results": f"{prefix}.credit"}

    def compose_environment(self):
        return {
            **self.secrets,
            "CABINET_IMAGE_ASSESSMENT": self.descriptor.app_image,
            "CABINET_IMAGE_ASSESSMENT_KAFKA": self.descriptor.kafka_image,
            "ASSESSMENT_CREDIT_KAFKA_ENABLED": "false",
        }

    def volume_binding(self):
        bindings = {}
        for name in VOLUMES:
            project = self.deployment.project
            volume = one_json("docker", "volume", "inspect", f"{project}_{name}")
            labels = volume.get("Labels") or {}
            if (volume.get("Driver") != "local" or labels.get("com.docker.compose.project") != project
                    or labels.get("com.docker.compose.volume") != name or not volume.get("CreatedAt")
                    or volume.get("Name") != f"{project}_{name}"):
                raise RuntimeError("Том Assessment принадлежит другому проекту или был заменён")
            bindings[name] = {"name": volume["Name"], "created_at": volume["CreatedAt"]}
        return bindings

    def database(self, profile, statement):
        role = {"admin": "assessment_admin", "app": "assessment_app", "owner": "assessment_owner"}[profile]
        database = "postgres" if profile == "admin" else "assessment"
        variable = {"admin": "POSTGRES_PASSWORD", "app": "ASSESSMENT_DB_PASSWORD",
                    "owner": "ASSESSMENT_MIGRATION_PASSWORD"}[profile]
        return run(
            "docker", "exec", "-i", self.deployment.container("assessment-db"), "sh", "-ec",
            'export PGPASSWORD="$(printenv "$1")"; shift; exec psql -XqwAt -v ON_ERROR_STOP=1 -h 127.0.0.1 "$@"',
            "psql", variable, "-U", role, "-d", database, stdin=statement,
        )

    def kafka(self, tool, *args):
        return self.deployment.compose("exec", "-T", "assessment-kafka", f"/opt/kafka/bin/{tool}.sh",
                                       "--bootstrap-server", BROKER, *args, capture=True)

    def ensure_topics(self, existing):
        status = self.kafka("kafka-metadata-quorum", "describe", "--status")
        match = re.search(r"^ClusterId:\s*(\S+)\s*$", status, re.MULTILINE)
        if not match or match[1] != self.secrets["ASSESSMENT_CLUSTER_ID"]:
            raise RuntimeError("Kafka Assessment использует другое KRaft-хранилище")
        topics = set(self.kafka("kafka-topics", "--list").splitlines())
        for topic in self.topics.values():
            if topic not in topics:
                if existing:
                    raise RuntimeError("Отсутствует ранее созданный topic Assessment")
                self.kafka("kafka-topics", "--create", "--if-not-exists", "--topic", topic,
                           "--partitions", "1", "--replication-factor", "1",
                           "--config", "retention.ms=-1", "--config", "retention.bytes=-1",
                           "--config", "cleanup.policy=delete")
            description = self.kafka("kafka-topics", "--describe", "--topic", topic)
            settings = re.search(r"\bConfigs:\s*([^\r\n]*)", description)
            configs = (dict(item.split("=", 1) for item in re.split(r",(?=[\w.-]+=)", settings[1]) if "=" in item)
                       if settings else {})
            if (not re.search(r"\bPartitionCount:\s*1\b", description)
                    or not re.search(r"\bReplicationFactor:\s*1\b", description)
                    or not re.search(r"\bLeader:\s*1\b", description)
                    or any(configs.get(key) != value for key, value in (
                        ("retention.ms", "-1"), ("retention.bytes", "-1"), ("cleanup.policy", "delete"),
                    ))):
                raise RuntimeError("Неверная конфигурация topic Assessment")

    def offsets(self, direction, time="-1"):
        topic = self.topics[direction]
        output = self.kafka("kafka-get-offsets", "--topic", topic, "--time", time).strip()
        match = re.fullmatch(re.escape(topic) + r":0:([0-9]+)", output)
        if not match:
            raise RuntimeError("Не удалось подтвердить границу Kafka Assessment")
        return int(match[1])

    def committed(self, direction, end):
        offset = self.group_offset(direction, end)
        if offset == end:
            return end
        raise RuntimeError("Assessment ещё завершает чтение Kafka под прежним Credit")

    def group_offset(self, direction, end):
        if end == 0:
            return 0
        group, topic = self.groups[direction], self.topics[direction]
        groups = self.kafka("kafka-consumer-groups", "--list").split()
        if group not in groups:
            return None
        rows = self.kafka("kafka-consumer-groups", "--describe", "--group", group).splitlines()
        for row in rows:
            fields = row.split()
            if len(fields) >= 6 and fields[:3] == [group, topic, "0"]:
                current, broker_end, lag = fields[3:6]
                if current.isdecimal() and lag.isdecimal() and broker_end == str(end) and int(current) + int(lag) == end:
                    return int(current)
                if current == "-" and broker_end == str(end):
                    return None
                raise RuntimeError("Kafka Assessment изменилась во время проверки offsets")
        return None

    def require_stopped(self, *services):
        if self.deployment.compose("ps", "--quiet", *services, capture=True).strip():
            raise RuntimeError("Остановите писателей Assessment и закройте вход")

    def backup_checkpoint(self):
        self.require_stopped("credit", "assessment", "caddy")
        self.ensure_topics(existing=True)
        if self.database("owner", "select generation from assessment_ops.identity;") != self.generation:
            raise RuntimeError("Поколение Assessment не совпадает с checkpoint")
        offsets = {}
        for name, topic in self.topics.items():
            begin, end = self.offsets(name, "-2"), self.offsets(name)
            if begin != 0:
                raise RuntimeError("Брокер Assessment потерял начало журнала")
            offsets[name] = {"topic": topic, "group": self.groups[name], "begin": begin, "end": end,
                             "committed": self.group_offset(name, end)}
        return {
            "generation": self.generation, "cluster_id": self.secrets["ASSESSMENT_CLUSTER_ID"], "kafka": offsets,
            "credit": json.loads(self.deployment.sql("credit", CREDIT_SNAPSHOT)),
            "assessment": json.loads(self.database("app", ASSESSMENT_SNAPSHOT)),
        }

    def verify_restore(self, checkpoint):
        if not re.fullmatch(r"cabinet-dev-restore-[a-z0-9-]+", self.deployment.project):
            raise ValueError("Assessment восстанавливается только в отдельный проект")
        if self.backup_checkpoint() != checkpoint:
            raise RuntimeError("Восстановленный Assessment отличается от сохранённой точки")
        write_record(self.state / "restore-verified.json", checkpoint)

    def require_drained(self):
        def empty():
            return self.deployment.sql("credit", CREDIT_OPEN) == "0" and self.database("app", ASSESSMENT_OPEN) == "0"
        if not empty():
            raise RuntimeError("Assessment ещё имеет открытые циклы или outbox; завершите их под прежним Credit")
        offsets = {direction: self.committed(direction, self.offsets(direction)) for direction in self.topics}
        if not empty() or any(self.offsets(name) != end for name, end in offsets.items()):
            raise RuntimeError("Состояние Assessment изменилось во время проверки возврата")
        return offsets

    def verify_containers(self, expected):
        for service, image_id in expected.items():
            container = one_json("docker", "inspect", "--type", "container", self.deployment.container(service))
            if (container.get("Image") != image_id or not container.get("State", {}).get("Running")
                    or container.get("State", {}).get("Health", {}).get("Status") != "healthy"):
                raise RuntimeError("Запущенный контейнер Assessment не прошёл проверку readiness и OCI")

    def prepare(self):
        self.require_stopped("credit", "caddy")
        if self.deployment.sql("credit", "select (to_regclass('credit_assessment_cycle') is not null)::int;") != "1":
            raise RuntimeError("Перед запуском Assessment необходима миграция Credit V24")

        descriptor = self.descriptor
        expected = {name: verify_image(image, labels) for name, (image, labels) in descriptor.image_labels().items()}
        pending_path = self.state / "pending.json"
        pending = read_record(pending_path)
        active = read_record(self.state / "active.json")
        if pending == active:
            pending = None
        binding_path = self.state / "binding.json"
        binding = read_record(binding_path)
        if (pending or active) and not binding:
            raise RuntimeError("Не найдена привязка томов существующего Assessment")
        if pending and pending["manifest_sha256"] != descriptor.manifest_sha256:
            raise RuntimeError("Незаконченный запуск Assessment требует точного повтора прежнего manifest")
        if binding and binding != self.volume_binding():
            raise RuntimeError("Тома Assessment отличаются от ранее подтверждённых")

        up = partial(self.deployment.compose, "up", "-d", "--no-build", "--wait", "--wait-timeout", "150")
        self.deployment.compose("stop", "assessment")
        up("assessment-db", "assessment-kafka")
        self.database("admin", (FILES / "bootstrap.sql").read_text())
        if self.database("owner", (FILES / "identity.sql").read_text()) != self.generation:
            raise RuntimeError("База Assessment принадлежит другому поколению")
        self.deployment.compose("run", "--rm", "--no-deps", "assessment-migrate")
        self.database("owner", (FILES / "grants.sql").read_text())
        if self.database("app", (FILES / "check.sql").read_text()) != "1":
            raise RuntimeError("Не подтверждена ограниченная роль runtime Assessment")
        self.ensure_topics(binding is not None)
        ends = {name: self.offsets(name) for name in self.topics}
        for name, end in ends.items():
            if self.offsets(name, "-2") != 0 or any(
                record and end < record["offsets"][name] for record in (active, pending)
            ):
                raise RuntimeError("Утрачен ранее подтверждённый диапазон Kafka Assessment")
        current_binding = self.volume_binding()
        if binding and binding != current_binding:
            raise RuntimeError("Тома Assessment сменились во время запуска")
        if binding is None:
            write_record(binding_path, current_binding)
        if self.deployment.project.startswith("cabinet-dev-restore-") and active is None:
            restored = read_record(self.state / "restore-verified.json")
            if restored is None or self.backup_checkpoint() != restored:
                raise RuntimeError("Перед первым запуском восстановленного Assessment нужна сверка checkpoint")
        up("valkey", "external-sim", "assessment")
        self.verify_containers(expected)
        drained = (self.require_drained() if descriptor.mode == "local"
                   and any(record and record["mode"] == "remote" for record in (active, pending)) else None)
        receipt = {
            "revision": descriptor.revision, "manifest_sha256": descriptor.manifest_sha256,
            "mode": descriptor.mode, "generation": self.generation, "cluster_id": self.secrets["ASSESSMENT_CLUSTER_ID"],
            "images": expected, "offsets": ends, "drained": drained,
        }
        receipt_path = self.receipt_path(receipt)
        stored = read_record(receipt_path)
        if stored is None:
            write_record(receipt_path, receipt)
        elif stored != receipt:
            raise RuntimeError("Квитанция Assessment уже существует с другим содержимым")
        write_record(pending_path, receipt)
        return receipt

    def receipt_path(self, receipt):
        checksum = sha256(json.dumps(receipt, sort_keys=True).encode("ascii")).hexdigest()
        return self.state / f"receipt-{checksum}.json"

    def credit_environment(self, receipt):
        descriptor = self.descriptor
        pending = read_record(self.state / "pending.json")
        active = read_record(self.state / "active.json") if pending is None else None
        if (not isinstance(receipt, dict) or receipt.get("manifest_sha256") != descriptor.manifest_sha256
                or receipt.get("generation") != self.generation or receipt.get("mode") != descriptor.mode
                or receipt != read_record(self.receipt_path(receipt))
                or receipt != (pending if pending is not None else active)):
            raise RuntimeError("Для Credit нужна квитанция готовности Assessment выбранного выпуска")
        return {"ASSESSMENT_CREDIT_KAFKA_ENABLED": str(descriptor.mode == "remote").lower()}

    def verify_credit(self, receipt):
        expected = {
            "CABINET_ASSESSMENT_KAFKA_ENABLED": self.credit_environment(receipt)["ASSESSMENT_CREDIT_KAFKA_ENABLED"],
            "CABINET_ASSESSMENT_KAFKA_BOOTSTRAP_SERVERS": BROKER,
            "CABINET_ASSESSMENT_KAFKA_ENVIRONMENT": "dev",
            "CABINET_ASSESSMENT_KAFKA_GENERATION": self.generation,
        }
        container = self.deployment.container("credit")
        command = '{{range .Config.Env}}{{println .}}{{end}}'
        lines = run("docker", "inspect", "--type", "container", "--format", command, container).splitlines()
        actual = {key: value for key, _, value in (line.partition("=") for line in lines) if key in expected}
        if actual != expected:
            raise RuntimeError("Запущенный Credit использует другой режим или поколение Assessment")
        write_record(self.state / "active.json", receipt)
        (self.state / "pending.json").unlink(missing_ok=True)
