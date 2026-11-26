umask 077
stamp="postgres-$(date -u +%Y%m%dT%H%M%SZ)-$HOSTNAME"
temp="$(mktemp -d "/archive/.${stamp}.XXXXXXXX")"
trap 'rm -rf "$temp"' EXIT
trap 'exit 1' HUP INT TERM
pg_dumpall --globals-only --no-role-passwords > "$temp/globals.sql"
for db in identity credit engagement; do
  exists="$(psql -XAt -v ON_ERROR_STOP=1 -c "select 1 from pg_database where datname='$db'")"
  if [ "$exists" = 1 ]; then
    pg_dump --format=custom --file "$temp/$db.dump" "$db"
    pg_restore --list "$temp/$db.dump" >/dev/null
  else
    printf '%s\n' "$db" >> "$temp/not-yet-created.txt"
  fi
done
(cd "$temp" && sha256sum ./* > SHA256SUMS)
mv "$temp" "/archive/$stamp"
find /archive -mindepth 1 -maxdepth 1 -type d -name 'postgres-*' -mtime "+$BACKUP_RETENTION_DAYS" -exec rm -rf -- '{}' +
printf 'Verified postgres backup %s\n' "$stamp"
