#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."

: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
: "${GRADLE_USER_HOME:?Set GRADLE_USER_HOME for the Catalog integration build}"
gradle=("${CATALOG_TEST_GRADLE:-./gradlew}" --max-workers=1 "$@")
scratch=$(mktemp -d /tmp/cabinet-catalog-r1.XXXXXXXX)
reports="${CATALOG_TEST_REPORT_DIR:-ci-artifacts/catalog-r1}"
pg_bin="${CATALOG_TEST_PG_BIN:-$(pg_config --bindir)}"
pg_started=false
container=
catalog_pid=
unset JWT_SECRET SPRING_APPLICATION_JSON PGHOSTADDR PGSERVICE PGOPTIONS PGPASSWORD

cleanup() {
    local status=$?
    trap - EXIT
    if [[ -n "$catalog_pid" ]]; then
        kill "$catalog_pid" 2>/dev/null || true
        wait "$catalog_pid" 2>/dev/null || true
    fi
    mkdir -p "$reports"
    for log in postgres.log migrations.log cutover.log catalog.log; do
        if [[ -f "$scratch/$log" ]]; then
            cp "$scratch/$log" "$reports/$log"
        fi
    done
    if [[ -n "$container" ]]; then
        docker rm -f -v "$container" >/dev/null || status=1
    elif [[ "$pg_started" = true ]] && "$pg_bin/pg_ctl" -D "$scratch/db" status >/dev/null; then
        "$pg_bin/pg_ctl" -D "$scratch/db" -m fast -w stop >/dev/null || status=1
    fi
    rm -rf -- "$scratch"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

admin_password=$(openssl rand -hex 24)
export CREDIT_OWNER_PASSWORD=$(openssl rand -hex 24)
export CREDIT_APP_PASSWORD=$(openssl rand -hex 24)
export CATALOG_OWNER_PASSWORD=$(openssl rand -hex 24)
export CATALOG_APP_PASSWORD=$(openssl rand -hex 24)

free_port() {
    python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

if [[ -n "${CATALOG_TEST_POSTGRES_IMAGE:-}" ]]; then
    docker build --output "type=local,dest=$scratch/context" -f - . <<'DOCKERFILE'
FROM scratch
COPY contracts/proto/catalog/v1/catalog.proto /catalog.proto
DOCKERFILE
    cmp contracts/proto/catalog/v1/catalog.proto "$scratch/context/catalog.proto"
    # Only the runner supplies an already verified image. All local runs use native PostgreSQL.
    container=$(POSTGRES_PASSWORD="$admin_password" docker run -d --rm \
        --name "${CATALOG_TEST_POSTGRES_NAME:?Set the job-scoped container name}" -p 127.0.0.1::5432 \
        -e POSTGRES_USER=catalog_it_admin -e POSTGRES_PASSWORD -e POSTGRES_DB=postgres "$CATALOG_TEST_POSTGRES_IMAGE")
    address=$(docker port "$container" 5432/tcp)
    db_port="${address##*:}"
else
    printf '%s\n' "$admin_password" > "$scratch/pg-password"
    init_args=()
    if [[ -n "${CATALOG_TEST_PG_SHARE:-}" ]]; then
        init_args=(-L "$CATALOG_TEST_PG_SHARE")
    fi
    "$pg_bin/initdb" -D "$scratch/db" --username=catalog_it_admin --no-locale --encoding=UTF8 \
        --auth-local=trust --auth-host=scram-sha-256 --pwfile="$scratch/pg-password" "${init_args[@]}" > "$scratch/postgres.log" 2>&1
    db_port=$(free_port)
    pg_started=true
    "$pg_bin/pg_ctl" -D "$scratch/db" -l "$scratch/postgres.log" -o "-h 127.0.0.1 -p $db_port -k $scratch" -w start
fi

export PATH="$pg_bin:$PATH"
psql() {
    command psql -X -w -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$db_port" "$@"
}
for ((attempt=0; attempt<60; attempt++)); do
    if PGPASSWORD="$admin_password" psql -U catalog_it_admin -d postgres -qAtc 'select 1' >/dev/null 2>&1; then break; fi
    sleep 1
done
PGPASSWORD="$admin_password" psql -U catalog_it_admin -d postgres <<'SQL' > "$scratch/migrations.log"
\getenv credit_owner_password CREDIT_OWNER_PASSWORD
\getenv credit_app_password CREDIT_APP_PASSWORD
\getenv catalog_owner_password CATALOG_OWNER_PASSWORD
\getenv catalog_app_password CATALOG_APP_PASSWORD
create role credit_owner login password :'credit_owner_password';
create role credit_app login password :'credit_app_password';
create role catalog_owner login password :'catalog_owner_password';
create role catalog_app login password :'catalog_app_password';
create role catalog_writer nologin;
grant catalog_writer to catalog_app;
create database credit owner credit_owner;
create database catalog owner catalog_owner;
SQL

while IFS= read -r migration; do
    PGPASSWORD="$CREDIT_OWNER_PASSWORD" psql -U credit_owner -d credit -f "$migration" >> "$scratch/migrations.log"
done < <(printf '%s\n' services/credit/src/main/resources/db/migration/V*.sql | sort -V)
PGPASSWORD="$CREDIT_OWNER_PASSWORD" psql -U credit_owner -d credit -v credit_app=credit_app \
    -f services/catalog-grpc/cutover/credit-fence.sql >> "$scratch/migrations.log"
PGPASSWORD="$CREDIT_OWNER_PASSWORD" psql -U credit_owner -d credit <<'SQL' >> "$scratch/migrations.log"
grant usage on schema public to credit_app;
grant select, insert, update, delete on all tables in schema public to credit_app;
grant usage, select on all sequences in schema public to credit_app;
SQL
PGPASSWORD="$CATALOG_OWNER_PASSWORD" psql -U catalog_owner -d catalog \
    -f services/catalog-grpc/src/main/resources/db/catalog/V1__product_history.sql \
    -f services/catalog-grpc/cutover/grants.sql >> "$scratch/migrations.log"

export PGSERVICEFILE="$scratch/pg_service.conf"
cat > "$PGSERVICEFILE" <<EOF_SERVICE
[credit-owner]
host=127.0.0.1
port=$db_port
dbname=credit
user=credit_owner
password=$CREDIT_OWNER_PASSWORD

[catalog-owner]
host=127.0.0.1
port=$db_port
dbname=catalog
user=catalog_owner
password=$CATALOG_OWNER_PASSWORD
EOF_SERVICE
python3 services/catalog-grpc/cutover/snapshot.py switch credit-owner "$scratch/snapshot" \
    --catalog-service catalog-owner > "$scratch/cutover.log"

tls="$scratch/tls"
mkdir "$tls"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=Catalog-R1-CA \
    -keyout "$tls/ca.key" -out "$tls/ca.crt" 2> "$scratch/openssl.log"
for peer in server credit wrong; do
    case "$peer" in
        server) extension=serverAuth; san='IP:127.0.0.1' ;;
        credit) extension=clientAuth; san='URI:spiffe://creditcabinet/credit' ;;
        wrong) extension=clientAuth; san='URI:spiffe://creditcabinet/wrong' ;;
    esac
    printf 'extendedKeyUsage=%s\nsubjectAltName=%s\n' "$extension" "$san" > "$tls/$peer.ext"
    openssl req -new -newkey rsa:2048 -nodes -subj "/CN=$peer" \
        -keyout "$tls/$peer.key" -out "$tls/$peer.csr" 2>> "$scratch/openssl.log"
    openssl x509 -req -in "$tls/$peer.csr" -CA "$tls/ca.crt" -CAkey "$tls/ca.key" -CAcreateserial -days 1 \
        -extfile "$tls/$peer.ext" -out "$tls/$peer.crt" 2>> "$scratch/openssl.log"
