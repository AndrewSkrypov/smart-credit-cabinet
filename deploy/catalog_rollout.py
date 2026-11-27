"""Source-bound PostgreSQL provisioning and verified Catalog authority transfers."""
from contextlib import contextmanager
import fcntl
from functools import partial
from hashlib import sha256
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import NamedTemporaryFile, TemporaryDirectory
from uuid import uuid4

from catalog import ensure_private, fsync_dir, probe_mtls
from registry_release import REVISION, SHA256

ROOT = Path(__file__).resolve().parents[1]
SQL = ROOT / "deploy" / "catalog-sql"
SOURCE = ROOT / "services" / "catalog-grpc"
CUTOVER = SOURCE / "cutover"
V1 = SOURCE / "src/main/resources/db/catalog/V1__product_history.sql"
FENCE_CHECK = (SQL / "fence-check.sql").read_text()


def store(path, value, *, immutable=False):
    temporary = None
    try:
        with NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(value, stream, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        if immutable:
            os.link(temporary, path)
        else:
            temporary.replace(path)
        fsync_dir(path.parent)
    finally:
        if temporary:
            temporary.unlink(missing_ok=True)


@contextmanager
def rollout_lock(state):
    ensure_private(state)
    fd = os.open(state / "rollout.lock", os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        yield


class Operator:
    def __init__(self, deployment, work):
        values = deployment.values
        self.work = work
        self.container = deployment.container("postgres")
        self.image = deployment.release.images["postgres"]
        self.inspect_postgres(deployment)
        admin_name = values.get("POSTGRES_USER", "cabinet")
        if not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", admin_name):
            raise ValueError("Некорректная операторская роль PostgreSQL")
        profiles = (
            ("admin", "cabinet", admin_name, "POSTGRES_PASSWORD"),
            ("credit-owner", "credit", "credit_owner", "CREDIT_MIGRATION_PASSWORD"),
            ("credit-app", "credit", "credit_app", "CREDIT_DB_PASSWORD"),
            ("catalog-owner", "catalog", "catalog_owner", "CATALOG_MIGRATION_PASSWORD"),
            ("catalog-app", "catalog", "catalog_app", "CATALOG_DB_PASSWORD"),
        )
        fd = os.open(work / "pg_service.conf", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "w", encoding="ascii") as stream:
            for service, database, role, key in profiles:
                stream.write(f"[{service}]\nhost=127.0.0.1\nport=5432\nconnect_timeout=5\n"
                             f"dbname={database}\nuser={role}\npassword={values[key]}\n")
        self.base = [
            "docker", "run", "--rm", "--pull=never", "-i", "--read-only", "--tmpfs", "/tmp",
            "--cap-drop=ALL", "--security-opt", "no-new-privileges",
            "--user", f"{os.getuid()}:{os.getgid()}", "--network", f"container:{self.container}",
            "--mount", f"type=bind,src={work},dst={work}",
            "--env", f"PGSERVICEFILE={work / 'pg_service.conf'}",
            "--env", "PGOPTIONS=-c statement_timeout=600000",
            "--entrypoint", "psql",
        ]

    def inspect_postgres(self, deployment):
        result = subprocess.run(
            ["docker", "inspect", "--type", "container", "--format", "{{.State.Running}} {{.Image}}", self.container],
            check=False, capture_output=True, text=True, timeout=15,
        )
        if result.returncode or result.stdout.strip() != f"true {deployment.release.local_image_id('postgres')}":
            raise RuntimeError("PostgreSQL для переноса Catalog отличается от образа выпуска")

    def psql(self, service, directory=None, mounts=()):
        binds = [arg for path in mounts
                 for arg in ("--mount", f"type=bind,src={path},dst={path},readonly")]
        pwd = ["--workdir", str(directory)] if directory is not None else []
        return [*self.base, *binds, *pwd, self.image, "-X", "-w", "-v", "ON_ERROR_STOP=1",
                "-v", "VERBOSITY=terse", f"--dbname=service={service}"]

    def query(self, service, statement, *, args=(), directory=None, mounts=(), action="проверка PostgreSQL"):
        result = subprocess.run(
            [*self.psql(service, directory, mounts), "-Atq", *args], input=statement, text=True,
            capture_output=True, timeout=660,
        )
        if result.returncode:
            raise RuntimeError(f"Catalog: {action} не завершена") from None
        return result.stdout.strip()

    def snapshot(self, transfers):
        spec = importlib.util.spec_from_file_location("r1_snapshot", CUTOVER / "snapshot.py")
        native = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(native)
        native.psql = self.psql
        native.TemporaryDirectory = partial(TemporaryDirectory, dir=self.work)
        native.scalar = lambda service, query: self.query(service, query)
        original_save = native.save_export

        def run_sql(service, script, directory, **variables):
            self.query(service, "", directory=directory, mounts=(CUTOVER,),
                       args=(*[f"--set={key}={value}" for key, value in variables.items()],
                             "-f", str(CUTOVER / script)), action=script)

        def save_export(service, directory, database):
            checksums = original_save(service, directory, database)
            for name in (*native.FILES, native.MANIFEST):
                with (directory / name).open("rb") as stream:
                    os.fsync(stream.fileno())
            fsync_dir(directory)
            fsync_dir(directory.parent)
            return checksums

        native.run_sql = run_sql
        native.save_export = save_export
        # Only payloads under this directory may be visible to the isolated client.
        self.base.extend(["--mount", f"type=bind,src={transfers},dst={transfers}"])
        return native

    def create_database(self, values):
        passwords = (f"\\set owner_password {values['CATALOG_MIGRATION_PASSWORD']}\n"
                     f"\\set app_password {values['CATALOG_DB_PASSWORD']}\n")
        self.query("admin", passwords + (SQL / "roles.sql").read_text(), action="подготовка отдельной БД")

    def provision_database(self, values):
        self.create_database(values)
        migration = ("begin;\n"
                     "select to_regclass('catalog_cutover.authority') is null as pending \\gset\n"
                     "\\if :pending\n"
                     + V1.read_text() + "\n\\endif\ncommit;\n")
        self.query("catalog-owner", migration + (CUTOVER / "grants.sql").read_text(), action="миграция схемы Catalog")

    def restore_permissions(self):
        self.query("catalog-owner", (CUTOVER / "grants.sql").read_text(), action="восстановление прав Catalog")
        self.query("credit-owner", (SQL / "credit-permissions.sql").read_text(),
                   action="восстановление прав Credit")

    def install_credit_fence(self):
        if self.query("credit-owner", "select to_regclass('catalog_cutover.authority') is not null;") == "f":
            self.query("credit-owner", "\\set credit_app credit_app\n" + (CUTOVER / "credit-fence.sql").read_text(),
                       action="установка ограничения Credit")

    def validate_roles_and_fences(self):
        for database in ("credit", "catalog"):
            role = self.query(database + "-app", """
                select current_user = session_user and not (rolsuper or rolcreaterole or rolbypassrls)
                    and not pg_has_role(pg_get_userbyid(c.relowner), 'MEMBER')
                    and not has_table_privilege('catalog_cutover.authority', 'UPDATE')
                    and has_table_privilege('public.products', 'SELECT')
                from pg_roles cross join pg_class c
                where rolname = current_user and c.oid in ('public.products'::regclass, 'public.product_versions'::regclass)
                order by c.oid;
                """, action=f"проверка ограничений роли {database}")
            if role != "t\nt":
                raise RuntimeError(f"Catalog: у роли приложения {database} есть лишние права")
            triggers = self.query(database + "-owner", """
                select count(*) from pg_trigger where tgname = 'catalog_write_fence'
                  and tgrelid in ('public.products'::regclass, 'public.product_versions'::regclass)
                  and tgenabled in ('O', 'A') and not tgisinternal;
                """, action=f"проверка триггеров {database}")
            if triggers != "2":
                raise RuntimeError(f"Catalog: ограничители записи {database} не установлены")

    def assert_inactive_writer(self, database):
        self.query(database + "-app", FENCE_CHECK, action=f"подтверждение блокировки записи {database}")


def state_for(snapshot):
    return {database: snapshot.is_writable(database + "-owner", database) for database in ("credit", "catalog")}


def validated_journal(path):
    journal = json.loads(path.read_text()) if path.exists() else {}
    if not isinstance(journal, dict) or journal.get("active") not in (None, "credit", "catalog"):
        raise RuntimeError("Catalog: повреждён журнал передачи")
    pending = journal.get("pending")
    if pending is not None and (
        not isinstance(pending, dict) or pending.get("source") not in ("credit", "catalog")
        or pending.get("target") not in ("credit", "catalog") or pending["target"] == pending["source"]
        or not SHA256.fullmatch(str(pending.get("manifest_sha256")))
        or not REVISION.fullmatch(str(pending.get("revision")))
        or not re.fullmatch(r"transfer-[a-f0-9]{32}", str(pending.get("snapshot")))
    ):
        raise RuntimeError("Catalog: повреждён журнал передачи")
    return journal


def finish_transfer(operator, snapshot, journal_path, journal, pending, directory, durable):
    checksums = snapshot.checked_manifest(directory)
    for database in ("credit", "catalog"):
        snapshot.verify(database + "-owner", database, checksums)
    flags = state_for(snapshot)
    if flags != {pending["source"]: False, pending["target"]: True}:
        raise RuntimeError("Catalog: передача не подтвердила единственного писателя")
    operator.assert_inactive_writer(pending["source"])
    hashes = {name: checksum for checksum, name in (line.split() for line in checksums.splitlines())}
    receipt = {
        "transfer_id": pending["snapshot"],
        "from": pending["source"], "to": pending["target"],
        "manifest_sha256": pending["manifest_sha256"], "revision": pending["revision"],
        "history_sha256": sha256(checksums.encode("ascii")).hexdigest(),
        "snapshot_sha256": hashes,
    }
    try:
        store(durable, receipt, immutable=True)
    except FileExistsError:
        if durable.is_symlink() or not durable.is_file() or json.loads(durable.read_text()) != receipt:
            raise RuntimeError("Catalog: квитанция этой попытки содержит другую историю") from None
        fsync_dir(durable.parent)
    journal.update(active=pending["target"], last_transfer=receipt)
    journal.pop("pending", None)
    store(journal_path, journal)
    shutil.rmtree(directory)


def reconcile(deployment, operator, snapshot, journal_path):
    desired = "catalog" if deployment.catalog_mode == "remote" else "credit"
    flags = state_for(snapshot)
    if all(flags.values()):
        raise RuntimeError("Catalog: оба писателя открыты")
    journal = validated_journal(journal_path)
    pending = journal.get("pending")
    if pending:
        if (pending["target"] != desired or pending["revision"] != deployment.release.revision
                or pending["manifest_sha256"] != deployment.release.checksum):
            raise RuntimeError("Catalog: сначала повторите manifest незавершённой передачи")
        if flags[desired]:
            directory = journal_path.parent / "transfers" / pending["snapshot"]
            receipts = ensure_private(journal_path.parent / "receipts")
            finish_transfer(operator, snapshot, journal_path, journal, pending, directory,
                            receipts / (directory.name + ".json"))
            return
    elif flags[desired]:
        if journal.get("active") not in (None, desired) or (
            desired == "catalog" and journal.get("last_transfer", {}).get("to") != desired
        ):
            raise RuntimeError("Catalog: нет квитанции смены владельца")
        operator.assert_inactive_writer("credit" if desired == "catalog" else "catalog")
        if journal.get("active") is None and desired == "credit":
            directory = operator.work / "shadow"
            hashes = snapshot.save_export("credit-owner", directory, "credit")
            snapshot.run_sql("catalog-owner", "import.sql", directory, expected_database="catalog")
            snapshot.verify("catalog-owner", "catalog", hashes)
        journal["active"] = desired
        store(journal_path, journal)
        return
    elif not any(flags.values()):
        raise RuntimeError("Catalog: оба писателя закрыты без известной незавершённой передачи")
    else:
        source = "credit" if desired == "catalog" else "catalog"
        if journal.get("active") not in (None, source) or (
            source == "catalog" and journal.get("last_transfer", {}).get("to") != source
        ):
            raise RuntimeError("Catalog: исходный писатель не подтверждён журналом")
        pending = {"source": source, "target": desired, "revision": deployment.release.revision,
                   "manifest_sha256": deployment.release.checksum}

    transfers = journal_path.parent / "transfers"
    old_directory = transfers / pending["snapshot"] if pending.get("snapshot") else None
    directory = transfers / f"transfer-{uuid4().hex}"
    receipts = ensure_private(journal_path.parent / "receipts")
    durable = receipts / (directory.name + ".json")
    if directory.exists() or directory.is_symlink() or durable.exists() or durable.is_symlink():
        raise RuntimeError("Catalog: имя новой попытки уже занято")
    operator.assert_inactive_writer(desired)
    pending["snapshot"] = directory.name
    journal["pending"] = pending
    store(journal_path, journal)
    if old_directory is not None and old_directory.is_dir():
        shutil.rmtree(old_directory)
    try:
        snapshot.switch("credit-owner", "catalog-owner", directory, desired)
        finish_transfer(operator, snapshot, journal_path, journal, pending, directory, durable)
    except (SystemExit, subprocess.SubprocessError):
        raise RuntimeError("Catalog: перенос не завершён; повторите тот же manifest") from None


def rollout(deployment):
    state = deployment.catalog_state
    with rollout_lock(state), TemporaryDirectory(prefix=".operator-", dir=state) as work:
        operator = Operator(deployment, Path(work))
        operator.provision_database(deployment.values)
        operator.install_credit_fence()
        operator.validate_roles_and_fences()
        snapshot = operator.snapshot(ensure_private(state / "transfers"))
        try:
            reconcile(deployment, operator, snapshot, state / "authority.json")
        except (SystemExit, subprocess.SubprocessError):
            raise RuntimeError("Catalog: не подтверждена передача единственному писателю") from None


def verify_runtime(deployment):
    expected = "catalog" if deployment.catalog_mode == "remote" else "credit"
    journal = validated_journal(deployment.catalog_state / "authority.json")
    if journal.get("active") != expected or journal.get("pending"):
        raise RuntimeError("Catalog: незавершённая или неподтверждённая смена владельца")
    active = {database: deployment.sql(database, "select writable from catalog_cutover.authority where singleton")
              for database in ("credit", "catalog")}
    if active != {"credit": "t" if expected == "credit" else "f",
                  "catalog": "t" if expected == "catalog" else "f"}:
        raise RuntimeError("Catalog: runtime не подтвердил единственного писателя")

    credit = deployment.container("credit")
    mode = subprocess.run(["docker", "exec", credit, "printenv", "CABINET_CATALOG_MODE"],
                          capture_output=True, text=True, timeout=12)
    if mode.returncode or mode.stdout.strip() != deployment.catalog_mode:
        raise RuntimeError("Catalog: режим запущенного Credit отличается от manifest")
    probe_mtls(["docker", "exec", "-i", credit], deployment.catalog_mode)
    return {"mode": deployment.catalog_mode, "writer": expected, "mtls": True,
            "jar_sha256": deployment.release.catalog["jar_sha256"],
            "last_transfer": journal.get("last_transfer")}
