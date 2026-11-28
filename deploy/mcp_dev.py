"""Select and attest the opt-in private MCP transport for normal HTTPS Dev."""
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import stat
import subprocess
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request

from dev_payment_reserve import ORIGIN, canonical_origin

FLAG = "CABINET_DEV_MCP_ENABLED"
OVERLAY = "deploy/compose.mcp-credit.yaml"
SERVICES = ("cabinet-mcp", "identity-mcp")
OAUTH_METADATA = "/.well-known/oauth-authorization-server"
MCP_RESOURCE = "/api/mcp"
MCP_METADATA = "/.well-known/oauth-protected-resource" + MCP_RESOURCE
OAUTH_PATHS = tuple(MCP_RESOURCE + "/oauth/" + name for name in ("authorize", "token", "revoke"))


def enabled(project, local, values, release, historical_backup):
    requested = values.get(FLAG, "false")
    if requested not in {"true", "false"}:
        raise ValueError(f"{FLAG} должен быть true или false")
    if requested == "false" or historical_backup:
        return False
    if (project != "cabinet-dev" or local or values.get("CABINET_DOMAIN") != "dev.credit-cabinet.ru"
            or canonical_origin(values.get("PUBLIC_CABINET_URL")) != ORIGIN):
        raise ValueError("MCP доступен только обычному HTTPS Dev")
    if release is None or "mcp" not in release.images:
        raise ValueError("Для MCP нужен digest образа из manifest выпуска")
    return True


def run_openssl(*args):
    try:
        return subprocess.run(["openssl", *args], capture_output=True, check=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        raise ValueError("Не прошла предварительная проверка сертификатов MCP") from None


def check_tls(root):
    info = root.lstat()
    if not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o700 or info.st_uid != os.getuid():
        raise ValueError("Каталог mcp-tls должен принадлежать оператору и иметь права 0700")
    for pair in ("identity", "credit", "client"):
        directory = root / pair
        info = directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o555 or info.st_uid != os.getuid():
            raise ValueError("Ожидается выделенный каталог сертификата MCP с правами 0555")
        for name in ("tls.crt", "tls.key", "ca.crt"):
            artifact = directory / name
            info = artifact.lstat()
            if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o444
                    or info.st_nlink != 1 or info.st_uid != os.getuid() or not info.st_size):
                raise ValueError("Ожидаются отдельные файлы сертификата MCP с правами 0444 внутри mcp-tls")
        leaf, key, ca = (str(directory / name) for name in ("tls.crt", "tls.key", "ca.crt"))
        purpose = "sslclient" if pair == "client" else "sslserver"
        name = {"identity": "identity-mcp", "credit": "credit"}.get(pair)
        hostname = ["-verify_hostname", name] if name else []
        if name:
            alternatives = run_openssl("x509", "-in", leaf, "-noout", "-ext", "subjectAltName")
            entries = re.split(rb"[\s,]+", alternatives)
            if ("DNS:" + name).encode("ascii") not in entries:
                raise ValueError("Сертификату MCP нужен точный DNS SAN")
        run_openssl("verify", "-purpose", purpose, *hostname, "-CAfile", ca, leaf)
        if run_openssl("x509", "-in", leaf, "-pubkey", "-noout") != run_openssl("pkey", "-in", key, "-pubout", "-passin", "pass:"):
            raise ValueError("Открытый ключ сертификата MCP не совпадает с ключом TLS")
    return sha256(run_openssl("x509", "-in", str(root / "client/tls.crt"), "-outform", "DER")).hexdigest()


