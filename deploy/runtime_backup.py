"""Backup and restore independent runtimes alongside one quiesced Credit snapshot."""
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import stat
import subprocess
from types import SimpleNamespace
import tarfile
from tempfile import NamedTemporaryFile, TemporaryDirectory
import time

from alpha_dev import SECRET_KEYS as ALPHA_SECRETS, analytics_owner, kafka, verify_credit_mode
from assessment_dev import FILES as ASSESSMENT_SQL, VOLUMES as ASSESSMENT_VOLUMES, read_record as assessment_record
from backup_support import checked_docker, document_inventory
from documents_dev import DocumentsDev
from registry_release import SERVICE_COMPONENTS
from runtime_delivery_support import ensure_private, fsync_dir, private_state, read_record, write_private, write_record
from runtime_rollout import stop_writers

UP = ("up", "-d", "--no-build", "--no-deps", "--wait", "--wait-timeout", "180")
ASSESSMENT_STATE = re.compile(r"(?:assessment\.env|active\.json|receipt-[a-f0-9]{64}\.json)")
SERVICING_STATE = re.compile(
    r"(?:servicing\.env|authority\.json|tls/(?:ca\.(?:key|crt)|(?:server|credit)/(?:ca\.crt|tls\.(?:crt|key)))"
    r"|attempts/[a-f0-9]{32}/(?:attempt|completed|verified|target-before|source-fenced)\.json"
    r"|ready/[a-f0-9]{64}-(?:[a-f0-9]{32}|initial-local)\.json)"
)


def private_output(path):
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    return os.fdopen(fd, "wb")


def runtime_modes(deployment):
    modes = dict(deployment.runtime_modes)
    if deployment.alpha_dev:
        modes["alpha_dev"] = "read" if deployment.alpha_dev.read_enabled else "shadow"
    return modes


def volume_inventory(deployment, mount, output):
    helper = deployment.release.images["postgres"]
    checked_docker("run", "--rm", "--network", "none", "--pull", "never",
                   "--mount", f"type=volume,source={mount},target=/runtime,readonly,volume-nocopy",
                   "--entrypoint", "/bin/tar", helper, "-czf", "-", "-C", "/runtime", ".",
                   stdout=output, stderr=subprocess.DEVNULL, timeout=1800)


def project_volume(deployment, key):
    return deployment.config()["volumes"][key]["name"]


def guard_volume(deployment, name, key):
    volume, = json.loads(checked_docker("volume", "inspect", name))
    labels = volume.get("Labels") or {}
    if volume["Name"] != name or volume["Driver"] != "local" or volume.get("Options") or (
        labels.get("com.docker.compose.project"), labels.get("com.docker.compose.volume")
    ) != (deployment.project, key):
        raise RuntimeError("Том runtime принадлежит другому проекту")
    return volume


def guard_mount(deployment, service, volume, destination, identifier=None):
    if identifier is None:
        identifier = deployment.container(service)
    container, = json.loads(checked_docker("inspect", "--type", "container", identifier))
    if [mount.get("Name") for mount in container["Mounts"] if mount["Destination"] == destination] != [volume]:
        raise RuntimeError("Контейнер runtime подключён к другому тому")
    return container


def archive_volume(deployment, key, path):
    volume = project_volume(deployment, key)
    guard_volume(deployment, volume, key)
    if checked_docker("ps", "-q", "--filter", "volume=" + volume).strip():
        raise RuntimeError("Холодная копия runtime требует остановки всех контейнеров с томом")
    with private_output(path) as stream:
        volume_inventory(deployment, volume, stream)
        stream.flush()
        os.fsync(stream.fileno())
    if checked_docker("ps", "-q", "--filter", "volume=" + volume).strip():
        raise RuntimeError("Во время холодной копии runtime появился контейнер с томом")
    return document_inventory(path)


def restore_volume(deployment, key, path, inventory):
    assert document_inventory(path) == inventory, "Состав архива runtime не совпал"
    volume = project_volume(deployment, key)
    names = checked_docker("volume", "ls", "--format", "{{.Name}}").decode().splitlines()
    if volume in names:
        raise RuntimeError("Целевой том runtime уже существует")
    checked_docker("volume", "create", "--label", "com.docker.compose.project=" + deployment.project,
                   "--label", "com.docker.compose.volume=" + key, volume)
    guard_volume(deployment, volume, key)
    helper = deployment.release.images["postgres"]
    with path.open("rb") as stream:
        checked_docker("run", "--rm", "-i", "--network", "none", "--pull", "never",
                       "--mount", f"type=volume,source={volume},target=/runtime,volume-nocopy",
                       "--entrypoint", "/bin/tar", helper, "-xzf", "-", "-C", "/runtime",
                       stdin=stream, timeout=1800)
    with NamedTemporaryFile() as check:
        volume_inventory(deployment, volume, check)
        if document_inventory(check.name) != inventory:
            raise RuntimeError("Содержимое восстановленного тома runtime отличается")


