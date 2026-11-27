"""Copy immutable catalog history and transfer the writer between Credit and Catalog."""

import argparse
from contextlib import contextmanager
from hashlib import file_digest
import os
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory


SQL = Path(__file__).resolve().parent
FILES = ("products.csv", "product_versions.csv")
MANIFEST = "SHA256SUMS"


def psql(service):
    return ["psql", "-X", "--no-password", "--set=ON_ERROR_STOP=1", f"--dbname=service={service}"]


def run_sql(service, script, directory, **variables):
    args = psql(service)
    args.extend(f"--set={key}={value}" for key, value in variables.items())
    subprocess.run([*args, "-f", str(SQL / script)], cwd=directory, check=True)


def scalar(service, query):
    result = subprocess.run([*psql(service), "-Atq"], input=query + "\n", text=True, check=True, stdout=subprocess.PIPE)
    return result.stdout.strip()


@contextmanager
def transfer_lock(credit_service):
    """Serialize switch and rollback from every operator host on the same Credit database."""
    with subprocess.Popen([*psql(credit_service), "-Atq"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True) as session:
        try:
            session.stdin.write("set lock_timeout = '30s'; select pg_advisory_lock(941528328);\n\\echo catalog_transfer_locked\n")
            session.stdin.flush()
            if not any(line.strip() == "catalog_transfer_locked" for line in session.stdout):
                raise RuntimeError("Не удалось заблокировать передачу записи")
            yield
        finally:
            session.stdin.close()
            session.wait()


def manifest(directory):
    hashes = []
    for filename in FILES:
        path = directory / filename
        if path.is_symlink() or not path.is_file():
            raise SystemExit(f"Снимок должен содержать обычный файл {filename}")
        with path.open("rb") as stream:
            hashes.append(f"{file_digest(stream, 'sha256').hexdigest()}  {filename}\n")
    return "".join(hashes)


def export(service, directory, database):
    run_sql(service, "export.sql", directory, expected_database=database, credit_source=str(database == "credit").lower())
    return manifest(directory)


def save_export(service, directory, database):
    directory.mkdir(mode=0o700)
    checksums = export(service, directory, database)
    (directory / MANIFEST).write_text(checksums, encoding="ascii")
    return checksums


def checked_manifest(directory):
    checksum_file = directory / MANIFEST
    if checksum_file.is_symlink() or not checksum_file.is_file():
        raise SystemExit("Снимок должен содержать обычный файл SHA256SUMS")
    expected = checksum_file.read_text(encoding="ascii")
    if manifest(directory) != expected:
        raise SystemExit("Не совпали контрольные суммы экспорта")
    return expected


def verify(service, database, expected):
    with TemporaryDirectory(prefix="catalog-verify-") as directory:
        actual = export(service, Path(directory), database)
    if actual != expected:
        raise SystemExit("Каталог отличается от исходного снимка")
    print("Снимок каталога и контрольные суммы совпали", flush=True)


def is_writable(service, database):
    state = scalar(service, f"select writable from catalog_cutover.authority where singleton and current_database() = '{database}';")
    if state not in ("t", "f"):
        raise SystemExit(f"Не удалось определить состояние записи для БД {database}")
    return state == "t"


def set_writable(service, database, writable):
    state = "true" if writable else "false"
    scalar(
        service,
        f"set lock_timeout = '30s'; update catalog_cutover.authority set writable = {state} "
        f"where singleton and current_database() = '{database}';",
    )
    if is_writable(service, database) != writable:
        raise SystemExit(f"Состояние записи БД {database} отличается от ожидаемого")


def switch(credit_service, catalog_service, directory, direction):
    endpoints = ((credit_service, "credit"), (catalog_service, "catalog"))
    if direction == "credit":
        endpoints = endpoints[::-1]
    source, target = endpoints
    with transfer_lock(credit_service):
        if is_writable(*target):
            raise SystemExit("Целевая БД уже открыта для записи")
        set_writable(*source, False)
        print(f"Запись в {source[1]} закрыта", flush=True)
        checksums = save_export(source[0], directory, source[1])
        run_sql(target[0], "import.sql", directory, expected_database=target[1])
        verify(*target, checksums)
        if is_writable(*source):
            raise SystemExit("Исходная БД открылась во время переноса")
        set_writable(*target, True)
        print(f"Запись открыта в {target[1]}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("export", "load", "verify", "switch"))
    parser.add_argument("service", help="libpq service name; use the Credit table owner for switch")
    parser.add_argument("directory", type=Path)
    parser.add_argument("--catalog-service", help="libpq service using the Catalog table owner for switch")
    parser.add_argument("--direction", choices=("catalog", "credit"), default="catalog", help="target database for switch")
    parser.add_argument("--database", choices=("catalog", "credit"), help="database for export, load or verify")
    args = parser.parse_args()
    for service in (args.service, args.catalog_service):
        if service is not None and not re.fullmatch(r"[A-Za-z0-9_.-]+", service):
            parser.error("Invalid libpq service name")

    os.umask(0o077)
    directory = args.directory.resolve()
    if args.action == "switch":
        if not args.catalog_service or directory.exists():
            parser.error("switch requires --catalog-service and a fresh snapshot directory")
        switch(args.service, args.catalog_service, directory, args.direction)
        return

    if args.action == "export":
        save_export(args.service, directory, args.database or "credit")
        return

    expected = checked_manifest(directory)
    database = args.database or "catalog"
    if args.action == "load":
        run_sql(args.service, "import.sql", directory, expected_database=database)
        print("Снимок каталога загружен и сверен в одной транзакции")
    else:
        verify(args.service, database, expected)


if __name__ == "__main__":
    main()
