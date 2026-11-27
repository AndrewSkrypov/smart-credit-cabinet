#!/bin/sh
# Run at the Credit peer boundary, using the application method and no user credentials.
set -eu
tls=/run/servicing-tls
endpoint=https://servicing:9095/creditcabinet.servicing.v1.ServicingService/ByApplication
headers=$(mktemp)
trap 'rm -f "$headers"' EXIT HUP INT TERM
rpc() {
    printf '\000\000\000\000\000' |
        curl --silent --show-error --noproxy '*' --max-time 8 --http2 --cacert "$tls/ca.crt" \
            --header 'content-type: application/grpc' --header 'te: trailers' --data-binary @- \
            --dump-header "$headers" --output /dev/null --write-out '%{http_version} %{http_code}' \
            "$@" "$endpoint"
}
if rpc 2>/dev/null; then
    exit 1
fi
test "$(rpc --cert "$tls/tls.crt" --key "$tls/tls.key")" = '2 200'
tr -d '\r' < "$headers" | grep -qix 'grpc-status: 16'
