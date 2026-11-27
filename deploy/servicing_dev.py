"""Private Servicing candidate and recoverable Credit writer transfer on cabinet-dev."""
from dataclasses import dataclass
from hashlib import sha256
from importlib.util import module_from_spec, spec_from_file_location
import os
from pathlib import Path
import re
import secrets
import subprocess

from runtime_attempts import require_servicing_authorization
from runtime_delivery_support import (
    Descriptor, ensure_private, ensure_tls, fsync_dir, load_secrets, private_state,
    read_record, run, verify_container, write_record,
)

ROOT = Path(__file__).resolve().parent.parent
OVERLAY = ROOT / "deploy/compose.servicing.yaml"
TRANSFER = ROOT / "services/servicing/cutover/transfer.py"
PROBE = ROOT / "deploy/servicing_probe.sh"
SECRET_KEYS = (
    "CABINET_SERVICING_PG_ADMIN_PASSWORD",
    "CABINET_SERVICING_PG_OWNER_PASSWORD",
    "CABINET_SERVICING_PG_APP_PASSWORD",
    "CABINET_SERVICING_GENERATION",
)
CLIENT_URI = "spiffe://creditcabinet/credit"
UP = ("up", "-d", "--wait", "--wait-timeout", "300", "--no-build")
ATTEMPT_ID = re.compile(r"[0-9a-f]{32}")
DIGEST = re.compile(r"[0-9a-f]{64}")


def _read(path):
    return read_record(path) if path.exists() or path.is_symlink() else None


def _save_once(path, value):
    written = _read(path)
    if written is None:
        write_record(path, value, immutable=True)
    elif written != value:
        raise RuntimeError(f"Запись Servicing не совпадает с квитанцией {path.name}")
    return value


def _stamp(writer, epoch, digest=None):
    return {"writer": writer, "epoch": epoch, "manifest_sha256": digest}


def _fence(transfer, subject):
    raw = transfer.fence_info(subject)
    writer, epoch, digest = (raw[key] for key in ("writer", "epoch", "manifest_sha256"))
    if writer not in {"LOCAL", "REMOTE", "FENCED"} or type(epoch) is not int or epoch < 1:
        raise RuntimeError("Некорректная запись владельца Servicing")
    if digest is not None and (not isinstance(digest, str) or not DIGEST.fullmatch(digest)):
        raise RuntimeError("Некорректная контрольная сумма владельца Servicing")
    return _stamp(writer, epoch, digest)


def _fences(transfer):
    return {database: _fence(transfer, database) for database in ("credit", "servicing")}


def _snapshot(transfer, directory, name, data):
    return _save_once(directory / name, {"schemaVersion": 1, "sha256": transfer.checksum(data), "tables": data})


def _load_snapshot(transfer, path):
    record = _read(path)
    if (not isinstance(record, dict) or set(record) != {"schemaVersion", "sha256", "tables"}
            or record["schemaVersion"] != 1 or not isinstance(record["tables"], dict)
            or set(record["tables"]) != set(transfer.TABLES)
            or record["sha256"] != transfer.checksum(record["tables"])):
        raise RuntimeError("Повреждён снимок переноса Servicing")
    return record


def _set_up_transfer(deployment):
    spec = spec_from_file_location("cabinet_servicing_transfer", TRANSFER)
    transfer = module_from_spec(spec)
    spec.loader.exec_module(transfer)
    containers = {
        "credit": (deployment.container("postgres"), deployment.values.get("POSTGRES_USER", "cabinet")),
        "servicing": (deployment.container("servicing-db"), "postgres"),
    }

    def owner_arguments(database):
        container, admin = containers[database]
        return [
            "docker", "exec", "-i", container, "psql", "-XAtqw",
            "-U", admin, "-d", database, "-v", "ON_ERROR_STOP=1",
            "-c", "SET log_statement='none'; SET log_min_error_statement='panic';",
            "-c", f"SET ROLE {database}_owner", "-f", "-",
        ]

    # The upstream cutover lock opens a second persistent owner session. Both that
    # session and one-shot queries must go to the same validated project containers.
    transfer.args_for = owner_arguments
    return transfer