def archive_state(state, target, allowed, required):
    selected = []
    for path in sorted(state.rglob("*")):
        key = path.relative_to(state).as_posix()
        if allowed.fullmatch(key):
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
                raise RuntimeError("Недопустимый файл в приватном состоянии runtime")
            selected.append((path, key))
    if not required <= {key for _, key in selected}:
        raise RuntimeError("Отсутствуют обязательные файлы состояния runtime")
    with private_output(target) as stream:
        with tarfile.open(fileobj=stream, mode="w:gz") as archive:
            for path, key in selected:
                archive.add(path, arcname=key, recursive=False)


def unpack_state(state, source, allowed, required):
    if state.exists() or state.is_symlink():
        raise RuntimeError("Целевое приватное состояние уже существует")
    with TemporaryDirectory(prefix=".runtime-restore-", dir=state.parent) as temporary:
        root = ensure_private(Path(temporary) / "state")
        seen = set()
        with tarfile.open(source, mode="r:gz") as archive:
            for member in archive:
                name = member.name
                if not member.isfile() or name in seen or not allowed.fullmatch(name):
                    raise RuntimeError("Недопустимый путь в архиве состояния runtime")
                seen.add(name)
                # Keep proof tied to source containers and database identities separate from live receipts.
                if name == "active.json":
                    name = "source-active.json"
                elif name.startswith("ready/"):
                    name = "source-" + name
                target = root / name
                target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                write_private(target, archive.extractfile(member).read().decode("utf-8"))
                if name.startswith("tls/") and name != "tls/ca.key":
                    target.chmod(0o444)
        if not required <= seen:
            raise RuntimeError("Архив состояния runtime неполон")
        for side in ("credit", "server"):
            if (root / "tls" / side).is_dir():
                (root / "tls" / side).chmod(0o555)
        root.rename(state)
        fsync_dir(state.parent)


def assessment_backup(deployment, directory):
    hook = deployment.assessment
    active = assessment_record(hook.state / "active.json")
    if (not active or active["manifest_sha256"] != deployment.release.checksum
            or active["mode"] != hook.descriptor.mode or assessment_record(hook.state / "pending.json")
            or assessment_record(hook.state / "binding.json") != hook.volume_binding()):
        raise RuntimeError("Assessment требует завершённую квитанцию и исходные тома")
    checkpoint = hook.backup_checkpoint()
    kafka_db = ASSESSMENT_VOLUMES[1]
    guard_mount(deployment, "assessment-kafka", project_volume(deployment, kafka_db), "/var/lib/kafka/data")
    with private_output(directory / "assessment.dump") as output:
        checked_docker("exec", deployment.container("assessment-db"), "sh", "-ec",
                       'PGPASSWORD="$POSTGRES_PASSWORD" exec pg_dump -h 127.0.0.1 -U assessment_admin -d assessment -Fc --no-acl',
                       stdout=output, stderr=subprocess.DEVNULL, timeout=1800)
    deployment.compose("stop", "-t", "150", "assessment-kafka")
    try:
        inventory = archive_volume(deployment, kafka_db, directory / "assessment-kafka.tar.gz")
    finally:
        deployment.compose(*UP, "--no-recreate", "assessment-kafka")
    if hook.backup_checkpoint() != checkpoint:
        raise RuntimeError("Assessment изменился во время холодной копии Kafka")
    archive_state(hook.state, directory / "assessment-state.tar.gz", ASSESSMENT_STATE, {"assessment.env", "active.json"})
    return {"checkpoint": checkpoint, "kafka_inventory": inventory}


