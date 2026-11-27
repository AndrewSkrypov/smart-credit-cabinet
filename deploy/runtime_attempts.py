"""Bind each multi-service rollout and ownership change to its protected pre-migration copy."""
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import stat
from uuid import uuid4

from backup_support import backup_archives, digest
from runtime_delivery_support import ensure_private, fsync_dir, read_private, read_record, write_private, write_record


def attempt_id(pointer):
    identifier = pointer.get("attempt") if pointer else None
    if not isinstance(identifier, str) or len(identifier) != 32 or any(character not in "0123456789abcdef" for character in identifier):
        raise RuntimeError("Нет подтверждённой записи попытки общего выпуска")
    return identifier


def enrollment(state):
    pointer = read_record(state / "runtime-enrollment.json")
    if pointer is None:
        return None
    identifier = attempt_id(pointer)
    entry = read_record(state / "runtime-attempts" / (identifier + "-enrollment.json"))
    if not entry or entry.get("attempt") != identifier:
        raise RuntimeError("Не найдена неизменяемая запись регистрации общего выпуска")
    return entry


def prepared_enrollment(state, previous, target, original_env, target_env, *, fresh_backup=False):
    directory = ensure_private(state / "runtime-attempts")
    identifier = uuid4().hex
    entry = {"attempt": identifier, "previous": previous, "target": target,
             "original_env_sha256": sha256(original_env).hexdigest(), "target_env_sha256": sha256(target_env).hexdigest()}
    if fresh_backup:
        require_fresh_backup(state, entry)
    if original_env != target_env:
        write_private(directory / (identifier + "-enrollment.env"), target_env.decode("utf-8"), exclusive=True)
    write_record(directory / (identifier + "-enrollment.json"), entry, immutable=True)
    return entry


def enroll(state, previous, target, original_env, target_env):
    if enrollment(state) is not None:
        raise RuntimeError("У проекта уже есть регистрация общего выпуска")
    entry = prepared_enrollment(state, previous, target, original_env, target_env)
    identifier = entry["attempt"]
    write_record(state / "runtime-enrollment.json", {"attempt": identifier}, immutable=True)
    return entry


def enrolled_env(state, entry, contents):
    observed = sha256(contents).hexdigest()
    if observed == entry["target_env_sha256"]:
        return contents
    if observed == entry["original_env_sha256"]:
        saved = read_private(state / "runtime-attempts" / (entry["attempt"] + "-enrollment.env"))
        if saved is not None and sha256(saved.encode("utf-8")).hexdigest() == entry["target_env_sha256"]:
            return saved.encode("utf-8")
    raise RuntimeError("Приватный ENV изменился после регистрации общего выпуска")


def enrollment_backup(state, entry):
    previous, target = (entry[key] for key in ("previous", "target"))
    return (state / "backups" / entry["attempt"] / "backup"
            if previous and previous["revision"] != target["revision"] else None)


def require_fresh_backup(state, entry):
    backup = enrollment_backup(state, entry)
    if backup is None or not absent(backup.parent):
        raise RuntimeError("Путь новой согласованной копии уже занят или не подтверждён")


def absent(path):
    try:
        path.lstat()
    except FileNotFoundError:
        return True
    return False


def private_directory(path):
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o022:
        raise RuntimeError("Каталог восстановления требует обычный закрытый путь оператора")


def stamped_record(path):
    raw = read_private(path)
    if raw is None:
        raise RuntimeError("Не найдена подтверждённая запись восстановления")
    record = json.loads(raw)
    if not isinstance(record, dict):
        raise RuntimeError("Некорректная запись восстановления")
    return record, sha256(raw.encode("utf-8")).hexdigest()


def prebackup_enrolled_env(state, entry, original_env):
    saved = read_private(state / "runtime-attempts" / (attempt_id(entry) + "-enrollment.env"))
    if saved is not None and sha256(saved.encode("utf-8")).hexdigest() != entry["target_env_sha256"]:
        raise RuntimeError("Сохранённый ENV регистрации восстановления изменился")
    return enrolled_env(state, entry, original_env)


