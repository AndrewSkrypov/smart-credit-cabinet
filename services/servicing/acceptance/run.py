#!/usr/bin/env python3
"""Reproducible isolated R3 acceptance. Never connects to an existing database/daemon."""
import argparse
from contextlib import contextmanager
import fcntl
from hashlib import sha256
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

from postgres_backend import CiPostgres, tool_environment

REPO = Path(__file__).resolve().parents[3]


def port():
    with socket.socket() as handle:
        handle.bind(("127.0.0.1", 0))
        return handle.getsockname()[1]


@contextmanager
def heavy_lock(timeout):
    with open("/tmp/alpha-heavy-jvm.lock", "a") as handle:
        until = time.monotonic() + timeout
        while True:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= until:
                    raise RuntimeError("Heavy JVM window is occupied")
                time.sleep(0.25)
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.root = args.artifacts.resolve()
        self.root.mkdir(mode=0o700)
        (self.root / "home").mkdir(mode=0o700)
        self.env = tool_environment(os.environ, self.root / "home", args.inherit_proxy_env)
        self.env.update(JAVA_HOME=str(args.java_home), GRADLE_USER_HOME=str(args.gradle_user_home))
        if args.read_only_dependency_cache:
            self.env["GRADLE_RO_DEP_CACHE"] = str(args.read_only_dependency_cache)
        self.pg_bin = args.pg_bin if args.ci_postgres_image else args.pg_root / "usr/lib/postgresql" / args.pg_version / "bin"
        if not args.ci_postgres_image:
            self.env["LD_LIBRARY_PATH"] = str(args.pg_root / "usr/lib/x86_64-linux-gnu")
        self.ci_postgres = None
        self.bootstrap_user = "r3_bootstrap"
        self.passwords = {role: secrets.token_hex(24) for role in (self.bootstrap_user, "credit_owner", "credit_app", "servicing_owner", "servicing_app")}
        self.env["PSQL"] = str(self.pg_bin / "psql")
        self.pg_port = port()
        self.grpc_port, self.http_port = port(), port()
        self.stand = None
        self.pg_started = False
        self.stage = "setup"
        self.receipt = {"status": "RUNNING", "checks": {}, "commands": [], "sourceSha": self.git("rev-parse", "HEAD"),
                        "sourceTree": self.git("rev-parse", "HEAD^{tree}"), "dirty": bool(self.git("status", "--porcelain")),
                        "environment": {"nativePostgres": not bool(args.ci_postgres_image), "docker": bool(args.ci_postgres_image), "grpcPort": self.grpc_port}}
        self.env["SOURCE_REVISION"] = self.receipt["sourceSha"]
        self.jwt = secrets.token_hex(32)
        self.db_password = self.passwords["credit_app"]
        self.env.update(DB_HOST="127.0.0.1", DB_PORT=str(self.pg_port), DB_PASSWORD=self.db_password,
                        JWT_SECRET=self.jwt, SPRING_FLYWAY_ENABLED="false", PIPELINE_ENABLED="false", OUTBOX_WORKER_ENABLED="false",
                        SERVICING_MODE="local", CABINET_SERVICING_MODE="local", APPLICATION_STATUS_EVENTS_ENABLED="false", IDENTITY_URL="", ENGAGEMENT_URL="", UPLOAD_DIR=str(self.root / "credit-uploads"),
                        ALPHA_SERVICING_TEST_JWT_SECRET=self.jwt, ALPHA_SERVICING_FIXTURE_DIR=str(self.root),
                        ALPHA_SERVICING_LIVE_TLS=str(self.root / "tls"), ALPHA_SERVICING_LIVE_ARTIFACTS=str(self.root),
                        ALPHA_SERVICING_LIVE_PORT=str(self.grpc_port))
        self.outputs = args.outputs_dir.resolve() if args.outputs_dir else self.root / "outputs"
        output = str(self.outputs).replace("\\", "\\\\").replace("'", "\\'")
        self.init = self.root / "relocate.gradle"
        self.init.write_text("allprojects { layout.buildDirectory.set(file('" + output + "' + '/' + rootProject.name + project.path.replace(':','_'))); tasks.withType(Test).configureEach { maxHeapSize='256m'; outputs.upToDateWhen { false } } }\n")
        self.write_receipt()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=REPO, text=True).strip()

    def run(self, label, command, env=None, accepted_codes=(0,)):
        self.stage = label
        print(label, flush=True)
        started = time.monotonic()
        with (self.root / (label + ".log")).open("wb") as log:
            result = subprocess.run(list(map(str, command)), cwd=REPO, env=env or self.env, stdout=log, stderr=subprocess.STDOUT, close_fds=True)
        self.receipt["commands"].append({"stage": label, "command": list(map(str, command)), "exitCode": result.returncode,
                                         "elapsedSeconds": round(time.monotonic() - started, 3), "acceptedExitCodes": list(accepted_codes)})
        self.write_receipt()
        if result.returncode not in accepted_codes:
            raise RuntimeError(label + " failed; inspect its local log")

    def gradle(self, label, tasks, extra=None):
        env = self.env.copy()
        env.update(extra or {})
        command = [self.args.gradle, "--no-daemon", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=384m",
                   "-Pkotlin.compiler.execution.strategy=in-process", "--project-cache-dir", self.args.project_cache_dir or self.root / "project-cache", "--init-script", self.init, *tasks]
        if self.args.write_locks:
            command.append("--write-locks")
        self.run(label, command, env)

    def output(self, module):
        return self.outputs / ("smart-credit-cabinet" + module.replace(":", "_"))

    def tests(self, module, label, minimum=1):
        files = list((self.output(module) / "test-results/test").glob("TEST-*.xml"))
        destination = self.root / "results" / label
        destination.mkdir(parents=True)
        counts = {key: 0 for key in ("tests", "skipped", "failures", "errors")}
        for file in files:
            root = ET.parse(file).getroot()
            for key in counts:
                counts[key] += int(root.attrib.get(key, 0))
            shutil.copy2(file, destination / file.name)
        if counts["tests"] < minimum or any(counts[key] for key in ("skipped", "failures", "errors")):
            raise RuntimeError(label + " did not run the required tests without skips: " + str(counts))
        self.receipt["checks"][label] = counts
        self.write_receipt()

    def sql(self, database, role, query, failure=False):
        result = subprocess.run([self.pg_bin / "psql", "-XAtq", "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1", "-p", str(self.pg_port), "-U", role, database],
                                input=query, text=True, capture_output=True, env={**self.env, "PGPASSWORD": self.passwords[role]}, close_fds=True)
        if (result.returncode == 0) == failure:
            raise RuntimeError("Unexpected PostgreSQL result in " + self.stage)
        return result.stdout.strip()

    def start_pg(self):
        if self.args.ci_postgres_image:
            self.ci_postgres = CiPostgres(self.args.ci_postgres_image, self.root, self.env)
            self.pg_port = self.ci_postgres.start(self.bootstrap_user, self.passwords[self.bootstrap_user])
        else:
            password_file = self.root / "bootstrap-password"
            password_file.write_text(self.passwords[self.bootstrap_user])
            password_file.chmod(0o600)
            self.run("pg-init", [self.pg_bin / "initdb", "-D", self.root / "pg-data", "-U", self.bootstrap_user, "-A", "scram-sha-256", "--pwfile", password_file, "--no-locale", "-E", "UTF8"])
            self.run("pg-start", [self.pg_bin / "pg_ctl", "-D", self.root / "pg-data", "-l", self.root / "postgres.log", "-o",
                     f"-h 127.0.0.1 -p {self.pg_port} -k {self.root} -c shared_buffers=16MB -c max_connections=40 -c wal_level=minimal -c max_wal_senders=0 -c max_wal_size=128MB -c min_wal_size=32MB -c password_encryption=scram-sha-256", "-w", "start"])
            self.pg_started = True
        self.env["DB_PORT"] = str(self.pg_port)
        self.grpc_port, self.http_port = port(), port()
        while self.http_port == self.grpc_port:
            self.http_port = port()
        self.env["ALPHA_SERVICING_LIVE_PORT"] = str(self.grpc_port)
        self.receipt["environment"]["grpcPort"] = self.grpc_port
        until = time.monotonic() + 60
        while True:
            try:
                self.sql("postgres", self.bootstrap_user, "select 1;")
                break
            except RuntimeError:
                if time.monotonic() >= until:
                    raise RuntimeError("Owned PostgreSQL readiness timeout")
                time.sleep(0.5)
        bootstrap = ";".join("create role " + role + " login password '" + password + "'" for role, password in self.passwords.items() if role != self.bootstrap_user)
        self.sql("postgres", self.bootstrap_user, bootstrap + ";create database credit owner credit_owner;create database servicing owner servicing_owner;")
        for db in ("postgres", "template0", "template1", "credit", "servicing"):
            self.sql("postgres", self.bootstrap_user, f"revoke connect,temporary on database {db} from public;")
        service_file = ""
        for db in ("credit", "servicing"):
            self.sql("postgres", self.bootstrap_user, f"grant connect on database {db} to {db}_owner,{db}_app;revoke create,temporary on database {db} from {db}_app;")
            for role in ("owner", "app"):
                user = db + "_" + role
                service_file += f"[{user}]\nhost=127.0.0.1\nport={self.pg_port}\ndbname={db}\nuser={user}\npassword={self.passwords[user]}\n\n"
        (self.root / "pg_service.conf").write_text(service_file)
        (self.root / "pg_service.conf").chmod(0o600)
        self.env["PGSERVICEFILE"] = str(self.root / "pg_service.conf")
        hashes = self.sql("postgres", self.bootstrap_user, "select bool_and(rolpassword like 'SCRAM-SHA-256$%') from pg_authid where rolname in ('credit_owner','credit_app','servicing_owner','servicing_app');")
        if hashes != "t":
            raise RuntimeError("Owned database roles must use SCRAM credentials")
        wrong = subprocess.run([self.pg_bin / "psql", "-XAtq", "-h", "127.0.0.1", "-p", str(self.pg_port), "-U", "credit_app", "credit", "-c", "select 1"],
                               env={**self.env, "PGPASSWORD": secrets.token_hex(24)}, capture_output=True, close_fds=True)
        if wrong.returncode == 0:
            raise RuntimeError("Owned PostgreSQL accepted an incorrect runtime password")
        self.receipt["checks"]["owned-scram-roles"] = True
        self.receipt["checks"]["owned-scram-auth-required"] = True

    def tls(self):
        directory = self.root / "tls"
        directory.mkdir(mode=0o700)
        self.run("tls-ca", ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", directory / "ca.key", "-out", directory / "ca.crt", "-days", "2", "-subj", "/CN=Alpha R3 isolated acceptance CA"])
        for name, san, usage in (("server", "DNS:localhost,IP:127.0.0.1,URI:spiffe://creditcabinet/servicing", "serverAuth"),
                                 ("credit", "URI:spiffe://creditcabinet/credit", "clientAuth"), ("other", "URI:spiffe://creditcabinet/other", "clientAuth")):
            (directory / (name + ".ext")).write_text(f"subjectAltName={san}\nextendedKeyUsage={usage}\n")
            self.run("tls-" + name, ["openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", directory / (name + ".key"), "-out", directory / (name + ".csr"), "-subj", "/CN=alpha-r3-" + name])
            self.run("tls-sign-" + name, ["openssl", "x509", "-req", "-in", directory / (name + ".csr"), "-CA", directory / "ca.crt", "-CAkey", directory / "ca.key", "-CAcreateserial", "-out", directory / (name + ".crt"), "-days", "2", "-extfile", directory / (name + ".ext")])
        for file in directory.glob("*.key"):
            file.chmod(0o600)

    def migrate(self, db):
        env = self.env.copy()
        env.update(MIGRATION_ONLY="true", DB_NAME=db, DB_USER=db + "_owner", DB_PASSWORD=self.passwords[db + "_owner"])
        jar = self.output(":services:" + db) / "libs/app.jar"
        self.run("migration-" + db, [self.args.java_home / "bin/java", "-Xmx192m", "-jar", jar], env)
        self.receipt["checks"]["flyway-" + db] = self.sql(db, db + "_owner", "select string_agg(version,',' order by installed_rank) from flyway_schema_history where success;")

    def transfer(self, direction, label):
        self.run(label, [sys.executable, REPO / "services/servicing/cutover/transfer.py", direction, "--credit-service", "credit_owner", "--servicing-service", "servicing_owner", "--artifacts", self.root / label])
        manifest = json.loads((self.root / label / "source-fenced.json").read_text())
        self.receipt["checks"][label] = {"sha256": manifest["sha256"], "counts": {table: len(rows) for table, rows in manifest["tables"].items()}}

    def operation_receipts(self, database):
        return json.loads(self.sql(database, database + "_owner", "set timezone='UTC'; select coalesce(jsonb_agg(to_jsonb(r)||jsonb_build_object('result',r.result::text) order by loan_account_id,operation_key),'[]'::jsonb) from loan_operations r;"))

    def retired_receipts(self, database):
        permissions = (REPO / "libs/platform-common/src/main/resources/cabinet/database-permissions.sql").read_text().replace("__OWNER__", database + "_owner").replace("__APP__", database + "_app")
        self.sql(database, database + "_owner", permissions)
        denied = self.sql(database, database + "_owner", f"select not(has_table_privilege('{database}_app','loan_operations','SELECT') or has_table_privilege('{database}_app','loan_operations','INSERT') or has_table_privilege('{database}_app','loan_operations','UPDATE') or has_table_privilege('{database}_app','loan_operations','DELETE'));")
        if denied != "t":
            raise RuntimeError("Retired operation receipts regained runtime privileges")
        self.sql(database, database + "_app", "select * from loan_operations;", failure=True)

    def forward_with_activation_fault(self):
        tool = REPO / "services/servicing/cutover/transfer.py"
        options = ["remote", "--credit-service", "credit_owner", "--servicing-service", "servicing_owner"]
        directory = self.root / "forward"
        self.run("forward-fault", [sys.executable, tool, *options, "--artifacts", directory, "--test-fail-after-target-activation"], accepted_codes=(1,))
        if self.sql("credit", "credit_owner", "select writer from servicing_writer_fence where singleton;") != "FENCED" or self.sql("servicing", "servicing_owner", "select writer from servicing_writer_fence where singleton;") != "REMOTE":
            raise RuntimeError("The injected checkpoint did not leave exactly the expected authority")
        self.retired_receipts("credit")
        legacy = json.loads((self.root / "legacy-account.json").read_text())
        loan_id = str(uuid.UUID(legacy["id"]))
        # Explicit storage fault fixture. The runtime payment path is tested independently below.
        self.sql("servicing", "servicing_app", f"""begin;
        update loan_accounts a set outstanding_principal=a.outstanding_principal-p.principal_amount,updated_at=now()
          from loan_payments p where p.loan_account_id=a.id and a.id='{loan_id}' and p.payment_number=1;
        update loan_payments set status='PAID',paid_at=now() where loan_account_id='{loan_id}' and payment_number=1;
        commit;""")
        before = self.sql("servicing", "servicing_app", f"select outstanding_principal::text from loan_accounts where id='{loan_id}';")
        manifest = directory / "source-fenced.json"
        self.run("active-target-abort-refused", [sys.executable, tool, *options, "--recover-manifest", manifest, "--abort"], accepted_codes=(1,))
        self.run("postactivation-resume", [sys.executable, tool, *options, "--recover-manifest", manifest])
        if before != self.sql("servicing", "servicing_app", f"select outstanding_principal::text from loan_accounts where id='{loan_id}';"):
            raise RuntimeError("Postactivation resume overwrote a later target payment")
        source = json.loads(manifest.read_text())
        self.receipt["checks"]["forward"] = {"sha256": source["sha256"], "counts": {table: len(rows) for table, rows in source["tables"].items()}}
        self.receipt["checks"]["postactivation-new-payment-preserved"] = True

    def reverse_with_abort_fault(self):
        tool = REPO / "services/servicing/cutover/transfer.py"
        options = ["local", "--credit-service", "credit_owner", "--servicing-service", "servicing_owner"]
        directory = self.root / "reverse-abort-checkpoint"
        self.run("reverse-before-activation", [sys.executable, tool, *options, "--artifacts", directory, "--test-fail-before-target-activation"], accepted_codes=(1,))
        manifest = directory / "source-fenced.json"
        self.run("abort-after-source-reactivation", [sys.executable, tool, *options, "--recover-manifest", manifest, "--abort", "--test-fail-after-source-reactivation"], accepted_codes=(1,))
        if self.sql("servicing", "servicing_owner", "select writer from servicing_writer_fence where singleton;") != "REMOTE" or self.sql("credit", "credit_owner", "select writer from servicing_writer_fence where singleton;") != "FENCED":
            raise RuntimeError("Abort fault did not leave the expected source-active checkpoint")
        legacy = json.loads((self.root / "legacy-after-draw.json").read_text())
        loan_id = str(uuid.UUID(legacy["id"]))
        self.sql("servicing", "servicing_app", f"""begin;
        update loan_accounts a set outstanding_principal=a.outstanding_principal-p.principal_amount,updated_at=now()
          from loan_payments p where p.loan_account_id=a.id and a.id='{loan_id}' and p.payment_number=2;
        update loan_payments set status='PAID',paid_at=now() where loan_account_id='{loan_id}' and payment_number=2;
        commit;""")
        before = self.sql("servicing", "servicing_app", f"select outstanding_principal::text from loan_accounts where id='{loan_id}';")
        for label in ("post-abort-completion", "post-abort-repeat"):
            self.run(label, [sys.executable, tool, *options, "--recover-manifest", manifest, "--abort"])
            if self.sql("servicing", "servicing_app", f"select outstanding_principal::text from loan_accounts where id='{loan_id}';") != before:
                raise RuntimeError("Post-abort completion overwrote the reactivated source payment")
        self.receipt["checks"]["post-abort-source-payment-preserved"] = True
        self.transfer("local", "reverse")
        if self.sql("credit", "credit_app", f"select status from loan_payments where loan_account_id='{loan_id}' and payment_number=2;") != "PAID":
            raise RuntimeError("Final reverse lost the source payment after abort")

    def start_stand(self, label):
        env = self.env.copy()
        env.update(SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{self.pg_port}/servicing", SPRING_DATASOURCE_USERNAME="servicing_app", SPRING_DATASOURCE_PASSWORD=self.passwords["servicing_app"], DB_PASSWORD=self.passwords["servicing_app"], DB_NAME="servicing", DB_USER="servicing_app", SERVER_PORT=str(self.http_port), CABINET_DATABASE_GUARD_ENABLED="true",
                   SERVICING_GRPC_HOST="127.0.0.1", SERVICING_GRPC_PORT=str(self.grpc_port), SERVICING_SERVER_CERTIFICATE=str(self.root / "tls/server.crt"),
                   SERVICING_SERVER_KEY=str(self.root / "tls/server.key"), SERVICING_CLIENT_CA=str(self.root / "tls/ca.crt"))
        log = (self.root / (label + ".log")).open("wb")
        self.stand = subprocess.Popen([self.args.java_home / "bin/java", "-Xmx256m", "-XX:MaxMetaspaceSize=192m", "-jar", self.output(":services:servicing") / "libs/app.jar"],
                                      env=env, stdout=log, stderr=subprocess.STDOUT, close_fds=True)
        log.close()
        until = time.monotonic() + 45
        while time.monotonic() < until:
            if self.stand.poll() is not None:
                raise RuntimeError("Candidate runtime exited; inspect " + label)
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{self.http_port}/actuator/health", timeout=1) as response:
                    if response.status == 200:
                        self.receipt["checks"][label] = {"pid": self.stand.pid, "health": 200}
                        return
            except Exception:
                pass
            time.sleep(0.2)
        raise RuntimeError("Candidate readiness timed out")

    def stop_stand(self):
        if self.stand is not None:
            if self.stand.poll() is None:
                self.stand.terminate()
                try:
                    self.stand.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    self.stand.kill()
                    self.stand.wait(timeout=5)
            self.stand = None

    def seed_fixture_counterparts(self):
        accounts = json.loads(self.sql("servicing", "servicing_owner", "select coalesce(jsonb_agg(to_jsonb(a)),'[]'::jsonb) from loan_accounts a;"))
        for account in accounts:
            app_id = str(uuid.UUID(account["application_id"]))
            if self.sql("credit", "credit_app", f"select exists(select 1 from applications where id='{app_id}');") == "t":
                continue
            # Direct domain/RPC fixtures bypass Credit; provide their known synthetic FK for reverse import.
            query = f"""insert into applications(id,company_id,company_inn,company_name,legal_form,created_by,product_code,purpose,amount,term_months,status,created_at,updated_at,status_changed_at,submitted_at,product_catalog_snapshot)
            values('{app_id}','{uuid.UUID(account['company_id'])}','0012345678','Standalone service fixture','LEGAL_ENTITY','{uuid.uuid4()}','OVERDRAFT','CASH_GAP',{int(account['approved_limit'])},{int(account['term_months'])},'DISBURSED',now(),now(),now(),now(),'{{"OVERDRAFT":1}}'::jsonb);"""
            self.sql("credit", "credit_app", query)

    def execute(self):
        if self.receipt["dirty"] and not self.args.write_locks:
            raise RuntimeError("Final acceptance requires a clean source tree; prepare and commit dependency locks first")
        self.gradle("build", [":libs:servicing-core:test", ":services:servicing:bootJar", ":services:credit:bootJar", ":services:credit:compileTestKotlin", ":services:servicing:compileTestKotlin"])
        self.tests(":libs:servicing-core", "core", 7)
        self.start_pg()
        self.tls()
        self.migrate("credit")
        self.migrate("servicing")
        local = {"DB_NAME": "credit", "DB_USER": "credit_app", "SPRING_DATASOURCE_URL": f"jdbc:postgresql://127.0.0.1:{self.pg_port}/credit", "SPRING_DATASOURCE_USERNAME": "credit_app", "SPRING_DATASOURCE_PASSWORD": self.db_password}
        filters = ["*LoanAccountDomainTest", "*LoanServicingIntegrationTest", "*PaymentReserveIntegrationTest", "*LoanOpeningAtomicityIntegrationTest", "*ServicingLegacyExportFixtureTest", "*LoanServicingVisibilityTest"]
        self.gradle("credit-local", [":services:credit:test", *sum((["--tests", name] for name in filters), [])], local)
        self.tests(":services:credit", "credit-local", 10)
        local_operations = self.operation_receipts("credit")
        if not local_operations:
            raise RuntimeError("Forward receipt fixture must contain a real committed operation")
        self.forward_with_activation_fault()
        if self.operation_receipts("servicing") != local_operations:
            raise RuntimeError("Forward cutover lost operation keys, fingerprints or first results")
        self.sql("credit", "credit_app", "select * from loan_accounts;", failure=True)
        self.sql("credit", "credit_app", "update servicing_writer_fence set writer='LOCAL';", failure=True)
        pg = {"ALPHA_SERVICING_TEST_JDBC_URL": f"jdbc:postgresql://127.0.0.1:{self.pg_port}/servicing", "ALPHA_SERVICING_TEST_DB_USER": "servicing_app", "ALPHA_SERVICING_TEST_DB_PASSWORD": self.passwords["servicing_app"]}
        self.gradle("servicing-postgres", [":services:servicing:test", "--tests", "*ServicingPostgresIntegrationTest", "--tests", "*ServicingWireTest"], pg)
        self.tests(":services:servicing", "servicing-postgres", 4)
        self.start_stand("runtime-first")
        self.gradle("live-open", [":services:servicing:test", "--tests", "*ServicingLiveGrpcTest"], {"ALPHA_SERVICING_LIVE_PHASE": "open"})
        self.tests(":services:servicing", "live-open")
        self.stop_stand()
        self.start_stand("runtime-restarted")
        self.gradle("live-replay", [":services:servicing:test", "--tests", "*ServicingLiveGrpcTest"], {"ALPHA_SERVICING_LIVE_PHASE": "replay"})
        self.tests(":services:servicing", "live-replay")
        self.gradle("credit-live-delivery", [":services:credit:test", "--tests", "*CreditServicingLiveDeliveryTest"], {**local, "ALPHA_SERVICING_CREDIT_LIVE": "true"})
        self.tests(":services:credit", "credit-live-delivery")
        self.seed_fixture_counterparts()
        self.stop_stand()
        remote_operations = self.operation_receipts("servicing")
        if len(remote_operations) <= len(local_operations):
            raise RuntimeError("Reverse receipt fixture must contain a new remote operation")
        self.reverse_with_abort_fault()
        if self.operation_receipts("credit") != remote_operations:
            raise RuntimeError("Reverse cutover lost operation keys, fingerprints or first results")
        self.retired_receipts("servicing")
        self.gradle("credit-reverse-operation-replay", [":services:credit:test", "--tests", "*ServicingLegacyExportFixtureTest"], {**local, "ALPHA_SERVICING_LOCAL_REPLAY": "true"})
        self.tests(":services:credit", "credit-reverse-operation-replay")
        self.receipt["checks"]["operation-receipts-forward-reverse-replay"] = {"local": len(local_operations), "remote": len(remote_operations), "firstResultsPreserved": True, "retiredRegrantDenied": True}
        latest = json.loads((self.root / "latest-payment-account.json").read_text())
        value = self.sql("credit", "credit_app", f"select outstanding_principal::text from loan_accounts where id='{uuid.UUID(latest['id'])}';")
        from decimal import Decimal
        if Decimal(value) != Decimal(str(latest["outstandingPrincipal"])):
            raise RuntimeError("Latest real payment was lost during reverse import")
        self.sql("servicing", "servicing_app", "select * from loan_accounts;", failure=True)
        legacy = json.loads((self.root / "legacy-after-draw.json").read_text())
        imported = self.sql("credit", "credit_app", f"select string_agg(id::text,',' order by payment_number) from loan_payments where loan_account_id='{uuid.UUID(legacy['id'])}';")
        expected_ids = ",".join(row["id"] for row in sorted(legacy["payments"], key=lambda row: row["paymentNumber"]))
        if imported != expected_ids:
            raise RuntimeError("Reverse import did not preserve the real rescheduled payment identities")
        self.receipt["checks"]["legacy-real-draw-reschedule-reverse"] = True
        self.receipt["checks"]["latest-real-payment-preserved-on-reverse"] = True
        self.receipt["artifacts"] = {db: {"jar": str(self.output(":services:" + db) / "libs/app.jar"), "sha256": sha256((self.output(":services:" + db) / "libs/app.jar").read_bytes()).hexdigest()} for db in ("credit", "servicing")}
        changed = bool(self.git("status", "--porcelain")) or self.git("rev-parse", "HEAD") != self.receipt["sourceSha"]
        self.receipt["status"] = "PREPARATION_PASSED" if self.args.write_locks or changed else "PASSED"
        if changed and not self.args.write_locks:
            raise RuntimeError("Source tree changed during acceptance; results are not a final candidate gate")

    def write_receipt(self):
        text = json.dumps(self.receipt, indent=2, ensure_ascii=False)
        (self.root / "receipt.json").write_text(text)
        if self.args.receipt_copy:
            self.args.receipt_copy.parent.mkdir(parents=True, exist_ok=True)
            self.args.receipt_copy.write_text(text)

    def close(self):
        self.stop_stand()
        if self.ci_postgres is not None:
            self.ci_postgres.close()
        if self.pg_started:
            with (self.root / "pg-stop.log").open("wb") as log:
                stopped = subprocess.run([self.pg_bin / "pg_ctl", "-D", self.root / "pg-data", "-m", "fast", "-w", "stop"], env=self.env, stdout=log, stderr=subprocess.STDOUT, close_fds=True)
                if stopped.returncode:
                    self.receipt.update(status="FAILED", failedStage="pg-cleanup", reason="Owned PostgreSQL did not stop")
                    self.write_receipt()
                    raise RuntimeError("Owned PostgreSQL cleanup failed")
        self.write_receipt()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pg-root", type=Path)
    parser.add_argument("--pg-bin", type=Path, help="PostgreSQL client directory for the CI container adapter")
    parser.add_argument("--ci-postgres-image")
    parser.add_argument("--inherit-proxy-env", action="store_true")
    parser.add_argument("--pg-version", default="16")
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--gradle-user-home", type=Path, required=True)
    parser.add_argument("--read-only-dependency-cache", type=Path)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--outputs-dir", type=Path, help="Reuse own build outputs outside artifact directory")
    parser.add_argument("--project-cache-dir", type=Path, help="Reuse own Gradle task cache")
    parser.add_argument("--write-locks", action="store_true")
    parser.add_argument("--receipt-copy", type=Path, help="Optional small receipt outside tmpfs artifacts")
    parser.add_argument("--lock-timeout", type=int, default=30)
    args = parser.parse_args()
    if args.ci_postgres_image and (args.pg_root or args.pg_bin is None):
        parser.error("CI adapter requires --pg-bin and forbids --pg-root")
    if not args.ci_postgres_image and args.pg_root is None:
        parser.error("Native local adapter requires --pg-root")
    if not 0 <= args.lock_timeout <= 60:
        parser.error("lock timeout must be between 0 and 60 seconds")
    with heavy_lock(args.lock_timeout):
        acceptance = Acceptance(args)
        try:
            acceptance.execute()
        except Exception as error:
            acceptance.receipt.update(status="FAILED", failedStage=acceptance.stage, reason=str(error))
            raise
        finally:
            acceptance.close()
        print(acceptance.root / "receipt.json")


if __name__ == "__main__":
    main()
