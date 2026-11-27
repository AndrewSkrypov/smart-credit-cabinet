"""Catalog rollout config and private Dev credentials."""
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
from tempfile import TemporaryDirectory

from registry_release import SHA256

SECRET_KEYS = ("CATALOG_DB_PASSWORD", "CATALOG_MIGRATION_PASSWORD")
SERVER = "catalog-grpc"
CLIENT_URI = "spiffe://creditcabinet/credit"
TLS_FILES = ("credit/ca.crt", "credit/tls.crt", "credit/tls.key",
             "server/ca.crt", "server/tls.crt", "server/tls.key")


def requested_mode(project, local, release):
    if (((project == "cabinet-dev" or project.startswith("cabinet-dev-restore-")) and not local)
            or project.startswith("cabinet-prod-restore")) and release and release.catalog:
        return release.catalog["dev_mode"]
    return "disabled"


def append_secrets(contents):
    newline = b"\r\n" if b"\r\n" in contents else b"\n"
    if contents and not contents.endswith(b"\n"):
        contents += newline
    return contents + b"".join(f"{key}={secrets.token_hex(32)}".encode("ascii") + newline for key in SECRET_KEYS)


def validate_secrets(values):
    protected = [values.get(key, "") for key in SECRET_KEYS]
    if any(not SHA256.fullmatch(value) for value in protected):
        raise ValueError("Catalog требует отдельные автоматически подготовленные пароли БД")
    other = {value for key, value in values.items() if key not in SECRET_KEYS}
    if len(set(protected)) != len(protected) or other.intersection(protected):
        raise ValueError("Пароли Catalog должны отличаться от других ключей")


def state_path(env_file, project):
    return Path(env_file).parent / ("catalog" if project == "cabinet-dev" else "catalog-" + project)


