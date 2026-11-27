#!/usr/bin/env python3
"""Dev-only Documents ownership, provenance, migration and consistent snapshots."""
import argparse
from contextlib import contextmanager
from dataclasses import dataclass
import fcntl
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import struct
import subprocess
from uuid import uuid4

from backup_support import checked_docker, digest, document_inventory
from maintenance import Database
from runtime_delivery_support import (
    Descriptor, ensure_private, ensure_tls, fsync_dir, inspect_one, load_secrets, private_state,
    matches, read_private, read_record, require_path, run, verify_container, write_private, write_record,
)
from stat import S_ISREG, S_IMODE

ROOT = Path(__file__).resolve().parent.parent
FILES = ROOT / "helm/cabinet/files/documents"
SOURCE = ROOT / "services/documents"
PERMISSIONS = ROOT / "libs/platform-common/src/main/resources/cabinet/database-permissions.sql"
SERVICES = ("documents", "documents-db", "documents-s3")
SECRET_KEYS = tuple("CABINET_DOCUMENTS_" + name for name in (
    "PG_ADMIN_PASSWORD", "PG_OWNER_PASSWORD", "PG_APP_PASSWORD",
    "S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_ADMIN_ACCESS_KEY", "S3_ADMIN_SECRET_KEY",
))
PEER = "spiffe://credit-cabinet/credit"
SHA = re.compile(r"[a-f0-9]{64}")
OBJECT_NAME = re.compile(r"[A-Za-z0-9._-]{1,255}")
GENERATION = re.compile(r"g[a-f0-9]{16}")
MAX_OBJECT_BYTES = 20 * 1024 * 1024
MAX_EPOCH = 2**63 - 1
GATE_QUERY = "select owner,epoch from public.document_ownership where singleton"
ZERO_SCOPE = b"00000000-0000-0000-0000-000000000000"
CREDIT_DOC = """
select jsonb_build_object(
'id',d.id,'scope',jsonb_build_object('companyId',a.company_id,'applicationId',d.application_id),
'type',d.type,'fileName',d.file_name,'storedName',d.stored_name,'sizeBytes',d.size_bytes,
'uploadedBy',d.uploaded_by,'uploadedAt',d.uploaded_at,'context',d.context,'backend',d.storage_backend,
'checksum',d.checksum_sha256,'privateToBank',d.private_to_bank,'deletedAt',d.deleted_at)::text
from public.documents d join public.applications a on a.id=d.application_id order by d.id;
"""
CREDIT_ARTIFACT = """
select jsonb_build_object(
'id',g.id,'scope',jsonb_build_object('companyId',a.company_id,'applicationId',g.application_id),
'offerId',g.offer_id,'kind',g.kind,'phase',g.phase,'templateVersion',g.template_version,
'inputHash',g.input_hash,'inputJson',g.input_json,'title',g.title,'fileName',g.file_name,
'contentType',g.content_type,'storedName',g.stored_name,'backend',g.storage_backend,'createdAt',g.created_at,
'status',g.status,'sizeBytes',g.size_bytes,'checksum',g.checksum,'readyAt',g.ready_at,
'attempts',g.attempts,'nextAttemptAt',g.next_attempt_at)::text
from public.generated_artifacts g join public.applications a on a.id=g.application_id order by g.id;
"""
DOCUMENTS_DOC = """
select (metadata || jsonb_build_object(
'uploadedAt',(metadata->>'uploadedAt')::timestamptz,'deletedAt',(metadata->>'deletedAt')::timestamptz)
)::text from public.document_records order by id;
"""
DOCUMENTS_ARTIFACT = """
select (metadata || jsonb_build_object(
'createdAt',(metadata->>'createdAt')::timestamptz,'readyAt',(metadata->>'readyAt')::timestamptz,
'attempts',attempts,'nextAttemptAt',next_attempt_at))::text
from public.artifact_records order by id;
"""
INTENTS = """
select jsonb_build_array(application_id,offer_id,phase,input_hash,input_json,created_at,delivered_at)::text
from public.document_artifact_inputs order by application_id,offer_id,phase,input_hash;
"""
DOCUMENTS_SHAPE = """
select not exists(
    select 1 from public.document_records d
    left join public.document_application_scopes s using(application_id)
    where s.company_id is distinct from d.company_id
       or (d.id,d.company_id,d.application_id,d.context) is distinct from
          ((metadata->>'id')::uuid,(metadata#>>'{scope,companyId}')::uuid,
           (metadata#>>'{scope,applicationId}')::uuid,metadata->>'context')
  ) and not exists(
    select 1 from public.artifact_records a
    left join public.document_application_scopes s using(application_id)
    where s.company_id is distinct from a.company_id
       or (a.id,a.company_id,a.application_id,a.offer_id,a.kind,a.phase,a.template_version,
           trim(a.input_hash),a.status) is distinct from
          ((metadata->>'id')::uuid,(metadata#>>'{scope,companyId}')::uuid,
           (metadata#>>'{scope,applicationId}')::uuid,(metadata->>'offerId')::uuid,
           metadata->>'kind',metadata->>'phase',(metadata->>'templateVersion')::integer,
           metadata->>'inputHash',metadata->>'status')
  ) and not exists(
    select 1 from public.artifact_outbox o join public.artifact_records a on a.id=o.artifact_id
    where a.status <> 'READY'
  );
"""
APP_PERMISSIONS = """
select session_user='documents_app'
   and not rolcreaterole and not rolcreatedb and not rolsuper and not rolreplication and not rolbypassrls
   and not has_schema_privilege(current_user,'public','CREATE')
   and not has_database_privilege(current_user,current_database(),'CREATE,TEMP')
   and not exists(select 1 from pg_database d where d.datallowconn and d.datname<>current_database()
      and has_database_privilege(current_user,d.oid,'CONNECT'))
   and not pg_has_role(current_user,'documents_owner','MEMBER')
   and not has_table_privilege(current_user,'public.document_ownership','INSERT,UPDATE,DELETE,TRUNCATE')
   and not has_column_privilege(current_user,'public.document_ownership','owner','UPDATE')
   and not has_column_privilege(current_user,'public.document_ownership','epoch','UPDATE')
   and has_column_privilege(current_user,'public.document_ownership','singleton','UPDATE')
   and not has_table_privilege(current_user,'public.artifact_outbox','UPDATE,DELETE,TRUNCATE')
   and not has_table_privilege(current_user,'public.document_application_scopes','UPDATE,DELETE,TRUNCATE')
from pg_roles where rolname=current_user;
begin;
select owner from public.document_ownership where singleton for share;
rollback;
"""


