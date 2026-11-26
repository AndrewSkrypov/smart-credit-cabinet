umask 077
stamp="valkey-$(date -u +%Y%m%dT%H%M%SZ)-$HOSTNAME"
temp="$(mktemp -d "/archive/.${stamp}.XXXXXXXX")"
trap 'rm -rf "$temp"' EXIT
trap 'exit 1' HUP INT TERM
valkey-cli -h valkey --rdb "$temp/dump.rdb"
valkey-check-rdb "$temp/dump.rdb" >/dev/null
(cd "$temp" && sha256sum dump.rdb > SHA256SUMS)
mv "$temp" "/archive/$stamp"
find /archive -mindepth 1 -maxdepth 1 -type d -name 'valkey-*' -mtime "+$BACKUP_RETENTION_DAYS" -exec rm -rf -- '{}' +
printf 'Verified valkey backup %s\n' "$stamp"
