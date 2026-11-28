#!/bin/sh
set -eu

# Exercise the packaged shell/date implementation without changing the actual scanner.
temporary=$(mktemp -d /tmp/cabinet-health-XXXXXXXX)
trap 'rm -f "$temporary/nc" "$temporary/date" "$temporary/reloads"; rmdir "$temporary"' EXIT
export TEST_SCANNER_RELOAD_LOG="$temporary/reloads"
cat > "$temporary/nc" <<'EOF'
#!/bin/sh
command=$(cat | tr -d '\000')
if [ "$command" = zRELOAD ]; then
    printf 'reload\n' >> "$TEST_SCANNER_RELOAD_LOG"
    printf 'RELOADING\000'
else
    printf '%s\000' "$TEST_SCANNER_VERSION"
fi
EOF
chmod 700 "$temporary/nc"
export TEST_HEALTH_DATE=$(command -v date)
export TEST_HEALTH_NOW=$("$TEST_HEALTH_DATE" -u +%s)
cat > "$temporary/date" <<'EOF'
#!/bin/sh
if [ "$#" -eq 2 ] && [ "$1" = -u ] && [ "$2" = +%s ]; then
    printf '%s\n' "$TEST_HEALTH_NOW"
else
    exec "$TEST_HEALTH_DATE" "$@"
fi
EOF
chmod 700 "$temporary/date"
export PATH="$temporary:$PATH"
now=$(date -u +%s)
serial=0
for database in /var/lib/clamav/daily.cvd /var/lib/clamav/daily.cld; do
    if [ -f "$database" ]; then
        candidate=$(dd if="$database" bs=512 count=1 2>/dev/null | cut -d: -f3)
        if [ "$candidate" -gt "$serial" ]; then serial=$candidate; fi
    fi
done
test "$serial" -gt 0
passed=0

verify() {
    expected=$1
    age=$2
    if [ "$#" -eq 3 ]; then
        export ANTIVIRUS_MAX_SIGNATURE_AGE=$3
    else
        unset ANTIVIRUS_MAX_SIGNATURE_AGE
    fi
    stamp=$(date -u -d "@$((now - age))" '+%a %b %d %H:%M:%S %Y')
    export TEST_SCANNER_VERSION="ClamAV 1.5.4/$serial/$stamp"
    actual=1
    if sh /cabinet/healthcheck.sh; then actual=0; fi
    test "$actual" -eq "$expected"
    passed=$((passed + 1))
}

verify 0 3600 PT72H
verify 1 345600 PT72H
verify 1 345600
verify 0 259200
verify 1 259201
verify 1 604801
verify 0 345600 P7D
verify 0 604800 P7D
verify 1 604801 P7D
verify 0 345600 PT168H
verify 1 -600 PT72H
verify 0 -300 PT72H
verify 1 -301 PT72H
verify 0 -120 PT72H
verify 1 7200 PT1H
verify 0 90000 P1DT2H
verify 0 432000 P7D
verify 1 3600 P8D
verify 1 3600 PT
verify 1 3600 invalid
export ANTIVIRUS_MAX_SIGNATURE_AGE=PT72H
stamp=$(date -u '+%a %b %d %H:%M:%S %Y')
export TEST_SCANNER_VERSION="ClamAV 1.5.4/$((serial - 1))/$stamp"
if sh /cabinet/healthcheck.sh; then exit 1; fi
if sh /cabinet/healthcheck.sh; then exit 1; fi
test "$(wc -l < "$TEST_SCANNER_RELOAD_LOG")" -eq 1
passed=$((passed + 2))
verify 0 3600 PT72H
export TEST_SCANNER_VERSION='Unknown scanner'
if sh /cabinet/healthcheck.sh; then exit 1; fi
passed=$((passed + 1))
printf 'Antivirus readiness cases passed: %s\n' "$passed"
