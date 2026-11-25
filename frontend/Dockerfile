# Use the Docker Engine's bundled BuildKit frontend; no registry fetch is needed to parse this file.
# DIST_SOURCE=node     (default, local docker compose) builds the SPA inside the image build.
# DIST_SOURCE=prebuilt (CI) takes frontend/dist that the CI build job already produced.
ARG DIST_SOURCE=node

FROM node:20-alpine AS dist-node
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

FROM scratch AS dist-prebuilt
COPY frontend/dist /app/dist

FROM dist-${DIST_SOURCE} AS dist

FROM nginx/nginx-unprivileged:1.30.7-alpine@sha256:0c53a1f2890c3526bfd201440f0fd3970e5b78f80630f58284aad0d34b5ec7bd
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.revision=$SOURCE_REVISION
COPY frontend/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY frontend/nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=dist /app/dist /usr/share/nginx/html
EXPOSE 8080
