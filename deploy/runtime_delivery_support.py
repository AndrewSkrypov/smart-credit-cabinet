"""Local primitives used by the isolated Documents and Servicing rollout hooks."""

from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
from tempfile import NamedTemporaryFile, TemporaryDirectory

from registry_release import IMAGE, REVISION, SHA256

COMPONENTS = {"documents", "servicing"}
MODES = {"disabled", "local", "remote"}
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")
STATE_KEY = re.compile(r"[A-Z][A-Z_0-9]+")
DEV_PROJECT = re.compile(r"cabinet-dev(?:-restore-[a-z0-9][a-z0-9-]*)?")
TLS_IDENTITIES = {
    "documents": "spiffe://credit-cabinet/credit",
    "servicing": "spiffe://creditcabinet/credit",
}
TLS_FILES = ("ca.crt", "tls.crt", "tls.key")


def matches(pattern, value):
    return isinstance(value, str) and pattern.fullmatch(value) is not None


def run(*args, stdin=None, env=None, timeout=90):
    try:
        result = subprocess.run(args, input=stdin, env=env, capture_output=True, text=True,
                                encoding="utf-8", timeout=timeout, check=False)
    except (OSError, subprocess.SubprocessError, UnicodeError):
        raise RuntimeError("Операция поставки runtime не завершилась") from None
    if result.returncode:
        raise RuntimeError(f"Операция поставки runtime завершилась с кодом {result.returncode}")
    return result.stdout.strip()


def inspect_one(*args):
    try:
        report = json.loads(run("docker", *args))
    except ValueError:
        raise RuntimeError("Некорректный отчёт OCI") from None
    if not isinstance(report, list) or len(report) != 1 or not isinstance(report[0], dict):
        raise RuntimeError("Ожидается один объект OCI")
    return report[0]


@dataclass(frozen=True)
class Descriptor:
    revision: str
    manifest_sha256: str
    mode: str
    image: str
    jar_sha256: str
    component: str

    @classmethod
    def from_release(cls, release, component):
        if component not in COMPONENTS:
            raise ValueError("Неизвестный компонент runtime")
        if release is None:
            return None
        images = release.images
        metadata = getattr(release, component, None)
        if metadata is None and component not in images:
            return None
        credit, image = images.get("credit"), images.get(component)
        if (not isinstance(metadata, dict) or set(metadata) != {"jar_sha256", "dev_mode"}
                or not matches(REVISION, release.revision) or not matches(SHA256, release.checksum)
                or not matches(SHA256, metadata["jar_sha256"])
                or not isinstance(metadata["dev_mode"], str) or metadata["dev_mode"] not in MODES
                or not matches(IMAGE, credit) or not matches(IMAGE, image)):
            raise ValueError(f"Некорректный descriptor выпуска {component}")
        repository = credit.rsplit("/", 1)[0]
        if credit.rsplit("@", 1)[0] != f"{repository}/credit" or image.rsplit("@", 1)[0] != f"{repository}/{component}":
            raise ValueError(f"Образ {component} должен использовать digest проекта выпуска")
        return cls(release.revision, release.checksum, metadata["dev_mode"], image, metadata["jar_sha256"], component)

    def verify_image(self):
        data = inspect_one("image", "inspect", self.image)
        labels = (data.get("Config") or {}).get("Labels") or {}
        if (not matches(IMAGE_ID, data.get("Id")) or self.image not in (data.get("RepoDigests") or ())
                or labels.get("org.opencontainers.image.revision") != self.revision
                or labels.get("org.opencontainers.image.jar.sha256") != self.jar_sha256):
            raise RuntimeError(f"OCI provenance {self.component} не совпадает с выпуском")
        return data["Id"]


def verify_container(deployment, service, image_id, *, ready=False):
    data = inspect_one("inspect", "--type", "container", deployment.container(service))
    state = data.get("State") or {}
    if (data.get("Image") != image_id or state.get("Running") is not True
            or (ready and (state.get("Health") or {}).get("Status") != "healthy")):
        raise RuntimeError(f"Контейнер {service} не подтвердил OCI ID или readiness")
    return image_id


def private_state(deployment, component):
    if component not in COMPONENTS:
        raise ValueError("Неизвестный компонент runtime")
    if deployment.local or not matches(DEV_PROJECT, deployment.project):
        return None
    name = component if deployment.project == "cabinet-dev" else f"{component}-{deployment.project}"
    return Path(deployment.path).parent / name


def require_path(path, type_check, mode):
    info = path.lstat()
    if not type_check(info.st_mode) or stat.S_IMODE(info.st_mode) != mode or info.st_uid != os.getuid():
        raise ValueError(f"Некорректный тип, владелец или права файла {path.name}")


def ensure_private(path):
    path = Path(path)
    path.mkdir(mode=0o700, exist_ok=True)
    require_path(path, stat.S_ISDIR, 0o700)
    return path


def fsync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def read_private(path):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return None
    with os.fdopen(fd, "r", encoding="utf-8", newline="") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.getuid():
            raise ValueError("Состояние runtime требует файл оператора с правами 0600")
        return stream.read()


def write_private(path, value, *, exclusive=False):
    path = Path(path)
    temporary = None
    try:
        with NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            os.fchmod(stream.fileno(), 0o600)
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        if exclusive:
            os.link(temporary, path, follow_symlinks=False)
        else:
            temporary.replace(path)
        fsync_dir(path.parent)
    finally:
        if temporary:
            temporary.unlink(missing_ok=True)


