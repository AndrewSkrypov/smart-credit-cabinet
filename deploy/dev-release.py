#!/usr/bin/env python3
"""Apply a protected release to the persistent dev Compose project."""
import argparse
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys
import tempfile

# The forced wrapper runs this file without -B. Keep diagnostic imports read-only.
if __name__ == "__main__":
    sys.dont_write_bytecode = True

from alpha_dev import PROJECT, SECRET_KEYS, stop_alpha
from catalog import SECRET_KEYS as CATALOG_SECRET_KEYS, append_secrets, ensure_tls, requested_mode
from catalog_rollout import store as write_state, validated_journal
from runtime_delivery_support import ensure_private, fsync_dir, read_record
import mcp_bootstrap
import mcp_dev
from common import Deployment, ROOT, deploy_lock, lock_environment, replace_env
from dev_diagnostics import diagnose, read_request
from dev_payment_reserve import canonical_origin
from business_smoke import run as run_business_smoke
from mcp_business_smoke import run as run_mcp_business_smoke
from jury import COMPANY, provision, read_credentials, verify
from registry_release import RegistryRelease, REVISION
import runtime_attempts


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


def run(root, script, *arguments, capture=False, lock_fd=None):
    result = subprocess.run([sys.executable, str(root / "deploy" / script), *arguments],
                            cwd=root, check=False, text=True, capture_output=capture, **lock_environment(lock_fd))
    if result.returncode:
        raise RuntimeError(f"{script}: код {result.returncode}")
    return result.stdout


def release_at(releases, revision):
    if not isinstance(revision, str) or not REVISION.fullmatch(revision):
        raise ValueError("Некорректная ревизия в состоянии dev")
    root = releases / revision
    release = RegistryRelease(root / "release.json", allow_legacy_alpha=True)
    if release.revision != revision:
        raise ValueError("Каталог релиза не совпадает с manifest")
    return root, release


def rollout_metadata(deployment, state_dir, release, status, pending):
    desired = {"revision": release.revision, "manifest_sha256": release.checksum}
    if deployment.values.get(mcp_dev.FLAG) == "true":
        desired["mcp"] = {"enabled": True}
    if deployment.catalog_enabled:
        desired["catalog"] = {"mode": deployment.catalog_mode}
    elif status.get("catalog") or pending.get("catalog") or (deployment.catalog_state / "authority.json").exists():
        raise ValueError("После ввода Catalog для возврата нужен release с режимом local")
    for name in ("assessment", "servicing", "documents"):
        if name in deployment.runtime_modes:
            desired[name] = {"mode": deployment.runtime_modes[name]}
        elif name in status or name in pending:
            raise ValueError(f"После ввода {name} для возврата нужен режим local")
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


def reverses_pending_catalog(deployment, status, pending, desired):
    if (pending.get("catalog", {}).get("mode") != "remote"
            or desired.get("catalog", {}).get("mode") != "local"
            or pending.get("alpha_dev") != desired.get("alpha_dev")
            or any(name in pending or name in desired for name in ("assessment", "servicing", "documents"))):
        return False
    journal = validated_journal(deployment.catalog_state / "authority.json")
    transfer = journal.get("last_transfer", {})
    return (not journal.get("pending") and journal.get("active") == "catalog"
            and transfer.get("to") == "catalog"
            and (transfer.get("manifest_sha256") == pending.get("manifest_sha256")
                 or status.get("catalog", {}).get("mode") == "remote"))


def capture_previous(args, env_file, env_args, status, desired, backup):
    _, old_release = release_at(ROOT.parent, status["revision"])
    if status["manifest_sha256"] != old_release.checksum:
        raise RuntimeError("Manifest предыдущего выпуска изменился")
    if (status.get("assessment", {}).get("mode") == "remote"
            and desired.get("assessment", {}).get("mode") == "local"):
        live = Deployment(PROJECT, env_file, release_manifest=old_release.path, historical_backup=True)
        live.assessment.require_drained()
    run(ROOT, "backup.py", *env_args, "--release-manifest", str(old_release.path),
        "--output", str(backup), "--resume-existing", lock_fd=getattr(args, "deployment_fd", None))


