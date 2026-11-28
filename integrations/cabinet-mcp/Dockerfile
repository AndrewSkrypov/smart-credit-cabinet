ARG NODE_IMAGE=node:24.13.0-bookworm-slim@sha256:4660b1ca8b28d6d1906fd644abe34b2ed81d15434d26d845ef0aced307cf4b6f
FROM ${NODE_IMAGE} AS build
WORKDIR /app
COPY package.json package-lock.json tsconfig.json ./
RUN npm ci --ignore-scripts --no-audit
COPY src/ src/
RUN npm run build && npm prune --omit=dev --ignore-scripts --no-audit

FROM ${NODE_IMAGE}
ARG NODE_IMAGE
LABEL org.opencontainers.image.base.name=$NODE_IMAGE
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build --chown=node:node /app/package.json ./
COPY --from=build --chown=node:node /app/dist/ dist/
COPY --from=build --chown=node:node /app/node_modules/ node_modules/
USER node
EXPOSE 8080
CMD ["node", "dist/http-main.js"]
