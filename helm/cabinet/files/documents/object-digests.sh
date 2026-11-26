#!/bin/sh
set -eu

# Keys enter curl via stdin. stdout contains only size-checked digests.
request() {
  method=$1
  limit=$2
  if [ "$method" = head ]; then
    set -- --head
  else
    set -- --max-filesize "$limit"
  fi
  printf 'user = "%s:%s"\n' "$access_key" "$secret_key" |
    curl --disable --config - --noproxy "*" --silent --show-error --fail --connect-timeout 10 --max-time 60 \
      --aws-sigv4 "aws:amz:$region:s3" "$@" "$endpoint/$bucket/documents/$name"
}

umask 077
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
while read -r side backend name size; do
  case "$name" in ''|.*|*[!a-zA-Z0-9._-]*) exit 2;; esac
  case "$size" in ''|*[!0-9]*) exit 2;; esac
  case "$side" in
    credit)
      dir=$DOCUMENTS_CREDIT_LOCAL_DIR
      endpoint=$DOCUMENTS_CREDIT_S3_ENDPOINT
      region=$DOCUMENTS_CREDIT_S3_REGION
      bucket=$DOCUMENTS_CREDIT_S3_BUCKET
      access_key=$DOCUMENTS_CREDIT_S3_ACCESS_KEY
      secret_key=$DOCUMENTS_CREDIT_S3_SECRET_KEY
      ;;
    documents)
      dir=$CABINET_STORAGE_LOCAL_DIR
      endpoint=$CABINET_STORAGE_S3_ENDPOINT
      region=$CABINET_STORAGE_S3_REGION
      bucket=$CABINET_STORAGE_S3_BUCKET
      access_key=$CABINET_STORAGE_S3_ACCESS_KEY
      secret_key=$CABINET_STORAGE_S3_SECRET_KEY
      ;;
    *) exit 2;;
  esac
  case "$backend" in
    LOCAL)
      file=$dir/$name
      test -f "$file" && test ! -L "$file"
      ;;
    S3)
      request head "$size" > "$scratch/headers"
      test "$(awk 'tolower($1)=="content-length:" {gsub(/\r/,"",$2); print $2}' "$scratch/headers")" = "$size"
      file=$scratch/object
      request get "$size" > "$file"
      ;;
    *) exit 2;;
  esac
  test "$(stat -c '%s' "$file")" = "$size"
  sha256sum "$file" | cut -d ' ' -f 1
  rm -f "$scratch/object"
done