def read_record(path):
    raw = read_private(path)
    if raw is None:
        return None
    record = json.loads(raw)
    if not isinstance(record, dict):
        raise ValueError("Ожидается запись состояния runtime")
    return record


def write_record(path, value, immutable=False):
    if not isinstance(value, dict):
        raise ValueError("Ожидается запись состояния runtime")
    serialized = json.dumps(value, sort_keys=True)
    try:
        write_private(path, serialized + "\n", exclusive=immutable)
    except FileExistsError:
        if not immutable or read_record(path) != json.loads(serialized):
            raise RuntimeError("Попытка изменить неизменяемую квитанцию runtime")


def load_secrets(path, keys, other_values=None):
    keys = tuple(keys)
    if not keys or len(set(keys)) != len(keys) or any(not matches(STATE_KEY, key) for key in keys):
        raise ValueError("Некорректный перечень секретов runtime")
    data = read_private(path)
    if data is None:
        data = "".join(f"{key}={secrets.token_hex(32)}\n" for key in keys)
        try:
            write_private(path, data, exclusive=True)
        except FileExistsError:
            data = read_private(path)
            if data is None:
                raise RuntimeError("Приватный ENV runtime исчез при открытии")
    values = {}
    for line in data.splitlines():
        key, delimiter, value = line.partition("=")
        if key in values or delimiter != "=" or not matches(SHA256, value):
            raise ValueError("Некорректный приватный ENV runtime")
        values[key] = value
    occupied = other_values.values() if isinstance(other_values, dict) else (other_values or ())
    if set(values) != set(keys) or len(set(values.values())) != len(keys) or set(values.values()).intersection(occupied):
        raise ValueError("Runtime требует отдельные автоматически созданные секреты")
    return values


def openssl(*arguments):
    return run("openssl", *arguments, timeout=30)


def create_tls(root, service, client_uri):
    ca_key, ca_certificate = root / "ca.key", root / "ca.crt"
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "3650",
            "-subj", f"/CN=cabinet-dev-{service}", "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign", "-keyout", ca_key, "-out", ca_certificate)
    ca_key.chmod(0o600)
    ca_certificate.chmod(0o444)
    for side, common_name, san, purpose in (("server", service, f"DNS:{service}", "serverAuth"),
                                           ("credit", "credit", f"URI:{client_uri}", "clientAuth")):
        directory = ensure_private(root / side)
        key, certificate, csr = directory / "tls.key", directory / "tls.crt", root / "leaf.csr"
        extension = root / "leaf.cnf"
        openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", f"/CN={common_name}",
                "-keyout", key, "-out", csr)
        extension.write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                             f"extendedKeyUsage={purpose}\nsubjectAltName={san}\n", encoding="ascii")
        openssl("x509", "-req", "-sha256", "-days", "730", "-in", csr, "-CA", ca_certificate,
                "-CAkey", ca_key, "-set_serial", "0x" + secrets.token_hex(16), "-extfile", extension, "-out", certificate)
        (directory / "ca.crt").write_bytes(ca_certificate.read_bytes())
        for name in TLS_FILES:
            artifact = directory / name
            artifact.chmod(0o444)
            with artifact.open("rb") as stream:
                os.fsync(stream.fileno())
        directory.chmod(0o555)
        fsync_dir(directory)
        csr.unlink()
        extension.unlink()
    for artifact in (ca_key, ca_certificate):
        with artifact.open("rb") as stream:
            os.fsync(stream.fileno())
    fsync_dir(root)


def ensure_tls(state, service, client_uri):
    if TLS_IDENTITIES.get(service) != client_uri:
        raise ValueError("Неизвестная mTLS identity runtime")
    state = ensure_private(state)
    root = state / "tls"
    if not os.path.lexists(root):
        with TemporaryDirectory(prefix=".tls-", dir=state) as scratch:
            candidate = ensure_private(Path(scratch) / "tls")
            create_tls(candidate, service, client_uri)
            candidate.rename(root)
            fsync_dir(state)
    require_path(root, stat.S_ISDIR, 0o700)
    require_path(root / "ca.key", stat.S_ISREG, 0o600)
    require_path(root / "ca.crt", stat.S_ISREG, 0o444)
    ca = (root / "ca.crt").read_bytes()
    if openssl("x509", "-in", root / "ca.crt", "-noout", "-pubkey") != openssl("pkey", "-in", root / "ca.key", "-pubout"):
        raise ValueError("Сертификат и ключ CA runtime различаются")
    for side, purpose in (("server", "sslserver"), ("credit", "sslclient")):
        directory = root / side
        require_path(directory, stat.S_ISDIR, 0o555)
        for name in TLS_FILES:
            require_path(directory / name, stat.S_ISREG, 0o444)
        if (directory / "ca.crt").read_bytes() != ca:
            raise ValueError("Цепочки доверия runtime различаются")
        certificate, key = directory / "tls.crt", directory / "tls.key"
        extra = ("-verify_hostname", service) if side == "server" else ()
        openssl("verify", "-CAfile", root / "ca.crt", "-purpose", purpose, *extra, certificate)
        if openssl("x509", "-in", certificate, "-noout", "-pubkey") != openssl("pkey", "-in", key, "-pubout"):
            raise ValueError("Сертификат и ключ runtime различаются")
        san = openssl("x509", "-in", certificate, "-noout", "-ext", "subjectAltName")
        expected = f"DNS:{service}" if side == "server" else f"URI:{client_uri}"
        names = [part.strip() for part in san.partition("\n")[2].split(",")]
        if names != [expected]:
            raise ValueError("Сертификат runtime содержит чужой SAN")
    return root
