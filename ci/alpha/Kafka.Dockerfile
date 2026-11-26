ARG UPSTREAM_KAFKA_IMAGE=apache/kafka:4.3.2@sha256:8f325fc00143fed78eabe00b033a86747078bb06edc21345d06651f77ae44f30
FROM ${UPSTREAM_KAFKA_IMAGE}

ARG UPSTREAM_KAFKA_IMAGE
LABEL org.opencontainers.image.base.name="${UPSTREAM_KAFKA_IMAGE}"

USER root
RUN apk add --no-cache brotli-libs=1.2.1-r0 \
    && test "$(apk info -v brotli-libs)" = brotli-libs-1.2.1-r0

# Apache's CVE-2026-114938 workaround for the bundled Temurin JVM.
# https://kafka.apache.org/blog/2026/11/19/mitigating-cve-2026-114938-java4shell-in-apache-kafka-deployments/
ENV KAFKA_OPTS=-XX:-UseBASE64Intrinsics
USER appuser