def prebackup_context(state, entry, current, original_env):
    """Read-only guards; the caller holds the ordinary deployment lock."""
    state = Path(state)
    if not state.is_absolute() or state.resolve(strict=True) != state:
        raise RuntimeError("Состояние восстановления не допускает ссылки и неоднозначные пути")
    private_directory(state)
    directory = state / "runtime-attempts"
    private_directory(directory)
    identifier = attempt_id(entry)
    stored, enrollment_hash = stamped_record(directory / (identifier + "-enrollment.json"))
    if stored != entry or read_record(state / "runtime-enrollment.json") != {"attempt": identifier}:
        raise RuntimeError("Активная регистрация восстановления изменилась")
    if (not current or read_record(state / "current.json") != current or entry.get("previous") != current
            or not re.fullmatch(r"[a-f0-9]{40}", current.get("revision", ""))
            or not re.fullmatch(r"[a-f0-9]{64}", current.get("manifest_sha256", ""))):
        raise RuntimeError("Исходный выпуск восстановления изменился")
    contents = read_private(state / "cabinet.env")
    original_hash = sha256(original_env).hexdigest()
    if (contents is None or contents.encode("utf-8") != original_env
            or entry.get("original_env_sha256") != original_hash):
        raise RuntimeError("Исходный ENV восстановления изменился")
    # Also preserve and verify the old private command input; never repurpose its secrets.
    prebackup_enrolled_env(state, entry, original_env)
    if (not absent(state / "pending.json") or not absent(state / "runtime-attempt.json")
            or not absent(directory / (identifier + ".json"))):
        raise RuntimeError("Начатую попытку выпуска нельзя закрыть как сбой резервирования")
    allowed = {identifier + suffix for suffix in ("-enrollment.json", "-enrollment.env", "-prebackup-abort.json")}
    if any(path.name.startswith(identifier) and path.name not in allowed for path in directory.iterdir()):
        raise RuntimeError("Найдены дополнительные квитанции начатой попытки")
    backup = enrollment_backup(state, entry)
    if backup is None:
        raise RuntimeError("Не подтверждён отдельный резерв исходного выпуска")
    for path in (state / "backups", backup.parent, backup):
        private_directory(path)
    def inaccessible(error):
        raise error

    for base, directories, files in os.walk(backup, followlinks=False, onerror=inaccessible):
        for name in directories + files:
            kind = (Path(base) / name).lstat().st_mode
            if not (stat.S_ISDIR(kind) or stat.S_ISREG(kind)):
                raise RuntimeError("Частичный резерв содержит ссылку или неизвестный тип файла")
    archive, archive_hash = stamped_record(backup / "manifest.json")
    if (archive.get("source_project") != "cabinet-dev" or archive.get("release") != current["revision"]
            or archive.get("release_manifest_sha256") != current["manifest_sha256"]
            or archive.get("backup_complete") is not False or archive.get("source_resumed") is not True):
        raise RuntimeError("Не подтверждён безопасно завершённый сбой резервирования")
    return {"format": 1, "kind": "prebackup-abort", "attempt": identifier, "previous": current,
            "original_env_sha256": original_hash, "old_enrollment_sha256": enrollment_hash,
            "partial_backup": {"path": str(backup.relative_to(state)), "manifest_sha256": archive_hash}}


def replacement_record(state, audit, context, target=None):
    keys = set(context) | {"new_target", "replacement_attempt", "replacement_enrollment_sha256", "replacement_env_sha256"}
    if set(audit) != keys or any(audit.get(key) != value for key, value in context.items()):
        raise RuntimeError("Квитанция закрытия частичного резерва изменилась")
    if target is not None and audit["new_target"] != target:
        raise RuntimeError("Закрытие частичного резерва закреплено за другим новым выпуском")
    identifier = attempt_id({"attempt": audit["replacement_attempt"]})
    if identifier == context["attempt"]:
        raise RuntimeError("Восстановление требует новую идентичность регистрации")
    directory = state / "runtime-attempts"
    stored, stamp = stamped_record(directory / (identifier + "-enrollment.json"))
    if (stamp != audit["replacement_enrollment_sha256"] or stored.get("attempt") != identifier
            or stored.get("previous") != context["previous"] or stored.get("target") != audit["new_target"]
            or stored.get("original_env_sha256") != context["original_env_sha256"]
            or stored.get("target_env_sha256") != audit["replacement_env_sha256"]
            or not absent(directory / (identifier + ".json"))):
        raise RuntimeError("Новая регистрация не совпадает с квитанцией восстановления")
    allowed = {identifier + suffix for suffix in ("-enrollment.json", "-enrollment.env")}
    if any(path.name.startswith(identifier) and path.name not in allowed for path in directory.iterdir()):
        raise RuntimeError("Новая регистрация содержит квитанции начатой попытки")
    require_fresh_backup(state, stored)
    return stored


