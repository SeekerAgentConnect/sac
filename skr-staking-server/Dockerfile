# Seeker Agent Connect SKR staking MCP server. Build from the repository root:
#   docker build -f skr-staking-server/Dockerfile -t seeker-agent-connect/skr-staking-server:local .
# The checkout context supplies the unpublished Direct Server SDK at build time. The runtime image
# runs this server alone: it is not the general MCP server, it serves no feed, and it holds no key.

ARG NODE_VERSION=24.21.0
ARG PNPM_VERSION=12.3.4

FROM --platform=$BUILDPLATFORM node:${NODE_VERSION}-bookworm-slim AS deps
ARG PNPM_VERSION
ENV CI=true
RUN npm install --global --no-fund --no-audit pnpm@${PNPM_VERSION}
WORKDIR /app
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY server-sdk/package.json server-sdk/
COPY mcp-server/package.json mcp-server/
COPY skr-staking-server/package.json skr-staking-server/
COPY test-agent/package.json test-agent/
RUN pnpm install --frozen-lockfile

FROM deps AS build
WORKDIR /app
COPY server-sdk/ server-sdk/
COPY skr-staking-server/ skr-staking-server/
RUN pnpm --filter @seeker-vault/skr-staking-server run build

FROM deps AS prod-deps
WORKDIR /app
RUN pnpm install --frozen-lockfile --prod

FROM node:${NODE_VERSION}-bookworm-slim AS runtime
ENV NODE_ENV=production \
    SKR_STAKING_DATA_DIR=/data \
    SKR_STAKING_DATABASE_PATH=/data/skr-staking-server.db \
    SKR_STAKING_HOST=0.0.0.0 \
    SKR_STAKING_PORT=8090

RUN groupadd --system --gid 10001 skrstaking \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home skrstaking \
 && mkdir -p /data \
 && chown skrstaking:skrstaking /data

WORKDIR /app
COPY --from=prod-deps --chown=root:root /app/node_modules node_modules
COPY --from=prod-deps --chown=root:root /app/skr-staking-server/node_modules skr-staking-server/node_modules
# The SDK is a workspace package rather than a published one, so its built output travels with the
# image and the workspace link above resolves to it.
COPY --from=build --chown=root:root /app/server-sdk/dist server-sdk/dist
COPY --chown=root:root server-sdk/package.json server-sdk/
COPY --from=build --chown=root:root /app/skr-staking-server/dist skr-staking-server/dist
COPY --chown=root:root skr-staking-server/package.json skr-staking-server/

VOLUME ["/data"]
USER skrstaking:skrstaking
EXPOSE 8090

HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=5 \
  CMD ["node", "skr-staking-server/dist/healthcheck.js"]

# Node is PID 1 and the CLI handles SIGINT/SIGTERM: it drains the listener, closes the database and
# releases the instance lock. Nothing here signs or broadcasts — the owner's wallet does both.
CMD ["node", "skr-staking-server/dist/cli.js", "start"]
