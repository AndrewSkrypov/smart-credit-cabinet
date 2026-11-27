"""Read-only diagnostics for the pinned cabinet-dev incident."""
from collections import Counter
import fcntl
from hashlib import sha256
import ipaddress
import json
import os
from pathlib import Path
import re
import selectors
import stat
import subprocess
import time


TARGET_REVISION = "3e434c0adee63917d2fe088e8939207de5b84452"
TARGET_MANIFEST_SHA256 = "07df658356bbc5e3501b0b00321f0177b6f72f6727e36ba624dfd44b0557791a"
STATE_DIR = Path("/var/lib/cabinet-dev")
PROJECT = "cabinet-dev"
OPERATION = "diagnose-v1"
REQUEST_KEYS = frozenset({"version", "operation", "revision", "target_revision", "target_manifest_sha256"})
MAX_REQUEST = 4096
MAX_FILE = 64 * 1024
MAX_DOCKER_BYTES = 128 * 1024
MAX_REPORT = 64 * 1024
MAX_CONTAINERS = 64
MAX_NETWORKS = 32
READ_TIMEOUT = 8
DOCKER_SOCKET = "unix:///var/run/docker.sock"
DOCKER_ROOT_FORMAT = "{{json .DockerRootDir}}"
DOCKER_READ_OPERATIONS = frozenset({"container.ls", "container.inspect", "network.ls", "network.inspect",
                                    "image.inspect", "system.info"})
