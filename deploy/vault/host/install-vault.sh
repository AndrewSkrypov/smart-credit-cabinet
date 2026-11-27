#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() { printf 'Vault install: %s\n' "$*" >&2; exit 1; }

if [[ $# != 5 && $# != 6 ]]; then
    printf 'Usage: %s PRIVATE_IPV4 PRIVATE_DNS FULLCHAIN_PEM KEY_PEM CA_PEM [RELEASE_FILES_DIRECTORY]\n' "$0" >&2
    exit 2
fi

[[ $EUID == 0 && -d /run/systemd/system ]] || fail 'Run as root on the dedicated Linux systemd host.'
package_dir=$(cd -- "$(dirname -- "$0")" && pwd)
private_ip=$1
vault_dns=$2
fullchain=$3
key=$4
ca=$5
release_files=${6-}
unit=pump-vault.service
config_dir=/etc/pump-vault
data_dir=/var/lib/pump-vault
binary_dir=/usr/local/lib/pump-vault

for command in awk cmp curl flock getent gpg groupadd ip mountpoint openssl sha256sum swapon systemctl unzip useradd; do
    command -v "$command" >/dev/null || fail "Required command unavailable: $command."
done

case $(uname -m) in
    x86_64) arch=amd64 ;;
    aarch64) arch=arm64 ;;
    *) fail 'Supported Linux architectures are x86_64 and aarch64.' ;;
esac

exec 9>/run/pump-vault-install.lock
flock -n 9 || fail 'Another Vault install or update is active.'

[[ $private_ip =~ ^(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3})$ ]] || fail 'An RFC1918 IPv4 address is required.'
ip -4 -o address show scope global | awk -v wanted="$private_ip" '
    { split($4, cidr, "/"); if (cidr[1] == wanted) assigned=1 }
    END { exit !assigned }
