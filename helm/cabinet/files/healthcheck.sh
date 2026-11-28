#!/bin/sh
set -eu
umask 077

# PONG alone says nothing about the age of the engine currently scanning documents.
version=$(printf 'zVERSION\000' | nc -w 2 127.0.0.1 3310 | tr -d '\000')
stamp=$(printf '%s\n' "$version" | awk -F/ 'NF == 3 && $1 ~ /^ClamAV / {print $3}')
test -n "$stamp"
loaded=$(printf '%s\n' "$version" | cut -d/ -f2)
case "$loaded" in ''|*[!0-9]*) exit 1;; esac
disk=0
for database in /var/lib/clamav/daily.cvd /var/lib/clamav/daily.cld; do
    if [ -f "$database" ]; then
        serial=$(dd if="$database" bs=512 count=1 2>/dev/null | awk -F: '/^ClamAV-VDB:/ && $3 ~ /^[0-9]+$/ {print $3; exit}')
        test -n "$serial"
        if [ "$serial" -gt "$disk" ]; then disk=$serial; fi
    fi
done
test "$disk" -gt 0
now=$(date -u +%s)
if [ "$loaded" -lt "$disk" ]; then
    # An update during initial engine loading can be missed by both NotifyClamd and SelfCheck.
    # Reload only already-downloaded databases; never weaken freshness or request new downloads here.
    previous=0
    requested=0
    mkdir -p /run/cabinet-health
    if [ -f /run/cabinet-health/reload ]; then read -r previous requested < /run/cabinet-health/reload || true; fi
    case "$previous:$requested" in *[!0-9:]*) previous=0; requested=0;; esac
    if [ "$previous" != "$disk" ] || [ "$((now - requested))" -ge 60 ]; then
        reply=$(printf 'zRELOAD\000' | nc -w 2 127.0.0.1 3310 | tr -d '\000')
        test "$reply" = RELOADING
        printf '%s %s\n' "$disk" "$now" > /run/cabinet-health/reload
    fi
    exit 1
fi
published=$(date -u -D '%a %b %d %H:%M:%S %Y' -d "$stamp" +%s)
# Use the same whole-second ISO-8601 duration as credit, bounded to its 1h..7d policy.
maximum=$(printf '%s\n' "${ANTIVIRUS_MAX_SIGNATURE_AGE:-PT72H}" | awk '
  /^P([0-9]+D)?(T([0-9]+H)?([0-9]+M)?([0-9]+S)?)?$/ {
    value=$0; gsub(/[PT]/,"",value); total=0
    while (match(value,/^[0-9]+[DHMS]/)) {
      part=substr(value,1,RLENGTH); unit=substr(part,length(part)); count=part+0
      total+=count*(unit=="D"?86400:unit=="H"?3600:unit=="M"?60:1)
      value=substr(value,RLENGTH+1)
    }
    if (value=="" && total>=3600 && total<=604800) {print total; valid=1}
  }
  END {if (!valid) exit 1}')
age=$((now - published))
test "$age" -ge -300 && test "$age" -le "$maximum"