def _require_drained(transfer):
    if transfer.sql("credit", "select count(*) from servicing_open_outbox where state <> 'CONFIRMED';") != "0":
        raise RuntimeError("Credit должен завершить или согласовать неподтверждённые открытия перед переносом Servicing")
    if transfer.sql("credit", "select count(*) from pipeline_jobs where step='DISBURSEMENT' and status='RUNNING';") != "0":
        raise RuntimeError("До переноса Servicing согласуйте незавершённые вызовы АБС в Credit")


def _require_authority(transfer, mode, fences):
    credit, servicing = (fences[key] for key in ("credit", "servicing"))
    if mode == "local":
        valid = credit["writer"] == "LOCAL" and servicing["writer"] == "FENCED"
    else:
        valid = (credit["writer"] == servicing["writer"] == "REMOTE"
                 and credit["manifest_sha256"] is not None
                 and credit["manifest_sha256"] == servicing["manifest_sha256"])
    if not valid:
        raise RuntimeError("Credit и Servicing не подтверждают записанного владельца финансовых данных")
    transfer.require_grants("credit", mode == "local")
    transfer.require_grants("servicing", mode == "remote")


def _retire(transfer, database, prior):
    expected = _stamp("FENCED", prior["epoch"] + 1, prior["manifest_sha256"])
    current = _fence(transfer, database)
    if current == prior:
        transfer.fence(database)
        current = _fence(transfer, database)
    if current != expected:
        raise RuntimeError("Эпоха блокировки Servicing изменилась во время переноса")
    transfer.require_grants(database, False)
    return expected


def _finish_attempt(transfer, mode, initial, directory):
    source, target = ("credit", "servicing") if mode == "remote" else ("servicing", "credit")
    source_writer = _fence(transfer, source)
    target_writer = _fence(transfer, target)
    before_path = directory / "target-before.json"
    source_path = directory / "source-fenced.json"
    verified_path = directory / "verified.json"
    completed_path = directory / "completed.json"
    if not (before_path.exists() or before_path.is_symlink()):
        if {source: source_writer, target: target_writer} != initial:
            raise RuntimeError("До блокировки Servicing отсутствует исходный снимок цели")
        _snapshot(transfer, directory, before_path.name, transfer.snapshot(target))
    before = _load_snapshot(transfer, before_path)
    source_fenced = _stamp("FENCED", initial[source]["epoch"] + 1, initial[source]["manifest_sha256"])
    target_fenced = _stamp("FENCED", initial[target]["epoch"] + 1, initial[target]["manifest_sha256"])
    if not (source_path.exists() or source_path.is_symlink()):
        if source_writer not in (initial[source], source_fenced) or target_writer not in (initial[target], target_fenced):
            raise RuntimeError("Невозможно продолжить неполную блокировку Servicing")
        _retire(transfer, source, initial[source])
        _retire(transfer, target, initial[target])
        _snapshot(transfer, directory, source_path.name, transfer.snapshot(source))
    document = _load_snapshot(transfer, source_path)
    digest = document["sha256"]
    active = _stamp(mode.upper(), target_fenced["epoch"] + 1, digest)
    retired = _stamp("REMOTE", source_fenced["epoch"] + 1, digest) if mode == "remote" else source_fenced
    checks = {"sha256": digest, "counts": {name: len(rows) for name, rows in document["tables"].items()}}

    source_writer, target_writer = _fence(transfer, source), _fence(transfer, target)
    if source_writer not in (source_fenced, retired) or transfer.checksum(transfer.snapshot(source)) != digest:
        raise RuntimeError("Заблокированный источник Servicing изменился после снимка")
    transfer.require_grants(source, False)
    if target_writer == target_fenced and source_writer == source_fenced:
        transfer.require_grants(target, False)
        observed = transfer.checksum(transfer.snapshot(target))
        if observed not in {before["sha256"], digest}:
            raise RuntimeError("Заблокированная цель Servicing содержит более новые или неожиданные изменения")
        if observed != digest:
            transfer.import_snapshot(target, document["tables"])
            if transfer.checksum(transfer.snapshot(target)) != digest:
                raise RuntimeError("Контрольные суммы импорта Servicing различаются, обе стороны заблокированы")
        _save_once(verified_path, checks)
        transfer.activate(target, mode.upper(), digest)
    elif target_writer != active or _read(verified_path) != checks:
        raise RuntimeError("Активная цель Servicing не подтверждена квитанцией сверки импорта")
    # On post-activation recovery the live target may contain new legitimate changes.
    # Its acceptance marker and grants are authoritative. Never replay the import over it.
    transfer.require_grants(target, True)
    transfer.finish_credit_marker("credit", mode, digest)
    fences = _fences(transfer)
    if fences != {source: retired, target: active}:
        raise RuntimeError("Не удалось согласовать владельца после активации Servicing")
    _require_authority(transfer, mode, fences)
    return _save_once(completed_path, {**checks, "mode": mode, "fences": fences})


