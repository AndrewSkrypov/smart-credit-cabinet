"""Bind each multi-service rollout and ownership change to its protected pre-migration copy."""
from hashlib import sha256
import json
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


def enroll(state, previous, target, original_env, target_env):
    if enrollment(state) is not None:
        raise RuntimeError("У проекта уже есть регистрация общего выпуска")
    directory = ensure_private(state / "runtime-attempts")
    identifier = uuid4().hex
    entry = {"attempt": identifier, "previous": previous, "target": target,
             "original_env_sha256": sha256(original_env).hexdigest(), "target_env_sha256": sha256(target_env).hexdigest()}
    if original_env != target_env:
        write_private(directory / (identifier + "-enrollment.env"), target_env.decode("utf-8"), exclusive=True)
    write_record(directory / (identifier + "-enrollment.json"), entry, immutable=True)
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