def prebackup_replacement_env(state, entry, current, revision, checksum, original_env):
    """Check every normal request against a sealed abort before bootstrap or cleanup."""
    private_directory(state / "runtime-attempts")
    audit = read_record(state / "runtime-attempts" / (attempt_id(entry) + "-prebackup-abort.json"))
    if audit is None:
        if entry["target"] == current:
            return original_env
        if entry["target"].get("revision") == revision and entry["target"].get("manifest_sha256") == checksum:
            return enrolled_env(state, entry, original_env)
        return original_env
    if (audit.get("new_target", {}).get("revision") != revision
            or audit.get("new_target", {}).get("manifest_sha256") != checksum):
        raise RuntimeError("Повтор восстановления требует тот же новый manifest")
    context = prebackup_context(state, entry, current, original_env)
    stored = replacement_record(state, audit, context)
    return prebackup_enrolled_env(state, stored, original_env)


def recover_prebackup(state, entry, current, target, original_env, target_env):
    """Seal a failed pre-backup and atomically hand its pointer to a fresh enrollment.

    No started rollout, complete backup, same-target retry or unbound input is eligible.
    Every old file remains available for audit; only the active pointer is replaced.
    """
    context = prebackup_context(state, entry, current, original_env)
    if (target.get("revision") in (entry["target"].get("revision"), current.get("revision"))
            or not re.fullmatch(r"[a-f0-9]{40}", target.get("revision", ""))
            or not re.fullmatch(r"[a-f0-9]{64}", target.get("manifest_sha256", ""))):
        raise RuntimeError("Закрытие частичного резерва требует другой проверенный выпуск")
    directory = state / "runtime-attempts"
    audit_path = directory / (entry["attempt"] + "-prebackup-abort.json")
    audit = read_record(audit_path)
    if audit is None:
        stored = prepared_enrollment(state, current, target, original_env, target_env, fresh_backup=True)
        _, stamp = stamped_record(directory / (stored["attempt"] + "-enrollment.json"))
        audit = {**context, "new_target": target, "replacement_attempt": stored["attempt"],
                 "replacement_enrollment_sha256": stamp, "replacement_env_sha256": stored["target_env_sha256"]}
    stored = replacement_record(state, audit, context, target)
    if prebackup_enrolled_env(state, stored, original_env) != target_env:
        raise RuntimeError("Повтор восстановления изменил закреплённый новый ENV")
    write_record(audit_path, audit, immutable=True)
    # Existing immutable write_record can return without fsync: replay still seals the audit.
    fsync_dir(directory)
    if prebackup_context(state, entry, current, original_env) != context:
        raise RuntimeError("Исходное состояние изменилось перед передачей регистрации")
    if read_record(audit_path) != audit:
        raise RuntimeError("Квитанция восстановления изменилась перед передачей регистрации")
    stored = replacement_record(state, audit, context, target)
    if prebackup_enrolled_env(state, stored, original_env) != target_env:
        raise RuntimeError("Новый ENV изменился перед передачей регистрации")
    write_record(state / "runtime-enrollment.json", {"attempt": stored["attempt"]})
    return stored


def begin(state, current, desired, backup, *, identifier=None):
    pointer = state / "runtime-attempt.json"
    identifier = identifier or uuid4().hex
    attempts = ensure_private(state / "runtime-attempts")
    if current and current["revision"] != desired["revision"] and backup is None:
        raise RuntimeError("Перед новым выпуском нужна согласованная копия текущего")
    archive = None
    if backup is not None:
        if not backup.is_dir():
            raise RuntimeError("Не найдена зарезервированная копия общего выпуска")
        ensure_private(backup)
        archive = read_record(backup / "manifest.json")
        if archive is None:
            raise RuntimeError("Зарегистрированная резервная копия не завершена")
        backup_archives(archive)
        if (archive.get("source_project") != "cabinet-dev" or archive.get("release") != current["revision"]
                or archive.get("release_manifest_sha256") != current["manifest_sha256"] or archive.get("source_resumed") is not True):
            raise RuntimeError("Копия не связана с подтверждённым текущим выпуском")
    record = {
        "attempt": identifier,
        "previous": current,
        "target": desired,
        "backup": None if backup is None else {
            "path": str(backup.relative_to(state)), "sha256": digest(backup / "manifest.json"),
        },
    }
    if current.get("alpha_dev"):
        if archive is None:
            raise RuntimeError("Для сохранения текущего поколения Alpha нужна согласованная копия")
        alpha = archive.get("alpha_runtime")
        preflight_file = backup / "alpha-preflight.json"
        if (not isinstance(alpha, dict) or read_record(preflight_file) != alpha["preflight"]
                or manifest_stamp(preflight_file) != archive["files"][preflight_file.name]
                or archive["runtimes"].get("alpha_dev") != ("read" if current["alpha_dev"]["read_enabled"] else "shadow")
                or alpha["frozen"]["checkpoint"]["generation"] != current["alpha_dev"]["generation"]):
            raise RuntimeError("Копия Alpha не связана с подтверждённым текущим выпуском")
        record["alpha"] = {"preflight": alpha["preflight"], "frozen": alpha["frozen"]}
    write_record(attempts / (identifier + ".json"), record, immutable=True)
    write_record(pointer, {"attempt": identifier}, immutable=True)
    return record


