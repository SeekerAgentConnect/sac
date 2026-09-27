# Seeker Agent Connect MCP server. Build from the repository root:
#   docker build -f servers/mcp-server/Dockerfile -t seeker-agent-connect/mcp-server:local .
# The checkout context supplies the unpublished Direct Server SDK at build time. The runtime image
# contains the same vendored SDK output as the npm artifact and starts no feed component.

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
COPY tools/test-agent/package.json tools/test-agent/
RUN pnpm install --frozen-lockfile

FROM deps AS build
WORKDIR /app
COPY packages/server-sdk/ packages/server-sdk/
COPY servers/mcp-server/ servers/mcp-server/
COPY scripts/package-mcp-artifact.mjs scripts/
RUN pnpm --filter @seeker_agent_connect/mcp-server run build

FROM deps AS prod-deps
WORKDIR /app
RUN pnpm install --frozen-lockfile --prod

FROM node:${NODE_VERSION}-bookworm-slim AS runtime
ARG VERSION
ARG REVISION
LABEL org.opencontainers.image.title="Seeker Agent Connect MCP server" \
      org.opencontainers.image.description="One owner's self-hosted direct server, speaking MCP to agents and the protocol to their phone." \
      org.opencontainers.image.source="https://github.com/BrRenat/SeekerAgentConnect" \
      org.opencontainers.image.url="https://github.com/BrRenat/SeekerAgentConnect" \
      org.opencontainers.image.documentation="https://github.com/BrRenat/SeekerAgentConnect/blob/master/servers/mcp-server/README.md" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="Seeker Agent Connect" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
ENV NODE_ENV=production \
    MCP_SERVER_DATA_DIR=/data \
    DATABASE_PATH=/data/sidecar.db \
    SIDECAR_HOST=0.0.0.0 \
    SIDECAR_PORT=8080

RUN groupadd --system --gid 10001 mcpserver \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home mcpserver \
 && mkdir -p /data \
 && chown mcpserver:mcpserver /data

WORKDIR /app
COPY --from=prod-deps --chown=root:root /app/node_modules node_modules
COPY --from=prod-deps --chown=root:root /app/servers/mcp-server/node_modules servers/mcp-server/node_modules
COPY --from=build --chown=root:root /app/servers/mcp-server/dist servers/mcp-server/dist
COPY --chown=root:root servers/mcp-server/package.json servers/mcp-server/

VOLUME ["/data"]
USER mcpserver:mcpserver
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=5 \
  CMD ["node", "servers/mcp-server/dist/healthcheck.js"]

# The same executable application path used by source and the npm package. Node is PID 1 and the
# CLI handles SIGINT/SIGTERM, drains the listener, closes SQLite and releases the instance lock.
CMD ["node", "servers/mcp-server/dist/cli.js", "start"]
