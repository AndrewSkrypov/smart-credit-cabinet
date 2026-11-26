#!/bin/sh
set -eu
umask 077

source=/var/lib/clamav
target=/seed
certificates=/etc/clamav/certs

serial() {
  value=$(dd if="$1" bs=512 count=1 2>/dev/null |
    awk -F: '$1 == "ClamAV-VDB" && $3 ~ /^[0-9]+$/ && length($3) <= 9 {printf "%.0f\n", $3 + 0; exit}')
  test -n "$value" || return 1
  printf '%s\n' "$value"
}

install_archive() {
  name=$1
  version=$2
  signature="$name-$version.cvd.sign"
  cp "$source/$name.cvd" "$stage/$name.cvd"
  cp "$source/$signature" "$stage/$signature"
  sigtool --cvdcertsdir "$certificates" --verify "$stage/$name.cvd" >/dev/null
  chmod 0600 "$stage/$name.cvd" "$stage/$signature"
  chown clamav:clamav "$stage/$name.cvd" "$stage/$signature"
  # The engine starts after init finishes. Publish the detached signature
  # first so an interrupted install never publishes an unsigned archive.
  mv -fT "$stage/$signature" "$target/$signature"
  mv -fT "$stage/$name.cvd" "$target/$name.cvd"
  printf 'Seeded verified ClamAV %s database version %s\n' "$name" "$version"
}

stage=$(mktemp -d "$target/.cabinet-seed.XXXXXXXX")
trap 'rm -r "$stage"' EXIT
trap 'exit 1' HUP INT TERM

# Daily is published last. An initialized PVC is updated only with daily;
# a retry of a partial initial seed can finish any missing baseline files.
if [ ! -e "$target/daily.cvd" ] && [ ! -e "$target/daily.cld" ]; then
  for name in main bytecode; do
    if [ ! -e "$target/$name.cvd" ] && [ ! -e "$target/$name.cld" ]; then
      install_archive "$name" "$(serial "$source/$name.cvd")"
    fi
  done
fi

baked=$(serial "$source/daily.cvd")
cvd=0
cld=0
if [ -e "$target/daily.cvd" ]; then
  cvd=$(serial "$target/daily.cvd") || {
    echo 'Leaving unreadable daily CVD for freshclam to recover' >&2
    exit 0
  }
fi
if [ -e "$target/daily.cld" ]; then
  cld=$(serial "$target/daily.cld") || {
    echo 'Leaving unreadable daily CLD for freshclam to recover' >&2
    exit 0
  }
fi

if [ "$baked" -gt "$cvd" ] && [ "$baked" -gt "$cld" ]; then
  install_archive daily "$baked"
  cvd=$baked
fi

# Recover a previous interruption after the signed archive was published
# but before the old CLD was moved out of the engine's database directory.
if [ "$cld" -gt 0 ] && [ "$cld" -lt "$baked" ] && [ "$cvd" -eq "$baked" ] &&
   sigtool --cvdcertsdir "$certificates" --verify "$target/daily.cvd" >/dev/null 2>&1; then
  mv -fT "$target/daily.cld" "$stage/superseded-daily.cld"
fi
