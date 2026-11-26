#!/bin/sh
set -eu
umask 077

# Restrict interpolated values to JSON-safe characters; never print credentials.
for value in "${S3_ACCESS_KEY:?}" "${S3_SECRET_KEY:?}" "${S3_ADMIN_ACCESS_KEY:?}" "${S3_ADMIN_SECRET_KEY:?}"; do
  case "$value" in *[!a-zA-Z0-9_./+=-]*|'') echo 'Invalid S3 credential encoding' >&2; exit 1;; esac
done
case "${S3_BUCKET:?}" in *[!a-z0-9.-]*|'') echo 'Invalid S3 bucket' >&2; exit 1;; esac
if [ "$S3_ACCESS_KEY" = "$S3_ADMIN_ACCESS_KEY" ]; then
  echo 'Runtime and operator S3 identities must differ' >&2
  exit 1
fi
printf '{"identities":[{"name":"credit","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Read:%s/documents/*","Write:%s/documents/*","List:%s"]},{"name":"operator","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Admin","Read","Write","List"]}]}\n' \
  "$S3_ACCESS_KEY" "$S3_SECRET_KEY" "$S3_BUCKET" "$S3_BUCKET" "$S3_BUCKET" \
  "$S3_ADMIN_ACCESS_KEY" "$S3_ADMIN_SECRET_KEY" > /tmp/cabinet-s3.json

# All metadata and object volumes live under /data. Snapshot only while stopped.
# No web administration or anonymous WebDAV endpoint is needed by the cabinet.
exec weed mini -dir=/data -ip=127.0.0.1 -ip.bind=0.0.0.0 \
  -bucket="$S3_BUCKET" -s3.config=/tmp/cabinet-s3.json \
  -admin.ui=false -webdav=false -s3.iam=false -s3.port.iceberg=0 -s3.port.lance=0 \
  -s3.autoCreateBucket=false -s3.allowDeleteBucketNotEmpty=false \
  -s3.concurrentUploadLimitMB=64 -s3.readerCacheSizeMB=64
