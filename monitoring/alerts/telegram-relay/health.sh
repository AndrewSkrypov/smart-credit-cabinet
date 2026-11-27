#!/usr/bin/env bash
set -euo pipefail

authority=${1:?usage: health.sh IP:PORT PROXY_AUTH_FILE [PROXY_CA_FILE]}
authorization=$(<"${2:?missing proxy authorization file}")
ca=${3:-}
[[ $authority =~ ^[0-9.]+:[0-9]+$ && $authorization =~ ^Basic\ [A-Za-z0-9+/=]+$ ]]
scheme=http
if [[ -n $ca ]]; then
  [[ $ca =~ ^/[a-zA-Z0-9._/-]+$ ]]
  scheme=https
fi

# Pass credentials directly to curl without exposing them in argv or output.
result=$({
  printf 'proxy = "%s://%s"\n' "$scheme" "$authority"
  printf 'proxy-header = "Proxy-Authorization: %s"\n' "$authorization"
  if [[ -n $ca ]]; then printf 'proxy-cacert = "%s"\n' "$ca"; fi
  printf '%s\n' 'noproxy = ""' head silent 'max-time = 25' 'output = "/dev/null"' \
    'write-out = "%{http_connect}/%{http_code}/%{ssl_verify_result}/%{proxy_ssl_verify_result}"' \
    'url = "https://api.telegram.org/"'
} | curl -q --config -)
[[ $result == '200/302/0/0' ]]
printf '%s\n' 'Telegram HEAD 302 through verified CONNECT'