def stable_digest(value):
    return sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def read_rows(sql, query):
    return [json.loads(line) for line in sql("set timezone='UTC';\n" + query).splitlines()]


def gate(value):
    owner, sep, epoch = value.partition("|")
    if not sep or owner not in {"LOCAL", "FROZEN", "REMOTE", "DOCUMENTS"} or not epoch.isascii() or not epoch.isdigit():
        raise RuntimeError("Неверная строка владения Documents")
    number = int(epoch)
    if not 1 <= number <= MAX_EPOCH:
        raise RuntimeError("Некорректное поколение Documents")
    return owner, number


def stable_gates(mode, epoch):
    if mode == "local":
        return ("LOCAL", epoch), ("FROZEN", epoch)
    if mode == "remote":
        return ("REMOTE", epoch), ("DOCUMENTS", epoch)
    raise ValueError("Неизвестный режим Documents")


def transition_gates(mode, epoch):
    if epoch >= MAX_EPOCH:
        raise ValueError("Исчерпан диапазон поколений Documents")
    source = stable_gates(mode, epoch)
    frozen_credit = ("FROZEN", epoch + 1)
    frozen_own = ("FROZEN", epoch + 1)
    destination = stable_gates("remote" if mode == "local" else "local", epoch + 1)
    # Only the selected writer is activated, and only after both are frozen.
    return (source, (frozen_credit, source[1]), (frozen_credit, frozen_own),
            (destination[0], frozen_own), destination)


def valid_state(record):
    if (not isinstance(record, dict) or set(record) != {"mode", "epoch", "receipt"}
            or record["mode"] not in {"local", "remote"} or type(record["epoch"]) is not int
            or not 1 <= record["epoch"] <= MAX_EPOCH
            or (record["receipt"] is not None and not matches(SHA, record["receipt"]))
            or (record["receipt"] is None and (record["mode"], record["epoch"]) != ("local", 1))):
        raise ValueError("Повреждено состояние Documents")
    return record


def rpc_body(epoch):
    number = bytearray()
    while epoch > 127:
        number.append(0x80 | (epoch & 127))
        epoch >>= 7
    number.append(epoch)
    message = b"\x0a\x24" + ZERO_SCOPE + b"\x12\x24" + ZERO_SCOPE + b"\x18" + bytes(number)
    # protobuf and its gRPC frame have no non-ASCII bytes for normal epochs.
    # Preserve binary bytes in subprocess input rather than round-tripping Unicode.
    return b"\0" + struct.pack(">I", len(message)) + message


def object_requests(rows, side):
    for row in rows:
        name, backend, size = (row[field] for field in ("storedName", "backend", "sizeBytes"))
        if (not isinstance(name, str) or not OBJECT_NAME.fullmatch(name) or name in {".", ".."}
                or backend not in {"LOCAL", "S3"} or type(size) is not int or not 1 <= size <= MAX_OBJECT_BYTES):
            raise ValueError("Некорректное описание объекта Documents")
        yield f"{side} {backend} {name} {size}\n"


def compare_parity(credit, documents, credit_bytes, documents_bytes):
    if any(len(rows) != len({row["id"] for row in rows}) for rows in (credit, documents)):
        raise RuntimeError("Повтор идентификатора при сверке Documents")
    if len(credit) != len(documents) or len(credit_bytes) != len(documents_bytes):
        raise RuntimeError("Неполный набор данных Documents")
    differences = {"credit": credit_bytes, "documents": documents_bytes}
    normalized = {"credit": [], "documents": []}
    for side, records in (("credit", credit), ("documents", documents)):
        checksums = iter(differences[side])
        for entry in records:
            row = dict(entry)
            if row.get("status") is None or row["status"] == "READY":
                checksum = next(checksums, "")
                if not matches(SHA, checksum) or (row["checksum"] != checksum and not (
                        side == "credit" and "status" not in row and row["checksum"] is None)):
                    raise RuntimeError("SHA-256 объекта Documents не совпадает с metadata")
                row["checksum"] = checksum
            if side == "documents" and "inputJson" in row:
                raw = row["inputJson"]
                if sha256(raw.encode()).hexdigest() != row["inputHash"]:
                    raise RuntimeError("Изменился frozen input артефакта Documents")
                row["inputJson"] = json.loads(raw)
            normalized[side].append(row)
        if next(checksums, None) is not None:
            raise RuntimeError("Получены лишние контрольные суммы Documents")
    if normalized["credit"] != normalized["documents"]:
        raise RuntimeError("Различаются metadata или байты Credit и Documents")
    return normalized["documents"]


def validate_intents(before, after, artifacts):
    by_key = {tuple(row[:4]): row[4:] for row in after}
    for row in before:
        if by_key.get(tuple(row[:4])) != row[4:]:
            raise RuntimeError("Изменился существующий immutable input в Credit")
    for row in artifacts:
        key = row["scope"]["applicationId"], row["offerId"], row["phase"], row["inputHash"]
        if key not in by_key or by_key[key][0] != row["inputJson"] or by_key[key][2] is None:
            raise RuntimeError("Отсутствует неизменяемый Credit input/receipt для артефакта Documents")


