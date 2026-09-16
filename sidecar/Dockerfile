# Sidecar image. Build from the repository root:
#   docker build -f sidecar/Dockerfile .
# The context is the root because the pnpm workspace's lockfile, catalog, and the
# sibling package manifests all take part in resolving this package's dependencies.
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
FROM node:${NODE_VERSION}-bookworm-slim AS deps
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

# --- runtime ----------------------------------------------------------------
FROM node:${NODE_VERSION}-bookworm-slim AS runtime
ENV NODE_ENV=production
# Durable requests live on a volume, never in the image's writable layer.
ENV DATABASE_PATH=/data/sidecar.db

# A fixed, unprivileged uid/gid, so the volume's ownership is the same on every
# host. The stock `node` user is uid 1000, which collides with the first human
# account on a typical Linux VPS; 10001 is outside that range.
RUN groupadd --system --gid 10001 sidecar \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home sidecar \
 && mkdir -p /data \
 && chown sidecar:sidecar /data

WORKDIR /app
COPY --from=prod-deps --chown=root:root /app/node_modules node_modules
COPY --from=prod-deps --chown=root:root /app/sidecar/node_modules sidecar/node_modules
COPY --from=build --chown=root:root /app/sidecar/dist sidecar/dist
COPY --chown=root:root sidecar/package.json sidecar/
COPY --chown=root:root package.json pnpm-workspace.yaml ./

# The application tree is root-owned and read-only to the account that runs it;
# only /data is writable.
VOLUME ["/data"]
USER sidecar:sidecar

EXPOSE 8080

# `pnpm dev:sidecar` runs the TypeScript entry point; the image runs the compiled
# one. Exec form on purpose: node is PID 1 and receives SIGTERM itself, which is
# what main.ts's handler needs to close the listener and the database cleanly.
CMD ["node", "sidecar/dist/main.js"]
