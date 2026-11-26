#!/usr/bin/env bash
set -euo pipefail
umask 077

# Primary fingerprint for HashiCorp's archive release signing key.
signing_fingerprint=C874011F0AB405110D02105534365D9472D7468F
version=2.2.2

if [[ $# != 2 ]]; then
    printf 'Usage: %s RELEASE_METADATA_DIRECTORY amd64|arm64\n' "$0" >&2
    exit 2
fi

metadata=$1
arch=$2
case $arch in
    amd64) pinned_digest=5700edf139113609962fe371af7d2a53de8ecb720d545dc39b43de204515d3c4 ;;
    arm64) pinned_digest=78634c8672601ad1ca9b27f24e76c54224d12521c59ece957ab8d23976ddca12 ;;
    *) printf 'Unsupported Vault archive architecture\n' >&2; exit 2 ;;
esac

keyring=$(mktemp -d)
trap 'rm -rf -- "$keyring"' EXIT
manifest="$metadata/vault_${version}_SHA256SUMS"
if ! gpg --no-options --homedir "$keyring" --batch --quiet --import "$metadata/hashicorp-release-key.asc" ||
   ! status=$(gpg --no-options --homedir "$keyring" --batch --no-auto-key-retrieve --status-fd 1 \
       --verify "$manifest.72D7468F.sig" "$manifest" 2>"$keyring/verify.log") ||
   ! awk -v pin="$signing_fingerprint" '
       $1 == "[GNUPG:]" && $2 == "VALIDSIG" && $12 == pin { valid++ }
       $1 == "[GNUPG:]" && $2 ~ /^(BADSIG|ERRSIG|EXPSIG|EXPKEYSIG|REVKEYSIG|SIGEXPIRED)$/ { bad++ }
       END { exit !(valid == 1 && !bad) }
   ' <<<"$status"; then
    printf 'Vault checksum manifest did not verify with the pinned HashiCorp key\n' >&2
    exit 1
fi

archive="vault_${version}_linux_${arch}.zip"
if ! awk -v archive="$archive" -v digest="$pinned_digest" '
    $2 == archive { matches++; if ($1 == digest && NF == 2) valid++ }
    END { exit !(matches == 1 && valid == 1) }
' "$manifest"; then
    printf 'Signed Vault checksum does not match the exact pinned archive\n' >&2
    exit 1
fi

printf '%s  %s\n' "$pinned_digest" "$archive"