def assessment_restore(deployment, directory, meta):
    hook = deployment.assessment
    restore_volume(deployment, ASSESSMENT_VOLUMES[1], directory / "assessment-kafka.tar.gz", meta["kafka_inventory"])
    deployment.compose(*UP, "assessment-db", "assessment-kafka")
    hook.database("admin", (ASSESSMENT_SQL / "bootstrap.sql").read_text())
    with (directory / "assessment.dump").open("rb") as stream:
        checked_docker("exec", "-i", deployment.container("assessment-db"), "sh", "-ec",
                       'PGPASSWORD="$POSTGRES_PASSWORD" exec pg_restore -h 127.0.0.1 -U assessment_admin '
                       '--role=assessment_owner -d assessment --no-owner --no-acl --single-transaction',
                       stdin=stream, timeout=1800)
    hook.database("owner", (ASSESSMENT_SQL / "grants.sql").read_text())
    if hook.database("app", (ASSESSMENT_SQL / "check.sql").read_text()) != "1":
        raise RuntimeError("Не подтверждены права восстановленной Assessment")
    hook.verify_restore(meta["checkpoint"])


ALPHA_STORES = (
    ("analytics-db", "alpha-analytics-data", "/var/lib/postgresql/data", "alpha-runtime-db.tar.gz"),
    ("analytics-kafka", "alpha-kafka-data", "/var/lib/kafka/data", "alpha-runtime-kafka.tar.gz"),
)
CREDIT_STORE = ("postgres", "cabinet-postgres-data", "/var/lib/postgresql/data", None)


def observe_store(deployment, store, *, cold=False):
    service, key, destination, _ = store
    volume = project_volume(deployment, key)
    observation = guard_volume(deployment, volume, key)
    created = observation.get("CreatedAt")
    if not isinstance(created, str) or not created:
        raise RuntimeError("Не подтверждена идентичность тома Alpha или Credit")
    identity = {"name": volume, "created_at": created}
    if cold:
        identifiers = deployment.compose("ps", "-a", "-q", service, capture=True).split()
        if not identifiers:
            return identity, None
        if len(identifiers) != 1:
            raise RuntimeError("Найдено несколько контейнеров хранилища Alpha или Credit")
        identifier, = identifiers
    else:
        identifier = deployment.container(service)
    container = guard_mount(deployment, service, volume, destination, identifier)
    return identity, {"reference": container["Config"]["Image"], "image_id": container["Image"]}


def store_images(release):
    result = {}
    for service, *_ in (CREDIT_STORE, *ALPHA_STORES):
        component = SERVICE_COMPONENTS[service]
        result[service] = {"reference": release.image_for(component), "image_id": release.local_image_id(component)}
    return result


def alpha_volumes(deployment, *, previous_release=False):
    identities = {}
    images = store_images(deployment.release) if previous_release else {}
    for store in ALPHA_STORES:
        service, key, _, _ = store
        identity, image = observe_store(deployment, store)
        if previous_release and images[service] != image:
            raise RuntimeError("Работающий образ Alpha не совпадает с прежним manifest")
        identities[key] = identity
    return identities


def alpha_preflight(deployment):
    verify_credit_mode(deployment)
    images = store_images(deployment.release)
    volumes = {}
    for store in (CREDIT_STORE, *ALPHA_STORES):
        service, key, _, _ = store
        volumes[key], image = observe_store(deployment, store)
        if images[service] != image:
            raise RuntimeError("Работающее хранилище Alpha или Credit не совпадает с исходным manifest")
    credit_volume = volumes.pop(CREDIT_STORE[1])
    return {"credit_volume": credit_volume, "volumes": volumes, "source_images": images,
            "checkpoint": alpha_checkpoint(deployment)}


def resume_alpha_stores(deployment, attempt, up):
    preflight, frozen = (attempt["alpha"][key] for key in ("preflight", "frozen"))
    volumes = {**frozen["volumes"], CREDIT_STORE[1]: preflight["credit_volume"]}
    source_images = preflight["source_images"]
    target_images = store_images(deployment.release)
    phase_path = deployment.path.parent / "runtime-attempts" / (attempt["attempt"] + "-alpha-stores.json")
    phase = {"attempt": attempt["attempt"], "previous_manifest_sha256": attempt["previous"]["manifest_sha256"],
             "target_manifest_sha256": deployment.release.checksum, "volumes": volumes,
             "source_images": source_images, "target_images": target_images}
    started = read_record(phase_path)
    if started is not None and started != phase:
        raise RuntimeError("Изменилась подтверждённая фаза запуска хранилищ Alpha")
    stores = (CREDIT_STORE, *ALPHA_STORES)
    for store in stores:
        service, key, _, _ = store
        identity, image = observe_store(deployment, store, cold=True)
        if identity != volumes[key]:
            raise RuntimeError("Изменился том Alpha или Credit до запуска")
        permitted = [source_images[service], target_images[service]] if started else [source_images[service]]
        if image is not None and image not in permitted:
            raise RuntimeError("Контейнер хранилища не совпадает с образами подтверждённой фазы Alpha")
    write_record(phase_path, phase, immutable=True)
    stop_writers(deployment)
    for store in stores:
        service, key, _, _ = store
        deployment.compose(*up, "--no-deps", service)
        if observe_store(deployment, store) != (volumes[key], target_images[service]):
            raise RuntimeError("Запущенное хранилище не совпадает с подтверждённой фазой Alpha")
    cutoff = frozen["checkpoint"]["outbox"]["cutoff"]
    check_alpha_continuity(frozen, {"volumes": alpha_volumes(deployment), "checkpoint": alpha_checkpoint(deployment, cutoff)})


