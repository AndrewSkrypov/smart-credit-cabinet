#!/bin/sh
set -eu
case "${VALKEY_PASSWORD:-}" in
  ''|*[!A-Za-z0-9_-]*) echo 'VALKEY_PASSWORD must be a nonempty URL-safe generated secret' >&2; exit 1 ;;
esac
umask 077
mkdir -p /run/cabinet-valkey
cat > /run/cabinet-valkey/valkey.conf <<EOF
bind 0.0.0.0
port 6379
protected-mode yes
requirepass $VALKEY_PASSWORD
dir /data
appendonly yes
appendfsync always
save ""
maxmemory 64mb
maxmemory-policy noeviction
timeout 0
maxclients 256
tcp-keepalive 30
EOF
chown -R valkey:valkey /run/cabinet-valkey
unset VALKEY_PASSWORD
exec /usr/local/bin/docker-entrypoint.sh valkey-server /run/cabinet-valkey/valkey.conf
