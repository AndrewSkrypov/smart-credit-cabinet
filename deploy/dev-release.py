#!/usr/bin/env python3
"""Apply a protected release to the persistent dev Compose project."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from alpha_dev import PROJECT, stop_alpha
from common import Deployment, ROOT
from jury import COMPANY, provision, read_credentials, verify
from registry_release import RegistryRelease, REVISION


def write_state(path, value):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
        json.dump(value, stream)
        temporary = Path(stream.name)
    temporary.replace(path)


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
    if env_file.stat().st_mode & 0o077:
        raise ValueError("cabinet.env должен иметь права 0600")
    env_args = ["--project", PROJECT, "--env-file", str(env_file)]
    release_args = ["--release-manifest", str(release.path)]
    deployment = Deployment(PROJECT, env_file, release_manifest=release.path)
    status = json.loads(current_file.read_text()) if current_file.exists() else {}
    pending = json.loads(pending_file.read_text()) if pending_file.exists() else {}
    desired, history_file, history = rollout_metadata(deployment, state_dir, release, status, pending)
    if status.get("revision") == release.revision and status.get("manifest_sha256") != release.checksum:
        raise ValueError("Manifest для уже применённой ревизии изменился")
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
