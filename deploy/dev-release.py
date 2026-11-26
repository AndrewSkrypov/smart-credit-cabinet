#!/usr/bin/env python3
"""Apply a protected release to the persistent dev Compose project."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys
import tempfile

from alpha_dev import PROJECT, SECRET_KEYS, stop_alpha
from common import Deployment, ROOT
from jury import COMPANY, provision, read_credentials, verify
from registry_release import RegistryRelease, REVISION


def write_state(path, value):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
        json.dump(value, stream)
        temporary = Path(stream.name)
    temporary.replace(path)


def with_alpha_config(contents):
    values = {
        "CABINET_ALPHA_DEV_ENABLED": "true",
        "CABINET_ALPHA_DEV_GENERATION": "g" + secrets.token_hex(8),
        "CABINET_ALPHA_DEV_READ_ENABLED": "false",
        **{key: secrets.token_hex(32) for key in SECRET_KEYS},
    }
    newline = b"\r\n" if b"\r\n" in contents else b"\n"
    separator = newline if contents and not contents.endswith((b"\n", b"\r")) else b""
    return contents + separator + b"".join(f"{key}={value}".encode("ascii") + newline for key, value in values.items())


def replace_env(path, original, contents):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".cabinet.env.", delete=False) as stream:
            temporary = Path(stream.name)
            created = os.fstat(stream.fileno())
            if (created.st_uid, created.st_gid) != (original.st_uid, original.st_gid):
                os.fchown(stream.fileno(), original.st_uid, original.st_gid)
            os.fchmod(stream.fileno(), stat.S_IMODE(original.st_mode))
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            current = path.lstat()
            if not os.path.samestat(current, original) or current.st_ctime_ns != original.st_ctime_ns:
                raise RuntimeError("cabinet.env изменился во время выкладки. Повторите запуск.")
            temporary.replace(path)
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def run(root, script, *arguments, capture=False):
    result = subprocess.run([sys.executable, str(root / "deploy" / script), *arguments],
                            cwd=root, check=False, text=True, capture_output=capture)
    if result.returncode:
        raise RuntimeError(f"{script}: код {result.returncode}")
    return result.stdout


def release_at(releases, revision):
    if not isinstance(revision, str) or not REVISION.fullmatch(revision):
        raise ValueError("Некорректная ревизия в состоянии dev")
    root = releases / revision
    release = RegistryRelease(root / "release.json")
    if release.revision != revision:
        raise ValueError("Каталог релиза не совпадает с manifest")
    return root, release


def rollout_metadata(deployment, state_dir, release, status, pending):
    desired = {"revision": release.revision, "manifest_sha256": release.checksum}
    alpha = deployment.alpha_dev
    history_file = state_dir / "alpha-generations.json"
    if deployment.values.get("CABINET_ALPHA_DEV_ENABLED") == "true" and alpha is None:
        raise ValueError("Для пилота Dev нужен alpha_dev из защищённого release-images")
    if alpha is None:
        return desired, history_file, None

    desired["alpha_dev"] = alpha.state()
    current_generation = status.get("alpha_dev", {}).get("generation")
    pending_generation = pending.get("alpha_dev", {}).get("generation")
    history = set(json.loads(history_file.read_text())) if history_file.exists() else set()
    if alpha.generation in history and alpha.generation not in {current_generation, pending_generation}:
        raise ValueError("Поколение Analytics уже использовалось. Выберите новое.")
    if alpha.read_enabled and alpha.generation != current_generation:
        raise ValueError("Перед переключением чтения запустите это поколение в теневом режиме")
    history.add(alpha.generation)
    return desired, history_file, history


def resumes_pending_in_shadow(pending, desired):
    alpha = pending.get("alpha_dev", {})
    return alpha.get("read_enabled") is True and desired == {
        **pending, "alpha_dev": {**alpha, "read_enabled": False},
    }


def deploy(args, state_dir, release):
    current_file, pending_file = (state_dir / name for name in ("current.json", "pending.json"))
    env_file = state_dir / "cabinet.env"
    env_stat = env_file.stat()
    if env_stat.st_mode & 0o077:
        raise ValueError("cabinet.env должен иметь права 0600")
    env_args = ["--project", PROJECT, "--env-file", str(env_file)]
    release_args = ["--release-manifest", str(release.path)]
    may_initialize = not args.verify_only and release.alpha_dev is not None
    if may_initialize and not stat.S_ISREG(env_stat.st_mode):
        raise ValueError("Для настройки Analytics нужен обычный файл cabinet.env")
    env_contents = env_file.read_bytes() if may_initialize else None
    deployment = Deployment(PROJECT, env_file, release_manifest=release.path, env_contents=env_contents)
    status = json.loads(current_file.read_text()) if current_file.exists() else {}
    pending = json.loads(pending_file.read_text()) if pending_file.exists() else {}
    if status.get("revision") == release.revision and status.get("manifest_sha256") != release.checksum:
        raise ValueError("Manifest для уже применённой ревизии изменился")
    initialized_env = None
    if may_initialize and not any(key.startswith("CABINET_ALPHA_DEV_") for key in deployment.values):
        if status.get("alpha_dev") or pending.get("alpha_dev"):
            raise ValueError("В cabinet.env отсутствуют настройки записанного поколения Analytics")
        if not stat.S_ISREG(env_file.lstat().st_mode):
            raise ValueError("Для настройки Analytics нужен обычный файл cabinet.env")
        initialized_env = with_alpha_config(env_contents)
        deployment = Deployment(PROJECT, env_file, release_manifest=release.path, env_contents=initialized_env)
    desired, history_file, history = rollout_metadata(deployment, state_dir, release, status, pending)
    if pending and pending != desired and not resumes_pending_in_shadow(pending, desired):
        raise RuntimeError("Есть незавершённый выпуск. Повторите тот же manifest или восстановите отдельную копию.")
    if args.verify_only and (desired != status or pending):
        raise RuntimeError("Указанный выпуск не записан как текущий")

    if not args.verify_only and (desired != status or pending):
        if status and not pending and status["revision"] != release.revision:
            old_root, old_release = release_at(ROOT.parent, status["revision"])
            if status["manifest_sha256"] != old_release.checksum:
                raise RuntimeError("Manifest предыдущего выпуска изменился")
            backup_dir = state_dir / "backups"
            backup_dir.mkdir(mode=0o700, exist_ok=True)
            directory = Path(tempfile.mkdtemp(prefix="pre-migration-", dir=backup_dir))
            resume_options = ["--resume-existing"] if status.get("alpha_dev") else []
            run(old_root, "backup.py", *env_args, "--release-manifest", str(old_release.path),
                "--output", str(directory / "backup"), *resume_options)
        if initialized_env is not None:
            # Keep the generation and credentials for retry before starting any Alpha service.
            replace_env(env_file, env_stat, initialized_env)
        write_state(pending_file, desired)
        if history is not None:
            write_state(history_file, sorted(history))
        run(ROOT, "start.py", *env_args, *release_args, "--no-build",
            "--host-memory-gib", str(args.host_memory_gib))
        if status.get("alpha_dev") and deployment.alpha_dev is None:
            stop_alpha()

    credentials = read_credentials(state_dir / "jury.json", create=not args.verify_only)
    if not args.verify_only:
        provision(deployment, credentials)
    origin = deployment.values["PUBLIC_CABINET_URL"]
    report = {"manifest_sha256": release.checksum, "role_checks": verify(origin, credentials, expected_company=COMPANY)}
    report["release"] = json.loads(run(ROOT, "verify-release.py", *env_args, *release_args,
                                       "--expected-revision", release.revision, capture=True))
    if not args.verify_only and pending_file.exists():
        write_state(current_file, desired)
        pending_file.unlink()
    print(json.dumps(report, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description="Применить или проверить защищённый выпуск на dev ВМ")
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--release-manifest", type=Path, required=True)
    parser.add_argument("--host-memory-gib", type=int, default=8)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    try:
        release = RegistryRelease(args.release_manifest)
        if ROOT.name != release.revision or release.path != ROOT / "release.json":
            raise ValueError("Код и release.json должны находиться в одном каталоге <SHA>")
        state_dir = args.state_dir.resolve()
        if state_dir.stat().st_mode & 0o077:
            raise ValueError("Каталог состояния должен иметь права 0700")
        descriptor = os.open(state_dir / "deploy.lock", os.O_CREAT | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            deploy(args, state_dir, release)
    except (KeyError, OSError, RuntimeError, ValueError) as error:
        # JSON parsing errors can include private input. Only print controlled errors.
        if type(error) in (RuntimeError, ValueError):
            parser.exit(1, f"Выпуск не подтверждён. {error}\n")
        parser.exit(1, f"Выпуск не подтверждён. {type(error).__name__}\n")


if __name__ == "__main__":
    main()
