# syntax=docker/dockerfile:1.7
FROM postgres:16.15-alpine@sha256:deb28ee471183cb0c303113dac9b2433c51eeeeb678def7af9a9ec5820367815 AS patched

# The bundled gosu carries an outdated static Go runtime; the entrypoint only needs UID/GID switching.
RUN apk add --no-cache su-exec=0.3-r0 \
    && rm /usr/local/bin/gosu \
    && cp /sbin/su-exec /usr/local/bin/gosu

# Copy the resulting filesystem so the obsolete executable is absent from shipped lower layers too.
FROM scratch
COPY --from=patched / /
ENV PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    LANG=en_US.utf8 PG_MAJOR=16 PG_VERSION=16.15 PGDATA=/var/lib/postgresql/data
VOLUME /var/lib/postgresql/data
EXPOSE 5432
STOPSIGNAL SIGINT
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["postgres"]