MEMINFO = Path("/proc/meminfo")
CAPACITY_FILESYSTEMS = ("root", "state", "releases", "docker")
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"[0-9a-f]{64}")
ID = re.compile(r"[0-9a-f]{64}")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")
GENERATION = re.compile(r"g[0-9a-f]{16}")
PRIVATE_RANGES = tuple(ipaddress.IPv4Network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
TIMESTAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z")
SERVICES = frozenset({
    "identity", "credit", "engagement", "external-sim", "frontend", "postgres",
    "db-init", "identity-migrate", "credit-migrate", "engagement-migrate",
    "swagger-ui", "ssh-bridge", "ollama-ssh-bridge", "gateway", "caddy", "valkey",
    "clamav", "seaweedfs", "mailpit", "ollama", "ollama-pull",
    "analytics", "analytics-db", "analytics-kafka", "analytics-migrate",
    "prometheus", "grafana", "tempo", "loki", "alloy", "alertmanager",
    "blackbox", "node-exporter", "postgres-exporter", "cadvisor", "gotenberg",
    "goalert",
    "catalog-grpc", "assessment", "assessment-db", "assessment-kafka", "assessment-migrate",
    "servicing", "servicing-db", "servicing-migrate", "documents", "documents-db",
    "documents-cutover", "documents-probe", "documents-s3",
})
NETWORKS = frozenset({"cabinet", "edge", "default", "alpha-stream", "alpha-store", "alpha-read",
                      "prometheus", "monitoring", "grafana_edge", "ollama-private", "ollama-egress",
                      "alpha-cache", "catalog-rpc", "catalog-db", "assessment-db", "assessment-stream",
                      "assessment-cache", "assessment-sources", "servicing-rpc", "servicing-store",
                      "servicing-identity", "servicing-cache", "documents-rpc", "documents-store",
                      "documents-source", "documents-scan"})
IMAGE_COMPONENTS = frozenset({"identity", "credit", "engagement", "external-sim", "frontend",
                              "postgres", "swagger", "analytics", "kafka", "ssh-bridge", "clamav",
                              "catalog-grpc", "assessment", "servicing", "documents"})
STOCK_REFS = frozenset({
    "caddy:2.11.8-alpine@sha256:f6228111e5fafa1a5238258d0858e103f10599b8d1659a146e7726681a002bb9",
    "nginx:1.30.7-alpine@sha256:caf4e1e032a20f9c3c5e28fc7f25ffd768ad8677b4975c54193075997b3a97e3",
    "valkey/valkey:9.1.2-alpine@sha256:48332870af354a799964c0012ae1194a0bf2bf894eb508f945810596dc2d8d11",
    "grafana/grafana:13.2.5@sha256:9f7aa1e1e3246aade5792d35a738049f7f32f89cd51d1956cf426a4e86268a84",
    "chrislusf/seaweedfs:4.55@sha256:7ca46aeae2f223b37e5d91593c17ab37738eec6df6ab69b48a0487f7643342b9",
})
ERROR_CATEGORIES = (
    ("docker_unavailable", ("cannot connect to the docker daemon", "failed to connect to the docker api",
                            "permission denied while trying to connect", "is the docker daemon running",
                            "error during connect")),
    ("disk_full", ("no space left on device", "disk quota exceeded")),
    ("filesystem_read_only", ("read-only file system",)),
    ("network_overlap", ("pool overlaps", "overlaps with other one", "invalid subnet")),
    ("network_missing", ("network not found", "no such network", "could not find network")),
    ("port_conflict", ("port is already allocated", "address already in use", "failed to bind host port")),
    ("container_conflict", ("container name is already in use", "is already in use by container")),
    ("mount_failure", ("invalid mount config", "failed to mount", "error while mounting", "mount denied",
                        "bind source path does not exist", "not a directory")),
    ("image_unavailable", ("no such image", "pull access denied", "manifest unknown", "image not found")),
    ("dependency_unhealthy", ("is unhealthy", "dependency failed to start", "dependency health")),
    ("container_exited", ("exited with code", "container exited", "service did not complete successfully")),
    ("timeout", ("deadline exceeded", "timed out", "timeout exceeded")),
)


def error_category(message):
    lowered = (message or "")[:16384].lower()
    for category, markers in ERROR_CATEGORIES:
        if any(marker in lowered for marker in markers):
            return category
    return "unclassified"


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Повторяющееся поле в JSON диагностики")
        result[key] = value
    return result


def bounded_file(path, limit):
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    with os.fdopen(os.open(path, flags), "rb") as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError("Файл диагностики должен быть обычным")
        raw = stream.read(limit + 1)
    if len(raw) > limit:
        raise ValueError("Превышен лимит JSON диагностики")
    return raw


def decode_json(raw, message):
    try:
        return json.loads(raw, object_pairs_hook=unique_object)
    except (ValueError, UnicodeError, RecursionError):
        raise ValueError(message) from None


def matches(pattern, value):
    return isinstance(value, str) and pattern.fullmatch(value) is not None


def read_request(path):
    raw = bounded_file(path, MAX_FILE)
    # The preflight recognizes even a partial diagnostic envelope. Such an
    # envelope must never reach the release parser or the deployment function.
    try:
        probe = json.loads(raw)
    except (ValueError, UnicodeError, RecursionError):
        raise ValueError("Некорректный JSON манифеста") from None
    if not isinstance(probe, dict) or not (REQUEST_KEYS - {"revision"}).intersection(probe):
        return None
    if len(raw) > MAX_REQUEST:
        raise ValueError("Превышен лимит диагностического запроса")
    request = decode_json(raw, "Некорректный JSON диагностического запроса")
    if (set(request) != REQUEST_KEYS or type(request["version"]) is not int or request["version"] != 1
            or request["operation"] != OPERATION or not matches(SHA, request["revision"])
            or request["target_revision"] != TARGET_REVISION
            or request["target_manifest_sha256"] != TARGET_MANIFEST_SHA256):
        raise ValueError("Неподдерживаемый диагностический запрос")
    return request


def state_summary(path):
    try:
        raw = bounded_file(path, MAX_FILE)
    except FileNotFoundError:
        return {"present": False}
    record = decode_json(raw, "Повреждено состояние Dev")
    if not isinstance(record, dict) or not matches(SHA, record.get("revision")) or not matches(DIGEST, record.get("manifest_sha256")):
        raise ValueError("Повреждено состояние Dev")
    value = {"present": True, "revision": record["revision"], "manifest_sha256": record["manifest_sha256"]}
    for service in ("catalog", "assessment", "servicing", "documents"):
        if service in record:
            runtime = record[service]
            if (not isinstance(runtime, dict) or not isinstance(runtime.get("mode"), str)
                    or runtime["mode"] not in {"local", "remote"}):
                raise ValueError("Повреждено состояние режима сервиса")
            value[service] = {"mode": runtime["mode"]}
    if "alpha_dev" in record:
        alpha = record["alpha_dev"]
        if (not isinstance(alpha, dict) or not matches(GENERATION, alpha.get("generation"))
                or type(alpha.get("read_enabled")) is not bool):
            raise ValueError("Повреждено состояние поколения Alpha")
        value["alpha_dev"] = {"generation": alpha["generation"], "read_enabled": alpha["read_enabled"]}
    return value


def target_images(releases):
    target = releases / TARGET_REVISION
    if target.resolve() != target or not target.is_dir():
        raise ValueError("Каталог целевого выпуска недоступен")
    raw = bounded_file(target / "release.json", MAX_FILE)
    if sha256(raw).hexdigest() != TARGET_MANIFEST_SHA256:
        raise ValueError("SHA256 целевого манифеста не совпадает")
    value = decode_json(raw, "Некорректен целевой манифест")
    if (not isinstance(value, dict) or value.get("revision") != TARGET_REVISION
            or not isinstance(value.get("images"), dict) or not isinstance(value.get("alpha_dev"), dict)):
        raise ValueError("Некорректен целевой манифест")
    target_refs = {**value["images"], "analytics": value["alpha_dev"].get("image"),
                   "kafka": value["alpha_dev"].get("kafka_image")}
    identity = target_refs.get("identity")
    if not isinstance(identity, str) or not re.fullmatch(r"[a-z0-9./:_-]{1,180}/identity@sha256:[0-9a-f]{64}", identity):
        raise ValueError("Некорректен целевой манифест")
    repository = identity.rsplit("/", 1)[0]
    refs = {ref for component, ref in target_refs.items()
            if component in IMAGE_COMPONENTS and isinstance(ref, str)
            and re.fullmatch(re.escape(repository) + "/" + re.escape(component) + r"@sha256:[0-9a-f]{64}", ref)}
    # Target Kafka may use its own pinned version tag in this same Registry.
    kafka = target_refs["kafka"]
    if isinstance(kafka, str) and re.fullmatch(re.escape(repository) + r"/kafka(?::[0-9.]+)?@sha256:[0-9a-f]{64}", kafka):
        refs.add(kafka)
    return repository, refs | STOCK_REFS


def docker_read_error(message):
    lowered = message[:16384].lower()
    if any(marker in lowered for marker in ("template parsing error", "executing template", "error calling index",
                                            "index of untyped nil", "template:")):
        return "template_error"
    if any(marker in lowered for marker in ("unknown flag:", "unknown shorthand flag", "unknown command",
                                            "is not a docker command",
                                            "minimum supported api version", "maximum supported api version",
                                            "unsupported api version", "client is newer than server")):
        return "unsupported_cli"
    category = error_category(lowered)
    return "engine_error" if category == "unclassified" else category


def docker_read(kind, action, *args):
    """Capture a bounded local-engine response with a deadline, without inheriting a Docker context."""
    operation = f"{kind}.{action}"
    if (operation not in DOCKER_READ_OPERATIONS
            or (operation == "system.info" and args != ("--format", DOCKER_ROOT_FORMAT))):
        raise ValueError("Неподдерживаемая операция диагностики Docker")
    command = ["docker", "--host", DOCKER_SOCKET, kind, action, *args]
    environment = {"PATH": "/usr/local/bin:/usr/bin:/bin", "LC_ALL": "C"}
    try:
        process = subprocess.Popen(command, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except OSError:
        raise RuntimeError(f"Docker {operation}: docker_unavailable") from None
    outputs = {"stdout": bytearray(), "stderr": bytearray()}
    deadline = time.monotonic() + READ_TIMEOUT
    try:
        with selectors.DefaultSelector() as ready:
            ready.register(process.stdout, selectors.EVENT_READ, "stdout")
            ready.register(process.stderr, selectors.EVENT_READ, "stderr")
            while ready.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RuntimeError(f"Docker {operation}: timeout")
                for key, _ in ready.select(remaining):
                    chunk = os.read(key.fileobj.fileno(), 8192)
                    if not chunk:
                        ready.unregister(key.fileobj)
                        continue
                    output = outputs[key.data]
                    output.extend(chunk)
                    if sum(map(len, outputs.values())) > MAX_DOCKER_BYTES:
                        raise RuntimeError(f"Docker {operation}: output_limit")
        if process.wait(timeout=max(0.01, deadline - time.monotonic())):
            category = docker_read_error(outputs["stderr"].decode(errors="replace"))
            raise RuntimeError(f"Docker {operation}: {category}")
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"Docker {operation}: timeout") from None
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        process.stdout.close()
        process.stderr.close()
    return bytes(outputs["stdout"])


def object_ids(kind, limit):
    options = ["--all"] if kind == "container" else []
    output = docker_read(kind, "ls", "--quiet", "--no-trunc", *options,
                         "--filter", f"label=com.docker.compose.project={PROJECT}")
    ids = output.decode("ascii", errors="replace").splitlines()
    if len(ids) > limit or len(ids) != len(set(ids)) or not all(matches(ID, ident) for ident in ids):
        raise RuntimeError("Docker: unclassified_object_list")
    return ids


def inspect(kind, ids, fields):
    if not ids:
        return []
    template = "{" + ",".join(f'"{key}":{expression}' for key, expression in fields.items()) + "}"
    output = docker_read(kind, "inspect", "--format", template, *ids)
    if len(lines := output.splitlines()) != len(ids):
        raise RuntimeError("Docker: unclassified_inspection")
    records = [decode_json(line, "Docker: unclassified_inspection") for line in lines]
    if not all(isinstance(record, dict) and record.keys() == fields.keys() for record in records):
        raise RuntimeError("Docker: unclassified_inspection")
    return records


def template_or_null(key, body):
    # Docker's raw inspect fallback sets missingkey=error. Go's map index
    # returns nil for an absent key, including fields omitted by the Engine.
    return '{{with index . "' + key + '"}}' + body + "{{else}}null{{end}}"


def value_field(key):
    return '{{json (index . "' + key + '")}}'


def label_field(key):
    return template_or_null("Labels", value_field(key))


def config_label(key):
    return template_or_null("Config", label_field(key))


def state_field(key):
    return template_or_null("State", value_field(key))


PROJECT_LABEL = "com.docker.compose.project"
REVISION_LABEL = "org.opencontainers.image.revision"
CONTAINER_FIELDS = {
    "id": "{{json .Id}}",
    "project": config_label(PROJECT_LABEL),
    "service": config_label("com.docker.compose.service"),
    "oneoff": config_label("com.docker.compose.oneoff"),
    "state": state_field("Status"),
    "health": template_or_null("State", template_or_null("Health", value_field("Status"))),
    "exit_code": state_field("ExitCode"),
    "oom_killed": state_field("OOMKilled"),
    "started_at": state_field("StartedAt"),
    "finished_at": state_field("FinishedAt"),
    "error": state_field("Error"),
    "registry_ref": template_or_null("Config", value_field("Image")),
    "image_id": "{{json .Image}}",
    "networks": '[{{with index . "NetworkSettings"}}{{range $name, $_ := index . "Networks"}}'
                "{{json $name}},{{end}}{{end}}null]",
}
IMAGE_FIELDS = {"id": "{{json .Id}}", "revision": config_label(REVISION_LABEL)}
NETWORK_FIELDS = {
    "id": "{{json .Id}}",
    "name": "{{json .Name}}",
    "project": label_field(PROJECT_LABEL),
    "logical": label_field("com.docker.compose.network"),
    "internal": "{{json .Internal}}",
    "subnets": '[{{with index . "IPAM"}}{{range index . "Config"}}{{if .}}{{json (index . "Subnet")}},'
               "{{end}}{{end}}{{end}}null]",
}


def choice(value, allowed):
    return value if isinstance(value, str) and value in allowed else "unclassified"


def bounded_list(values, maximum):
    if not isinstance(values, list) or len(values) > maximum:
        raise RuntimeError("Docker: unclassified_inspection")
    return values


def verify_ids(records, ids):
    observed = [record.get("id") for record in records]
    if not all(isinstance(value, str) for value in observed) or sorted(observed) != sorted(ids):
        raise RuntimeError("Docker: unclassified_inspection")


def collect(repository, pinned):
    container_ids = object_ids("container", MAX_CONTAINERS)
    network_ids = object_ids("network", MAX_NETWORKS)
    raw_containers = inspect("container", container_ids, CONTAINER_FIELDS)
    raw_networks = inspect("network", network_ids, NETWORK_FIELDS)
    verify_ids(raw_containers, container_ids)
    verify_ids(raw_networks, network_ids)
    if any(record["project"] != PROJECT for record in raw_containers + raw_networks):
        raise RuntimeError("Docker: unclassified_project")

    image_ids = sorted({record["image_id"] for record in raw_containers if matches(IMAGE_ID, record["image_id"])})
    raw_images = inspect("image", image_ids, IMAGE_FIELDS)
    verify_ids(raw_images, image_ids)
    revisions = {item["id"]: item["revision"] if matches(SHA, item["revision"]) else "unclassified"
                 for item in raw_images}

    networks = []
    names = set()
    for raw in raw_networks:
        logical = choice(raw["logical"], NETWORKS)
        name = f"{PROJECT}_{logical}"
        if logical == "unclassified" or raw["name"] != name:
            name = "unclassified"
        else:
            names.add(name)
        subnets = []
        for subnet in bounded_list(raw["subnets"], 9):
            if subnet is None:
                continue
            try:
                if not isinstance(subnet, str) or len(subnet) > 50 or "/" not in subnet:
                    raise ValueError()
                network = ipaddress.IPv4Network(subnet)
                if not any(network.subnet_of(scope) for scope in PRIVATE_RANGES):
                    raise ValueError()
                subnets.append(str(network))
            except ValueError:
                subnets.append("unclassified")
        networks.append({"name": name, "labels": {"project": PROJECT, "network": logical},
                         "internal": raw["internal"] if type(raw["internal"]) is bool else None,
                         "subnets": subnets if name != "unclassified" else []})

    containers = []
    ref_pattern = re.compile(re.escape(repository) + "/(?:" + "|".join(sorted(IMAGE_COMPONENTS))
                             + r")@sha256:[0-9a-f]{64}")
    for raw in raw_containers:
        service = choice(raw["service"], SERVICES)
        oneoff = choice(raw["oneoff"], {"True", "False", "true", "false"})
        state = choice(raw["state"], {"created", "running", "paused", "restarting", "removing", "exited", "dead"})
        health = None if raw["health"] is None else choice(raw["health"], {"healthy", "unhealthy", "starting"})
        exit_code = raw["exit_code"] if type(raw["exit_code"]) is int and 0 <= raw["exit_code"] <= 255 else None
        oom = raw["oom_killed"] if type(raw["oom_killed"]) is bool else None
        ref = raw["registry_ref"]
        image = raw["image_id"] if matches(IMAGE_ID, raw["image_id"]) else "unclassified"
        categories = []
        if raw["error"]:
            categories.append(error_category(raw["error"] if isinstance(raw["error"], str) else ""))
        if oom:
            categories.append("oom_killed")
        if health == "unhealthy":
            categories.append("unhealthy")
        if state in {"exited", "dead", "restarting"} and exit_code:
            categories.append("nonzero_exit")
        containers.append({
            "labels": {"project": PROJECT, "service": service, "oneoff": oneoff},
            "state": state, "health": health, "exit_code": exit_code, "oom_killed": oom,
            "started_at": raw["started_at"] if matches(TIMESTAMP, raw["started_at"]) else None,
            "finished_at": raw["finished_at"] if matches(TIMESTAMP, raw["finished_at"]) else None,
            "registry_ref": ref if isinstance(ref, str) and (ref in pinned or matches(ref_pattern, ref)) else "unclassified",
            "image_id": image, "oci_revision": revisions.get(image, "unclassified"),
            "networks": [choice(name, names) for name in bounded_list(raw["networks"], 17) if name is not None],
            "error_categories": categories,
        })
    return {"containers": containers, "networks": networks,
            "error_counts": dict(Counter(category for item in containers for category in item["error_categories"]))}


def valid_byte_counts(total, available):
    return type(total) is int and type(available) is int and 0 <= available <= total < 2**64 and total > 0


def host_memory():
    """Read the kernel's host totals without exposing any other procfs fields."""
    fields = {b"MemTotal": "total_bytes", b"MemAvailable": "available_bytes"}
    memory = {}
    try:
        for line in bounded_file(MEMINFO, 16 * 1024).splitlines():
            key, _, value = line.partition(b":")
            if key not in fields:
                continue
            match = re.fullmatch(rb"[ \t]+([0-9]{1,16})[ \t]+kB", value)
            if match is None or fields[key] in memory:
                return None
            memory[fields[key]] = int(match[1]) * 1024
    except (OSError, ValueError):
        return None
    return memory if valid_byte_counts(memory.get("total_bytes"), memory.get("available_bytes")) else None


def docker_root():
    try:
        value = decode_json(docker_read("system", "info", "--format", DOCKER_ROOT_FORMAT),
                            "Недоступен каталог данных Docker")
    except (RuntimeError, ValueError):
        return None
    if not isinstance(value, str) or not value or any(ord(char) < 32 for char in value):
        return None
    path = Path(value)
    return path if path.is_absolute() and ".." not in path.parts else None


def resource_summary(releases, state):
    paths = {"root": Path("/"), "state": state, "releases": releases, "docker": docker_root()}
    groups = {}
    filesystems = dict.fromkeys(CAPACITY_FILESYSTEMS)
    for key, path in paths.items():
        if path is None:
            continue
        try:
            stats = os.statvfs(path)
            device = path.stat().st_dev
        except (OSError, ValueError):
            continue
        total = stats.f_blocks * stats.f_frsize
        available = stats.f_bavail * stats.f_frsize
        if valid_byte_counts(total, available):
            filesystems[key] = {"total_bytes": total, "available_bytes": available,
                                "group": groups.setdefault(device, len(groups))}
    return {"source": "dev-host", "memory": host_memory(), "filesystems": filesystems}


def valid_resources(resources):
    if (not isinstance(resources, dict) or set(resources) != {"source", "memory", "filesystems"}
            or resources["source"] != "dev-host"):
        return False
    memory = resources["memory"]
    filesystems = resources["filesystems"]
    counts = {"total_bytes", "available_bytes"}
    if memory is not None and (not isinstance(memory, dict) or set(memory) != counts or not valid_byte_counts(
            memory["total_bytes"], memory["available_bytes"])):
        return False
    return isinstance(filesystems, dict) and set(filesystems) == set(CAPACITY_FILESYSTEMS) and all(
        value is None or (
            isinstance(value, dict) and set(value) == counts | {"group"}
            and valid_byte_counts(value["total_bytes"], value["available_bytes"])
            and type(value["group"]) is int and 0 <= value["group"] < len(CAPACITY_FILESYSTEMS)
        ) for value in filesystems.values()
    )


def diagnose(root, path, state_dir, request):
    if root.name != request["revision"] or path.resolve() != root / "release.json":
        raise ValueError("Запрос диагностики должен находиться в каталоге собственной ревизии")
    if state_dir != STATE_DIR or state_dir.resolve() != STATE_DIR or state_dir.stat().st_mode & 0o077:
        raise ValueError("Недопустимый каталог состояния диагностики")
    lock = os.open(state_dir / "deploy.lock", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        if not stat.S_ISREG(os.fstat(lock).st_mode):
            raise ValueError("Недопустимая блокировка Dev")
        try:
            fcntl.flock(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Блокировка Dev занята") from None
        repository, refs = target_images(root.parent)
        current = state_summary(state_dir / "current.json")
        pending = state_summary(state_dir / "pending.json")
        report = json.dumps({**request, "current": current, "pending": pending, **collect(repository, refs),
                             "resources": resource_summary(root.parent, state_dir)},
                            ensure_ascii=True, separators=(",", ":"))
        if len(report) > MAX_REPORT:
            raise RuntimeError("Превышен лимит отчёта диагностики")
        return report
    finally:
        os.close(lock)