def load(state, release):
    identifier = attempt_id(read_record(state / "runtime-attempt.json"))
    record = read_record(state / "runtime-attempts" / (identifier + ".json"))
    if (not record or record.get("attempt") != identifier or record.get("target", {}).get("revision") != release.revision
            or record["target"].get("manifest_sha256") != release.checksum):
        raise RuntimeError("Попытка общего выпуска связана с другим manifest")
    return record


def manifest_stamp(path):
    if path.is_symlink() or not path.is_file():
        return None
    return {"bytes": path.stat().st_size, "sha256": digest(path)}


def checked_backup(state, record, service):
    binding = record.get("backup")
    if not binding:
        raise RuntimeError(f"Перед сменой владельца {service} нужна согласованная копия")
    path = state / binding["path"]
    if not path.is_dir() or path.is_symlink() or not path.resolve().is_relative_to((state / "backups").resolve()):
        raise RuntimeError("Копия общего выпуска находится вне защищённого каталога")
    archive = path / "manifest.json"
    if digest(archive) != binding["sha256"]:
        raise RuntimeError("Квитанция копии общего выпуска изменилась")
    manifest = json.loads(archive.read_bytes())
    previous = record["previous"]
    if (manifest.get("source_project") != "cabinet-dev" or manifest.get("release") != previous.get("revision")
            or manifest.get("source_resumed") is not True or not manifest.get("backup_complete")):
        raise RuntimeError("Копия относится к другому текущему выпуску")
    if not previous.get("manifest_sha256") or manifest.get("release_manifest_sha256") != previous["manifest_sha256"]:
        raise RuntimeError("Копия связана с другим исходным manifest")
    backup_archives(manifest)
    files = ["credit.dump"]
    if service in previous:
        if service + "_runtime" not in manifest:
            raise RuntimeError(f"Копия не включает состояние {service}")
        if service == "servicing":
            files.append("servicing.dump")
            if manifest["servicing_runtime"].get("mode") != previous[service]["mode"]:
                raise RuntimeError("Копия Servicing не соответствует прежнему владельцу")
    for name in files:
        item = path / name
        stamp = manifest["files"].get(name)
        if not stamp or stamp != manifest_stamp(item):
            raise RuntimeError(f"Повреждён файл согласованной копии {name}")
    return path


def servicing_binding(deployment, record, before):
    state = deployment.path.parent
    target = deployment.servicing.descriptor.mode
    if (before == target or before != record["previous"].get("servicing", {}).get("mode", "local")
            or record["target"].get("servicing", {}).get("mode") != target):
        raise RuntimeError("Перенос Servicing не совпадает с режимами общей попытки")
    backup = checked_backup(state, record, "servicing")
    return {
        "attempt": record["attempt"], "from_mode": before,
        "to_mode": target, "manifest_sha256": deployment.release.checksum,
        "backup_manifest_sha256": digest(backup / "manifest.json"),
    }


def authorize_servicing(deployment, record):
    before = record["previous"].get("servicing", {}).get("mode", "local")
    if deployment.servicing.descriptor.mode != before:
        binding = servicing_binding(deployment, record, before)
        directory = ensure_private(deployment.path.parent / "runtime-attempts")
        write_record(directory / (record["attempt"] + "-servicing.json"), binding, immutable=True)


def require_servicing_authorization(deployment, before):
    state = deployment.path.parent
    record = load(state, deployment.release)
    if read_record(state / "pending.json") != record["target"]:
        raise RuntimeError("Нет общей предварительной записи переноса Servicing")
    binding = servicing_binding(deployment, record, before)
    if read_record(state / "runtime-attempts" / (record["attempt"] + "-servicing.json")) != binding:
        raise RuntimeError("Нет неизменяемого разрешения на перенос Servicing")
    return binding


def close_enrollment(state, entry):
    pointer = state / "runtime-attempt.json"
    active = read_record(pointer)
    if active is not None:
        if attempt_id(active) != entry["attempt"]:
            raise RuntimeError("Закрытие регистрации связано с другой попыткой общего выпуска")
        pointer.unlink()
        fsync_dir(state)
    (state / "runtime-enrollment.json").unlink()
    fsync_dir(state)
    directory = state / "runtime-attempts"
    (directory / (entry["attempt"] + "-enrollment.env")).unlink(missing_ok=True)
    fsync_dir(directory)
