#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [[ $# -lt 2 ]]; then
  echo "Usage: $0 {seed|dump|restore|credit|projection|credit-evidence|analytics-evidence|generation} DATABASE [ARCHIVE]" >&2
  exit 2
fi
operation=$1
database=$2
if [[ ! "$database" =~ ^alpha_qa_[a-z0-9_]+$ ]]; then
  echo "Use an isolated alpha_qa_* database" >&2
  exit 2
fi
if [[ "${PGHOST:-}" == *,* ]]; then
  echo "Supply one isolated PostgreSQL host" >&2
  exit 2
fi
case "${PGHOST:-}" in
  127.0.0.1|::1|/*) ;;
  *) echo "PGHOST must be the isolated stack's loopback address or private Unix socket directory" >&2; exit 2 ;;
esac
unset PGHOSTADDR PGSERVICE PGSERVICEFILE PGOPTIONS
psql_cmd=(psql -XqAt -v ON_ERROR_STOP=1 -d "$database")

case "$operation" in
  seed|credit|projection|credit-evidence|analytics-evidence|generation)
    [[ $# == 2 ]] || exit 2
    case "$operation" in
      seed) "${psql_cmd[@]}" -f "$here/seed-credit.sql" ;;
      credit) "${psql_cmd[@]}" -f "$here/export-credit.sql" ;;
      projection) "${psql_cmd[@]}" -f "$here/export-projection.sql" ;;
      credit-evidence) "${psql_cmd[@]}" -f "$here/observe-recovery-credit.sql" ;;
      analytics-evidence) "${psql_cmd[@]}" -f "$here/observe-recovery-analytics.sql" ;;
      generation) "${psql_cmd[@]}" -f "$here/observe-generation.sql" ;;
    esac
    ;;
  dump|restore)
    [[ $# == 3 && "$3" == /tmp/* ]] || { echo "Supply a scratch archive under /tmp" >&2; exit 2; }
    archive=$3
    if [[ "$operation" == dump ]]; then
      umask 077
      # Refuse to replace an earlier recovery checkpoint.
      (set -o noclobber; pg_dump --format=custom --no-owner --no-privileges -d "$database" > "$archive")
    else
      tables=$("${psql_cmd[@]}" -c "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r', 'p', 'S', 'v', 'm', 'f')")
      [[ "$tables" == 0 ]] || { echo "Recovery target public schema is not empty" >&2; exit 2; }
      pg_restore --exit-on-error --single-transaction --no-owner --no-privileges --dbname="$database" "$archive"
    fi
    ;;
  *) echo "Unknown operation $operation" >&2; exit 2 ;;
esac