def ensure_private(path):
    path.mkdir(mode=0o700, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o700 or info.st_uid != os.getuid():
        raise ValueError("Состояние Catalog требует принадлежащий оператору каталог 0700")
    return path


def fsync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def openssl(directory, *arguments):
    try:
        return subprocess.run(["openssl", *arguments], cwd=directory, check=True, capture_output=True, timeout=30).stdout
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("Не удалось подготовить или проверить mTLS Catalog") from None


def ensure_tls(state):
    ensure_private(state)
    tls = state / "tls"
    if not tls.exists():
        with TemporaryDirectory(prefix=".tls-", dir=state) as temporary:
            build = Path(temporary) / "tls"
            ensure_private(build)
            openssl(build, "req", "-x509", "-newkey", "rsa:2048",
                    "-nodes", "-sha256", "-days", "3650", "-subj", "/CN=cabinet-catalog-dev",
                    "-addext", "basicConstraints=critical,CA:TRUE",
                    "-addext", "keyUsage=critical,keyCertSign,cRLSign", "-keyout", "ca.key", "-out", "ca.crt")
            (build / "ca.key").chmod(0o600)
            for name, identity, usage, purpose in (
                ("server", f"DNS:{SERVER}", "serverAuth", SERVER),
                ("credit", f"URI:{CLIENT_URI}", "clientAuth", "credit"),
            ):
                target = build / name
                target.mkdir(mode=0o700)
                openssl(build, "req", "-new", "-newkey", "rsa:2048",
                        "-nodes", "-subj", f"/CN={purpose}", "-keyout", f"{name}/tls.key", "-out", "request.csr")
                (build / "extension.cnf").write_text(
                    f"basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
                    f"extendedKeyUsage={usage}\nsubjectAltName={identity}\n", encoding="ascii",
                )
                openssl(build, "x509", "-req", "-sha256", "-days", "730", "-in", "request.csr",
                        "-CA", "ca.crt", "-CAkey", "ca.key", "-set_serial", f"0x{secrets.token_hex(16)}",
                        "-extfile", "extension.cnf", "-out", f"{name}/tls.crt")
                (target / "ca.crt").write_bytes((build / "ca.crt").read_bytes())
                # The parent is private on the host. Only the leaf directory is mounted in a non-root JVM.
                for artifact in target.iterdir():
                    artifact.chmod(0o444)
                    with artifact.open("rb") as stream:
                        os.fsync(stream.fileno())
                target.chmod(0o555)
                fsync_dir(target)
            for name in ("request.csr", "extension.cnf", "ca.key", "ca.crt"):
                (build / name).unlink()
            fsync_dir(build)
            build.rename(tls)
            fsync_dir(state)
    check_tls(tls)
    openssl(tls, "verify", "-CAfile", "server/ca.crt", "-purpose", "sslserver", "-verify_hostname", SERVER, "server/tls.crt")
    openssl(tls, "verify", "-CAfile", "credit/ca.crt", "-purpose", "sslclient", "credit/tls.crt")
    names = openssl(tls, "x509", "-noout", "-ext", "subjectAltName", "-in", "credit/tls.crt")
    if f"URI:{CLIENT_URI}".encode("ascii") not in names.split():
        raise RuntimeError("Неверный URI сертификата Credit")
    for name in ("server", "credit"):
        public_key = openssl(tls, "x509", "-noout", "-pubkey", "-in", f"{name}/tls.crt")
        if openssl(tls, "pkey", "-in", f"{name}/tls.key", "-pubout") != public_key:
            raise RuntimeError("Ключ mTLS Catalog не соответствует сертификату")


def check_tls(tls):
    info = tls.lstat()
    if not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o700 or info.st_uid != os.getuid():
        raise ValueError("mTLS Catalog требует приватный каталог 0700")
    for relative in TLS_FILES:
        path = tls / relative
        entry = path.lstat()
        if (not stat.S_ISREG(entry.st_mode) or stat.S_IMODE(entry.st_mode) != 0o444
                or not stat.S_ISDIR(path.parent.lstat().st_mode)):
            raise ValueError("Некорректные файлы mTLS Catalog")


def probe_mtls(exec_prefix, mode):
    """Verify gRPC application status and peer authentication from a Credit container."""
    # kubectl/docker can fail before invoking curl. Only the in-container shell can record its exit.
    runner = [*exec_prefix, "sh", "-c",
              'status=0; LC_ALL=C curl --disable "$@" 2>&1 || status=$?; '
              'printf "\\n__CATALOG_CURL_EXIT__=%s\\n" "$status"', "catalog-probe"]
    curl = ["--silent", "--show-error", "--http2",
            "--noproxy", "*", "--connect-timeout", "3", "--max-time", "8",
            "--cacert", "/run/catalog-tls/ca.crt", "--header", "Content-Type: application/grpc",
            "--header", "TE: trailers", "--data-binary", "@-", "--dump-header", "-", "--output", "/dev/null"]
    client = ["--cert", "/run/catalog-tls/tls.crt", "--key", "/run/catalog-tls/tls.key"]
    route = "https://catalog-grpc:9094/creditcabinet.catalog.v1.CatalogService/"

    def call(method, content, *, authenticated=True):
        frame = b"\0" + len(content).to_bytes(4, "big") + content
        try:
            result = subprocess.run([*runner, *curl, *(client if authenticated else []), route + method],
                                    input=frame, capture_output=True, timeout=12)
        except (OSError, subprocess.TimeoutExpired):
            raise RuntimeError("Catalog: транспорт проверки mTLS недоступен") from None
        output, sep, status = result.stdout.rpartition(b"\n__CATALOG_CURL_EXIT__=")
        if result.returncode or not sep or not re.fullmatch(rb"[0-9]{1,3}\r?\n", status):
            raise RuntimeError("Catalog: не подтверждено выполнение curl в контейнере Credit")
        statuses = re.findall(rb"(?im)^grpc-status:[ \t]*([0-9]+)[ \t]*\r?$", output)
        return int(status), statuses, output

    code = b"__RELEASE_PROBE__"
    request = bytes((10, len(code))) + code  # product_code is field 1 in both requests.
    required_status = b"0" if mode == "remote" else b"14"
    if call("ListCurrentProducts", request)[:2] != (0, [required_status]):
        raise RuntimeError("Catalog: не подтверждён mTLS gRPC доступ к актуальному каталогу")
    if call("ListProductHistory", request)[:2] not in ((0, [b"0"]), (0, [b"5"])):
        raise RuntimeError("Catalog: не подтверждён mTLS gRPC доступ к истории")
    exit_code, statuses, output = call("ListProductHistory", request, authenticated=False)
    if exit_code == 0 and statuses in ([b"7"], [b"16"]):
        return
    # The explicit certificate-required TLS alert proves rejection. Transport failures are inconclusive.
    if exit_code in {35, 55, 56} and not statuses and re.search(
        rb"(?im)^curl: \(" + str(exit_code).encode("ascii")
        + rb"\) [^\r\n]*(?:\balert certificate required\b|\bSSL alert number 116\b)", output,
    ):
        return
    raise RuntimeError("Catalog: не подтверждён отказ в доступе без клиентского сертификата")