def environment(env_path, values):
    identifier = values.get("CABINET_MCP_OAUTH_CLIENT_ID", "")
    name = values.get("CABINET_MCP_OAUTH_CLIENT_NAME", "")
    redirect = values.get("CABINET_MCP_OAUTH_REDIRECT_URI", "")
    if not re.fullmatch(r"[A-Za-z0-9._~:-]{1,255}", identifier) or not name.strip() or len(name) > 100:
        raise ValueError("Нужно настроить идентификатор и имя OAuth-клиента MCP")
    try:
        target = urlsplit(redirect)
        loopback = target.hostname in {"127.0.0.1", "::1"} and target.port is not None and target.port > 0
        if (not target.hostname or target.username is not None or target.query or target.fragment
                or not target.path or len(redirect) > 1000
                or not (target.scheme == "https" or target.scheme == "http" and loopback)):
            raise ValueError()
    except ValueError:
        raise ValueError("Нужен точный HTTPS или loopback redirect URI OAuth-клиента MCP") from None
    identity, credit = (values.get("CABINET_MCP_" + role + "_SERVICE_TOKEN", "") for role in ("IDENTITY", "CREDIT"))
    if (any(not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", value) for value in (identity, credit))
            or identity == credit or values.get("JWT_SECRET") in (identity, credit)):
        raise ValueError("Нужны два разных служебных MCP credentials, отдельных от JWT")
    root = Path(env_path).parent / "mcp-tls"
    return {
        "CABINET_MCP_TLS_DIR": str(root),
        "CABINET_MCP_CREDIT_TLS_PEER_SHA256": check_tls(root),
    }


def remove_inactive_services(project):
    if project != "cabinet-dev":
        raise ValueError("Очистка MCP доступна только для cabinet-dev")
    for service in SERVICES:
        ids = subprocess.check_output(
            ["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={project}",
             "--filter", f"label=com.docker.compose.service={service}"],
            text=True,
        ).split()
        if ids:
            subprocess.run(["docker", "rm", "--force", *ids], check=True, capture_output=True)


def read_status(opener, origin, route, method="GET"):
    try:
        request = Request(origin + route, method=method)
        with opener.open(request, timeout=10) as response:
            return response.status, response.headers, response.read(16385)
    except HTTPError as error:
        with error:
            return error.code, error.headers, b""
    except URLError:
        raise RuntimeError("HTTPS-проверка MCP не завершилась") from None


def verify_public(opener, origin):
    resource = origin + MCP_RESOURCE
    scope = ["cabinet:mcp:read"]
    oauth_metadata = {
        "issuer": origin,
        "scopes_supported": scope,
        "code_challenge_methods_supported": ["S256"],
        **{name + "_endpoint": origin + path for name, path in zip(
            ("authorization", "token", "revocation"), OAUTH_PATHS, strict=True,
        )},
    }
    mcp_metadata = {"resource": resource, "authorization_servers": [origin], "scopes_supported": scope}
    for route, expected in ((OAUTH_METADATA, oauth_metadata), (MCP_METADATA, mcp_metadata)):
        status, _, body = read_status(opener, origin, route)
        try:
            data = json.loads(body) if status == 200 and len(body) <= 16384 else {}
            valid = all(data.get(key) == value for key, value in expected.items())
        except (ValueError, AttributeError):
            valid = False
        if not valid:
            raise RuntimeError("MCP metadata не совпадает с origin и OAuth-профилем выпуска")
    for route, expected in zip(OAUTH_PATHS, (400, 405, 405), strict=True):
        if read_status(opener, origin, route)[0] != expected:
            raise RuntimeError("Внешний gateway не подтвердил точный маршрут OAuth")
    status, headers, _ = read_status(opener, origin, MCP_RESOURCE, "POST")
    challenge = headers.get("WWW-Authenticate", "")
    if status != 401 or f'resource_metadata="{origin}{MCP_METADATA}"' not in challenge:
        raise RuntimeError("MCP не подтвердил обязательную авторизацию")
    for route in (MCP_RESOURCE + "/", "/internal/mcp/authorize", "/internal/mcp/redeem", "/internal/mcp/credit/priorities"):
        if read_status(opener, origin, route)[0] != 404:
            raise RuntimeError("Внешний gateway не подтвердил закрытие внутренних маршрутов MCP")
    return {"resource": resource, "metadata_http": 200, "unauthenticated_http": 401, "private_http": 404}


def verify_private(deployment):
    """Prove peer certificate, SAN and protected endpoints before opening Dev ingress."""
    result = subprocess.run(
        ["docker", "exec", deployment.container("cabinet-mcp"), "node", "--input-type=module", "--eval", (Path(__file__).with_name("mcp-private-probe.mjs")).read_text()],
        capture_output=True, check=False, timeout=15,
    )
    if result.returncode:
        raise RuntimeError("Внутренние TLS-проверки MCP не прошли")
