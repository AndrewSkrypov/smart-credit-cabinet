"""Run the packaged Credit and Assessment against isolated PostgreSQL, Kafka, and HTTP fixtures."""

import argparse
import base64
from collections import defaultdict
from contextlib import ExitStack
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import tempfile
from threading import Thread
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import UUID, uuid4
from xml.etree import ElementTree


def wait_for(condition, description, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = condition()
        if value:
            return value
        time.sleep(0.3)
    raise AssertionError(description)


def allocate_ports(count):
    with ExitStack() as stack:
        sockets = [stack.enter_context(socket.socket()) for _ in range(count)]
        for listener in sockets:
            listener.bind(("127.0.0.1", 0))
        return [listener.getsockname()[1] for listener in sockets]


def listening(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return True
    except OSError:
        return False


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(25)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(5)


class SourceFixtures:
    def __init__(self):
        self.tax_available = False
        self.calls = defaultdict(int)
        self.consents = []

    def response(self, path, headers):
        last = path.rsplit("/", 1)[-1]
        if path.startswith("/internal/v1/users/"):
            return 200, {"credentialsVersion": 0}
        if path.startswith("/internal/v1/companies/"):
            return 200, {"id": last, "inn": "0000000000", "name": "Synthetic", "legalForm": "ENTREPRENEUR"}
        if path.startswith("/ext/bank/"):
            self.calls["bank"] += 1
            return 200, {"client": True, "settlementAccount": "00000000000000000001",
                         "turnovers": [{"inflow": 1080000 + 100000 * (i % 2)} for i in range(12)]}
        if path.startswith("/ext/registry/"):
            self.calls["registry"] += 1
            return 200, {"inn": last, "ogrn": "315000000000206", "fullName": "Synthetic", "shortName": "Synthetic",
                         "status": "ACTIVE", "statusTitle": "Active", "registrationDate": "2018-01-01",
                         "okvedMain": {"code": "56.10", "name": "Cafe"}, "director": {"fullName": "Synthetic"},
                         "address": "Synthetic", "unreliableAddress": False, "unreliableDirector": False}
        if path.startswith("/ext/fns/"):
            self.calls["tax"] += 1
            if not self.tax_available:
                return 503, {}
            return 200, {"hasReports": True, "reportingYear": 2025, "revenue": 14400000, "expenses": 12600000,
                         "taxDebt": 0, "averageHeadcount": 6, "taxRegime": "USN", "massAddress": False}
        if path.startswith("/ext/bureau/"):
            self.calls["bureau"] += 1
            consent = headers.get("X-Consent-Id")
            if not consent:
                return 400, {}
            self.consents.append(consent)
            return 200, {"obligations": [], "monthlyPaymentsTotal": 30000, "currentOverdueDaysMax": 0,
                         "worstOverdueDays24m": 0, "inquiries30d": 1}
        return 404, {}

    def serve(self):
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                status, value = fixture.response(self.path, self.headers)
                payload = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        Thread(target=server.serve_forever, daemon=True).start()
        return server


def sign_client(secret):
    def encode(data):
        return base64.urlsafe_b64encode(data).rstrip(b"=")

    claims = {"sub": str(uuid4()), "role": "CLIENT", "name": "Synthetic", "cid": str(uuid4()),
              "sign": True, "exp": int(time.time()) + 600}
    message = encode(b'{"alg":"HS256","typ":"JWT"}') + b"." + encode(json.dumps(claims).encode())
    return (message + b"." + encode(hmac.digest(secret.encode(), message, hashlib.sha256))).decode()


def submit(port, secret):
    headers = {"Authorization": "Bearer " + sign_client(secret), "Content-Type": "application/json"}
    terms = {"productCode": "WORKING_CAPITAL", "purpose": "WORKING_CAPITAL", "amount": 1000000, "termMonths": 12,
             "declaredRevenue12m": 14400000, "declaredMonthlyDebtPayments": 30000, "bureauConsent": True}
    base = f"http://127.0.0.1:{port}/api/v1/applications"
    try:
        with urlopen(Request(base, json.dumps(terms).encode(), headers, method="POST"), timeout=10) as response:
            assert response.status == 201
            app_id = UUID(json.load(response)["id"])
        with urlopen(Request(f"{base}/{app_id}/submit", headers=headers, method="POST"), timeout=10) as response:
            assert response.status == 200
    except HTTPError as error:
        raise AssertionError(f"Credit returned HTTP {error.code}: {error.read(500).decode(errors='replace')}") from error
    return app_id


def verify(args, run_dir, cleanup):
    if args.postgres_port:
        broker_port, controller_port, credit_port = allocate_ports(3)
        db_port = args.postgres_port
        db_user = "cabinet"
        db_password = os.environ["PGPASSWORD"]
    else:
        db_port, broker_port, controller_port, credit_port = allocate_ports(4)
        db_user = "assessment_check"
        db_password = "synthetic"
    db_host = ["-h", "127.0.0.1", "-p", str(db_port), "-U", db_user]
    suffix = uuid4().hex[:16]
    credit_db = f"r2_exchange_credit_{suffix}"
    assessment_db = f"r2_exchange_assessment_{suffix}"
    data_dir = run_dir / "postgres"
    db_log = run_dir / "postgres.log"
    java_home = os.environ.get("JAVA_HOME")
    java = Path(java_home) / "bin/java" if java_home else "java"
    source = SourceFixtures()
    source_server = cleanup.enter_context(source.serve())
    cleanup.callback(source_server.shutdown)
    source_url = f"http://127.0.0.1:{source_server.server_port}"
    stream = "synthetic.proof"
    bootstrap = f"127.0.0.1:{broker_port}"
    secret = secrets.token_hex(16)
    host_env = {key: os.environ[key] for key in ("JAVA_HOME", "PATH", "LD_LIBRARY_PATH", "LANG", "TZ") if key in os.environ}

    def pg(command, *arguments):
        return subprocess.check_output([args.pg_bin / command, *arguments], env=host_env | {"PGPASSWORD": db_password}, text=True,
                                       stderr=subprocess.STDOUT, timeout=25)

    def query(database, sql):
        return pg("psql", *db_host, "-d", database, "-XqAt", "-v", "ON_ERROR_STOP=1", "-c", sql).strip()

    if not args.postgres_port:
        print(pg("initdb", "-D", data_dir, "-U", db_user, "--auth=trust", "--locale=C", "--encoding=UTF8").splitlines()[-1])
        pg("pg_ctl", "-D", data_dir, "-l", db_log, "-w", "start",
           "-o", f"-h 127.0.0.1 -p {db_port} -k {run_dir} -c max_connections=40 -c shared_buffers=32MB")
        cleanup.callback(pg, "pg_ctl", "-D", data_dir, "-w", "-m", "fast", "stop")
    for database in (credit_db, assessment_db):
        query("postgres", f"create database {database}")
        cleanup.callback(query, "postgres", f"drop database {database} with (force)")
    print(query(credit_db, "select version()"))

    env = host_env | {
        "DB_HOST": "127.0.0.1", "DB_PORT": str(db_port), "DB_NAME": credit_db, "DB_USER": db_user,
        "DB_PASSWORD": db_password, "JWT_SECRET": secret, "IDENTITY_URL": source_url, "EXTERNAL_SIM_URL": source_url,
        "SERVER_PORT": str(credit_port), "UPLOAD_DIR": str(run_dir / "uploads"), "ANTIVIRUS_ENABLED": "false",
        "OUTBOX_WORKER_ENABLED": "false", "SOURCE_TIMEOUT": "PT30S", "CABINET_ASSESSMENT_KAFKA_ENABLED": "true",
        "CABINET_ASSESSMENT_KAFKA_BOOTSTRAP_SERVERS": bootstrap, "CABINET_ASSESSMENT_KAFKA_ENVIRONMENT": "synthetic",
        "CABINET_ASSESSMENT_KAFKA_GENERATION": "proof", "CABINET_ASSESSMENT_SOURCES_URL": source_url,
        "CABINET_ASSESSMENT_SOURCES_API_KEY": "synthetic",
        "ASSESSMENT_DB_URL": f"jdbc:postgresql://127.0.0.1:{db_port}/{assessment_db}",
        "ASSESSMENT_DB_USER": db_user, "ASSESSMENT_DB_PASSWORD": db_password,
        "KAFKA_HEAP_OPTS": "-Xms96m -Xmx320m", "KAFKA_OPTS": "-XX:-UseBASE64Intrinsics -XX:ActiveProcessorCount=2",
        "LOG_DIR": str(run_dir / "kafka-logs"),
    }

    kafka_config = run_dir / "kafka.properties"
    kafka_config.write_text(f"""process.roles=broker,controller
node.id=1
controller.quorum.bootstrap.servers=127.0.0.1:{controller_port}
listeners=PLAINTEXT://{bootstrap},CONTROLLER://127.0.0.1:{controller_port}
advertised.listeners=PLAINTEXT://{bootstrap},CONTROLLER://127.0.0.1:{controller_port}
inter.broker.listener.name=PLAINTEXT
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
log.dirs={run_dir / "kafka-data"}
offsets.topic.replication.factor=1
offsets.topic.num.partitions=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
auto.create.topics.enable=false
group.initial.rebalance.delay.ms=0
num.io.threads=3
num.network.threads=2
""")

    def spawn(name, command):
        with (run_dir / f"{name}.log").open("a") as log:
            process = subprocess.Popen(command, env=env, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT)
        cleanup.callback(stop, process)
        return process

    def kafka(script, *arguments, input_text=None):
        return subprocess.run([args.kafka_home / "bin" / script, *arguments], env=env, text=True, input=input_text,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=35, check=True).stdout

    def start_broker():
        broker = spawn("broker", [args.kafka_home / "bin/kafka-server-start.sh", kafka_config])
        wait_for(lambda: listening(broker_port), "Kafka listener never started")
        return broker

    def start_assessment():
        return spawn("assessment", [java, "-Xmx320m", "-XX:ActiveProcessorCount=2", "-jar", args.assessment])

    def ready(process, check):
        if process.poll() is not None:
            raise AssertionError(f"Process {process.pid} exited with status {process.returncode}")
        return check()

    def app_status(app_id):
        return query(credit_db, f"select status from applications where id='{app_id}'")

    def consumed(direction, consumer_name, expected):
        group = f"alpha.{stream}.assessment.v1.{consumer_name}"
        topic = f"alpha.synthetic.assessment.v1.proof.{direction}"
        offsets = kafka("kafka-consumer-groups.sh", "--bootstrap-server", bootstrap, "--describe", "--group", group)
        for line in offsets.splitlines():
            fields = line.split()
            if len(fields) >= 6 and fields[:3] == [group, topic, "0"]:
                current, end, lag = fields[3:6]
                return current.isdecimal() and int(current) >= expected and current == end and lag == "0"
        return False

    def applied(app_id):
        return query(credit_db, f"""
            select (select count(*) from decisions where application_id='{app_id}'),
                   (select count(*) from offers where application_id='{app_id}'),
                   (select count(*) from credit_assessment_cycle where application_id='{app_id}' and closed_at is not null)
        """)

    kafka("kafka-storage.sh", "format", "--standalone", "-t", "JJABkEHaReKNgTEht9Ml3w", "-c", kafka_config)
    broker = start_broker()
    for direction in ("commands", "results"):
        print(kafka("kafka-topics.sh", "--bootstrap-server", bootstrap, "--create", "--topic",
                    f"alpha.synthetic.assessment.v1.proof.{direction}", "--partitions", "1", "--replication-factor", "1").strip())
    assessment = start_assessment()
    credit = spawn("credit", [java, "-Xmx450m", "-XX:ActiveProcessorCount=2", "-jar", args.credit,
                              "--server.address=127.0.0.1", "--cabinet.assessment.kafka.result-grace=PT12S"])
    wait_for(lambda: ready(credit, lambda: listening(credit_port)), "Credit listener never started", timeout=55)
    wait_for(lambda: ready(assessment, lambda: "Started AssessmentApplication" in (run_dir / "assessment.log").read_text()),
             "Assessment never started", timeout=55)
    print(f"Started Credit PID {credit.pid}, Assessment PID {assessment.pid}, Kafka PID {broker.pid}")

    first = submit(credit_port, secret)
    wait_for(lambda: query(assessment_db, f"""
        select count(*) from assessment_execution where application_id='{first}' and completed_at is null
              and progress->'facts'->'registry' <> 'null' and progress->'facts'->'bureau' <> 'null'
    """) == "1", "Assessment never persisted partial facts")
    before_restart = dict(source.calls)
    stop(assessment)
    source.tax_available = True
    restarted = start_assessment()
    wait_for(lambda: app_status(first) == "OFFER_ISSUED", "Restored Assessment did not finish the application")
    assert source.calls["registry"] == before_restart["registry"] == 1
    assert source.calls["bureau"] == before_restart["bureau"] == 1
    assert source.calls["tax"] > before_restart["tax"]
    assert source.consents == [str(first)]
    assert applied(first) == "1|1|1"
    assert query(credit_db, f"""
        select count(*) from credit_assessment_cycle c join decisions d on d.id=c.cycle_id
        where c.application_id='{first}' and c.stream='{stream}'
          and d.rule_set_version=(c.request->'rules'->>'version')::integer
          and c.request->'cycle' = c.result->'cycle'
    """) == "1"
    print(f"Assessment restarted as PID {restarted.pid}; reused persisted registry and bureau facts; one decision and offer")

    for direction, column in (("commands", "request"), ("results", "result")):
        payload = query(credit_db, f"select {column}::text from credit_assessment_cycle where application_id='{first}'")
        assert payload
        kafka("kafka-console-producer.sh", "--bootstrap-server", bootstrap, "--topic",
              f"alpha.synthetic.assessment.v1.proof.{direction}", "--property", "parse.key=true",
              input_text=f"{first}\t{payload}\n")
    wait_for(lambda: consumed("commands", "executor", 2), "Assessment did not confirm the replayed command")
    wait_for(lambda: consumed("results", "credit", 2), "Credit did not confirm the replayed result")
    assert applied(first) == "1|1|1"
    assert query(assessment_db, f"""
        select (select count(*) from assessment_execution where application_id='{first}'),
               (select count(*) from assessment_exchange_outbox where application_id='{first}')
    """) == "1|1"
    assert source.consents == [str(first)]
    print("Kafka replayed the command and result; both consumer groups confirmed offsets and kept one effect")

    stop(broker)
    second = submit(credit_port, secret)
    wait_for(lambda: query(credit_db, f"""
        select count(*) from assessment_exchange_outbox where application_id='{second}'
              and last_error is not null and sent_at is null
    """) == "1", "The unpublished command was not recorded during Kafka outage")
    assert app_status(second) == "ENRICHMENT"
    new_broker = start_broker()
    wait_for(lambda: app_status(second) == "OFFER_ISSUED", "Application did not recover after Kafka restart")
    assert applied(second) == "1|1|1"
    assert source.consents == [str(first), str(second)]
    print(f"Kafka restarted as PID {new_broker.pid}; Credit retried its persisted command and issued one offer")

    source.tax_available = False
    third = submit(credit_port, secret)
    wait_for(lambda: app_status(third) == "MANUAL_REVIEW", "Incomplete source did not reach manual review", timeout=50)
    assert applied(third) == "1|0|1"
    assert query(credit_db, f"""
        select (result->>'outcome'), (result->'missingSources'->>0), (result->'facts'->'registry' <> 'null')
        from credit_assessment_cycle where application_id='{third}'
    """) == "INCOMPLETE|TAX|t"
    assert source.consents == [str(first), str(second), str(third)]
    print("Tax remained unavailable; Credit kept the partial facts and recorded one manual decision")

    stop(restarted)
    fourth = submit(credit_port, secret)
    wait_for(lambda: app_status(fourth) == "MANUAL_REVIEW", "Credit did not close an unavailable Assessment cycle", timeout=55)
    assert applied(fourth) == "1|0|1"
    final_assessment = start_assessment()
    wait_for(lambda: query(assessment_db, f"""
        select count(*) from assessment_exchange_outbox where application_id='{fourth}' and sent_at is not null
    """) == "1", "Assessment did not publish the late result after returning")
    wait_for(lambda: consumed("results", "credit", 5), "Credit did not confirm the late result offset")
    assert query(credit_db, f"""
        select count(*) from credit_assessment_cycle where application_id='{fourth}' and result is null
    """) == "1"
    assert applied(fourth) == "1|0|1"
    assert source.consents == [str(first), str(second), str(third)]
    print("Assessment returned after Credit's deadline; the late publication produced no extra decision or offer")

    for process in (final_assessment, credit):
        stop(process)
    with (run_dir / "invalid-config.log").open("w") as log:
        invalid = subprocess.run([java, "-Xmx240m", "-XX:ActiveProcessorCount=2", "-jar", args.assessment,
                                  "--cabinet.assessment.kafka.bootstrap-servers="],
                                 env=env, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT, timeout=20, check=False)
    assert invalid.returncode != 0
    assert "Assessment Kafka requires explicit broker addresses" in (run_dir / "invalid-config.log").read_text()
    print("Assessment failed startup with remote mode enabled and broker addresses missing")

    if args.require_replay_test:
        root = Path(__file__).resolve().parents[2]
        gradle_cache_env = {
            name: os.environ[name] for name in ("GRADLE_USER_HOME", "GRADLE_RO_DEP_CACHE") if name in os.environ
        }
        replay_env = host_env | gradle_cache_env | {
            **{name: env[name] for name in ("ASSESSMENT_DB_URL", "ASSESSMENT_DB_USER", "ASSESSMENT_DB_PASSWORD")},
            "R2_KAFKA_BOOTSTRAP": bootstrap,
            "CABINET_ASSESSMENT_KAFKA_ENABLED": "false",
        }
        gradle = os.environ.get("ASSESSMENT_TEST_GRADLE", "./gradlew")
        subprocess.run([gradle, ":services:assessment:assessmentKafkaTest", "--no-daemon", "--max-workers=1"],
                       env=replay_env, cwd=root, timeout=300, check=True)
        reports = root / "services/assessment/build/test-results/assessmentKafkaTest"
        report, = reports.glob("TEST-*.xml")
        summary = ElementTree.parse(report).getroot().attrib
        assert summary["name"].endswith(".AssessmentKafkaExchangeTest")
        assert [summary[key] for key in ("tests", "skipped", "failures", "errors")] == ["1", "0", "0", "0"]
        print("AssessmentKafkaExchangeTest passed with zero skips")
    stop(new_broker)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("credit", "assessment", "pg-bin", "kafka-home"):
        parser.add_argument(f"--{name}", type=lambda path: Path(path).resolve(strict=True), required=True)
    parser.add_argument("--postgres-port", type=int, metavar="PORT")
    parser.add_argument("--require-replay-test", action="store_true")
    args = parser.parse_args()
    if args.postgres_port is not None and not 1 <= args.postgres_port <= 65535:
        parser.error("--postgres-port must be in 1..65535")

    def terminate(signum, _frame):
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGTERM, terminate)
    with tempfile.TemporaryDirectory(prefix="alpha-r2-e2e.", dir="/tmp") as workspace:
        run_dir = Path(workspace)
        try:
            with ExitStack() as cleanup:
                verify(args, run_dir, cleanup)
        except Exception:
            for name in ("credit.log", "assessment.log", "broker.log", "postgres.log", "invalid-config.log"):
                log_path = run_dir / name
                if log_path.exists():
                    print(f"--- {name} ---")
                    print("\n".join(log_path.read_text(errors="replace").splitlines()[-45:]))
            raise


if __name__ == "__main__":
    main()
