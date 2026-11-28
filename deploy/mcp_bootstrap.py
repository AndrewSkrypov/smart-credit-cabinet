"""Prepare the versioned MCP opt-in under the ordinary Dev deployment lock."""
import ctypes
import errno
from hashlib import sha256
import os
from pathlib import Path
import re
import secrets
import stat
from tempfile import TemporaryDirectory

from dev_payment_reserve import ORIGIN, canonical_origin
import mcp_dev
from registry_release import IMAGE
from runtime_delivery_support import fsync_dir, read_record, require_path, write_record


# This registration is the versioned opt-in for the protected ordinary Dev controller.
DEV_CLIENT = {
    "CABINET_MCP_OAUTH_CLIENT_ID": "alpha-hack-dev",
    "CABINET_MCP_OAUTH_CLIENT_NAME": "Alpha Hack Dev",
    "CABINET_MCP_OAUTH_REDIRECT_URI": "http://127.0.0.1:38765/callback",
}
TOKENS = ("CABINET_MCP_IDENTITY_SERVICE_TOKEN", "CABINET_MCP_CREDIT_SERVICE_TOKEN")
SETTINGS = {*DEV_CLIENT, *TOKENS}
TLS_PAIRS = {"identity": "identity-mcp", "credit": "credit", "client": None}
TLS_FILES = {"ca.crt", "tls.crt", "tls.key"}
PRIVATE_DIR = "mcp-bootstrap"
TLS_DIR = "mcp-tls"
RECORD = "prepared.json"
TOKEN = re.compile(r"[A-Za-z0-9_-]{32,128}")
ENV_ASSIGNMENT = re.compile(r"([A-Z_][A-Z_0-9]*)=([^\r\n]*)")
SCOPE_KEYS = {"CABINET_DOMAIN", "PUBLIC_CABINET_URL", "CABINET_ISOLATED_DEMO", "DEMO_ACCOUNTS",
              "SIM_CONTROL_ENABLED", "JWT_SECRET", mcp_dev.FLAG, *SETTINGS}


def read_values(contents):
    values = {}
    for line in contents.decode("utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        item = ENV_ASSIGNMENT.fullmatch(line)
        if not item:
            raise ValueError("Для MCP нужен cabinet.env в формате KEY=value")
        name, value = item.groups()
        if name in SCOPE_KEYS and name in values:
            raise ValueError("Повтор настройки области или учетных данных MCP в cabinet.env")
        values[name] = value
    return values


def publish_directory(source, target):
    # The controller runs on Linux. An ordinary rename can replace an empty directory.
    rename = getattr(ctypes.CDLL(None, use_errno=True), "renameat2", None)
    if rename is None:
        raise ValueError("Для атомарной публикации MCP нужен Linux renameat2")
    rename.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
    rename.restype = ctypes.c_int
    at_current_directory, no_replace = -100, 1
    if rename(at_current_directory, os.fsencode(source), at_current_directory, os.fsencode(target), no_replace):
        if ctypes.get_errno() in {errno.EEXIST, errno.ENOTEMPTY}:
            raise ValueError("Путь публикации MCP уже занят")
        raise ValueError("Не удалось атомарно опубликовать каталог MCP")
    fsync_dir(target.parent)
    fsync_dir(source.parent)


def file_hash(path):
    with path.open("rb") as stream:
        return sha256(stream.read(16 * 1024 + 1)).hexdigest()


def tls_hashes(root):
    require_path(root, stat.S_ISDIR, 0o700)
    if {path.name for path in root.iterdir()} != TLS_PAIRS.keys():
        raise ValueError("Неожиданный состав подготовленного mcp-tls")
    hashes = {}
    for pair in TLS_PAIRS:
        directory = root / pair
        require_path(directory, stat.S_ISDIR, 0o555)
        if {path.name for path in directory.iterdir()} != TLS_FILES:
            raise ValueError("Неожиданный состав пары сертификатов MCP")
        for name in sorted(TLS_FILES):
            path = directory / name
            info = path.lstat()
            if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o444
                    or info.st_uid != os.getuid() or info.st_nlink != 1 or not 0 < info.st_size <= 16 * 1024):
                raise ValueError("Некорректный файл подготовленного сертификата MCP")
            hashes[f"{pair}/{name}"] = file_hash(path)
    return hashes


def sync_file(path):
    with path.open("rb") as stream:
        os.fsync(stream.fileno())


