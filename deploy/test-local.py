#!/usr/bin/env python3
"""Exercise production TLS, memory limits and full recovery using disposable local projects."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
from uuid import uuid4
from common import Deployment, ROOT
from backup_support import database_snapshot
from maintenance import Database
from artifact_checks import issued_contract, ready_artifacts, download_artifacts

sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import Cabinet

parser = argparse.ArgumentParser(description="Изолированная проверка prod: только localhost, новые проекты и внутренний CA")
parser.add_argument("--image-prefix", default="smart-credit-cabinet", help="Префикс пяти уже собранных Docker-образов")
parser.add_argument("--companies", type=int, default=20)
parser.add_argument("--https-port", type=int, default=19443)
parser.add_argument("--http-port", type=int, default=19080)
parser.add_argument("--subnet-block", type=int, default=42)
parser.add_argument("--keep", action="store_true", help="Оставить только созданные этим запуском проекты для осмотра")
parser.add_argument("--storage", choices=["local", "s3"], default="local")
parser.add_argument("--legacy-backup", action="store_true", help="Проверить local-копию с manifest прежнего формата 1")
parser.add_argument("--expected-revision", help="Потребовать одинаковый OCI revision у всех пяти образов, например SHA текущего CI")
parser.add_argument("--final-profiles", action="store_true", help="Проверить исходный стенд с профилями ai и monitoring")
parser.add_argument("--host-memory-gib", type=int, default=8)
parser.add_argument("--business-api-smoke", action="store_true", help="CI-only normal API business flow on the owned restored base stack; no Dev/REMOTE acceptance")
args = parser.parse_args()
if args.business_api_smoke:
    from ci_business_api import ci_environment
    ci_environment(os.environ, args.expected_revision)
    assert not args.keep and not args.final_profiles and args.storage == "local" and not args.legacy_backup, "Business CI requires an ephemeral base-local stack"
assert not args.legacy_backup or args.storage == "local", "Формат 1 не поддерживал S3"
assert re.fullmatch(r"[a-z0-9][a-z0-9_.-]{2,80}", args.image_prefix)
assert 1 <= args.companies <= 30 and 32 <= args.subnet_block < 250
assert all(1024 <= value < 65535 for value in [args.https_port, args.http_port])
assert len({args.https_port, args.https_port + 1, args.http_port, args.http_port + 1}) == 4
suffix = secrets.token_hex(6)
source_name = "cabinet-prod-test-" + suffix
target_name = "cabinet-prod-restore-test-" + suffix
runtime = ROOT / "deploy/runtime" / ("test-" + suffix)
runtime.mkdir(parents=True, exist_ok=False)
source_path = runtime / "source.env"
target_path = runtime / "target.env"
output = ROOT / "deploy/reports" / ("test-" + suffix)
output.mkdir(parents=True)
backup = ROOT / "deploy/backups" / ("test-" + suffix)
report = {"started_at": datetime.now(timezone.utc).isoformat(), "source_project": source_name, "target_project": target_name,
          "image_prefix": args.image_prefix, "companies": args.companies, "storage": args.storage, "checks": [], "memory_samples": [], "passed": False}
owned = []
stop_sampling = threading.Event()
stage = "prepare"


def run(script, *arguments, **kwargs):
    return subprocess.run([sys.executable, str(ROOT / "deploy" / script), *map(str, arguments)], cwd=ROOT, check=True, **kwargs)


def configure(path, index):
    run("configure.py", "--local", "--storage", args.storage, "--output", path)
    values = dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines())
    block = args.subnet_block + index
    values.update(CABINET_RELEASE="local-verification-" + suffix, HTTPS_PORT=str(args.https_port + index), HTTP_PORT=str(args.http_port + index),
                  PUBLIC_CABINET_URL="https://localhost:" + str(args.https_port + index),
                  ANTIVIRUS_MAX_SIGNATURE_AGE="P7D",
                  EDGE_SUBNET=f"172.30.{block}.0/28", CADDY_EDGE_IP=f"172.30.{block}.2", GATEWAY_EDGE_IP=f"172.30.{block}.3")
    if args.final_profiles and index == 0:
        values.update(CABINET_OPTIONAL_PROFILES="ai,monitoring", ASSISTANT_MODEL="qwen2.5:1.5b",
                      OLLAMA_URL="http://ollama:11434", GRAFANA_PASSWORD=secrets.token_urlsafe(24))
    path.write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")


def api_for(deployment):
    return Cabinet("https://localhost:" + deployment.values["HTTPS_PORT"],
                   ssl_context=ssl.create_default_context(cafile=str(deployment.gateway_config.parent / "local-ca.crt")))


def verify_release(deployment, artifact):
    if args.expected_revision:
        result = run("verify-release.py", "--project", deployment.project, "--env-file", deployment.path,
                     "--local", "--expected-revision", args.expected_revision, text=True, encoding="utf-8",
                     stdout=subprocess.PIPE)
        (output / artifact).write_text(result.stdout, encoding="utf-8")


def check(name, condition):
    assert condition, name
    report["checks"].append(name)


def verify_final_profiles(deployment, api, token):
    engagement = deployment.container("engagement")

    def curl(url):
        return subprocess.check_output(["docker", "exec", engagement, "curl", "-fsS", "--max-time", "5", url],
                                       text=True, encoding="utf-8", timeout=10)

    def model_status():
        return api.json("GET", "/api/v1/assistant/status", token=token)

    def completed_generations():
        metrics = curl("http://127.0.0.1:8080/actuator/prometheus")
        return sum(float(match) for match in re.findall(
            r'^cabinet_assistant_model_total\{[^}\n]*outcome="(?:ACCEPTED|GUARD_REJECTED)"[^}\n]*\}\s+([\d.eE+-]+)',
            metrics, re.MULTILINE,
        ))

    status = model_status()
    check("Ollama доступна с выбранной моделью", status["mode"] == "MODEL" and status["model"] == deployment.values["ASSISTANT_MODEL"])
    question = {"question": "Что банк узнаёт из ЕГРЮЛ и ФНС?"}
    before = completed_generations()
    answer = api.json("POST", "/api/v1/assistant/messages", question, token, timeout=45)
    generated = completed_generations() - before
    check("Помощник обработал настоящий ответ Ollama", generated == 1 and "ЕГРЮЛ, ЕГРИП и ФНС" in answer["sources"])

    required = ["credit", "engagement", "external-sim", "identity"]
    deadline = time.monotonic() + 45
    while True:
        targets = json.loads(curl("http://prometheus:9090/api/v1/targets"))
        healthy = sorted({item["labels"]["service"] for item in targets["data"]["activeTargets"] if item["health"] == "up"})
        if healthy == required or time.monotonic() >= deadline:
            break
        time.sleep(2)
    check("Prometheus наблюдает четыре сервиса", healthy == required)
    report["final_profiles"] = {"model": status["model"], "generated_answers": int(generated), "prometheus_targets": healthy}

    deployment.compose("stop", "ollama")
    deadline = time.monotonic() + 40
    while model_status()["mode"] == "MODEL":
        assert time.monotonic() < deadline, "Помощник не обнаружил остановку Ollama"
        time.sleep(1)
    fallback = api.json("POST", "/api/v1/assistant/messages", question, token)
    check("Помощник отвечает по правилам после остановки Ollama без перезапуска Engagement",
          fallback["mode"] == "RULES" and "ЕГРЮЛ, ЕГРИП и ФНС" in fallback["sources"] and deployment.container("engagement") == engagement)
    report["final_profiles"]["fallback"] = fallback["mode"]


def sample_memory():
    while not stop_sampling.is_set():
        identifiers = subprocess.run(["docker", "ps", "-q", "--filter", "label=com.docker.compose.project=" + source_name], capture_output=True, text=True)
        if identifiers.stdout.strip():
            result = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.MemUsage}}", *identifiers.stdout.split()], capture_output=True, text=True)
            values = {}
            for line in result.stdout.splitlines():
                name, usage = line.split("|", 1)
                match = re.match(r"([0-9.]+)([KMG]?i?B)", usage.strip())
                if match:
                    factors = {"B": 1, "kB": 1000, "KB": 1000, "KiB": 1024, "MB": 1000**2, "MiB": 1024**2, "GB": 1000**3, "GiB": 1024**3}
                    values[name] = int(float(match[1]) * factors[match[2]])
            if values:
                report["memory_samples"].append({"at": datetime.now(timezone.utc).isoformat(), "used_bytes": values, "sum_bytes": sum(values.values())})
        stop_sampling.wait(2)


sampler = threading.Thread(target=sample_memory, daemon=True)
try:
    for project in [source_name, target_name]:
        existing = subprocess.check_output(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + project], text=True)
        volumes = subprocess.check_output(["docker", "volume", "ls", "-q", "--filter", "label=com.docker.compose.project=" + project], text=True)
        assert not existing.strip() and not volumes.strip(), "Случайное имя уже занято; существующие ресурсы не трогаем"
    configure(source_path, 0)
    configure(target_path, 1)
    source = Deployment(source_name, source_path, True)
    target = Deployment(target_name, target_path, True)
    report["app_images"] = {}
    report["app_source_revisions"] = {}
    for service in ["identity", "credit", "engagement", "external-sim", "frontend"]:
        reference = args.image_prefix + "-" + service
        info = json.loads(subprocess.check_output(["docker", "image", "inspect", reference], text=True))[0]
        report["app_images"][service] = info["Id"]
        revision = (info["Config"].get("Labels") or {}).get("org.opencontainers.image.revision", "unknown")
        report["app_source_revisions"][service] = revision
        if args.expected_revision:
            assert revision == args.expected_revision, "Образ не помечен ожидаемым исходным SHA"
        subprocess.run(["docker", "tag", info["Id"], "cabinet/" + service + ":" + source.values["CABINET_RELEASE"]], check=True)
    stage = "source-start"
    owned.append(source)
    sampler.start()
    run("start.py", "--project", source_name, "--env-file", source_path, "--local", "--no-build", "--host-memory-gib", args.host_memory_gib)
    run("verify.py", "--project", source_name, "--env-file", source_path, "--host-memory-gib", args.host_memory_gib)
    shutil.copyfile(ROOT / f"deploy/reports/{source_name}-boundary.json", output / "source-boundary.json")
    stage = "source-release"
    verify_release(source, "source-release.json")
    api = api_for(source)
    stage = "operator-bootstrap"
    bank_password = secrets.token_urlsafe(24)
    bank_email = "staff-" + suffix + "@example.test"
    run("provision-bank-user.py", "--project", source_name, "--env-file", source_path, "--local", "--role", "UNDERWRITER",
        "--email", bank_email, "--name", "Оператор локальной проверки", "--password-stdin", input=bank_password + "\n", text=True, encoding="utf-8")
    bank = api.sign_in(bank_email, bank_password)["token"]
    stage = "register-companies"
    clients = []
    pdf = (ROOT / "contracts/postman/fixtures/synthetic.pdf").read_bytes()
    for index in range(args.companies):
        client = api.register("prod-test")
        client["application_id"] = api.create(client["token"])["id"]
        clients.append(client)
        time.sleep(2.1)
    stage = "business-document-upload"
    document = api.upload(clients[0]["token"], clients[0]["application_id"], "FINANCIAL_STATEMENTS", pdf)["body"]

    def process(client):
        route = "/api/v1/applications/" + client["application_id"]
        api.json("POST", route + "/submit", token=client["token"], headers={"Idempotency-Key": "prod-" + client["application_id"]})
        app = api.wait(client["token"], client["application_id"], {"OFFER_ISSUED", "MANUAL_REVIEW", "INFO_REQUESTED", "REJECTED"})
        if app["status"] == "MANUAL_REVIEW":
            api.json("POST", "/api/v1/underwriting/applications/" + client["application_id"] + "/approve",
                     {"comment": "Синтетическая проверка лимитов памяти и восстановления"}, bank)
        else:
            if app["status"] != "OFFER_ISSUED":
                report.setdefault("unexpected_pipeline_states", []).append(app["status"])
            assert app["status"] == "OFFER_ISSUED", "Неожиданный исход синтетического здорового сценария"
        if client is clients[0]:
            attachment = api.upload_chat(client["token"], client["application_id"], pdf)["body"]
            api.json("POST", route + "/messages", {"text": "Вложение для проверки восстановления", "documentIds": [attachment["id"]]},
                     client["token"], expected=(200, 201), headers={"Idempotency-Key": str(uuid4())})
            client["chat_document"] = attachment["id"]
        accepted = api.json("POST", route + "/offer/accept", token=client["token"])
        api.json("POST", route + "/sign", {"code": accepted["process"]["signing"]["code"]}, client["token"])
        api.wait(client["token"], client["application_id"], {"DISBURSED"})

    stage = "concurrent-pipeline"
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(process, clients))
    check("Все компании дошли до DISBURSED при производственных лимитах памяти", int(source.sql("credit", "select count(*) from loans")) == args.companies)
    fixture = clients[0]
    route = "/api/v1/applications/" + fixture["application_id"]
    schedule = api.json("GET", route + "/schedule", token=fixture["token"])
    stage = "generated-artifacts-source"
    contract_text = issued_contract(api, route, fixture["token"])
    report["contract_text_sha256"] = contract_text["snapshot"]["sha256"]
    artifact_rows = ready_artifacts(api, route, fixture["token"])
    generated_before = download_artifacts(api, route, fixture["token"], artifact_rows)
    report["generated_files"] = generated_before
    # Keep the original two-upload fixture, and require three READY final files for every loan.
    expected_generated = args.companies * 3
    deadline = time.monotonic() + 180
    while int(source.sql("credit", "select count(*) from generated_artifacts where phase='FINAL' and status='READY'")) != expected_generated:
        assert time.monotonic() < deadline, "Генерация файлов всех выданных кредитов не завершилась"
        time.sleep(1)
    check("Оба загруженных документа сохранены", int(source.sql("credit", "select count(*) from documents")) == 2)
    check("Для каждого кредита готовы три окончательных файла без лишних записей",
          int(source.sql("credit", "select count(*) from generated_artifacts")) == expected_generated)
    check("Авторизованно скачаны три PDF/XLSX с верными размером, типом и SHA256", len(generated_before) == 3)
    stage = "maintenance-lock"
    lock = Database(source.container("postgres"), source.values["POSTGRES_USER"])
    try:
        assert lock.sql("select pg_try_advisory_lock(16781, 8801);") == "t"
        refused = subprocess.run([sys.executable, str(ROOT / "deploy/backup.py"), "--project", source_name,
                                  "--env-file", str(source_path), "--local", "--output", str(runtime / "lock-refused")],
                                 cwd=ROOT, capture_output=True, env={**os.environ, "PYTHONUTF8": "1"})
        check("Backup отклонён при занятой блокировке переноса до остановки записи", refused.returncode != 0
              and "Перенос или другая копия уже выполняется" in refused.stderr.decode("utf-8", errors="replace")
              and all(source.container(name) for name in ["caddy", "credit", "identity", "engagement"]))
    finally:
        lock.close()
    stage = "source-database-permissions"
    subprocess.run([sys.executable, str(ROOT / "security/db-permissions.py"), "--project", source_name,
                    "--report-name", "prod-source-" + suffix], cwd=ROOT, check=True)
    shutil.copyfile(ROOT / ("security/reports/prod-source-" + suffix + "/verified.json"), output / "source-db-permissions.json")
    check("Runtime-роли исходного стенда изолированы от чужих БД, DDL и изменения аудита", True)
    stage = "backup"
    run("backup.py", "--project", source_name, "--env-file", source_path, "--local", "--output", backup)
    manifest = json.loads((backup / "manifest.json").read_text(encoding="utf-8"))
    check("Согласованная копия завершена, исходные writers восстановлены", manifest["backup_complete"] and manifest["source_resumed"])
    check("Копия охватывает выбранный backend, две загрузки и все сгенерированные файлы",
          manifest["storage"]["backends"] == {args.storage.upper(): 2 + expected_generated})
    if args.legacy_backup:
        assert not manifest["storage"]["s3_volume_included"]
        manifest["format_version"] = 1
        del manifest["storage"]
        (backup / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    report["restored_manifest_version"] = manifest["format_version"]
    if os.name == "posix":
        for name in ["documents.tar.gz", "identity.dump", "credit.dump", "engagement.dump", "manifest.json"] + (["seaweedfs.tar.gz"] if args.storage == "s3" else []):
            attributes = (backup / name).stat()
            check("Частная копия принадлежит запускающему пользователю: " + name,
                  attributes.st_uid == os.getuid() and attributes.st_mode & 0o777 == 0o600)
    if args.final_profiles:
        stage = "optional-profiles"
        verify_final_profiles(source, api, fixture["token"])
    # The restore is independent; stop the owned source so two antivirus engines do not exceed CI RAM.
    stop_sampling.set()
    sampler.join(timeout=10)
    source.compose("stop")
    report["source_stopped_before_restore"] = True
    stage = "restore"
    owned.append(target)
    run("restore.py", backup, "--project", target_name, "--env-file", target_path, "--local")
    shutil.copyfile(ROOT / "deploy/reports/restore.json", output / "restore.json")
    run("verify.py", "--project", target_name, "--env-file", target_path)
    shutil.copyfile(ROOT / f"deploy/reports/{target_name}-boundary.json", output / "restored-boundary.json")
    stage = "restored-release"
    verify_release(target, "restored-release.json")
    stage = "restored-database-permissions"
    subprocess.run([sys.executable, str(ROOT / "security/db-permissions.py"), "--project", target_name,
                    "--report-name", "prod-restored-" + suffix], cwd=ROOT, check=True)
    shutil.copyfile(ROOT / ("security/reports/prod-restored-" + suffix + "/verified.json"), output / "restored-db-permissions.json")
    check("После restore владельцы, runtime-права и запреты аудита восстановлены", True)
    restored_api = api_for(target)
    stage = "restored-api"
    check("Смена секрета JWT при восстановлении отклоняет прежний токен", restored_api.request("GET", "/api/v1/users/me", token=fixture["token"], expected=None)["status"] == 401)
    session = restored_api.sign_in(fixture["email"], "Test2026safe")
    token = session["token"]
    check("Восстановленная учётная запись входит с прежним паролем", session["user"]["id"] == fixture["user_id"])
    check("Сохранён итоговый статус заявки", restored_api.json("GET", route, token=token)["status"] == "DISBURSED")
    check("График платежей совпадает побайтно по структуре JSON", restored_api.json("GET", route + "/schedule", token=token) == schedule)
    check("Замороженный текст договора сохранён после восстановления",
          issued_contract(restored_api, route, token) == contract_text)
    restored_artifacts = ready_artifacts(restored_api, route, token)
    check("Сохранены ID, готовность и метаданные файлов", restored_artifacts == artifact_rows)
    check("Все три PDF/XLSX скачиваются после restore с прежними SHA256 и размерами",
          download_artifacts(restored_api, route, token, restored_artifacts) == generated_before)
    request = urllib.request.Request(restored_api.base + route + "/documents/" + document["id"], headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(request, context=restored_api.ssl_context, timeout=20) as response:
        content = response.read()
    check("Загруженный PDF доступен после восстановления и совпадает по SHA256", hashlib.sha256(content).digest() == hashlib.sha256(pdf).digest())
    messages = restored_api.json("GET", route + "/messages", token=token)
    check("Сохранена привязка вложения к сообщению", any(item["attachments"] and any(doc["id"] == fixture["chat_document"] for doc in item["attachments"])
                                                       for item in messages["messages"]))
    request = urllib.request.Request(restored_api.base + route + "/documents/" + fixture["chat_document"], headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(request, context=restored_api.ssl_context, timeout=20) as response:
        content = response.read()
    check("Вложение чата совпадает по SHA256 после восстановления", hashlib.sha256(content).digest() == hashlib.sha256(pdf).digest())
    check("Сохранены все выданные кредиты", int(target.sql("credit", "select count(*) from loans")) == args.companies)
    stage = "nonempty-target-guard"
    before = database_snapshot(target)
    refused = subprocess.run([sys.executable, str(ROOT / "deploy/restore.py"), str(backup), "--project", target_name,
                               "--env-file", str(target_path), "--local"], cwd=ROOT, capture_output=True)
    check("Повторное восстановление в непустой проект отклоняется без изменения данных", refused.returncode != 0 and database_snapshot(target) == before)
    stage = "memory-evidence"
    report["peak_sampled_sum_bytes"] = max(sample["sum_bytes"] for sample in report["memory_samples"])
    report["containers"] = []
    for identifier in subprocess.check_output(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + source_name], text=True).split():
        info = json.loads(subprocess.check_output(["docker", "inspect", identifier], text=True))[0]
        report["containers"].append({"name": info["Name"], "image_id": info["Image"], "limit_bytes": info["HostConfig"]["Memory"],
                                     "restarts": info["RestartCount"], "oom_killed": info["State"]["OOMKilled"]})
    check("Нет OOM/restart при наблюдаемой нагрузке", all(item["restarts"] == 0 and not item["oom_killed"] for item in report["containers"]))
    if args.business_api_smoke:
        # Run last: retain every existing source/restore count and backup assertion.
        # The provider's finally destroys only the two projects it created above.
        stage = "isolated-business-api"
        from ci_business_api import run as run_business_api
        report["business_api"] = run_business_api(target, args.expected_revision, runtime, output)
        check("Normal business APIs passed on owned base-local CI; Dev/4REMOTE acceptance remains separate",
              report["business_api"]["passed"] is True and report["business_api"]["feature_restored_false"] is True)
    report["passed"] = True
except Exception as error:
    report.update(failed_stage=stage, error_type=type(error).__name__)
    status = re.search(r": HTTP ([0-9]{3}),", str(error))
    if status:
        report["error_http_status"] = int(status[1])
    for category, message in {"antivirus": "Проверка файлов временно недоступна", "storage": "Хранилище документов временно недоступно"}.items():
        if message in str(error):
            report["error_component"] = category
    # Frame locations diagnose the stage without publishing request bodies, tokens or credentials.
    report["error_frames"] = [{"file": Path(frame.filename).name, "line": frame.lineno, "function": frame.name}
                              for frame in traceback.extract_tb(error.__traceback__)]
    for deployment in owned:
        try:
            identifiers = subprocess.check_output(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + deployment.project], text=True).split()
            for identifier in identifiers:
                info = json.loads(subprocess.check_output(["docker", "inspect", identifier], text=True))[0]
                report.setdefault("failed_containers", []).append({"name": info["Name"], "restarts": info["RestartCount"],
                    "oom_killed": info["State"]["OOMKilled"], "status": info["State"]["Status"], "health": info["State"].get("Health", {}).get("Status")})
            logs = subprocess.run(["docker", "logs", "--tail", "160", deployment.container("clamav")], capture_output=True)
            (output / (deployment.project + "-clamav.log")).write_bytes(logs.stdout + logs.stderr)
        except Exception:
            report.setdefault("diagnostic_failures", []).append(deployment.project)
finally:
    stop_sampling.set()
    if sampler.is_alive():
        sampler.join(timeout=10)
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    report["kept_projects"] = args.keep
    if not args.keep:
        for deployment in reversed(owned):
            try:
                deployment.compose("down", "-v", "--remove-orphans")
            except Exception:
                report["passed"] = False
                report.setdefault("cleanup_errors", []).append(deployment.project)
    (output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in {"memory_samples", "containers"}}, ensure_ascii=False, indent=2))
raise SystemExit(0 if report["passed"] else 1)