' || fail 'The private address must be assigned to this host.'
label='[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?'
[[ ${#vault_dns} -le 253 && $vault_dns =~ ^$label(\.$label)+$ ]] || fail 'A private DNS hostname without a trailing dot is required.'

[[ ! -L $data_dir ]] && mountpoint -q -- "$data_dir" || fail 'Mount the dedicated Raft volume at /var/lib/pump-vault first.'
[[ $(stat -c %d -- "$data_dir") != "$(stat -c %d -- /var/lib)" ]] || fail 'Raft storage must use a filesystem separate from /var/lib.'
active_swap=$(swapon --noheadings --show=NAME) || fail 'Could not inspect host swap.'
[[ -z $active_swap ]] || fail 'Disable swap on this dedicated Raft host before installing.'

for pem in "$fullchain" "$key" "$ca"; do
    [[ -s $pem && -f $pem && ! -L $pem ]] || fail 'All TLS inputs must be nonempty regular files.'
done
case $(stat -c '%u:%a' -- "$key") in
    0:400|0:600) ;;
    *) fail 'Stage the server private key as root with mode 0400 or 0600.' ;;
esac

work_dir=$(mktemp -d)
staged_file=
trap 'if [[ -n $staged_file ]]; then rm -f -- "$staged_file"; fi; rm -rf -- "$work_dir"' EXIT

openssl x509 -in "$fullchain" -out "$work_dir/leaf.pem"
openssl x509 -in "$work_dir/leaf.pem" -noout -ext subjectAltName | awk -v host="DNS:$vault_dns" '
    { for (i=1; i<=NF; i++) { sub(/,$/, "", $i); if (tolower($i) == tolower(host)) found=1 } }
    END { exit !found }
' || fail 'The certificate requires an exact DNS SAN for this Vault endpoint.'
openssl verify -CAfile "$ca" -no-CApath -no-CAstore -untrusted "$fullchain" \
    -purpose sslserver -verify_hostname "$vault_dns" "$work_dir/leaf.pem" >/dev/null
openssl x509 -in "$work_dir/leaf.pem" -noout -pubkey >"$work_dir/leaf-public.pem"
openssl pkey -in "$key" -passin pass: -pubout >"$work_dir/key-public.pem"
cmp -s "$work_dir/leaf-public.pem" "$work_dir/key-public.pem" || fail 'The server certificate and private key do not match.'

release=https://releases.hashicorp.com/vault/2.2.2
manifest=vault_2.2.2_SHA256SUMS
archive="vault_2.2.2_linux_${arch}.zip"
curl_args=(--fail --silent --show-error --location --proto '=https' --tlsv1.2 --connect-timeout 10)
if [[ -n $release_files ]]; then
    for name in hashicorp-release-key.asc "$manifest" "$manifest.72D7468F.sig" "$archive"; do
        [[ -f $release_files/$name && ! -L $release_files/$name ]] || fail "Missing regular release input: $name."
        install -m 600 -- "$release_files/$name" "$work_dir/$name"
    done
else
    curl --disable "${curl_args[@]}" --max-time 30 -o "$work_dir/hashicorp-release-key.asc" 'https://www.hashicorp.com/.well-known/pgp-key.txt'
    curl --disable "${curl_args[@]}" --max-time 30 -o "$work_dir/$manifest" "$release/$manifest"
    curl --disable "${curl_args[@]}" --max-time 30 -o "$work_dir/$manifest.72D7468F.sig" "$release/$manifest.72D7468F.sig"
fi
"$package_dir/verify-release.sh" "$work_dir" "$arch" >"$work_dir/archive.sha256"
if [[ -z $release_files ]]; then
    curl --disable "${curl_args[@]}" --max-time 480 --retry 2 -o "$work_dir/$archive" "$release/$archive"
fi
(
    cd -- "$work_dir"
    sha256sum --check --strict archive.sha256
    unzip -p "$archive" vault >vault
)
[[ -s $work_dir/vault ]] || fail 'The verified archive did not provide a Vault executable.'

sed -e "s/@PRIVATE_IPV4@/$private_ip/" -e "s/@PRIVATE_DNS@/$vault_dns/" \
    "$package_dir/vault.hcl.in" >"$work_dir/vault.hcl"

stage() {
    local source=$1 destination=$2 mode=$3 group=$4
    [[ ! -L $destination && ( ! -e $destination || -f $destination ) ]] || fail "Unexpected path: $destination."
    if [[ -f $destination ]] && cmp -s -- "$source" "$destination" &&
       [[ $(stat -c '%U:%G:%a' -- "$destination") == "root:$group:$mode" ]]; then
        return
    fi
    if [[ $active_state != inactive && $active_state != failed ]]; then
        fail 'Vault is active or transitioning. Use a controlled maintenance window to change files.'
    fi
    staged_file=$(mktemp "${destination}.XXXXXX")
    install -o root -g "$group" -m "$mode" -- "$source" "$staged_file"
    mv -fT -- "$staged_file" "$destination"
    staged_file=
}

active_state=$(systemctl show --property=ActiveState --value "$unit")
getent group pump-vault >/dev/null || groupadd --system pump-vault
getent passwd pump-vault >/dev/null || useradd --system --gid pump-vault \
    --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin pump-vault
getent passwd pump-vault | awk -F: -v group_id="$(getent group pump-vault | cut -d: -f3)" '
    $3 != 0 && $4 == group_id && $6 == "/nonexistent" && $7 == "/usr/sbin/nologin" { valid=1 }
    END { exit !valid }
' || fail 'The existing pump-vault identity has unexpected attributes.'
for directory in "$config_dir" "$config_dir/tls" "$binary_dir" "$data_dir/raft"; do
    [[ ! -L $directory && ( ! -e $directory || -d $directory ) ]] || fail "Unexpected path: $directory."
done
install -d -o root -g root -m 755 -- "$config_dir" "$binary_dir" "$data_dir"
install -d -o root -g root -m 700 -- "$config_dir/tls"
install -d -o pump-vault -g pump-vault -m 700 -- "$data_dir/raft"

stage "$work_dir/vault" "$binary_dir/vault" 755 root
stage "$work_dir/vault.hcl" "$config_dir/vault.hcl" 640 pump-vault
stage "$fullchain" "$config_dir/tls/fullchain.pem" 644 root
stage "$key" "$config_dir/tls/key.pem" 600 root
stage "$ca" "$config_dir/ca.pem" 644 root
stage "$package_dir/$unit" "/etc/systemd/system/$unit" 644 root

if command -v systemd-analyze >/dev/null; then
    systemd-analyze verify --man=no "/etc/systemd/system/$unit"
fi
systemctl daemon-reload
systemctl enable "$unit"
printf 'Vault package ready for https://%s:8200. Start the service explicitly.\n' "$vault_dns"