def generate_tls(bundle, scratch):
    root = bundle / TLS_DIR
    root.mkdir(mode=0o700)
    ca_key = bundle / "ca.key"
    ca = scratch / "ca.crt"
    request = scratch / "leaf.csr"
    extensions = scratch / "leaf.ext"
    openssl = mcp_dev.run_openssl
    ec = ("-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes")
    openssl("req", "-new", "-x509", *ec, "-sha256", "-days", "3650", "-subj", "/CN=Alpha Hack Dev MCP CA",
            "-keyout", str(ca_key), "-out", str(ca),
            "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0", "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    ca_key.chmod(0o600)
    sync_file(ca_key)
    for pair, host in TLS_PAIRS.items():
        directory = root / pair
        directory.mkdir(mode=0o700)
        leaf, key, chain = (directory / name for name in ("tls.crt", "tls.key", "ca.crt"))
        openssl("req", "-new", *ec, "-sha256", "-subj", f"/CN={host or 'cabinet-mcp'}",
                "-keyout", str(key), "-out", str(request))
        purpose = "serverAuth" if host else "clientAuth"
        extensions.write_text(
            "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
            f"extendedKeyUsage={purpose}\n" + (f"subjectAltName=DNS:{host}\n" if host else ""), encoding="ascii",
        )
        openssl("x509", "-req", "-in", str(request), "-CA", str(ca), "-CAkey", str(ca_key),
                "-set_serial", "0x" + secrets.token_hex(16), "-days", "365", "-sha256",
                "-extfile", str(extensions), "-out", str(leaf))
        chain.write_bytes(ca.read_bytes())
        for path in (leaf, key, chain):
            path.chmod(0o444)
            sync_file(path)
        directory.chmod(0o555)
        fsync_dir(directory)
    fsync_dir(root)


def valid_settings(settings, jwt):
    return (
        isinstance(settings, dict) and settings.keys() == SETTINGS
        and all(settings[key] == value for key, value in DEV_CLIENT.items())
        and all(isinstance(settings[key], str) and TOKEN.fullmatch(settings[key]) for key in TOKENS)
        and len({jwt, *(settings[key] for key in TOKENS)}) == 3
    )


def prepare_bundle(state, bundle, values):
    with TemporaryDirectory(prefix=".mcp-bootstrap-", dir=state) as directory:
        scratch = Path(directory)
        candidate = scratch / "bundle"
        candidate.mkdir(mode=0o700)
        settings = {**DEV_CLIENT, **{name: secrets.token_urlsafe(48) for name in TOKENS}}
        if not valid_settings(settings, values.get("JWT_SECRET")):
            raise ValueError("Не удалось подготовить разные служебные credentials MCP")
        generate_tls(candidate, scratch)
        mcp_dev.check_tls(candidate / TLS_DIR)
        write_record(candidate / RECORD, {"schema": 1, "settings": settings,
                                        "tls_sha256": tls_hashes(candidate / TLS_DIR),
                                        "ca_key_sha256": file_hash(candidate / "ca.key")}, immutable=True)
        publish_directory(candidate, bundle)


def managed_settings(state, values):
    bundle, live = state / PRIVATE_DIR, state / TLS_DIR
    if not os.path.lexists(bundle):
        if os.path.lexists(live):
            raise ValueError("Найден mcp-tls без подтвержденной настройки MCP")
        prepare_bundle(state, bundle, values)
    require_path(bundle, stat.S_ISDIR, 0o700)
    staged = bundle / TLS_DIR
    has_staged, has_live = os.path.lexists(staged), os.path.lexists(live)
    expected = {RECORD, "ca.key"} | ({TLS_DIR} if has_staged else set())
    if has_staged == has_live or {path.name for path in bundle.iterdir()} != expected:
        raise ValueError("Неполная или конфликтующая подготовка MCP")
    record_path = bundle / RECORD
    require_path(record_path, stat.S_ISREG, 0o600)
    if not 0 < record_path.stat().st_size <= 8 * 1024:
        raise ValueError("Некорректный размер приватной записи подготовки MCP")
    record = read_record(record_path)
    ca_key = bundle / "ca.key"
    require_path(ca_key, stat.S_ISREG, 0o600)
    ca_info = ca_key.stat()
    if (record is None or set(record) != {"schema", "settings", "tls_sha256", "ca_key_sha256"}
            or type(record["schema"]) is not int or record["schema"] != 1
            or not valid_settings(record["settings"], values.get("JWT_SECRET"))
            or not 0 < ca_info.st_size <= 16 * 1024 or ca_info.st_nlink != 1
            or record["ca_key_sha256"] != file_hash(ca_key)):
        raise ValueError("Не подтверждена приватная запись подготовки MCP")
    root = staged if has_staged else live
    if record["tls_sha256"] != tls_hashes(root):
        raise ValueError("Изменились подготовленные сертификаты MCP")
    mcp_dev.check_tls(root)
    if has_staged:
        publish_directory(staged, live)
    else:
        fsync_dir(bundle)
        fsync_dir(state)
    return record["settings"]


def prepare_env(project, env_file, release, contents, *, allow_new, verify_only=False):
    """Return candidate bytes; the controller owns backup, enrollment and ENV replacement."""
    if verify_only or project != "cabinet-dev":
        return contents
    image = getattr(release, "images", {}).get("mcp")
    if not isinstance(image, str) or not IMAGE.fullmatch(image) or not image.rsplit("/", 1)[-1].startswith("mcp@"):
        return contents
    values = read_values(contents)
    if (values.get("CABINET_DOMAIN") != "dev.credit-cabinet.ru" or canonical_origin(values.get("PUBLIC_CABINET_URL")) != ORIGIN
            or any(values.get(name, "false") != "false" for name in
                   ("CABINET_ISOLATED_DEMO", "DEMO_ACCOUNTS", "SIM_CONTROL_ENABLED"))):
        return contents
    flag = values.get(mcp_dev.FLAG)
    if flag == "false":
        return contents
    if flag == "true" and SETTINGS <= values.keys():
        mcp_dev.environment(env_file, values)
        return contents
    if flag is not None or SETTINGS & values.keys():
        raise ValueError("Неполная ручная настройка MCP в cabinet.env")
    if not allow_new:
        return contents
    require_path(env_file.parent, stat.S_ISDIR, 0o700)
    require_path(env_file, stat.S_ISREG, 0o600)
    settings = managed_settings(env_file.parent, values)
    newline = b"\r\n" if b"\r\n" in contents else b"\n"
    separator = newline if contents and not contents.endswith((b"\n", b"\r")) else b""
    return contents + separator + b"".join(
        f"{key}={value}".encode("ascii") + newline for key, value in {mcp_dev.FLAG: "true", **settings}.items()
    )
