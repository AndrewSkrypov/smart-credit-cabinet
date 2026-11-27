FROM alpine:3.23@sha256:469c9ea410b9215eaee7976ba6c0639039b444be8e5d638f7abbada189971eb2
RUN apk add --no-cache openssh-client-default \
 && addgroup -S -g 10002 bridge \
 && adduser -S -D -H -u 10002 -G bridge bridge
USER 10002:10002
ENTRYPOINT ["ssh"]