def _aggregates(transfer):
    result = {}
    for database in ("credit", "servicing"):
        data = transfer.snapshot(database)
        result[database] = {
            "sha256": transfer.checksum(data),
            "counts": {name: len(rows) for name, rows in data.items()},
        }
    return result


@dataclass
class ServicingDev:
    descriptor: Descriptor
    state: Path
    tls: Path
    secrets: dict
    project: str = "cabinet-dev"
    authorized_mode: str | None = None

    @classmethod
    def open(cls, deployment):
        state = private_state(deployment, "servicing")
        if state is None:
            return None
        descriptor = Descriptor.from_release(deployment.release, "servicing")
        if descriptor is None or descriptor.mode == "disabled":
            if state.exists() or state.is_symlink():
                raise RuntimeError("После регистрации Servicing нужен защищённый manifest с режимом local или remote")
            return None
        state = ensure_private(state)
        env = state / "servicing.env"
        registered = any(os.path.lexists(state / name) for name in ("database.json", "authority.json", "pending.json"))
        if registered and (not env.is_file() or not (state / "tls").is_dir()):
            raise RuntimeError("Утрачены приватный ENV или TLS-идентичность Servicing")
        secret_values = load_secrets(env, SECRET_KEYS, deployment.values)
        tls = ensure_tls(state, service="servicing", client_uri=CLIENT_URI)
        return cls(descriptor, state, tls, secret_values, project=deployment.project)

    @property
    def generation(self):
        return self.secrets["CABINET_SERVICING_GENERATION"]

    @property
    def pending(self):
        return self.state / "pending.json"

    @property
    def database_identity(self):
        return _read(self.state / "database.json")

    @property
    def volume_name(self):
        return f"{self.project}-servicing-{self.generation}"

    def _tls_identity(self):
        return {key: sha256((self.tls / path).read_bytes()).hexdigest() for key, path in (
            ("ca_sha256", "server/ca.crt"), ("server_certificate_sha256", "server/tls.crt"),
            ("credit_certificate_sha256", "credit/tls.crt"),
        )}

    def provenance(self):
        return {
            "revision": self.descriptor.revision,
            "manifest_sha256": self.descriptor.manifest_sha256,
            "image": self.descriptor.image,
            "jar_sha256": self.descriptor.jar_sha256,
            "generation": self.generation,
        }

    def compose_environment(self):
        return {
            **self.secrets,
            "CABINET_SERVICING_TLS_DIR": str(self.tls),
            "CABINET_SERVICING_VOLUME": self.volume_name,
            "CABINET_SERVICING_CREDIT_MODE": "local",
        }

    def credit_environment(self):
        if self.authorized_mode is None:
            raise RuntimeError("Режим Credit ещё не согласован с владельцем Servicing")
        return {"CABINET_SERVICING_CREDIT_MODE": self.authorized_mode}

    def _require_volume_config(self, deployment):
        if deployment.project != self.project or deployment.environment.get("CABINET_SERVICING_VOLUME") != self.volume_name:
            raise RuntimeError("Настройки Compose требуют собственный том PostgreSQL Servicing для выбранного проекта")

    def _observe_database(self, deployment):
        image = deployment.release.local_image_id("postgres")
        verify_container(deployment, "servicing-db", image, ready=True)
        volume = self.volume_name
        container = deployment.container("servicing-db")
        mounted = run("docker", "inspect", "--format",
                      '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}',
                      container)
        if mounted != volume:
            raise RuntimeError("Контейнер PostgreSQL Servicing подключил том вне поколения проекта")
        labels = run("docker", "volume", "inspect", "--format",
                     '{{index .Labels "com.docker.compose.project"}} {{index .Labels "com.docker.compose.volume"}}', volume)
        if labels != f"{deployment.project} servicing-data":
            raise RuntimeError("Том PostgreSQL Servicing принадлежит другому проекту")
        system_id = run(
            "docker", "exec", container, "psql", "-XAtqw",
            "-U", "postgres", "-d", "servicing", "-v", "ON_ERROR_STOP=1",
            "-c", "select system_identifier from pg_control_system()",
        ).strip()
        if not re.fullmatch(r"[0-9]{1,20}", system_id):
            raise RuntimeError("Не удалось подтвердить идентичность PostgreSQL Servicing")
        return {"generation": self.generation, "pg_system_identifier": system_id, **self._tls_identity()}

    def _check_database(self, deployment):
        self._require_volume_config(deployment)
        observed = self._observe_database(deployment)
        identity = self.database_identity
        if identity is None or any(identity.get(key) != value for key, value in observed.items()):
            raise RuntimeError("Идентичность PostgreSQL или сертификатов Servicing изменилась")
        return identity

    def prepare(self, deployment, up):
        self.descriptor.verify_image()
        self._require_volume_config(deployment)
        self._check_pending()
        identity = self.database_identity
        if identity is not None:
            if identity["generation"] != self.generation or any(identity.get(key) != value for key, value in self._tls_identity().items()):
                raise RuntimeError("Поколение PostgreSQL или сертификаты Servicing изменились")
            # Never let Compose silently create a new empty database for an enrolled generation.
            run("docker", "volume", "inspect", self.volume_name)
        elif deployment.project != "cabinet-dev":
            raise RuntimeError("Новый проект восстановления требует подтверждённой копии базы Servicing")
        elif _read(self.state / "authority.json") is not None or _read(self.pending) is not None:
            raise RuntimeError("У зарегистрированного владельца отсутствует идентичность PostgreSQL Servicing")
        deployment.compose(*up, "servicing-db")
        if identity is not None:
            return self._check_database(deployment)
        return _save_once(self.state / "database.json", self._observe_database(deployment))

    def _verify_candidate(self, deployment, *, running_credit):
        image = verify_container(deployment, "servicing", self.descriptor.verify_image(), ready=True)
        arguments = ("exec", "-T", "credit", "sh", "-s") if running_credit else (
            "run", "--rm", "--no-deps", "-T", "--entrypoint", "sh", "credit", "-s",
        )
        run(*deployment.command, *arguments, stdin=PROBE.read_text(encoding="utf-8"),
            env=deployment.environment, timeout=40)
        return image

    def _attempt_directory(self, attempt):
        if not isinstance(attempt, str) or not ATTEMPT_ID.fullmatch(attempt):
            raise RuntimeError("Некорректный идентификатор попытки переноса Servicing")
        path = self.state / "attempts" / attempt
        if path.is_symlink() or not path.is_dir():
            raise RuntimeError("Отсутствует каталог попытки переноса Servicing")
        ensure_private(path.parent)
        return ensure_private(path)

    def _authority(self, transfer):
        authority = _read(self.state / "authority.json")
        fences = _fences(transfer)
        if authority is None:
            if fences != {"credit": _stamp("LOCAL", 1), "servicing": _stamp("FENCED", 1)}:
                raise RuntimeError("У существующего владельца Servicing отсутствует квитанция")
            authority = {"mode": "local", "attempt": None, "fences": fences}
        if authority["mode"] not in {"local", "remote"} or authority["fences"] != fences:
            raise RuntimeError("Фактический владелец Servicing отличается от записанного")
        if authority["attempt"] is not None:
            directory = self._attempt_directory(authority["attempt"])
            completed = _read(directory / "completed.json")
            attempt = _read(directory / "attempt.json")
            target = "credit" if authority["mode"] == "local" else "servicing"
            if (not completed or not attempt or attempt.get("generation") != self.generation
                    or attempt.get("direction") != authority["mode"]
                    or completed.get("mode") != authority["mode"] or completed.get("fences") != fences
                    or completed.get("sha256") != fences[target]["manifest_sha256"]
                    or _read(directory / "verified.json") != {key: completed.get(key) for key in ("sha256", "counts")}):
                raise RuntimeError("Квитанция последнего принятого переноса Servicing отсутствует или изменилась")
        elif fences != {"credit": _stamp("LOCAL", 1), "servicing": _stamp("FENCED", 1)}:
            raise RuntimeError("Смену владельца Servicing должна подтверждать квитанция переноса")
        _require_authority(transfer, authority["mode"], fences)
        return authority

    def _check_pending(self):
        pending = _read(self.pending)
        if pending is not None and (pending.get("provenance") != self.provenance() or pending.get("direction") != self.descriptor.mode):
            raise RuntimeError("Незавершённую попытку Servicing продолжает тот же защищённый manifest")
        return pending

    def _switch_authority(self, transfer, authorize):
        mode = self.descriptor.mode
        pending = self._check_pending()
        if pending is None:
            authority = self._authority(transfer)
            if authority["mode"] == mode:
                _save_once(self.state / "authority.json", authority)
                return authority
            binding = authorize(authority["mode"])
            _require_drained(transfer)
            attempt = secrets.token_hex(16)
            directory = ensure_private(ensure_private(self.state / "attempts") / attempt)
            detail = {**self.provenance(), "direction": mode, "initial": authority["fences"], "authorization": binding}
            write_record(directory / "attempt.json", detail, immutable=True)
            write_record(self.pending, {"attempt": attempt, "provenance": self.provenance(), "direction": mode}, immutable=True)
        else:
            binding = authorize("local" if mode == "remote" else "remote")
            attempt = pending["attempt"]
            directory = self._attempt_directory(attempt)
            detail = _read(directory / "attempt.json")
            if not detail or detail != {**self.provenance(), "direction": mode, "initial": detail.get("initial"),
                                        "authorization": binding}:
                raise RuntimeError("Изменилась начальная квитанция попытки Servicing")
        _require_drained(transfer)
        completed = _finish_attempt(transfer, mode, detail["initial"], directory)
        authority = {"mode": mode, "attempt": attempt, "fences": completed["fences"]}
        write_record(self.state / "authority.json", authority)
        self.pending.unlink()
        fsync_dir(self.state)
        return authority

    def switch(self, deployment):
        self.authorized_mode = None
        self._check_pending()
        if deployment.compose("ps", "-q", "caddy", "credit", capture=True).strip():
            raise RuntimeError("Перед Servicing остановите внешние запросы и дождитесь завершения всех задач Credit")
        self._check_database(deployment)
        deployment.compose(*UP, "valkey")
        deployment.compose("stop", "-t", "150", "servicing")
        deployment.compose("run", "--rm", "--no-deps", "servicing-migrate")
        deployment.compose(*UP, "--no-deps", "servicing")
        self._verify_candidate(deployment, running_credit=False)
        deployment.compose("stop", "-t", "150", "servicing")
        transfer = _set_up_transfer(deployment)
        try:
            transfer.validate_databases("credit", "servicing")
            with transfer.control_lock("credit"):
                authority = self._switch_authority(transfer, lambda before: require_servicing_authorization(deployment, before))
        except (subprocess.CalledProcessError, SystemExit) as error:
            raise RuntimeError("Перенос Servicing не завершён, проверьте записи попытки и состояние владельца") from error
        deployment.compose(*UP, "--no-deps", "servicing")
        self._verify_candidate(deployment, running_credit=False)
        self.authorized_mode = authority["mode"]
        return self.credit_environment()

    def verify_credit(self, deployment):
        if _read(self.pending) is not None:
            raise RuntimeError("Незавершённая попытка Servicing блокирует готовность Credit")
        identity = self._check_database(deployment)
        transfer = _set_up_transfer(deployment)
        try:
            transfer.validate_databases("credit", "servicing")
            authority = self._authority(transfer)
        except (subprocess.CalledProcessError, SystemExit) as error:
            raise RuntimeError("Не удалось подтвердить фактического владельца Servicing") from error
        mode = self.descriptor.mode
        if _read(self.state / "authority.json") is None or authority["mode"] != mode:
            raise RuntimeError("Режим manifest и согласованный режим Credit различаются")
        deployment.compose("exec", "-T", "credit", "sh", "-ec", 'test "$SERVICING_MODE" = "$1"', "mode", mode)
        image = self._verify_candidate(deployment, running_credit=True)
        result = {
            **self.provenance(),
            **identity,
            **authority,
            "image_id": image,
            "credit_mtls": True,
            "by_application_requires_delegated_jwt": True,
            "runtime_ready": True,
        }
        receipt = self.state / "ready"
        ensure_private(receipt)
        attempt = authority["attempt"] or "initial-local"
        return _save_once(receipt / f"{self.descriptor.manifest_sha256}-{attempt}.json", result)

    def backup(self, deployment, directory):
        if _read(self.pending) is not None:
            raise RuntimeError("Копия Servicing требует завершённого переноса")
        if deployment.compose("ps", "-q", "credit", "servicing", capture=True).strip():
            raise RuntimeError("Для согласованной копии остановите Credit и Servicing")
        identity = self._check_database(deployment)
        transfer = _set_up_transfer(deployment)
        try:
            transfer.validate_databases("credit", "servicing")
            with transfer.control_lock("credit"):
                authority = self._authority(transfer)
                if authority["mode"] != self.descriptor.mode:
                    raise RuntimeError("Копия Servicing требует manifest фактического владельца")
                aggregates = _aggregates(transfer)
                path = directory / "servicing.dump"
                fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                with os.fdopen(fd, "wb") as stream:
                    result = subprocess.run(
                        ["docker", "exec", deployment.container("servicing-db"), "pg_dump", "-U", "postgres",
                         "-d", "servicing", "-Fc", "--no-owner", "--no-acl"],
                        stdout=stream, stderr=subprocess.DEVNULL, timeout=180, check=False,
                    )
                    if result.returncode:
                        raise RuntimeError("Не получена согласованная копия PostgreSQL Servicing")
                    stream.flush()
                    os.fsync(stream.fileno())
        except (subprocess.CalledProcessError, SystemExit) as error:
            raise RuntimeError("Не удалось зафиксировать состояние владельца для копии Servicing") from error
        return {**self.provenance(), **identity, **authority, "aggregates": aggregates}

    def restore(self, deployment, directory, backup, up=UP):
        """Restore a validated dump and bind it to a fresh, separately named Dev volume."""
        if not deployment.project.startswith("cabinet-dev-restore-") or self.database_identity is not None:
            raise RuntimeError("Servicing восстанавливается только в новый изолированный проект Dev")
        self._require_volume_config(deployment)
        expected = {**self.provenance(), **self._tls_identity(), "mode": self.descriptor.mode}
        if any(backup.get(key) != value for key, value in expected.items()):
            raise RuntimeError("В восстановленной конфигурации Servicing изменились выпуск, CA или поколение")
        authority = {key: backup[key] for key in ("mode", "attempt", "fences")}
        old_system_id = backup.get("pg_system_identifier")
        if not isinstance(old_system_id, str) or not re.fullmatch(r"[0-9]{1,20}", old_system_id):
            raise RuntimeError("Копия Servicing не содержит идентичность исходной БД")
        if _read(self.pending) is not None or _read(self.state / "authority.json") != authority:
            raise RuntimeError("Приватная квитанция восстановленного владельца Servicing отличается от копии")
        if deployment.compose("ps", "-a", "-q", "credit", "servicing", "servicing-db", capture=True).strip():
            raise RuntimeError("Целевой проект восстановления уже содержит контейнеры Servicing или Credit")
        if self.volume_name in run("docker", "volume", "ls", "--format", "{{.Name}}").splitlines():
            raise RuntimeError("Целевой том Servicing уже существует")

        self.descriptor.verify_image()
        deployment.compose(*up, "servicing-db")
        identity = self._observe_database(deployment)
        if identity["pg_system_identifier"] == old_system_id:
            raise RuntimeError("Для восстановления нужна отдельная физическая БД Servicing")
        with (directory / "servicing.dump").open("rb") as dump:
            restored = subprocess.run(
                ["docker", "exec", "-i", deployment.container("servicing-db"), "pg_restore", "-U", "postgres",
                 "--role=servicing_owner", "-d", "servicing", "--no-owner", "--no-acl",
                 "--clean", "--if-exists", "--single-transaction"],
                stdin=dump, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180, check=False,
            )
        if restored.returncode:
            raise RuntimeError("Логическая копия Servicing не восстановлена")
        deployment.compose("run", "--rm", "--no-deps", "servicing-migrate")
        transfer = _set_up_transfer(deployment)
        try:
            transfer.validate_databases("credit", "servicing")
            with transfer.control_lock("credit"):
                if self._authority(transfer) != authority or _aggregates(transfer) != backup.get("aggregates"):
                    raise RuntimeError("После восстановления Credit и Servicing отличаются владельцы, количества или SHA-256")
        except (subprocess.CalledProcessError, SystemExit):
            raise RuntimeError("Не удалось проверить владельца восстановленной копии Servicing") from None
        return _save_once(self.state / "database.json", {
            **identity, "restored_from_pg_system_identifier": old_system_id,
        })