def alpha_outbox(deployment, cutoff=None):
    if cutoff is None:
        cutoff = deployment.sql("credit", """
            select to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
        """)
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z", cutoff):
        raise RuntimeError("Не подтверждена граница Alpha outbox")
    topic = deployment.alpha_dev.topic
    data = json.loads(deployment.sql("credit", f"""
        select json_build_object('rows', count(*),
            'sha256', encode(sha256(convert_to(coalesce(string_agg(
               jsonb_build_array(id,event_key,payload)::text, chr(10) order by id), ''), 'UTF8')), 'hex'))
        from application_status_outbox where stream_generation='{topic}' and created_at <= '{cutoff}'::timestamptz;
    """))
    data["cutoff"] = cutoff
    data["open"] = int(deployment.sql("credit", f"""
        select count(*) from application_status_outbox
        where stream_generation='{topic}' and state in ('PENDING','RUNNING','FAILED');
    """))
    return data


def alpha_committed_offsets(group, topic, description, bounds):
    committed = {}
    for line in description.splitlines():
        fields = line.split()
        if not fields or fields[0] == "GROUP":
            continue
        if (len(fields) < 6 or fields[:2] != [group, topic] or fields[2] not in {"0", "1", "2"}
                or fields[2] in committed or not re.fullmatch(r"[0-9]+|-", fields[3])
                or not re.fullmatch(r"[0-9]+", fields[4])):
            raise RuntimeError("Не подтверждены offsets группы Alpha")
        committed[fields[2]] = None if fields[3] == "-" else int(fields[3])
    for partition in sorted({"0", "1", "2"} - committed.keys()):
        # With no active members Kafka lists only partitions with stored commits.
        # An omitted empty partition has no commit; preserve that state as None.
        if bounds["begin"][partition] != 0 or bounds["end"][partition] != 0:
            raise RuntimeError("Неполные offsets группы Alpha")
        committed[partition] = None
    return committed


def alpha_checkpoint(deployment, cutoff=None):
    alpha = deployment.alpha_dev
    marker = analytics_owner(deployment, "-qtA", "-c",
                             "SELECT generation || ' ' || snapshot_sha256 FROM application_status_generation WHERE singleton")
    if not re.fullmatch(re.escape(alpha.topic) + r" [a-f0-9]{64}", marker):
        raise RuntimeError("Не подтверждён снимок Alpha")
    topics = {}
    for topic in (alpha.topic, alpha.dlq):
        description = kafka(deployment, "kafka-topics", "--describe", "--topic", topic)
        identity = re.search(r"\bTopicId:\s*([A-Za-z0-9_-]{22})\s+PartitionCount:\s*3\s+ReplicationFactor:\s*1\b", description)
        if not identity:
            raise RuntimeError("Не подтверждена идентичность topic Alpha")
        checkpoint = {"id": identity[1]}
        for key, time_bound in (("begin", "-2"), ("end", "-1")):
            offsets = {}
            for line in kafka(deployment, "kafka-get-offsets", "--topic", topic, "--time", time_bound).splitlines():
                value = re.fullmatch(re.escape(topic) + r":([0-2]):([0-9]+)", line)
                if not value or value[1] in offsets:
                    raise RuntimeError("Невалидная граница Kafka Alpha")
                offsets[value[1]] = int(value[2])
            if set(offsets) != {"0", "1", "2"}:
                raise RuntimeError("Неполная граница Kafka Alpha")
            checkpoint[key] = offsets
        topics[topic] = checkpoint
    groups = kafka(deployment, "kafka-consumer-groups", "--describe", "--group", alpha.group, "--offsets")
    committed = alpha_committed_offsets(alpha.group, alpha.topic, groups, topics[alpha.topic])
    return {"generation": alpha.generation, "marker": marker, "topics": topics, "committed": committed,
            "outbox": alpha_outbox(deployment, cutoff)}


