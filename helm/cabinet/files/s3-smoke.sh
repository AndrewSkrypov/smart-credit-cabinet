set -eu
umask 077
bucket=${S3_SMOKE_BUCKET:-cabinet-documents}
region=${S3_SMOKE_REGION:-us-east-1}
case "$bucket" in ''|*[!a-z0-9.-]*) echo 'Invalid bucket' >&2; exit 2;; esac
case "$region" in ''|*[!a-z0-9-]*) echo 'Invalid region' >&2; exit 2;; esac
for value in "${S3_ACCESS_KEY:?}" "${S3_SECRET_KEY:?}"; do
  case "$value" in ''|*[!a-zA-Z0-9_./+=-]*) echo 'Invalid credential encoding' >&2; exit 2;; esac
done
endpoint="${S3_SMOKE_ENDPOINT:?}/$bucket"
object="documents/.cabinet-smoke-${S3_SMOKE_ID:-$(openssl rand -hex 16)}"
work=$(mktemp -d)
written=false
request() {
  printf 'user = "%s:%s"\n' "$S3_ACCESS_KEY" "$S3_SECRET_KEY" |
    curl --disable --config - --noproxy '*' --proto '=http' --silent --show-error \
      --connect-timeout 5 --max-time 15 --aws-sigv4 "aws:amz:$region:s3" \
      --output "$work/response" --write-out '%{http_code}' "$@"
}
expect() {
  expected=$1
  step=$2
  shift 2
  status=$(request "$@")
  if [ "$status" != "$expected" ]; then
    printf 'S3 %s returned HTTP %s, expected %s\n' "$step" "$status" "$expected" >&2
    exit 1
  fi
}
cleanup() {
  if [ "$written" = true ]; then
    request --request DELETE "$endpoint/$object" >/dev/null || true
  fi
  rm -r "$work"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

printf 'Cabinet S3 smoke fixture\n' > "$work/payload"
expect 200 put --upload-file "$work/payload" "$endpoint/$object"
written=true
expect 200 get "$endpoint/$object"
cmp -s "$work/payload" "$work/response"
expect 403 write-outside-documents --upload-file "$work/payload" "$endpoint/${object#documents/}"
status=$(curl --disable --noproxy '*' --proto '=http' --silent --show-error --connect-timeout 5 \
  --max-time 15 --output /dev/null --write-out '%{http_code}' "$endpoint/$object")
if [ "$status" != 403 ]; then
  printf 'Anonymous S3 GET returned HTTP %s, expected 403\n' "$status" >&2
  exit 1
fi
expect 204 cleanup --request DELETE "$endpoint/$object"
written=false
printf 'S3 put/get byte comparison, access denials and cleanup passed.\n'
