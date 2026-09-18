# Render image: the sidecar and a Caddy gateway in one container, for a Render web
# service that terminates HTTPS in front of it (deploy/render/README.md). Build from
# the repository root:
#   docker build -f deploy/render/Dockerfile .
# The context is the root because the pnpm workspace's lockfile, catalog, and the
# sibling package manifests all take part in resolving this package's dependencies.
# The stages up to `prod-deps` are the same as sidecar/Dockerfile's.
#
# The sidecar holds no key and signs nothing (AGENTS.md), so the image ships no
# credential. Everything it needs arrives as environment variables at run time.

# The toolchain is pinned to the version .nvmrc and package.json name, so a build
# from a clean checkout resolves the same tree every time.
ARG NODE_VERSION=24.21.0
ARG PNPM_VERSION=12.3.4

# --- dependencies -----------------------------------------------------------
# Installed from the lockfile alone, before any source is copied, so editing a
# source file does not invalidate this layer.
#
# This stage, and the two that build on it, run on the machine's own platform
# ($BUILDPLATFORM) even when the image is built for another one: installing and
# compiling need no emulation, and pnpm's binary aborts under QEMU on an Apple
# silicon Mac. What they produce is platform-independent: the sidecar's JavaScript,
# and a dependency tree whose two native addons (bufferutil, utf-8-validate) ship
# prebuilt binaries for every platform, linux-x64 included. Only the runtime stage
# below is built for the target platform.
FROM --platform=$BUILDPLATFORM node:${NODE_VERSION}-bookworm-slim AS deps
ARG PNPM_VERSION
ENV CI=true
RUN npm install --global --no-fund --no-audit pnpm@${PNPM_VERSION}
WORKDIR /app
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY sidecar/package.json sidecar/
COPY test-agent/package.json test-agent/
RUN pnpm install --frozen-lockfile

# --- build ------------------------------------------------------------------
FROM deps AS build
WORKDIR /app
COPY sidecar/ sidecar/
# tsc emits sidecar/dist; src/testing and the tests are excluded by tsconfig.build.json.
RUN pnpm --filter @seeker-vault/sidecar run build

# --- production dependencies ------------------------------------------------
# A second install from the same lockfile, without devDependencies, so the
# runtime image carries no compiler, linter, or test tooling.
FROM deps AS prod-deps
WORKDIR /app
RUN pnpm install --frozen-lockfile --prod


# Render terminates HTTPS. Caddy routes HTTP to the loopback-only sidecar.
FROM caddy:2.10.2-alpine AS caddy

FROM node:${NODE_VERSION}-bookworm-slim AS runtime
ENV NODE_ENV=production \
    PORT=10000 \
    SIDECAR_HOST=127.0.0.1 \
    SIDECAR_PORT=8080 \
    LIVE_COMMAND_TIMEOUT_SECONDS=60 \
    MCP_DEMO_TOOLS=false \
    DATABASE_PATH=/data/sidecar.db \
    XDG_CONFIG_HOME=/tmp/caddy-config \
    XDG_DATA_HOME=/tmp/caddy-data

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates tini gosu libcap2-bin \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 sidecar \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home sidecar \
 && mkdir -p /data \
 && chown sidecar:sidecar /data

COPY --from=caddy /usr/bin/caddy /usr/bin/caddy
# Caddy listens on an unprivileged port. Do not require file capabilities.
RUN if [ -n "$(getcap /usr/bin/caddy)" ]; then setcap -r /usr/bin/caddy; fi \
 && caddy version

WORKDIR /app
COPY --from=prod-deps --chown=root:root /app/node_modules node_modules
COPY --from=prod-deps --chown=root:root /app/sidecar/node_modules sidecar/node_modules
COPY --from=build --chown=root:root /app/sidecar/dist sidecar/dist
COPY --chown=root:root sidecar/package.json sidecar/
COPY --chown=root:root package.json pnpm-workspace.yaml ./
COPY --chown=root:root deploy/render/Caddyfile /etc/caddy/Caddyfile
COPY --chown=root:root deploy/render/supervisor.mjs /app/render/supervisor.mjs
COPY --chown=root:root deploy/render/entrypoint.sh /usr/local/bin/render-entrypoint
COPY --chown=root:root deploy/render/pair.sh /usr/local/bin/render-pair
RUN chmod 755 /usr/local/bin/render-entrypoint /usr/local/bin/render-pair

LABEL org.opencontainers.image.source="https://github.com/BrRenat/SeekerAgentWallet"
EXPOSE 10000
STOPSIGNAL SIGTERM
# Root is used only to initialize ownership of the mounted disk.
# The entrypoint immediately drops to uid/gid 10001 for both services.
ENTRYPOINT ["/usr/local/bin/render-entrypoint"]
CMD ["/usr/bin/tini", "--", "node", "/app/render/supervisor.mjs"]