done

export CATALOG_TEST_JDBC_URL="jdbc:postgresql://127.0.0.1:$db_port/catalog"
export CATALOG_TEST_DATABASE_USER=catalog_app CATALOG_TEST_DATABASE_PASSWORD="$CATALOG_APP_PASSWORD" CATALOG_TEST_CERTS="$tls"
rm -f services/{catalog-grpc,credit}/build/test-results/catalogIntegrationTest/TEST-*.xml
"${gradle[@]}" :services:catalog-grpc:bootJar :services:catalog-grpc:catalogIntegrationTest

export JWT_SECRET=$(openssl rand -hex 32)
server_port=$(free_port)
export CABINET_CATALOG_GRPC_DATABASE_PASSWORD="$CATALOG_APP_PASSWORD"
"$JAVA_HOME/bin/java" -Xmx256m -XX:ActiveProcessorCount=2 -jar services/catalog-grpc/build/libs/app.jar \
    --cabinet.catalog.grpc.database.url="$CATALOG_TEST_JDBC_URL" --cabinet.catalog.grpc.database.user=catalog_app \
    --cabinet.catalog.grpc.server.host=127.0.0.1 --cabinet.catalog.grpc.server.port="$server_port" \
    --cabinet.catalog.grpc.server.certificate="$tls/server.crt" \
    --cabinet.catalog.grpc.server.key="$tls/server.key" --cabinet.catalog.grpc.server.client-ca="$tls/ca.crt" \
    > "$scratch/catalog.log" 2>&1 &
