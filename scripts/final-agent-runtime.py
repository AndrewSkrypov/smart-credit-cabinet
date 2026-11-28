#!/usr/bin/env python3
"""Run a revision-bound synthetic cabinet with private native processes on Linux."""
import argparse
import calendar
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import signal
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


ROOT = Path(__file__).resolve().parents[1]
SERVICES = ("external-sim", "engagement", "identity", "credit")
DATABASES = ("identity", "credit", "engagement")
GATEWAY = "docker/gateway"
PERMISSIONS = "libs/platform-common/src/main/resources/cabinet/database-permissions.sql"
OFFSETS = {"gateway": 0, "identity": 1, "credit": 2, "engagement": 3, "external-sim": 4,
           "postgres": 5, "valkey": 6, "clamd": 7}
class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


HTTP = build_opener(ProxyHandler({}), NoRedirect())


def git(*args, repo=ROOT):
    return subprocess.check_output(["git", "-C", str(repo), *args])


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source(revision, path, repo=ROOT):
    return git("show", f"{revision}:{path}", repo=repo).decode()


def executable(path):
    # Debian's Valkey entry point is a symlink to a multicall binary that uses argv[0].
    path = path.absolute()
    if not path.is_file() or not os.access(path, os.X_OK):
        raise ValueError(f"Исполняемый файл недоступен: {path}")
    return str(path)