def check_alpha_continuity(before, live):
    if before["volumes"] != live["volumes"]:
        raise RuntimeError("Изменились тома Analytics или Kafka")
    old, new = (value["checkpoint"] for value in (before, live))
    if (old["generation"], old["marker"]) != (new["generation"], new["marker"]):
        raise RuntimeError("Изменилось поколение или исходный snapshot Analytics")
    for topic, bound in old["topics"].items():
        current = new["topics"][topic]
        if (bound["id"], bound["begin"]) != (current["id"], current["begin"]) or any(
            current["end"][partition] < end for partition, end in bound["end"].items()
        ):
            raise RuntimeError("Изменились идентичность или сохранённый диапазон Kafka Alpha")
    if any(offset is not None and (new["committed"][key] is None or new["committed"][key] < offset)
           for key, offset in old["committed"].items()):
        raise RuntimeError("Утрачены сохранённые offsets группы Alpha")
    if any(old["outbox"][key] != new["outbox"][key] for key in ("cutoff", "rows", "sha256")):
        raise RuntimeError("Утрачены ранее сохранённые события Alpha outbox")


def projection_matches(credit_rows, analytics_rows):
    observed = {application: (version, status) for application, version, status in
                map(json.loads, analytics_rows.splitlines())}
    for application, version, status, event_version, event_status in map(json.loads, credit_rows.splitlines()):
        projected = observed.pop(application, None)
        if version is None:
            return False
        if projected is None:
            # Draft creation does not produce a status event after the initial snapshot.
            if status != "DRAFT" or event_version is not None:
                return False
        elif projected[1] != status or projected[0] > version or (
                event_version is not None and projected != (event_version, event_status)):
            return False
    return not observed


def verify_alpha_catchup(deployment, before, *, timeout=120):
    alpha = deployment.alpha_dev
    volumes = alpha_volumes(deployment)
    credit_status_source = verify_credit_mode(deployment)
    cutoff = before["checkpoint"]["outbox"]["cutoff"]

    credit_query = f"""
        with stream_events as (
            select (payload->>'applicationId')::uuid application_id,
                   (payload->>'applicationVersion')::bigint application_version, payload->>'status' status
            from application_status_outbox where stream_generation='{alpha.topic}'
        ), latest as (
            select distinct on (application_id) application_id, application_version, status
            from stream_events order by application_id, application_version desc
        )
        select jsonb_build_array(coalesce(a.id,e.application_id),a.version,a.status,e.application_version,e.status)::text
        from applications a full join latest e on a.id=e.application_id order by coalesce(a.id,e.application_id);
    """
    analytics_query = """
        select jsonb_build_array(application_id,application_version,status)::text
        from application_status_projection order by application_id;
    """
    analytics_history_query = """
        with latest as (
            select distinct on (application_id) application_id, application_version, status
            from application_status_versions order by application_id, application_version desc
        )
        select not exists (
            select 1 from latest l full join application_status_projection p using (application_id)
            where (l.application_version,l.status) is distinct from (p.application_version,p.status)
        );
    """
    deadline = time.monotonic() + timeout
    while True:
        source = deployment.sql("credit", credit_query)
        target = analytics_owner(deployment, "-qtA", "-c", analytics_query)
        checkpoint = alpha_checkpoint(deployment, cutoff)
        check_alpha_continuity(before, {"volumes": volumes, "checkpoint": checkpoint})
        end = checkpoint["topics"][alpha.topic]["end"]
        old_dlq = before["checkpoint"]["topics"][alpha.dlq]["end"]
        latest = {part: int(offset) for _, part, offset in (line.split(":") for line in
                  kafka(deployment, "kafka-get-offsets", "--topic", alpha.topic, "--time", "-1").splitlines())}
        if (checkpoint["outbox"]["open"] == 0 and old_dlq == checkpoint["topics"][alpha.dlq]["end"]
                and end == latest and all(checkpoint["committed"][key] == value or
                    (value == 0 and checkpoint["committed"][key] is None) for key, value in end.items())
                and projection_matches(source, target) and source == deployment.sql("credit", credit_query)
                and analytics_owner(deployment, "-qtA", "-c", analytics_history_query) == "t"):
            return {"volumes": volumes, "checkpoint": checkpoint, "projection_sha256": sha256(target.encode()).hexdigest(),
                    "caught_up": True, "credit_status_source": credit_status_source}
        if time.monotonic() >= deadline:
            raise RuntimeError("Проекция Alpha или offsets ещё не подтверждены; HTTPS остаётся закрытым")
        time.sleep(3)