def deploy(args, state_dir, release):
    current_file, pending_file = (state_dir / name for name in ("current.json", "pending.json"))
    env_file = state_dir / "cabinet.env"
    env_stat = env_file.lstat()
    if not stat.S_ISREG(env_stat.st_mode) or stat.S_IMODE(env_stat.st_mode) != 0o600:
        raise ValueError("Нужен обычный файл cabinet.env с правами 0600")
    env_args = ["--project", PROJECT, "--env-file", str(env_file)]
    release_args = ["--release-manifest", str(release.path)]
    env_contents = env_file.read_bytes()
    original_env = env_contents
    status = json.loads(current_file.read_text()) if current_file.exists() else {}
    pending = json.loads(pending_file.read_text()) if pending_file.exists() else {}
    enrollment = runtime_attempts.enrollment(state_dir)
    if enrollment and not args.verify_only:
        env_contents = runtime_attempts.prebackup_replacement_env(
            state_dir, enrollment, status, release.revision, release.checksum, original_env,
        )
    if enrollment and enrollment["target"] == status and not args.verify_only:
        if pending and pending != status:
            raise RuntimeError("Запись завершённого выпуска не совпадает с pending")
        pending_file.unlink(missing_ok=True)
        fsync_dir(state_dir)
        runtime_attempts.close_enrollment(state_dir, enrollment)
        enrollment, pending = None, {}
    if status.get("revision") == release.revision and status.get("manifest_sha256") != release.checksum:
        raise ValueError("Manifest для уже применённой ревизии изменился")
    replacement = enrollment is not None and (
        enrollment["target"].get("revision") != release.revision
        or enrollment["target"].get("manifest_sha256") != release.checksum
    )
    if enrollment and args.verify_only:
        env_contents = runtime_attempts.enrolled_env(state_dir, enrollment, original_env)
    names = {line.split(b"=", 1)[0].decode("utf-8") for line in env_contents.splitlines() if b"=" in line}
    if not args.verify_only and release.alpha_dev is not None and not any(name.startswith("CABINET_ALPHA_DEV_") for name in names):
        if status.get("alpha_dev") or pending.get("alpha_dev"):
            raise ValueError("В cabinet.env отсутствуют настройки записанного поколения Analytics")
        env_contents = with_alpha_config(env_contents)
    if (not args.verify_only and requested_mode(PROJECT, False, release) != "disabled"
            and not names.intersection(CATALOG_SECRET_KEYS)):
        if status.get("catalog") or pending.get("catalog"):
            raise ValueError("В cabinet.env отсутствуют пароли уже настроенного Catalog")
        env_contents = append_secrets(env_contents)
    env_contents = mcp_bootstrap.prepare_env(
        PROJECT, env_file, release, env_contents, verify_only=args.verify_only,
        allow_new=not pending and (enrollment is None or replacement) and status.get("revision") != release.revision,
    )
    deployment = Deployment(PROJECT, env_file, release_manifest=release.path, env_contents=env_contents, open_hooks=False)
    desired, history_file, history = rollout_metadata(deployment, state_dir, release, status, pending)
    need_attempt = bool(deployment.runtime_modes or deployment.alpha_dev) and release.has_all_runtimes
    if enrollment and (not need_attempt or enrollment["previous"] != status):
        raise RuntimeError("Регистрация общего выпуска требует исходный текущий выпуск и тот же целевой manifest")
    if release.has_all_runtimes and "alpha_dev" in status and desired.get("alpha_dev") != status["alpha_dev"]:
        raise RuntimeError("Общий выпуск сохраняет текущее поколение и режим чтения Alpha")
    if pending and pending != desired:
        legacy_resume = not release.has_all_runtimes and (
            resumes_pending_in_shadow(pending, desired)
            or reverses_pending_catalog(deployment, status, pending, desired)
        )
        if not legacy_resume:
            raise RuntimeError("Есть незавершённый выпуск. Повторите тот же manifest или восстановите отдельную копию.")
    if args.verify_only and (desired != status or pending or enrollment):
        raise RuntimeError("Указанный выпуск не записан как текущий")
    if (desired.get("documents", {}).get("mode") == "remote" and
            "documents" not in status and "documents" not in pending):
        raise RuntimeError("Для первого включения Documents нужен защищённый выпуск с режимом local")
    # The ordinary target/Alpha/mode guards above run before any recovery pointer change.
    if enrollment and enrollment["target"] != desired:
        if args.verify_only or not replacement:
            raise RuntimeError("Регистрация общего выпуска требует тот же целевой manifest")
        enrollment = runtime_attempts.recover_prebackup(
            state_dir, enrollment, status, desired, original_env, env_contents,
        )

    if not args.verify_only and (desired != status or pending):
        if need_attempt:
            if enrollment is None:
                if pending:
                    raise RuntimeError("Для pending общего выпуска отсутствует защищённая регистрация")
                enrollment = runtime_attempts.enroll(state_dir, status, desired, original_env, env_contents)
            backup = runtime_attempts.enrollment_backup(state_dir, enrollment)
            if backup is not None and not backup.exists():
                if pending or (state_dir / "runtime-attempt.json").exists():
                    raise RuntimeError("Утрачена согласованная копия зарегистрированного выпуска")
                ensure_private(backup.parent.parent)
                ensure_private(backup.parent)
                fsync_dir(backup.parent.parent)
                fsync_dir(state_dir)
                capture_previous(args, env_file, env_args, status, desired, backup)
            attempt = runtime_attempts.begin(state_dir, status, desired, backup, identifier=enrollment["attempt"])
            write_state(pending_file, desired)
        elif status and not pending and status["revision"] != release.revision:
            backup_dir = state_dir / "backups"
            backup_dir.mkdir(mode=0o700, exist_ok=True)
            directory = Path(tempfile.mkdtemp(prefix="pre-migration-", dir=backup_dir))
            capture_previous(args, env_file, env_args, status, desired, directory / "backup")
        if env_contents != original_env:
            replace_env(env_file, env_stat, env_contents)
        if deployment.catalog_enabled:
            ensure_tls(deployment.catalog_state)
        deployment = Deployment(PROJECT, env_file, release_manifest=release.path, env_contents=env_contents)
        if need_attempt and deployment.servicing:
            runtime_attempts.authorize_servicing(deployment, attempt)
        if not need_attempt:
            write_state(pending_file, desired)
        if deployment.documents and deployment.documents.current()["mode"] != deployment.documents.descriptor.mode:
            if not deployment.documents.active_request():
                if not need_attempt:
                    raise RuntimeError("Documents требует общей записанной попытки с резервом")
                deployment.documents.authorize(runtime_attempts.checked_backup(state_dir, attempt, "documents"))
        if history is not None:
            write_state(history_file, sorted(history))
        run(ROOT, "start.py", *env_args, *release_args, "--no-build",
            "--host-memory-gib", str(args.host_memory_gib), lock_fd=getattr(args, "deployment_fd", None))
        if status.get("mcp") and not desired.get("mcp"):
            mcp_dev.remove_inactive_services(PROJECT)
        if status.get("alpha_dev") and deployment.alpha_dev is None:
            stop_alpha()

    try:
        credentials = read_credentials(state_dir / "jury.json", create=not args.verify_only)
        if not args.verify_only:
            provision(deployment, credentials)
        configured_origin = deployment.values["PUBLIC_CABINET_URL"]
        origin = canonical_origin(configured_origin) or configured_origin
        report = {"manifest_sha256": release.checksum, "role_checks": verify(origin, credentials, expected_company=COMPANY)}
        report["release"] = json.loads(run(ROOT, "verify-release.py", *env_args, *release_args,
                                           "--expected-revision", release.revision, capture=True))
        if (not args.verify_only and pending_file.exists() and release.has_all_runtimes
                and all(deployment.runtime_modes.get(name) == "remote"
                        for name in ("catalog", "assessment", "servicing", "documents"))
                and deployment.dev_payment_reserve_simulation):
            report["release"]["business_smoke"] = run_business_smoke(
                deployment, state_dir, status, desired, report, lock_fd=args.deployment_fd,
            )
            if getattr(deployment, "mcp", False) is True:
                report["release"]["mcp_business_smoke"] = run_mcp_business_smoke(
                    deployment, state_dir, status, desired, report, lock_fd=args.deployment_fd,
                )
        if (not args.verify_only and pending_file.exists() and release.has_all_runtimes
                and (deployment.runtime_modes or deployment.alpha_dev)):
            attempt = runtime_attempts.load(state_dir, release)
            if attempt.get("alpha"):
                alpha = read_record(state_dir / "runtime-attempts" / (attempt["attempt"] + "-alpha-ready.json"))
                if (not alpha or alpha.get("manifest_sha256") != release.checksum
                        or alpha.get("caught_up") is not True):
                    raise RuntimeError("Не подтверждена сохранность и сходимость текущей Alpha")
                # Business API writes and cleanup happened after the original ready journal.
                # Recheck stores/outbox/consumer/projection without overwriting that journal.
                if "business_smoke" in report["release"]:
                    from runtime_backup import verify_alpha_catchup
                    fresh_alpha = verify_alpha_catchup(deployment, attempt["alpha"]["frozen"])
                    report["alpha_continuity"] = {**fresh_alpha, "manifest_sha256": release.checksum}
                    report["release"]["business_smoke"]["alpha_catchup_verified"] = True
                    if "mcp_business_smoke" in report["release"]:
                        report["release"]["mcp_business_smoke"]["alpha_catchup_verified"] = True
                else:
                    report["alpha_continuity"] = alpha
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        if not args.verify_only and pending_file.exists() and release.has_all_runtimes:
            deployment.compose("stop", "caddy")
        raise
    if not args.verify_only and pending_file.exists():
        write_state(current_file, desired)
        pending_file.unlink()
        fsync_dir(state_dir)
        if need_attempt:
            runtime_attempts.close_enrollment(state_dir, enrollment)
    print(json.dumps(report, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description="Применить или проверить защищённый выпуск на dev ВМ")
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--release-manifest", type=Path, required=True)
    parser.add_argument("--host-memory-gib", type=int, default=8)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    try:
        request = read_request(args.release_manifest)
        if request is not None:
            if args.verify_only:
                raise ValueError("verify-only неприменим к диагностическому запросу")
            print(diagnose(ROOT, args.release_manifest, args.state_dir, request))
            return
        release = RegistryRelease(args.release_manifest)
        if ROOT.name != release.revision or release.path != ROOT / "release.json":
            raise ValueError("Код и release.json должны находиться в одном каталоге <SHA>")
        state_dir = args.state_dir.resolve()
        if state_dir.stat().st_mode & 0o077:
            raise ValueError("Каталог состояния должен иметь права 0700")
        with deploy_lock(state_dir / "cabinet.env", PROJECT) as descriptor:
            args.deployment_fd = descriptor
            deploy(args, state_dir, release)
    except (KeyError, OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        # JSON parsing errors can include private input. Only print controlled errors.
        if type(error) in (RuntimeError, ValueError):
            parser.exit(1, f"Выпуск не подтверждён. {error}\n")
        parser.exit(1, f"Выпуск не подтверждён. {type(error).__name__}\n")


if __name__ == "__main__":
    main()