@dataclass
class DocumentsDev:
    descriptor: Descriptor
    state: Path
    project: str
    source_storage: str
    has_s3: bool
    identity: dict
    credentials: dict
    tls: Path

    @classmethod
    def open(cls, deployment):
        state = private_state(deployment, "documents")
        if state is None:
            return None
        metadata = getattr(deployment.release, "documents", None)
        descriptor = (Descriptor.from_release(deployment.release, "documents")
                      if metadata is not None or os.path.lexists(state) else None)
        if descriptor is None or descriptor.mode == "disabled":
            if os.path.lexists(state):
                raise RuntimeError("Уже подключённый Documents требует descriptor")
            return None
        mode = deployment.values["STORAGE_BACKEND"]
        has_s3 = "storage" in deployment.profiles
        ensure_private(state)
        with cls.state_lock(state):
            identity_path = state / "identity.json"
            identity = read_record(identity_path)
            if identity is None and descriptor.mode != "local" and deployment.project == "cabinet-dev":
                raise RuntimeError("Для первого включения Documents нужен manifest с dev_mode=local")
            credentials_path = state / "credentials.env"
            if identity is not None and (read_private(credentials_path) is None or not (state / "tls").exists()):
                raise RuntimeError("У подключённого Documents отсутствуют исходные credentials или CA")
            credentials = load_secrets(credentials_path, SECRET_KEYS, deployment.values)
            tls = ensure_tls(state, service="documents", client_uri=PEER)
            expected = {"source_storage": mode, "has_s3": has_s3, "credentials_sha256": digest(credentials_path),
                        "ca_sha256": digest(tls / "ca.crt")}
            if identity is None:
                identity = {"generation": "g" + uuid4().hex[:16], **expected}
            cls.validate_identity(identity)
            if any(identity[key] != value for key, value in expected.items()):
                raise ValueError("Изменилась закреплённая конфигурация, CA или credentials Documents")
            write_record(identity_path, identity, immutable=True)
        return cls(descriptor, state, deployment.project, mode, has_s3, identity, credentials, tls)

    @staticmethod
    def validate_identity(identity):
        if (not isinstance(identity, dict) or set(identity) != {
                "generation", "source_storage", "has_s3", "credentials_sha256", "ca_sha256"}
                or not matches(GENERATION, identity["generation"]) or not matches(SHA, identity["credentials_sha256"])
                or not matches(SHA, identity["ca_sha256"])
                or identity["source_storage"] not in {"local", "s3"} or type(identity["has_s3"]) is not bool
                or (identity["source_storage"] == "s3" and not identity["has_s3"])):
            raise ValueError("Некорректная неизменяемая идентичность Documents")

    @staticmethod
    @contextmanager
    def state_lock(state):
        fd = os.open(state / "lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "r+") as lock:
            info = os.fstat(lock.fileno())
            if not S_ISREG(info.st_mode) or S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.getuid():
                raise ValueError("Замок состояния Documents должен иметь права 0600")
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            yield

    def current(self):
        default = {"mode": "local", "epoch": 1, "receipt": None}
        current = valid_state(read_record(self.state / "current.json") or default)
        if current["receipt"]:
            receipt = read_record(self.state / "receipts" / f"{current['receipt']}.json")
            if (not receipt or (receipt.get("to_mode"), receipt.get("epoch")) != (current["mode"], current["epoch"])
                    or receipt.get("identity") != self.identity or stable_digest(receipt) != current["receipt"]):
                raise RuntimeError("Не найдена проверенная квитанция активного поколения Documents")
        return current

    def compose_environment(self):
        prefix = f"{self.project}-documents-{self.identity['generation']}"
        return {
            **self.credentials,
            "CABINET_DOCUMENTS_MODE": "local",
            "CABINET_DOCUMENTS_EPOCH": "1",
            "CABINET_DOCUMENTS_TLS_DIR": str(self.tls),
            "CABINET_DOCUMENTS_STORAGE_MODE": self.source_storage,
            "CABINET_DOCUMENTS_S3_BUCKET": f"documents-{self.identity['generation']}",
            "CABINET_DOCUMENTS_DB_VOLUME": prefix + "-db",
            "CABINET_DOCUMENTS_LOCAL_VOLUME": prefix + "-local",
            "CABINET_DOCUMENTS_S3_VOLUME": prefix + "-s3",
        }

    def credit_environment(self):
        current = self.current()
        return {"CABINET_DOCUMENTS_MODE": current["mode"], "CABINET_DOCUMENTS_EPOCH": str(current["epoch"])}

    def sql(self, deployment, statement, *, app=False):
        connection = ["psql", "-X", "-w", "-qAt", "-v", "ON_ERROR_STOP=1", "-d", "documents"]
        if app:
            command = ["sh", "-c", 'PGPASSWORD="$CABINET_DOCUMENTS_PG_APP_PASSWORD" exec "$@"', "documents",
                       *connection, "-h", "127.0.0.1", "-U", "documents_app"]
        else:
            command = [*connection, "-U", "postgres"]
        return run("docker", "exec", "-i", deployment.container("documents-db"), *command, stdin=statement)

    def credit_sql(self, deployment, statement):
        return deployment.sql("credit", statement)

    def gates(self, deployment):
        return (gate(self.credit_sql(deployment, GATE_QUERY)), gate(self.sql(deployment, GATE_QUERY)))

    def prepare_schema(self, deployment):
        migrations = sorted((SOURCE / "src/main/resources/db/migration").glob("V*.sql"))
        if [path.name for path in migrations] != ["V1__documents.sql"]:
            raise RuntimeError("Обновите bootstrap Documents для новых миграций")
        schema = migrations[0].read_text()
        checksum = sha256(schema.encode()).hexdigest()
        marker = "public.documents_delivery_schema"
        result = self.sql(deployment, f"select to_regclass('{marker}') is not null;")
        if result == "f":
            self.sql(deployment, f"""
begin;
select pg_advisory_xact_lock(17127,26);
set local role documents_owner;
create table {marker} (version int primary key check(version=1), sha256 text not null);
{schema}
insert into {marker} values(1,'{checksum}');
commit;
""")
        elif result != "t":
            raise RuntimeError("Не определена версия схемы Documents")
        if self.sql(deployment, f"select sha256 from {marker} where version=1;") != checksum:
            raise RuntimeError("SHA-256 применённой схемы Documents изменился")
        permissions = PERMISSIONS.read_text().replace("__OWNER__", "documents_owner").replace("__APP__", "documents_app")
        grants = (SOURCE / "cutover/grants.sql").read_text() + (FILES / "fence-documents.sql").read_text()
        # Apply baseline and narrower grants in one commit.
        self.sql(deployment, permissions.removesuffix("commit;\n") + grants + "\ncommit;")
        self.audit_roles(deployment)
        self.credit_sql(deployment, "begin;\n" + (SOURCE / "cutover/credit-grants.sql").read_text()
                        + (FILES / "fence-credit.sql").read_text() + "\ncommit;")
        if self.credit_sql(deployment, """
select count(*)=2
from pg_trigger t
join pg_proc p on p.oid=t.tgfoid
where t.tgname in ('documents_owner_fence','artifacts_owner_fence')
  and t.tgenabled='O' and p.prosecdef
  and not has_function_privilege('credit_app',p.oid,'EXECUTE')
  and not has_column_privilege('credit_app','public.document_ownership','owner','UPDATE')
  and not has_column_privilege('credit_app','public.document_ownership','epoch','UPDATE');
""") != "t":
            raise RuntimeError("Не подтверждены триггеры sole-writer Credit")


    def audit_roles(self, deployment):
        result = self.sql(deployment, APP_PERMISSIONS, app=True).splitlines()
        if len(result) != 2 or result[0] != "t" or result[1] not in {"FROZEN", "DOCUMENTS"}:
            raise RuntimeError("Не подтверждены минимальные права Documents runtime")

    def assert_writers_stopped(self, deployment):
        running = deployment.compose("ps", "--status", "running", "-q", "caddy", "credit", "documents", capture=True)
        if running.strip():
            raise RuntimeError("Cutover Documents требует остановленных HTTPS, Credit и Documents")

    def verify_source_store(self, deployment):
        if self.has_s3:
            env = deployment.config()["services"]["credit"]["environment"]
            if (env["S3_ENDPOINT"] != "http://seaweedfs:8333"
                    or env["S3_BUCKET"] == self.compose_environment()["CABINET_DOCUMENTS_S3_BUCKET"]):
                raise ValueError("Documents cutover/backup требует отдельных управляемых хранилищ S3")
            for name in ("S3_BUCKET", "S3_REGION"):
                if not re.fullmatch(r"[a-z0-9][a-z0-9.-]*", env[name]):
                    raise ValueError("Некорректная конфигурация источника S3 Documents")

    def maintenance(self, deployment):
        return Database(deployment.container("postgres"), deployment.values.get("POSTGRES_USER", "cabinet"))

    def active_request(self):
        return read_record(self.state / "active.json")

    def preflight(self, deployment):
        current = self.current()
        active = self.active_request()
        if active and (active.get("revision"), active.get("manifest_sha256")) != (
                self.descriptor.revision, self.descriptor.manifest_sha256):
            raise RuntimeError("Возобновите незавершённую попытку Documents с исходным manifest")
        if current["mode"] != self.descriptor.mode and active is None:
            raise RuntimeError("Смена владельца Documents требует подготовленной попытки с проверенным бэкапом")
        self.verify_source_store(deployment)

    def prepare(self, deployment, arguments):
        with self.state_lock(self.state):
            self.preflight(deployment)
            image_id = self.descriptor.verify_image()
            self.assert_writers_stopped(deployment)
            services = ["documents-db", *(["documents-s3"] if self.has_s3 else [])]
            deployment.compose(*arguments, *services)
            self.prepare_schema(deployment)
            if self.active_request():
                self.cutover(deployment)
            current = self.current()
            if self.gates(deployment) != stable_gates(current["mode"], current["epoch"]):
                raise RuntimeError("Базы Documents не соответствуют активному поколению")
            deployment.compose(*arguments, "documents")
            verify_container(deployment, "documents", image_id, ready=True)
            self.probe(deployment)
            deployment.environment.update(self.credit_environment())

    def probe(self, deployment, *, from_credit=False):
        current = self.current()
        curl = ["--disable", "--noproxy", "*", "--http2", "--silent", "--show-error", "--fail",
                "--max-time", "20", "--cacert", "/run/documents-tls/ca.crt",
                "--cert", "/run/documents-tls/tls.crt", "--key", "/run/documents-tls/tls.key",
                "-H", "content-type: application/grpc", "-H", "te: trailers",
                "--data-binary", "@-", "-D", "-", "-o", "/dev/null",
                "https://documents:9096/creditcabinet.documents.v1.DocumentsService/ListDocuments"]
        if from_credit:
            command = ["docker", "exec", "-i", deployment.container("credit"), "curl", *curl]
        else:
            command = [*deployment.command, "run", "--rm", "--no-deps", "-T", "documents-probe", *curl]
        result = subprocess.run(command, cwd=ROOT, env=deployment.environment, input=rpc_body(current["epoch"]),
                                capture_output=True, timeout=30)
        if result.returncode or not result.stdout.startswith(b"HTTP/2 200") or re.findall(
                rb"^grpc-status: ([0-9]+)\r?$", result.stdout, re.I | re.M
        ) != [b"0" if current["mode"] == "remote" else b"9"]:
            raise RuntimeError("Не подтверждён gRPC Documents: mTLS, SAN, scope или fencing epoch")

    def verify_credit(self, deployment):
        current = self.current()
        if self.active_request() or current["mode"] != self.descriptor.mode:
            raise RuntimeError("Режим manifest и согласованный режим Documents различаются")
        credit_image = verify_container(deployment, "credit", deployment.release.local_image_id("credit"), ready=True)
        documents_image = verify_container(deployment, "documents", self.descriptor.verify_image(), ready=True)
        config = inspect_one("inspect", "--type", "container", deployment.container("credit")).get("Config") or {}
        fields = [line.partition("=") for line in config.get("Env") or ()]
        for key, value in self.credit_environment().items():
            if [field for name, delimiter, field in fields if name == key and delimiter] != [value]:
                raise RuntimeError("Credit не использует подтверждённый режим и epoch Documents")
        if self.gates(deployment) != stable_gates(current["mode"], current["epoch"]):
            raise RuntimeError("Credit и Documents не подтвердили режим одного writer")
        self.probe(deployment, from_credit=True)
        receipt = {
            "revision": self.descriptor.revision,
            "manifest_sha256": self.descriptor.manifest_sha256,
            "image": self.descriptor.image,
            "jar_sha256": self.descriptor.jar_sha256,
            "documents_image_id": documents_image,
            "credit_image_id": credit_image,
            "generation": self.identity["generation"],
            "mode": current["mode"],
            "epoch": current["epoch"],
            "cutover_receipt": current["receipt"],
            "credit_mtls": True,
        }
        ready = ensure_private(self.state / "ready")
        write_record(ready / f"{self.descriptor.manifest_sha256}-{current['epoch']}.json", receipt, immutable=True)
        return receipt

    def move_gate(self, deployment, side, before, after):
        old_owner, old_epoch = before
        new_owner, new_epoch = after
        statement = ("set lock_timeout='25s'; update public.document_ownership "
                     f"set owner='{new_owner}',epoch={new_epoch} "
                     f"where singleton and owner='{old_owner}' and epoch={old_epoch} returning owner,epoch;")
        sql = self.credit_sql if side == "credit" else self.sql
        if gate(sql(deployment, statement)) != after:
            raise RuntimeError("Конфликт перехода writer Documents")

    def migration(self, deployment, request):
        self.assert_writers_stopped(deployment)
        name = f"{self.project}-documents-copy-{request['attempt']}"
        # A detached engine operation can survive interruption of the deployment process.
        running = run("docker", "ps", "-q", "--filter", f"name=^/{name}$")
        if running:
            raise RuntimeError("Перенос Documents ещё выполняется; дождитесь завершения контейнера попытки")
        direction = "forward" if request["to_mode"] == "remote" else "reverse"
        output = run(*deployment.command, "run", "--rm", "--no-deps", "-T", "--name", name,
                     "-e", "DOCUMENTS_MIGRATION_DIRECTION=" + direction, "documents-cutover",
                     env=deployment.environment, timeout=3600)
        pattern = (r"^documents_migration direction=forward documents=\d+ artifacts=\d+ checksums=verified owner=FROZEN$"
                   if direction == "forward" else r"^documents_migration direction=reverse checksums=verified owner=FROZEN$")
        if len(re.findall(pattern, output, re.M)) != 1:
            raise RuntimeError("Documents JAR не подтвердил результат переноса")

    def records(self, deployment):
        credit_sql = lambda statement: self.credit_sql(deployment, statement)
        own_sql = lambda statement: self.sql(deployment, statement)
        if own_sql(DOCUMENTS_SHAPE) != "t":
            raise RuntimeError("Нарушена связь metadata, scope или outbox Documents")
        return (read_rows(credit_sql, CREDIT_DOC), read_rows(credit_sql, CREDIT_ARTIFACT),
                read_rows(own_sql, DOCUMENTS_DOC), read_rows(own_sql, DOCUMENTS_ARTIFACT))

    def object_digests(self, deployment, source, target):
        if not self.has_s3 and any(row["backend"] == "S3" for row in [*source, *target]):
            raise RuntimeError("Для объектов S3 Documents нужен защищённый профиль storage")
        payload = "".join([*object_requests(source, "credit"), *object_requests(target, "documents")])
        if not payload:
            return [], []
        output = run(*deployment.command, "run", "--rm", "--no-deps", "-T", "--entrypoint", "/bin/sh",
                     "documents-cutover", "/cabinet/object-digests.sh", stdin=payload,
                     env=deployment.environment, timeout=3600).splitlines()
        if len(output) != len(source) + len(target):
            raise RuntimeError("Не получены SHA-256 всех объектов Documents")
        return output[:len(source)], output[len(source):]

    def parity(self, deployment, intents_before):
        credit, credit_artifacts, own, own_artifacts = self.records(deployment)
        ready = [row for row in credit_artifacts if row["status"] == "READY"]
        own_ready = [row for row in own_artifacts if row["status"] == "READY"]
        source, target = self.object_digests(deployment, credit + ready, own + own_ready)
        docs = compare_parity(credit, own, source[:len(credit)], target[:len(own)])
        artifacts = compare_parity(credit_artifacts, own_artifacts, source[len(credit):], target[len(own):])
        intents = read_rows(lambda statement: self.credit_sql(deployment, statement), INTENTS)
        validate_intents(intents_before, intents, artifacts)
        return {
            "documents": len(docs), "artifacts": len(artifacts), "ready_artifacts": len(own_ready),
            "backends": sorted({row["backend"] for row in docs + own_ready}),
            "metadata_and_bytes_sha256": stable_digest([docs, artifacts]),
            "credit_immutable_inputs_sha256": stable_digest(intents),
        }

    def cutover(self, deployment):
        request = self.active_request()
        self.validate_request(request)
        current = self.current()
        if (current["mode"], current["epoch"]) == (request["to_mode"], request["epoch"] + 1):
            self.finish_request(request)
            return
        if (current["mode"], current["epoch"]) != (request["from_mode"], request["epoch"]):
            raise RuntimeError("Попытка Documents относится к другой исходной эпохе")
        lock = self.maintenance(deployment)
        try:
            if lock.sql("select pg_try_advisory_lock(16781,8801);") != "t":
                raise RuntimeError("Выполняется другая операция backup/storage")
            self.resume_attempt(deployment, request)
        finally:
            lock.close()
        self.finish_request(request)

    def resume_attempt(self, deployment, request):
        self.assert_writers_stopped(deployment)
        epoch = request["epoch"]
        sequence = transition_gates(request["from_mode"], epoch)
        gates = self.gates(deployment)
        if gates not in sequence:
            raise RuntimeError("Состояние writer Documents не принадлежит текущей попытке")
        journal = ensure_private(ensure_private(self.state / "attempts") / request["attempt"])
        write_record(journal / "request.json", request, immutable=True)
        verified_path = journal / "verified.json"
        verified = read_record(verified_path)
        if gates in sequence[3:] and not verified:
            raise RuntimeError("Нет квитанции проверенного переноса Documents до активации")
        if gates == sequence[0]:
            self.move_gate(deployment, "credit", sequence[0][0], sequence[1][0])
            gates = sequence[1]
        if gates == sequence[1]:
            self.move_gate(deployment, "documents", sequence[1][1], sequence[2][1])
            gates = sequence[2]
        if gates == sequence[2] and not verified:
            self.sql(deployment, "update public.artifact_records set lease_token=null,lease_until=null "
                     "where lease_token is not null or lease_until is not null;")
            inputs = read_rows(lambda statement: self.credit_sql(deployment, statement), INTENTS)
            intent_path = journal / "original-inputs.json"
            original = read_record(intent_path)
            if original is None:
                original = {"inputs": inputs}
                write_record(intent_path, original, immutable=True)
            self.migration(deployment, request)
            evidence = self.parity(deployment, original["inputs"])
            verified = {"request": stable_digest(request), "image": self.descriptor.image,
                        "jar_sha256": self.descriptor.jar_sha256, "evidence": evidence}
            write_record(verified_path, verified, immutable=True)
        if verified["request"] != stable_digest(request) or (verified["image"], verified["jar_sha256"]) != (
                self.descriptor.image, self.descriptor.jar_sha256):
            raise RuntimeError("Квитанция переноса Documents относится к другой попытке")
        if gates == sequence[2]:
            self.move_gate(deployment, "credit", sequence[2][0], sequence[3][0])
            gates = sequence[3]
        if gates == sequence[3] and sequence[3] != sequence[4]:
            self.move_gate(deployment, "documents", sequence[3][1], sequence[4][1])
        if self.gates(deployment) != sequence[4]:
            raise RuntimeError("Переход Documents завершился без подтверждённого writer")
        receipt = {**request, "epoch": epoch + 1, "identity": self.identity, **verified}
        key = stable_digest(receipt)
        write_record(ensure_private(self.state / "receipts") / f"{key}.json", receipt, immutable=True)
        write_record(self.state / "current.json", {"mode": request["to_mode"], "epoch": epoch + 1, "receipt": key})

    def validate_request(self, request):
        if (not isinstance(request, dict) or set(request) != {
                "attempt", "from_mode", "to_mode", "epoch", "backup_sha256", "revision", "manifest_sha256"}
                or not matches(re.compile(r"[a-f0-9]{32}"), request["attempt"])
                or not matches(SHA, request["backup_sha256"]) or request["to_mode"] != self.descriptor.mode
                or request["from_mode"] not in {"local", "remote"} or request["from_mode"] == request["to_mode"]
                or type(request["epoch"]) is not int or not 1 <= request["epoch"] < MAX_EPOCH
                or (request["revision"], request["manifest_sha256"]) != (
                    self.descriptor.revision, self.descriptor.manifest_sha256)):
            raise RuntimeError("Попытка переноса Documents не соответствует выбранному выпуску")

    def finish_request(self, request):
        receipt_key = self.current()["receipt"]
        receipt = read_record(self.state / "receipts" / f"{receipt_key}.json") if receipt_key else None
        if not receipt or receipt.get("request") != stable_digest(request):
            raise RuntimeError("Невозможно закрыть попытку Documents без её квитанции")
        (self.state / "active.json").unlink()
        fsync_dir(self.state)

    def authorize(self, directory):
        with self.state_lock(self.state):
            if self.project != "cabinet-dev" or self.active_request() is not None:
                raise RuntimeError("Новая попытка разрешена только для cabinet-dev без незавершённого cutover")
            current = self.current()
            manifest_path = directory / "manifest.json"
            raw = read_private(manifest_path)
            manifest = json.loads(raw) if raw is not None else {}
            backup = manifest.get("documents_runtime")
            if (manifest.get("source_project") != self.project or manifest.get("backup_complete") is not True
                    or manifest.get("source_resumed") is not True or not isinstance(backup, dict)):
                raise RuntimeError("Нужна завершённая согласованная копия cabinet-dev")
            self.verify_backup(directory, backup)
            required = {"credit.dump", "documents.tar.gz", *backup["files"]}
            if self.has_s3:
                required.add("seaweedfs.tar.gz")
            for name in required:
                record = manifest.get("files", {}).get(name)
                if record != self.file_stamp(directory / name):
                    raise RuntimeError("Повреждён файл согласованной копии Documents/Credit")
            if (backup["identity"], backup["current"]) != (self.identity, current):
                raise RuntimeError("Копия относится к другой идентичности или эпохе Documents")
            request = {
                "attempt": uuid4().hex, "from_mode": current["mode"], "to_mode": self.descriptor.mode,
                "epoch": current["epoch"], "backup_sha256": sha256(raw.encode()).hexdigest(),
                "revision": self.descriptor.revision, "manifest_sha256": self.descriptor.manifest_sha256,
            }
            self.validate_request(request)
            journal = ensure_private(ensure_private(self.state / "attempts") / request["attempt"])
            write_record(journal / "request.json", request, immutable=True)
            write_record(self.state / "active.json", request, immutable=True)
            return request["attempt"]

    @staticmethod
    def file_stamp(path):
        require_path(path, S_ISREG, 0o600)
        return {"bytes": path.stat().st_size, "sha256": digest(path)}

    def store_snapshot(self, deployment):
        credit, artifacts, own, own_artifacts = self.records(deployment)
        credit_ready = credit + [row for row in artifacts if row["status"] == "READY"]
        own_ready = own + [row for row in own_artifacts if row["status"] == "READY"]
        source, target = self.object_digests(deployment, credit_ready, own_ready)
        for rows, checksums in ((credit_ready, source), (own_ready, target)):
            for row, checksum in zip(rows, checksums, strict=True):
                if not matches(SHA, checksum) or (row["checksum"] is not None and row["checksum"] != checksum):
                    raise RuntimeError("Байты резервной копии Documents не соответствуют metadata")
        own_sql = lambda statement: self.sql(deployment, statement)
        snapshot = {
            table: stable_digest(read_rows(own_sql, f"select to_jsonb(t)::text from public.{table} t order by to_jsonb(t)::text;"))
            for table in ("documents_delivery_schema", "document_ownership", "document_application_scopes",
                          "document_records", "artifact_records", "artifact_outbox")
        }
        return {"docs_tables": snapshot, "source_sha256": stable_digest([credit, artifacts, source]),
                "target_sha256": stable_digest([own, own_artifacts, target]),
                "backends": sorted({entry["backend"] for entry in credit_ready + own_ready})}

    @staticmethod
    @contextmanager
    def private_file(path):
        with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600), "wb") as stream:
            yield stream
            stream.flush()
            os.fsync(stream.fileno())

    def volume(self, deployment, key):
        mapping = {"local": ("documents-local-data", "LOCAL"), "s3": ("documents-s3-data", "S3"),
                   "db": ("documents-db-data", "DB")}
        label, suffix = mapping[key]
        volume = self.compose_environment()[f"CABINET_DOCUMENTS_{suffix}_VOLUME"]
        data = inspect_one("volume", "inspect", volume)
        if (data.get("Name"), (data.get("Labels") or {}).get("com.docker.compose.project"),
                (data.get("Labels") or {}).get("com.docker.compose.volume")) != (volume, self.project, label):
            raise RuntimeError("Том Documents не принадлежит выбранному проекту")
        return volume

    def archive_volume(self, deployment, path, key):
        with self.private_file(path) as output:
            checked_docker("run", "--rm", "--network", "none", "--pull", "never",
                           "-v", self.volume(deployment, key) + ":/snapshot:ro",
                           deployment.release.images["postgres"], "tar", "-czf", "-", "-C", "/snapshot", ".",
                           stdout=output, stderr=subprocess.DEVNULL)
        return document_inventory(path)

    def backup(self, deployment, directory):
        import tarfile
        with self.state_lock(self.state):
            self.assert_writers_stopped(deployment)
            current = self.current()
            if self.active_request() or self.gates(deployment) != stable_gates(current["mode"], current["epoch"]):
                raise RuntimeError("Копия Documents требует стабильного владельца вне cutover")
            self.descriptor.verify_image()
            self.verify_source_store(deployment)
            self.audit_roles(deployment)
            snapshot = self.store_snapshot(deployment)
            paths = {"db": "documents-runtime.dump", "local": "documents-runtime-local.tar.gz",
                     "state": "documents-runtime-state.tar.gz"}
            with self.private_file(directory / paths["db"]) as output:
                checked_docker("exec", deployment.container("documents-db"), "pg_dump", "-U", "postgres",
                               "-d", "documents", "-Fc", "--no-owner", "--no-acl",
                               stdout=output, stderr=subprocess.DEVNULL)
            volumes = {"local": self.archive_volume(deployment, directory / paths["local"], "local")}
            if self.has_s3:
                paths["s3"] = "documents-runtime-s3.tar.gz"
                deployment.compose("stop", "documents-s3")
                try:
                    volumes["s3"] = self.archive_volume(deployment, directory / paths["s3"], "s3")
                finally:
                    deployment.compose("up", "-d", "--no-build", "--no-deps", "--wait", "--wait-timeout", "120",
                                       "documents-s3")
            files = [self.state / name for name in ("identity.json", "credentials.env", "current.json") if (self.state / name).exists()]
            for area in ("tls", "receipts", "attempts", "ready"):
                files.extend(path for path in (self.state / area).rglob("*") if path.is_file())
            with self.private_file(directory / paths["state"]) as output:
                with tarfile.open(fileobj=output, mode="w:gz") as archive:
                    for path in sorted(files):
                        if path.is_symlink():
                            raise ValueError("Недопустимая ссылка в состоянии Documents")
                        archive.add(path, arcname=str(path.relative_to(self.state)), recursive=False)
            return {"format": 1, "identity": self.identity, "current": current, "snapshot": snapshot, "volumes": volumes,
                    "paths": paths, "files": {path: self.file_stamp(directory / path) for path in paths.values()}}

    @classmethod
    def verify_backup(cls, directory, backup):
        if not isinstance(backup, dict) or set(backup) != {
                "format", "identity", "current", "snapshot", "volumes", "paths", "files"} or backup["format"] != 1:
            raise ValueError("Некорректный формат копии Documents")
        cls.validate_identity(backup["identity"])
        valid_state(backup["current"])
        paths = {"db": "documents-runtime.dump", "local": "documents-runtime-local.tar.gz",
                 "state": "documents-runtime-state.tar.gz"}
        if backup["identity"]["has_s3"]:
            paths["s3"] = "documents-runtime-s3.tar.gz"
        if backup["paths"] != paths or set(backup["files"]) != set(paths.values()):
            raise ValueError("Недопустимые имена файлов копии Documents")
        for path, expected in backup["files"].items():
            if cls.file_stamp(directory / path) != expected:
                raise RuntimeError("Неверный SHA-256 копии Documents")
        volumes = {"local", "s3"} & set(paths)
        if set(backup["volumes"]) != volumes:
            raise RuntimeError("Некорректный состав томов копии Documents")
        for key in volumes:
            if document_inventory(directory / paths[key]) != backup["volumes"][key]:
                raise RuntimeError("Архив тома Documents не совпадает с описью")
        return backup

    @classmethod
    def stage_restore(cls, state, directory, backup):
        import tarfile
        from tempfile import TemporaryDirectory
        cls.verify_backup(directory, backup)
        if os.path.lexists(state):
            raise RuntimeError("Целевое состояние Documents уже существует")
        # A private staging tree and atomic rename avoid a half-materialized CA or epoch.
        with TemporaryDirectory(prefix=".documents-restore-", dir=state.parent) as scratch:
            candidate = ensure_private(Path(scratch) / "state")
            pattern = re.compile(
                r"(?:identity\.json|credentials\.env|current\.json|tls/(?:ca\.(?:key|crt)|(?:server|credit)/(?:ca\.crt|tls\.(?:crt|key)))"
                r"|receipts/[a-f0-9]{64}\.json|ready/[a-f0-9]{64}-[1-9][0-9]{0,18}\.json"
                r"|attempts/[a-f0-9]{32}/(?:request|original-inputs|verified)\.json)"
            )
            seen = set()
            with tarfile.open(directory / backup["paths"]["state"], "r:gz") as archive:
                for entry in archive:
                    if not entry.isfile() or not pattern.fullmatch(entry.name) or entry.name in seen:
                        raise ValueError("Небезопасный архив состояния Documents")
                    seen.add(entry.name)
                    target = candidate / entry.name
                    for parent in reversed(target.parents):
                        if parent != candidate and candidate in parent.parents:
                            ensure_private(parent)
                    data = archive.extractfile(entry).read()
                    write_private(target, data.decode("utf-8"))
                    if entry.name.startswith("tls/") and entry.name != "tls/ca.key":
                        target.chmod(0o444)
            for side in ("server", "credit"):
                (candidate / "tls" / side).chmod(0o555)
            for artifact in ("identity.json", "credentials.env", "tls/ca.key"):
                if artifact not in seen:
                    raise ValueError("В копии отсутствует защищённая идентичность Documents")
            default = {"mode": "local", "epoch": 1, "receipt": None}
            if (read_record(candidate / "identity.json"),
                    valid_state(read_record(candidate / "current.json") or default)) != (backup["identity"], backup["current"]):
                raise RuntimeError("Идентичность или эпоха в копии Documents различаются")
            tls = ensure_tls(candidate, service="documents", client_uri=PEER)
            load_secrets(candidate / "credentials.env", SECRET_KEYS)
            if (digest(candidate / "credentials.env"), digest(tls / "ca.crt")) != (
                    backup["identity"]["credentials_sha256"], backup["identity"]["ca_sha256"]):
                raise RuntimeError("Реквизиты или CA отличаются от исходной идентичности Documents")
            candidate.rename(state)
            fsync_dir(state.parent)

    def restore(self, deployment, directory, backup, arguments):
        self.verify_backup(directory, backup)
        if (not self.project.startswith("cabinet-dev-restore-") or self.identity != backup["identity"]
                or self.current() != backup["current"] or self.active_request()):
            raise RuntimeError("Documents восстанавливается только в отдельный пустой Dev-проект")
        with self.state_lock(self.state):
            self.assert_writers_stopped(deployment)
            # Verify exact project-scoped names before allowing Docker to materialize new data.
            current_volumes = set(run("docker", "volume", "ls", "--format", "{{.Name}}").splitlines())
            own_volumes = {self.compose_environment()[f"CABINET_DOCUMENTS_{key}_VOLUME"] for key in ("DB", "LOCAL", "S3")}
            if current_volumes & own_volumes:
                raise RuntimeError("Целевые тома Documents уже существуют")
            self.descriptor.verify_image()
            deployment.compose(*arguments, "documents-db")
            self.volume(deployment, "db")
            with (directory / backup["paths"]["db"]).open("rb") as stream:
                checked_docker("exec", "-i", deployment.container("documents-db"), "pg_restore", "-U", "postgres",
                               "-d", "documents", "--no-owner", "--no-acl", "--exit-on-error", stdin=stream)
            self.prepare_schema(deployment)
            for key, inventory in backup["volumes"].items():
                env = self.compose_environment()
                label = f"documents-{key}-data"
                volume = env[f"CABINET_DOCUMENTS_{key.upper()}_VOLUME"]
                run("docker", "volume", "create", "--label", f"com.docker.compose.project={self.project}",
                    "--label", f"com.docker.compose.volume={label}", volume)
                self.volume(deployment, key)
                with (directory / backup["paths"][key]).open("rb") as stream:
                    checked_docker("run", "--rm", "-i", "--network", "none", "--pull", "never", "-v", volume + ":/snapshot",
                                   deployment.release.images["postgres"], "tar", "-xzf", "-", "-C", "/snapshot", stdin=stream)
                from tempfile import TemporaryDirectory
                with TemporaryDirectory(prefix="documents-restore-check-") as work:
                    if self.archive_volume(deployment, Path(work) / "check.tar.gz", key) != inventory:
                        raise RuntimeError("Том Documents после восстановления отличается от копии")
            if self.has_s3:
                deployment.compose(*arguments, "documents-s3", "seaweedfs")
            if self.gates(deployment) != stable_gates(self.current()["mode"], self.current()["epoch"]):
                raise RuntimeError("Обе БД после восстановления должны иметь исходную эпоху")
            if self.store_snapshot(deployment) != backup["snapshot"]:
                raise RuntimeError("Metadata или SHA-256 объектов Documents после восстановления отличаются")


def main():
    parser = argparse.ArgumentParser(description="Подготовить разрешённую попытку cutover Documents в cabinet-dev")
    parser.add_argument("authorize", choices=["authorize"])
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--release-manifest", required=True)
    parser.add_argument("--backup", required=True, type=Path)
    args = parser.parse_args()
    from common import Deployment
    deployment = Deployment("cabinet-dev", args.env_file, release_manifest=args.release_manifest)
    dev = DocumentsDev.open(deployment)
    if dev is None:
        parser.error("В выбранном выпуске нет активного descriptor Documents")
    attempt = dev.authorize(args.backup.resolve())
    print(f"Разрешена попытка Documents {attempt}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError) as failure:
        # Runtime commands already report fixed messages. OS/JSON errors can contain private paths.
        raise SystemExit(str(failure) if type(failure) in (RuntimeError, ValueError) else "Операция Documents не завершена") from None