def request(url, payload=None, token=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None if payload is None else json.dumps(payload).encode()
    if data:
        headers["Content-Type"] = "application/json"
    try:
        with HTTP.open(Request(url, data, headers), timeout=4) as response:
            return response.status, response.read(4 * 1024 * 1024)
    except HTTPError as error:
        return error.code, error.read(4 * 1024 * 1024)


def process_identity(pid):
    try:
        fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        return fields[19] if fields[0] != "Z" else None
    except FileNotFoundError:
        return None


def live(process):
    return bool(process["start"]) and process_identity(process["pid"]) == process["start"]


def wait_for(check, label, process=None, timeout=100):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process and not live(process):
            raise RuntimeError(f"{label} завершился. Смотрите журнал в каталоге запуска.")
        try:
            if check():
                return
        except (OSError, URLError, TimeoutError):
            pass
        time.sleep(0.35)
    raise RuntimeError(f"{label} не готов за {timeout} секунд. Смотрите журнал в каталоге запуска.")



def stop(state):
    for process in reversed(list(state["processes"].values())):
        try:
            descriptor = os.pidfd_open(process["pid"])
        except ProcessLookupError:
            continue
        try:
            if not live(process):
                continue
            signal.pidfd_send_signal(descriptor, signal.SIGTERM)
            deadline = time.monotonic() + 15
            while live(process) and time.monotonic() < deadline:
                time.sleep(0.15)
            if live(process):
                signal.pidfd_send_signal(descriptor, signal.SIGKILL)
        except ProcessLookupError:
            pass
        finally:
            os.close(descriptor)


def verify_artifacts(args):
    artifacts = args.artifacts.resolve(strict=True)
    revision = args.revision
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("Укажите полный SHA исходного коммита")
    resolved = git("rev-parse", "--verify", f"{revision}^{{commit}}", repo=args.source_checkout).decode().strip()
    if resolved != revision:
        raise ValueError("Укажите полный SHA исходного коммита, доступного в Git")
    recorded = artifacts / "source-revision.txt"
    if not recorded.is_file() or recorded.read_text().strip() != revision:
        raise ValueError("SHA артефактов отличается от выбранной ревизии")
    jars = {name: artifacts / f"{name}.jar" for name in SERVICES}
    spa = None if args.api_only else artifacts / "frontend"
    if any(not path.is_file() for path in jars.values()):
        raise ValueError("Нужны четыре JAR из одного исходного SHA")
    content = {path.name: digest(path) for path in jars.values()}
    if spa:
        content.update(frontend_digests(spa))
    return jars, spa, content


def frontend_digests(spa):
    if not (spa / "index.html").is_file():
        raise ValueError("Отсутствует собранный frontend/index.html")
    digests = {f"frontend/{path.relative_to(spa)}": digest(path)
               for path in sorted(spa.rglob("*")) if path.is_file()}
    if not any(path.startswith("frontend/assets/") and path.endswith(".js") for path in digests):
        raise ValueError("В сборке frontend отсутствуют JavaScript-артефакты")
    return digests


def listen_addresses(args):
    hosts = {"gateway": "127.0.0.1", **{name: args.private_host for name in OFFSETS if name != "gateway"}}
    ip = ipaddress.IPv4Address(args.private_host)
    if not ip.is_loopback or ip.is_unspecified:
        raise ValueError("Внутренние сервисы должны использовать IPv4 loopback")
    if not 1024 <= args.base_port <= 65535 - max(OFFSETS.values()):
        raise ValueError("Базовый порт должен оставлять диапазон для внутренних сервисов")
    addresses = {name: (host, args.base_port + OFFSETS[name]) for name, host in hosts.items()}
    for host, port in addresses.values():
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind((host, port))
            except OSError as error:
                raise ValueError(f"Недоступен loopback-порт {host}:{port}") from error
    return addresses


def nginx_path(path):
    value = str(path)
    if any(char in value for char in ("\n", "\r", '"', "$", "\\")):
        raise ValueError("Путь для локального nginx содержит управляющие символы")
    return f'"{value}"'


def render_gateway(directory, revision, addresses, spa, repo=ROOT):
    fragments = directory / "gateway"
    fragments.mkdir()
    base = source(revision, f"{GATEWAY}/nginx.conf", repo)
    base = base.replace("/etc/nginx/cabinet-gateway/", f"{fragments}/")
    base = re.sub(r"(^\s*include )([^;]+)(;)", lambda line: line[1] + nginx_path(line[2]) + line[3], base, flags=re.M)
    base = base.replace("listen 80;", f"listen {addresses['gateway'][0]}:{addresses['gateway'][1]};")
    for service in SERVICES:
        host, port = addresses[service]
        base = base.replace(f"http://{service}:8080;", f"http://{host}:{port};")
    # The single native gateway serves the built SPA; the API locations still use committed fragments.
    base = re.sub(r"^\s*set \$(frontend|swagger) .*?;\n", "\n", base, flags=re.M)
    (fragments / "cabinet.conf").write_text(base)
    for entry in git("ls-tree", "-r", "--name-only", revision, "--", f"{GATEWAY}/conf.d", repo=repo).decode().splitlines():
        name = Path(entry).name
        configuration = source(revision, entry, repo)
        if name == "upstreams.conf":
            configuration = re.sub(r"^resolver(?:_timeout)? .+;\n", "", configuration, flags=re.M)
            configuration = configuration.replace("credit:8080 resolve", "%s:%s" % addresses["credit"])
        if name == "public.conf":
            configuration = configuration.replace("proxy_pass $swagger;", "return 404;")
            configuration = configuration.replace("proxy_pass $frontend;", "try_files $uri $uri/ /index.html;")
            configuration += "\n    location /assets/ { try_files $uri =404; }\n"
        (fragments / name).write_text(configuration)
    # MIME types for the built SPA, without importing the host's global nginx config.
    config = directory / "nginx.conf"
    config.write_text(f"""
worker_processes 1;
pid {nginx_path(directory / "nginx.pid")};
error_log {nginx_path(directory / "nginx-error.log")};
events {{ worker_connections 256; }}
http {{
    access_log off;
    client_body_temp_path {nginx_path(directory / "nginx-client")};
    proxy_temp_path {nginx_path(directory / "nginx-proxy")};
    fastcgi_temp_path {nginx_path(directory / "nginx-fastcgi")};
    scgi_temp_path {nginx_path(directory / "nginx-scgi")};
    uwsgi_temp_path {nginx_path(directory / "nginx-uwsgi")};
    root {nginx_path(spa)};
    types {{
        text/html html;
        text/css css;
        application/javascript js mjs;
        application/json json;
        image/svg+xml svg;
        image/png png;
        image/vnd.microsoft.icon ico;
        font/woff woff;
        font/woff2 woff2;
    }}
    include {nginx_path(fragments / "cabinet.conf")};
}}
""")
    return config


def start(args, directory, state, persist):
    jars, spa, state["artifacts_sha256"] = verify_artifacts(args)
    addresses = listen_addresses(args)
    state["source_revision"] = args.revision
    state["endpoints"] = {name: f"http://{host}:{port}" for name, (host, port) in addresses.items()
                          if name in (*SERVICES, "gateway")}
    state["listeners"] = {name: f"{host}:{port}" for name, (host, port) in addresses.items()}
    state["serve_spa"] = spa is not None
    state["uploads_enabled"] = args.clamd is not None
    state["uploads_mode"] = "clamav" if args.clamd else "disabled"
    if bool(args.clamd) != bool(args.clam_db) or (args.clam_certs and not args.clamd):
        raise ValueError("Для загрузки документов нужны и clamd, и каталог сигнатур")
    java = executable(args.java)
    nginx = executable(args.nginx)
    valkey = executable(args.valkey)
    pg_bin = args.pg_bin.resolve(strict=True)
    postgres, initdb, psql, pg_isready = (executable(pg_bin / name) for name in ("postgres", "initdb", "psql", "pg_isready"))
    paths = [str(pg_bin), "/usr/bin", "/bin"]
    env = {"PATH": os.pathsep.join(paths), "HOME": str(directory), "TMPDIR": str(directory),
           "LC_ALL": "C.UTF-8", "TZ": "UTC"}
    env.update({key: value for key, value in (("LD_LIBRARY_PATH", args.native_libs),) if value})
    if spa is None:
        spa = directory / "api-only-root"
        spa.mkdir(mode=0o700)
    config = render_gateway(directory, args.revision, addresses, spa, args.source_checkout)
    nginx_args = [nginx, "-p", str(directory), "-c", str(config), "-g", "daemon off;"]
    persist()

    def run(name, command, settings=env, content=None):
        with (directory / f"{name}.log").open("ab") as log:
            result = subprocess.run(command, env=settings, cwd=directory, input=content,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=180)
        if result.returncode:
            raise RuntimeError(f"{name}: код {result.returncode}. Смотрите {name}.log.")

    def launch(name, command, settings=env):
        with (directory / f"{name}.log").open("ab") as log:
            child = subprocess.Popen(command, env=settings, cwd=directory, stdin=subprocess.DEVNULL,
                                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        state["processes"][name] = {"pid": child.pid, "start": process_identity(child.pid)}
        if not state["processes"][name]["start"]:
            raise RuntimeError(f"{name} не запустился. Смотрите {name}.log.")
        persist()
        return state["processes"][name]

    run("nginx-config", [*nginx_args, "-t"])
    db_host, db_port = addresses["postgres"]
    pg_data, pg_socket = directory / "postgres", directory / "pg-socket"
    pg_socket.mkdir(mode=0o700)
    admin_secret = directory / "postgres-init-password"
    admin_secret.write_text(secrets.token_hex(32) + "\n")
    try:
        run("initdb", [initdb, "-D", str(pg_data), "-U", "cabinet", "--auth-local=trust", "--auth-host=scram-sha-256",
                       "--no-locale", "--encoding=UTF8", "--pwfile", str(admin_secret)])
    finally:
        admin_secret.unlink()
    pg = launch("postgres", [postgres, "-D", str(pg_data), "-h", db_host, "-p", str(db_port),
                             "-k", str(pg_socket), "-c", "max_connections=48", "-c", "shared_buffers=48MB"])
    def postgres_ready():
        return subprocess.run([pg_isready, "-q", "-h", str(pg_socket), "-p", str(db_port), "-U", "cabinet"],
                              env=env, timeout=4).returncode == 0

    wait_for(postgres_ready, "PostgreSQL", pg, 30)

    db = {name: (secrets.token_hex(24), secrets.token_hex(24)) for name in DATABASES}
    admin_env = {**env, "PGHOST": str(pg_socket), "PGPORT": str(db_port), "PGUSER": "cabinet", "PGDATABASE": "postgres"}
    run("create-bootstrap-db", [psql, "-X", "-v", "ON_ERROR_STOP=1", "-c", "CREATE DATABASE cabinet"], admin_env)
    permissions = directory / "database-permissions.sql"
    permissions.write_text(source(args.revision, PERMISSIONS, args.source_checkout))
    bootstrap = source(args.revision, "docker/postgres/bootstrap.sh", args.source_checkout)
    bootstrap = bootstrap.replace("/cabinet/database-permissions.sql", shlex.quote(str(permissions)))
    for name, (app_password, owner_password) in db.items():
        admin_env[f"{name.upper()}_DB_PASSWORD"] = app_password
        admin_env[f"{name.upper()}_MIGRATION_PASSWORD"] = owner_password
    run("db-bootstrap", ["/bin/sh"], {**admin_env, "PGDATABASE": "cabinet"}, bootstrap.encode())

    redis_host, redis_port = addresses["valkey"]
    redis_secret = secrets.token_hex(24)
    redis_config = directory / "valkey.conf"
    redis_config.write_text(f'bind {redis_host}\nport {redis_port}\nprotected-mode yes\ndaemonize no\n'
                            f'save ""\nappendonly no\nrequirepass {redis_secret}\n')
    redis = launch("valkey", [valkey, str(redis_config)])
    def valkey_ready():
        with socket.create_connection((redis_host, redis_port), timeout=1) as connection:
            authenticate = f"*2\r\n$4\r\nAUTH\r\n${len(redis_secret)}\r\n{redis_secret}\r\n"
            connection.sendall(authenticate.encode("ascii") + b"*1\r\n$4\r\nPING\r\n")
            with connection.makefile("rb") as response:
                return response.readline(256) == b"+OK\r\n" and response.readline(256) == b"+PONG\r\n"

    wait_for(valkey_ready, "Valkey", redis, 20)
    endpoints = state["endpoints"]
    common = {**env, "JWT_SECRET": secrets.token_hex(32), "SIM_API_KEY": secrets.token_hex(24),
              "IDENTITY_LINK_KEY": secrets.token_hex(32), "SERVER_ADDRESS": args.private_host,
              "DB_HOST": db_host, "DB_PORT": str(db_port), "SPRING_FLYWAY_ENABLED": "false",
              "CABINET_DATABASE_GUARD_ENABLED": "true", "DEMO_ACCOUNTS": "true",
              "CABINET_VALKEY_ENABLED": "true", "CABINET_VALKEY_HOST": redis_host,
              "CABINET_VALKEY_PORT": str(redis_port), "CABINET_VALKEY_PASSWORD": redis_secret,
              "CABINET_MAIL_MODE": "off", "OLLAMA_URL": "", "SIM_CONTROL_ENABLED": "true",
              "IDENTITY_URL": endpoints["identity"], "ENGAGEMENT_URL": endpoints["engagement"],
              "CREDIT_URL": endpoints["credit"], "EXTERNAL_SIM_URL": endpoints["external-sim"],
              "REGISTRY_URL": endpoints["external-sim"], "CABINET_MAIL_PUBLICCABINETURL": endpoints["gateway"],
              "STORAGE_BACKEND": "local", "UPLOAD_DIR": str(directory / "uploads"),
              "DOCUMENT_UPLOADS_ENABLED": str(state["uploads_enabled"]).lower(),
              "ANTIVIRUS_ENABLED": "true", "ANTIVIRUS_HOST": args.private_host,
              "ANTIVIRUS_PORT": str(addresses["clamd"][1]), "APPLICATION_STATUS_EVENTS_ENABLED": "false",
              "ANALYTICS_STATUS_READ_ENABLED": "false"}
    (directory / "uploads").mkdir()
    if args.clamd:
        start_clam(args, directory, addresses["clamd"], launch, env)
    for name in DATABASES:
        run(f"{name}-migration", [java, "-XX:ActiveProcessorCount=2", "-Xmx256m", "-jar", str(jars[name])],
            {**common, "MIGRATION_ONLY": "true", "DB_NAME": name, "DB_USER": f"{name}_owner", "DB_PASSWORD": db[name][1]})
    for name in SERVICES:
        settings = {**common, "SERVER_PORT": str(addresses[name][1])}
        if name in db:
            settings.update(DB_NAME=name, DB_USER=f"{name}_app", DB_PASSWORD=db[name][0])
        launch(name, [java, "-XX:ActiveProcessorCount=2", "-Xms48m", "-Xmx384m", "-jar", str(jars[name])], settings)
    for name in SERVICES:
        wait_for(lambda: request(endpoints[name] + "/actuator/health")[0] == 200, name, state["processes"][name])
    gateway = launch("gateway", nginx_args)
    wait_for(lambda: request(endpoints["gateway"] + "/api/v1/products")[0] == 200, "Gateway", gateway)


def start_clam(args, directory, address, launch, env):
    clamd = executable(args.clamd)
    db = args.clam_db.resolve(strict=True)
    if not db.is_dir() or any(char.isspace() for char in str(db)):
        raise ValueError("Каталог сигнатур ClamAV должен существовать. Пробелы в пути не поддерживаются.")
    settings = source(args.revision, "docker/clamav/clamd.conf", args.source_checkout)
    settings = re.sub(r"^(?:LogFile|LocalSocket|TCPSocket|User|DatabaseDirectory) .*\n", "", settings, flags=re.M)
    host, port = address
    config = directory / "clamd.conf"
    config.write_text(f"Foreground yes\nTCPAddr {host}\nTCPSocket {port}\nDatabaseDirectory {db}\n" + settings)
    if args.clam_certs:
        certificates = args.clam_certs.resolve(strict=True)
        if not certificates.is_dir():
            raise ValueError("Каталог сертификатов ClamAV недоступен")
        env = {**env, "CVD_CERTS_DIR": str(certificates)}
    process = launch("clamd", [clamd, "-c", str(config)], env)

    def fresh_signatures():
        with socket.create_connection(address, timeout=1) as connection:
            connection.settimeout(2)
            connection.sendall(b"zVERSION\0")
            version = bytearray()
            while len(version) < 1024 and not version.endswith(b"\0"):
                chunk = connection.recv(1024 - len(version))
                if not chunk:
                    break
                version.extend(chunk)
        if not version.endswith(b"\0"):
            raise RuntimeError("ClamAV вернул неполную версию сигнатур")
        version = version[:-1].decode("ascii")
        if version.count("/") != 2 or not version.startswith("ClamAV "):
            raise RuntimeError("ClamAV не подтвердил версию сигнатур")
        published = calendar.timegm(time.strptime(version.rsplit("/", 1)[1], "%a %b %d %H:%M:%S %Y"))
        age = time.time() - published
        if not -300 <= age <= 160 * 60 * 60:
            raise RuntimeError("Сигнатуры ClamAV устарели")
        return True

    wait_for(fresh_signatures, "ClamAV", process, 120)


def status(state):
    health = {}
    for name, endpoint in state.get("endpoints", {}).items():
        route = "/api/v1/products" if name == "gateway" else "/actuator/health"
        try:
            health[name] = request(endpoint + route)[0] if live(state["processes"].get(name, {"pid": 0, "start": None})) else 0
        except (OSError, URLError):
            health[name] = 0
    return {
        "source_revision": state.get("source_revision"),
        "endpoints": state.get("endpoints", {}),
        "health": health,
        "processes": {name: live(process) for name, process in state["processes"].items()},
        "uploads_mode": state.get("uploads_mode"),
        "serve_spa": state.get("serve_spa", True),
    }


def smoke(state):
    result = status(state)
    gateway = result["endpoints"]["gateway"]
    if not all(result["health"].get(name) == 200 for name in (*SERVICES, "gateway")):
        raise RuntimeError("Для smoke нужны четыре работающих сервиса и шлюз")

    def get(path, expected=200, token=None):
        code, payload = request(gateway + path, token=token)
        if code != expected:
            raise RuntimeError(f"GET {path}: ожидался HTTP {expected}, получен {code}")
        return payload

    if result["serve_spa"] and b"/assets/" not in get("/sign-in"):
        raise RuntimeError("Шлюз не отдаёт собранную SPA")
    get("/api/v1/products")
    get("/api/v1/auth/options")
    get("/internal/v1/agents", 404)
    get("/api/v1/agent/clients", 401)
    sessions = {}
    for role, email in (("AGENT", "agent@demo.test"), ("CLIENT", "client@demo.test"),
                        ("MANAGER", "manager@demo.test")):
        code, payload = request(gateway + "/api/v1/auth/sign-in", {"email": email, "password": "Demo2026"})
        if code != 200:
            raise RuntimeError(f"Синтетический вход {role} не прошёл, HTTP {code}")
        session = json.loads(payload)
        if session["user"]["role"] != role or not session.get("token"):
            raise RuntimeError(f"Синтетический вход вернул неверную роль для {role}")
        sessions[role] = session["token"]
    companies = json.loads(get("/api/v1/agent/clients", token=sessions["AGENT"]))
    if not isinstance(companies, list) or not companies:
        raise RuntimeError("Синтетический агент не видит свой портфель")
    get("/api/v1/agent/clients", 403, token=sessions["CLIENT"])
    get("/api/v1/analytics/funnel", token=sessions["MANAGER"])
    return {**result, "smoke": {**({"spa": 200} if result["serve_spa"] else {}),
                               "products": 200, "auth_options": 200, "internal": 404,
                               "anonymous_portfolio": 401, "client_portfolio": 403, "agent_portfolio": 200,
                               "agent_companies": len(companies), "manager_funnel": 200}}


def attach_spa(args, directory, state):
    if state.get("serve_spa", True) or args.revision != state["source_revision"]:
        raise ValueError("SPA можно добавить к API-запуску той же ревизии")
    gateway = state["endpoints"]["gateway"]
    if not live(state["processes"]["gateway"]):
        raise RuntimeError("Локальный шлюз остановлен")
    with tempfile.TemporaryDirectory(dir=directory, prefix="spa-") as workspace:
        staged = Path(workspace) / "frontend"
        shutil.copytree(args.spa, staged)
        checksums = frontend_digests(staged)
        mounted = directory / "api-only-root"
        if any(mounted.iterdir()):
            if frontend_digests(mounted) != checksums:
                raise ValueError("В API-запуск уже добавлена другая сборка SPA")
        else:
            staged.replace(mounted)
    code, html = request(gateway + "/sign-in")
    if code != 200 or hashlib.sha256(html).hexdigest() != checksums["frontend/index.html"]:
        raise RuntimeError("Шлюз не отдаёт подключённую SPA")
    state["serve_spa"] = True
    state["artifacts_sha256"].update(checksums)


def write_state(receipt, state):
    temporary = receipt.with_name(".state.tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(receipt)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    up = commands.add_parser("up")
    up.add_argument("--artifacts", type=Path, required=True)
    up.add_argument("--revision", required=True)
    up.add_argument("--source-checkout", type=Path, default=ROOT)
    up.add_argument("--api-only", action="store_true")
    up.add_argument("--java", type=Path, required=True)
    up.add_argument("--pg-bin", type=Path, required=True)
    up.add_argument("--nginx", type=Path, required=True)
    up.add_argument("--valkey", type=Path, required=True)
    up.add_argument("--native-libs", default="")
    up.add_argument("--base-port", type=int, default=18680)
    up.add_argument("--private-host", default="127.77.0.1")
    up.add_argument("--clamd", type=Path)
    up.add_argument("--clam-db", type=Path)
    up.add_argument("--clam-certs", type=Path)
    attach = commands.add_parser("attach-spa")
    attach.add_argument("--spa", type=Path, required=True)
    attach.add_argument("--revision", required=True)
    for command in ("status", "smoke", "down"):
        commands.add_parser(command)
    args = parser.parse_args()
    os.umask(0o077)
    directory = args.state_dir.resolve()
    receipt = directory / "state.json"
    try:
        if args.command == "up":
            directory.mkdir(mode=0o700)
        if directory.stat().st_uid != os.getuid() or directory.stat().st_mode & 0o077:
            raise ValueError("Каталог запуска должен принадлежать текущему пользователю и иметь права 0700")
        with (directory / "runtime.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            if args.command == "up":
                state = {"format": 1, "processes": {}}

                write_state(receipt, state)
                try:
                    start(args, directory, state, lambda: write_state(receipt, state))
                except BaseException:
                    stop(state)
                    raise
            else:
                state = json.loads(receipt.read_text())
                if state.get("format") != 1:
                    raise ValueError("Неизвестный формат локального состояния")
            if args.command == "down":
                stop(state)
            if args.command == "attach-spa":
                attach_spa(args, directory, state)
                write_state(receipt, state)
            report = smoke(state) if args.command == "smoke" else status(state)
            print(json.dumps(report, ensure_ascii=False, indent=2))
            if args.command == "status" and (
                not report["health"] or any(code != 200 for code in report["health"].values())
            ):
                return 1
    except KeyboardInterrupt:
        return 130
    except FileExistsError:
        parser.exit(2, "Используйте новый каталог состояния для запуска или down для остановки существующего.\n")
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Локальный runtime: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