def alpha_backup(deployment, directory, preflight):
    cutoff = preflight["checkpoint"]["outbox"]["cutoff"]
    frozen = {"volumes": alpha_volumes(deployment, previous_release=True), "checkpoint": alpha_checkpoint(deployment, cutoff)}
    check_alpha_continuity(preflight, frozen)
    deployment.compose("stop", "-t", "150", *(item[0] for item in ALPHA_STORES))
    try:
        inventories = {key: archive_volume(deployment, key, directory / filename) for _, key, _, filename in ALPHA_STORES}
    finally:
        deployment.compose(*UP, "--no-recreate", *(item[0] for item in ALPHA_STORES))
    if (alpha_checkpoint(deployment, cutoff) != frozen["checkpoint"]
            or alpha_volumes(deployment, previous_release=True) != frozen["volumes"]):
        raise RuntimeError("Alpha изменилась во время создания копии")
    keys = (*ALPHA_SECRETS, "CABINET_ALPHA_DEV_GENERATION", "CABINET_ALPHA_DEV_READ_ENABLED", "CABINET_ALPHA_DEV_ENABLED")
    with private_output(directory / "alpha-runtime.env") as stream:
        stream.write("".join(f"{key}={deployment.values[key]}\n" for key in keys).encode("ascii"))
    return {"preflight": preflight, "frozen": frozen, "inventories": inventories}


def backup_private(deployment, directory, manifest, alpha_preflight):
    if deployment.assessment:
        manifest["assessment_runtime"] = assessment_backup(deployment, directory)
    if deployment.servicing:
        manifest["servicing_runtime"] = deployment.servicing.backup(deployment, directory)
        archive_state(deployment.servicing.state, directory / "servicing-state.tar.gz",
                      SERVICING_STATE, {"servicing.env", "authority.json", "tls/ca.key", "tls/ca.crt"})
    if deployment.documents:
        manifest["documents_runtime"] = deployment.documents.backup(deployment, directory)
    if deployment.alpha_dev:
        manifest["alpha_runtime"] = alpha_backup(deployment, directory, alpha_preflight)


def stage_private(project, env_file, directory, manifest):
    target = SimpleNamespace(project=project, local=False, path=env_file)
    modes = manifest["runtimes"]
    if "assessment" in modes:
        unpack_state(env_file.parent / ("assessment-" + project), directory / "assessment-state.tar.gz",
                     ASSESSMENT_STATE, {"assessment.env", "active.json"})
    if "servicing" in modes:
        unpack_state(private_state(target, "servicing"), directory / "servicing-state.tar.gz", SERVICING_STATE,
                     {"servicing.env", "authority.json", "tls/ca.key", "tls/ca.crt"})
    if "documents" in modes:
        DocumentsDev.stage_restore(private_state(target, "documents"), directory, manifest["documents_runtime"])


def restore_private(deployment, directory, manifest):
    if deployment.assessment:
        assessment_restore(deployment, directory, manifest["assessment_runtime"])
    if deployment.servicing:
        deployment.servicing.restore(deployment, directory, manifest["servicing_runtime"], UP)
    if deployment.documents:
        deployment.documents.restore(deployment, directory, manifest["documents_runtime"], UP)
    if deployment.alpha_dev:
        metadata = manifest["alpha_runtime"]
        for volume, filename in (("alpha-analytics-data", "alpha-runtime-db.tar.gz"),
                                 ("alpha-kafka-data", "alpha-runtime-kafka.tar.gz")):
            restore_volume(deployment, volume, directory / filename, metadata["inventories"][volume])
        deployment.compose(*UP, "analytics-db", "analytics-kafka")
        frozen = metadata["frozen"]["checkpoint"]
        if alpha_checkpoint(deployment, frozen["outbox"]["cutoff"]) != frozen:
            raise RuntimeError("Восстановленная Alpha отличается от сохранённой точки")
