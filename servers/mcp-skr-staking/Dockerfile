# Seeker Agent Connect SKR staking MCP server. Build from the repository root:
#   docker build -f servers/mcp-skr-staking/Dockerfile -t seeker-agent-connect/skr-staking-server:local .
# The checkout context supplies the unpublished Direct Server SDK at build time. The runtime image
# runs this server alone: it is not the general MCP server, it serves no feed, and it holds no key.

ARG NODE_VERSION=24.21.0
ARG PNPM_VERSION=12.3.4

# Release identity (SEE-168). The defaults make a local `docker build` produce an image that is
# honestly labelled as one, and .github/workflows/release.yml passes the released version and the
# exact commit it was built from, so an image digest can always be traced back to a revision.
ARG VERSION=0.0.0-dev
ARG REVISION=unknown

FROM --platform=$BUILDPLATFORM node:${NODE_VERSION}-bookworm-slim AS deps
ARG PNPM_VERSION
ENV CI=true
RUN npm install --global --no-fund --no-audit pnpm@${PNPM_VERSION}
WORKDIR /app
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY packages/server-sdk/package.json packages/server-sdk/
COPY servers/mcp-server/package.json servers/mcp-server/
COPY servers/mcp-skr-staking/package.json servers/mcp-skr-staking/
COPY tools/test-agent/package.json tools/test-agent/
RUN pnpm install --frozen-lockfile

FROM deps AS build
WORKDIR /app
COPY packages/server-sdk/ packages/server-sdk/
COPY servers/mcp-skr-staking/ servers/mcp-skr-staking/
COPY scripts/package-mcp-artifact.mjs scripts/
RUN pnpm --filter @seeker_agent_connect/mcp-skr-staking run build

FROM deps AS prod-deps
WORKDIR /app
RUN pnpm install --frozen-lockfile --prod

FROM node:${NODE_VERSION}-bookworm-slim AS runtime
ARG VERSION
ARG REVISION
LABEL org.opencontainers.image.title="Seeker Agent Connect SKR staking MCP server" \
      org.opencontainers.image.description="A standalone MCP server for staking the owner's own SKR. It holds no key and signs nothing." \
      org.opencontainers.image.source="https://github.com/BrRenat/SeekerAgentConnect" \
      org.opencontainers.image.url="https://github.com/BrRenat/SeekerAgentConnect" \
      org.opencontainers.image.documentation="https://github.com/BrRenat/SeekerAgentConnect/blob/master/servers/mcp-skr-staking/README.md" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="Seeker Agent Connect" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
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
COPY --from=prod-deps --chown=root:root /app/servers/mcp-skr-staking/node_modules servers/mcp-skr-staking/node_modules
# The SDK is a workspace package rather than a published one, so its built output travels with the
# image and the workspace link above resolves to it.
COPY --from=prod-deps --chown=root:root /app/packages/server-sdk/node_modules packages/server-sdk/node_modules
COPY --from=build --chown=root:root /app/packages/server-sdk/dist packages/server-sdk/dist
COPY --chown=root:root packages/server-sdk/package.json packages/server-sdk/
COPY --from=build --chown=root:root /app/servers/mcp-skr-staking/dist servers/mcp-skr-staking/dist
COPY --chown=root:root servers/mcp-skr-staking/package.json servers/mcp-skr-staking/

VOLUME ["/data"]
USER skrstaking:skrstaking
EXPOSE 8090

HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=5 \
  CMD ["node", "servers/mcp-skr-staking/dist/healthcheck.js"]

# Node is PID 1 and the CLI handles SIGINT/SIGTERM: it drains the listener, closes the database and
# releases the instance lock. Nothing here signs or broadcasts — the owner's wallet does both.
CMD ["node", "servers/mcp-skr-staking/dist/cli.js", "start"]
