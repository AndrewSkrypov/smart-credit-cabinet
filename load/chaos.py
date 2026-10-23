#!/usr/bin/env python3
"""Assert recovery, source isolation, replica routing and concurrent API actions."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
from urllib.error import URLError
from fault_observation import wait_for_container_healthy, wait_for_enrichment

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import Cabinet  # noqa: E402

parser = argparse.ArgumentParser(description="Проверки отказов только на отдельном синтетическом стенде")
parser.add_argument("mode", choices=["balance", "disburse", "crash", "recover-down", "recover-slow", "timeout", "races"])
parser.add_argument("--cohort", required=True, help="Метка когорты load/run.py --prepare-only")
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--project", default="cabinet-submission")
args = parser.parse_args()
if not re.fullmatch(r"cabinet-(?:submission|reliability|e2e|release)(?:-[a-z0-9]+)?", args.project):
    parser.error("Неверное имя отдельного тестового проекта")
if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.cohort):
    parser.error("Неверная метка когорты")
directory = ROOT / "load/reports" / args.cohort
cohort = json.loads((directory / "cohort.json").read_text())
api = Cabinet(args.base)
clients = cohort["clients"]
bank = cohort["underwriter"]
result = {"mode": args.mode, "project": args.project, "cohort": args.cohort, "started_at": datetime.now(timezone.utc).isoformat()}
observations = []


def observed_request(method, path, *arguments, **kwargs):
    started = time.monotonic()
    row = {"method": method}
    try:
        response = api.request(method, path, *arguments, **kwargs)
        row["status"] = response["status"]
        return response
    except (TimeoutError, ConnectionError, URLError) as error:
        row["error"] = type(error).__name__
        raise
    finally:
        row["seconds"] = time.monotonic() - started
        observations.append(row)


def docker(*arguments, check=True):
    process = subprocess.run(["docker", *arguments], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", check=check)
    return process.stdout


def containers(service):
    return docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}",
                  "--filter", f"label=com.docker.compose.service={service}").split()


pgs = containers("postgres")
assert len(pgs) == 1, "Нужен единственный PostgreSQL целевого проекта"
pg = pgs[0]
ids = ",".join(f"'{client['id']}'::uuid" for client in clients)


def sql(query):
    return json.loads(docker("exec", pg, "psql", "-U", "cabinet", "-d", "credit", "-tAc", query))


def jobs():
    return sql(f"select coalesce(json_agg(t),'[]'::json) from (select id,application_id,step,status,attempts,locked_until from pipeline_jobs where application_id in ({ids})) t")


def submit(client):
    return api.json("POST", f"/api/v1/applications/{client['id']}/submit", token=client["token"],
                    headers={"Idempotency-Key": f"chaos-{args.cohort}-{client['id']}"})


def audit(allow_manual=False):
    rows = []
    for client in clients:
        app = api.json("GET", f"/api/v1/applications/{client['id']}", token=client["token"])
        expected = {"OFFER_ISSUED", "REJECTED"} | ({"MANUAL_REVIEW"} if allow_manual else set())
        assert app["status"] in expected, (app["id"], app["status"])
        events = api.json("GET", f"/api/v1/applications/{client['id']}/events", token=client["token"])
        transitions = Counter((event["fromStatus"], event["toStatus"]) for event in events if event["fromStatus"] != event["toStatus"])
        assert all(count == 1 for count in transitions.values()), transitions
        assert sum(event["event"] == "SUBMIT" for event in events) == 1
        rows.append({"id": app["id"], "status": app["status"], "events": events})
    database = sql(f"""select json_build_object(
        'duplicate_offers',(select count(*) from (select application_id from offers where application_id in ({ids}) group by application_id having count(*)>1) d),
        'failed_jobs',(select count(*) from pipeline_jobs where application_id in ({ids}) and status='FAILED'),
        'due_open_jobs',(select count(*) from pipeline_jobs where application_id in ({ids}) and status in ('PENDING','RUNNING') and step not in ('EXPIRE_OFFER','EXPIRE_INFO_REQUEST')),
        'retried_jobs',(select count(*) from pipeline_jobs where application_id in ({ids}) and attempts>1))""")
    assert database["duplicate_offers"] == database["failed_jobs"] == database["due_open_jobs"] == 0, database
    result.update(applications=rows, database=database, jobs=jobs())


def finish(client):
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        try:
            response = observed_request("GET", f"/api/v1/applications/{client['id']}", token=client["token"], expected=(200, 502, 503, 504), timeout=15)
        except (TimeoutError, ConnectionError, URLError) as error:
            result.setdefault("transient_network", []).append({"id": client["id"], "error": type(error).__name__})
            time.sleep(0.5)
            continue
        if response["status"] != 200:
            result.setdefault("transient_http", []).append({"id": client["id"], "status": response["status"]})
        else:
            app = response["body"]
            if app["status"] in {"OFFER_ISSUED", "REJECTED"}:
                return
            if app["status"] == "MANUAL_REVIEW":
                try:
                    action = observed_request("POST", f"/api/v1/underwriting/applications/{client['id']}/approve", {
                        "comment": "Синтетическая проверка восстановления: сведения рассмотрены андеррайтером",
                    }, bank, expected=(200, 409, 502, 503, 504), timeout=15)
                    if action["status"] != 200:
                        result.setdefault("transient_http", []).append({"id": client["id"], "status": action["status"], "action": "approve"})
                except (TimeoutError, ConnectionError, URLError) as error:
                    result.setdefault("transient_network", []).append({"id": client["id"], "error": type(error).__name__, "action": "approve"})
        time.sleep(0.5)
    raise AssertionError(f"Заявка {client['id']} не восстановилась за 240 секунд")


def balance():
    replicas = containers("credit")
    assert len(replicas) == 2, "Для балансировки нужны ровно два экземпляра credit"
    metadata = json.loads(docker("inspect", *replicas))
    addresses = {item["NetworkSettings"]["Networks"][f"{args.project}_cabinet"]["IPAddress"]: item["Id"][:12] for item in metadata}
    time.sleep(12)
    prefix = f"balance-{args.cohort}-"
    def probe(index):
        api.json("GET", "/api/v1/products", headers={"X-Request-Id": prefix + str(index)})
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(probe, range(100)))
    gateway = containers("gateway")
    assert len(gateway) == 1
    logs = docker("logs", gateway[0])
    counts = Counter()
    for line in logs.splitlines():
        if prefix in line:
            match = re.search(r"upstream=([\d.]+):8080 status=200", line)
            if match:
                counts[match.group(1)] += 1
    assert set(counts) == set(addresses) and sum(counts.values()) == 100, (counts, addresses)
    result.update(upstreams=dict(counts), containers=addresses)


def disburse():
    started = time.monotonic()
    def sign(client):
        route = f"/api/v1/applications/{client['id']}"
        accepted = api.json("POST", route + "/offer/accept", token=client["token"])
        code = accepted["process"]["signing"]["code"]
        api.json("POST", route + "/sign", {"code": code}, client["token"])
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(sign, clients))
    rows = []
    for client in clients:
        api.wait(client["token"], client["id"], {"DISBURSED"}, timeout=120)
        events = api.json("GET", f"/api/v1/applications/{client['id']}/events", token=client["token"])
        assert sum(event["event"] == "DISBURSED" for event in events) == 1
        assert sum(event["event"] == "SIGN" for event in events) == 1
        rows.append({"id": client["id"], "sign_events": 1, "disbursed_events": 1})
    database = sql(f"""select json_build_object(
        'loans',(select count(*) from loans where application_id in ({ids})),
        'distinct_contracts',(select count(distinct contract_number) from loans where application_id in ({ids})),
        'disbursement_jobs',(select count(*) from pipeline_jobs where application_id in ({ids}) and step='DISBURSEMENT'),
        'completed_disbursement_jobs',(select count(*) from pipeline_jobs where application_id in ({ids}) and step='DISBURSEMENT' and status='DONE'))""")
    assert all(value == len(clients) for value in database.values()), database
    result.update(applications=rows, database=database, seconds_until_verified=time.monotonic()-started)


def fault_or_crash():
    original = api.json("GET", "/sim/control")
    previous = next(source["mode"] for source in original["sources"] if source["source"] == "BUREAU")
    victim = None
    started = time.monotonic()
    try:
        if args.mode != "crash":
            mode = "SLOW" if args.mode == "recover-slow" else "DOWN"
            api.json("PUT", "/sim/control/sources/BUREAU", {"mode": mode})
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(submit, clients))
        if args.mode == "crash":
            replicas = containers("credit")
            assert len(replicas) == 2 and len(clients) >= 20
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                running = [job for job in jobs() if job["status"] == "RUNNING"]
                # Each worker leases at most ten jobs before processing its batch serially.
                if len(running) > 10:
                    break
                time.sleep(0.05)
            assert len(running) > 10, "Не зафиксирована одновременно обрабатываемая пачка обоих экземпляров"
            victim = replicas[0]
            metadata = json.loads(docker("inspect", victim))[0]
            result.update(running_at_crash=running, stopped_container=victim,
                          before_crash={"name": metadata["Name"], "image_id": metadata["Image"], "state": metadata["State"]})
            docker("stop", "--time", "0", victim)
            state = json.loads(docker("inspect", victim))[0]["State"]
            assert not state["Running"] and state["ExitCode"] == 137, state
            result["stopped_state"] = state
        else:
            deadline = time.monotonic() + 60
            failed = []
            breaker = []
            while time.monotonic() < deadline:
                for client in clients:
                    events = api.json("GET", f"/api/v1/applications/{client['id']}/events", token=client["token"])
                    failed.extend(event for event in events if event["event"] == "SOURCE_FAILED")
                    breaker.extend(event for event in events if "после серии сбоев" in (event.get("comment") or ""))
                if failed and breaker:
                    break
                time.sleep(0.5)
            assert failed and breaker, "Не подтверждены сбой источника и открытый circuit breaker"
            result.update(source_failure_observed=True, breaker_open_observed=True, fault_seconds=time.monotonic() - started)
            if args.mode == "recover-slow":
                timed_out = any("timed out" in (event.get("comment") or "").lower() for event in failed)
                assert timed_out, "Медленный источник не вызвал подтверждённый HTTP-таймаут"
                result["source_http_timeout_observed"] = True
            def read_waiting():
                return [api.json("GET", f"/api/v1/applications/{client['id']}", token=client["token"])["status"] for client in clients]
            waiting = wait_for_enrichment(read_waiting)
            result["waiting_states"] = dict(Counter(waiting))
            if args.mode != "timeout":
                api.json("PUT", "/sim/control/sources/BUREAU", {"mode": "OK"})
        if args.mode == "timeout":
            for client in clients:
                api.wait(client["token"], client["id"], {"MANUAL_REVIEW"}, timeout=120)
            audit(allow_manual=True)
            assert all(any(event["event"] == "SOURCE_TIMED_OUT" for event in row["events"]) for row in result["applications"])
        else:
            with ThreadPoolExecutor(max_workers=8) as pool:
                list(pool.map(finish, clients))
            audit()
            if args.mode == "crash":
                assert result["database"]["retried_jobs"] >= 1, "Не подтверждён повтор захваченной задачи после падения"
            else:
                assert all(not any(event["event"] == "SOURCE_TIMED_OUT" for event in row["events"]) for row in result["applications"]), "Источник восстановлен слишком поздно"
        result["seconds_until_verified"] = time.monotonic() - started
    finally:
        try:
            api.json("PUT", "/sim/control/sources/BUREAU", {"mode": previous})
        finally:
            if victim:
                docker("start", victim)
                result["victim_restored"] = wait_for_container_healthy(
                    lambda: json.loads(docker("inspect", victim))[0]["State"])


def races():
    results = []
    for client in clients:
        app_id = client["id"]
        barrier = threading.Barrier(2)
        def same_submit(_):
            barrier.wait()
            return api.request("POST", f"/api/v1/applications/{app_id}/submit", token=client["token"], expected=None,
                               headers={"Idempotency-Key": f"race-{app_id}"})["status"]
        with ThreadPoolExecutor(max_workers=2) as pool:
            statuses = list(pool.map(same_submit, range(2)))
        assert sorted(statuses) in ([200, 200], [200, 409]), statuses
        api.json("POST", f"/api/v1/applications/{app_id}/submit", token=client["token"],
                 headers={"Idempotency-Key": f"race-{app_id}"})
        events = api.json("GET", f"/api/v1/applications/{app_id}/events", token=client["token"])
        assert sum(event["event"] == "SUBMIT" for event in events) == 1
        api.wait(client["token"], app_id, {"MANUAL_REVIEW"}, timeout=120)
        barrier = threading.Barrier(2)
        def action(side):
            barrier.wait()
            if side == "client":
                return api.request("POST", f"/api/v1/applications/{app_id}/withdraw", token=client["token"], expected=None)["status"]
            return api.request("POST", f"/api/v1/underwriting/applications/{app_id}/reject", {
                "comment": "Синтетическая проверка гонки: риск не принят специалистом",
            }, bank, expected=None)["status"]
        with ThreadPoolExecutor(max_workers=2) as pool:
            outcomes = list(pool.map(action, ["client", "bank"]))
        assert sorted(outcomes) == [200, 409], outcomes
        events = api.json("GET", f"/api/v1/applications/{app_id}/events", token=client["token"])
        terminal = [event for event in events if event["event"] in {"WITHDRAW", "REJECT"}]
        assert len(terminal) == 1, terminal
        current = api.json("GET", f"/api/v1/applications/{app_id}", token=client["token"])
        assert current["status"] in {"WITHDRAWN", "REJECTED"}, current["status"]
        persisted = sql(f"""select json_build_object(
            'manual_decisions',(select count(*) from decisions where application_id='{app_id}'::uuid and decided_by_type<>'SYSTEM'),
            'offers',(select count(*) from offers where application_id='{app_id}'::uuid),
            'open_jobs',(select count(*) from pipeline_jobs where application_id='{app_id}'::uuid and status in ('PENDING','RUNNING')))""")
        assert persisted["manual_decisions"] == (1 if current["status"] == "REJECTED" else 0), persisted
        assert persisted["offers"] == persisted["open_jobs"] == 0, persisted
        results.append({"id": app_id, "submit_statuses": statuses, "submit_events": 1,
                        "withdraw_reject_statuses": outcomes, "terminal_status": current["status"], "terminal_events": 1, "database": persisted})
    result["double_submits"] = results


try:
    if args.mode == "balance":
        balance()
    elif args.mode == "disburse":
        disburse()
    elif args.mode == "races":
        races()
    else:
        fault_or_crash()
    result["passed"] = True
except Exception as error:
    result.update(passed=False, error=str(error), jobs_at_failure=jobs())
    raise
finally:
    if observations:
        values = sorted(row["seconds"] for row in observations)
        result["recovery_http"] = {
            "n": len(values), "client_timeout_seconds": 15,
            "statuses": dict(Counter(str(row.get("status", "network_error")) for row in observations)),
            "errors": dict(Counter(row["error"] for row in observations if "error" in row)),
            "percentile_method": "nearest_rank",
            "p50_seconds": values[math.ceil(len(values) * .5) - 1],
            "p95_seconds": values[math.ceil(len(values) * .95) - 1],
            "p99_seconds": values[math.ceil(len(values) * .99) - 1],
            "requests": observations,
        }
    result["finished_at"] = datetime.now(timezone.utc).isoformat()
    (directory / f"{args.mode}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in {"applications", "jobs", "running_at_crash", "before_crash", "jobs_at_failure"}}, ensure_ascii=False, indent=2))
