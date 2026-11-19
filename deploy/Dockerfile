# syntax=docker/dockerfile:1
#
# Server + built Dashboard in one image, for a VPS (deploy/docker-compose.yml puts Caddy in front).
# Build from the repository root:  docker build -f deploy/Dockerfile -t hotspot-monitor .
# Debian slim rather than Alpine: @libsql/client ships prebuilt glibc binaries.
# Node 22 (latest 22.x): Vite 8 needs ^20.19 or >=22.12, like render.yaml and .nvmrc.

ARG NODE_IMAGE=node:22-bookworm-slim

# --- 1. build web/dist and server/dist ------------------------------------------------------------
FROM ${NODE_IMAGE} AS build
WORKDIR /app
# Manifests first so the dependency layer is cached until package files change.
COPY package.json package-lock.json tsconfig.base.json ./
COPY shared/package.json shared/
COPY server/package.json server/
COPY web/package.json web/
COPY tools/agent-sim/package.json tools/agent-sim/
RUN npm ci
COPY shared shared
COPY server server
COPY web web
RUN npm run build

# --- 2. production dependencies only -------------------------------------------------------------
FROM ${NODE_IMAGE} AS deps
WORKDIR /app
COPY package.json package-lock.json ./
COPY shared/package.json shared/
COPY server/package.json server/
COPY web/package.json web/
COPY tools/agent-sim/package.json tools/agent-sim/
RUN npm ci --omit=dev && npm cache clean --force

# --- 3. runtime ----------------------------------------------------------------------------------
FROM ${NODE_IMAGE}
ENV NODE_ENV=production \
    PORT=8080 \
    HOST=0.0.0.0 \
    DATABASE_URL=file:/data/hsm.db \
    WEB_DIST=/app/web/dist
WORKDIR /app
COPY --from=deps /app ./
COPY --from=build /app/server/dist ./server/dist
COPY --from=build /app/web/dist ./web/dist
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"]
# SIGTERM triggers a graceful shutdown (sockets closed with 1001, state flushed).
CMD ["node", "server/dist/index.js"]