catalog_pid=$!

catalog_ready() {
    timeout 2 openssl s_client -connect "127.0.0.1:$server_port" -alpn h2 -verify_ip 127.0.0.1 -verify_return_error \
        -CAfile "$tls/ca.crt" -cert "$tls/credit.crt" -key "$tls/credit.key" -brief </dev/null >/dev/null 2>&1
}
for ((attempt=0; attempt<25; attempt++)); do
    if catalog_ready; then break; fi
    kill -0 "$catalog_pid" || break
    sleep 1
done
if ! catalog_ready; then
    cat "$scratch/catalog.log" >&2
    exit 1
fi

export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$db_port/credit"
export SPRING_DATASOURCE_USERNAME=credit_app SPRING_DATASOURCE_PASSWORD="$CREDIT_APP_PASSWORD"
export CATALOG_TEST_OWNER_JDBC_URL="$CATALOG_TEST_JDBC_URL" CATALOG_TEST_OWNER_DATABASE_USER=catalog_owner
export CATALOG_TEST_OWNER_DATABASE_PASSWORD="$CATALOG_OWNER_PASSWORD"
export SPRING_APPLICATION_JSON=$(python3 - "$server_port" "$tls" <<'PY'
import json
from pathlib import Path
import sys

port, directory = sys.argv[1:]
tls = Path(directory)
print(json.dumps({
    "cabinet": {
        "catalog": {"grpc": {"client": {
            "host": "127.0.0.1", "port": int(port),
            "certificate": str(tls / "credit.crt"), "key": str(tls / "credit.key"), "server-ca": str(tls / "ca.crt"),
        }}},
        "storage": {"local-dir": str(tls.parent / "uploads")},
    },
    "spring": {"datasource": {"hikari": {"maximum-pool-size": 3, "minimum-idle": 0}}},
}))
PY
)
"${gradle[@]}" :services:credit:catalogIntegrationTest

python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree

for module, name in (
    ("catalog-grpc", "ru.creditcabinet.cataloggrpc.CatalogPostgresIntegrationTest"),
    ("credit", "ru.creditcabinet.product.RemoteCatalogIntegrationTest"),
):
    result = Path("services", module, "build", "test-results", "catalogIntegrationTest", f"TEST-{name}.xml")
    suite = ElementTree.parse(result).getroot()
    count = int(suite.attrib["tests"])
    if count < 1 or any(int(suite.attrib[field]) for field in ("skipped", "failures", "errors")):
        raise SystemExit(f"Invalid integration result for {name}: {suite.attrib}")
    print(f"{name}: {count} passed, 0 skipped")
PY
