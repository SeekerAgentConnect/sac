# Test-agent image. Build from the repository root:
#   docker build -f test-agent/Dockerfile .
# The context is the root for the same reason as the sidecar's: the workspace
# lockfile and catalog resolve this package's dependencies.
#
# This is a CLI, not a service. It runs one command and exits; it holds no key,
# and it is not an LLM. Nothing here starts on its own — see gateway/compose.yaml,
# where it sits behind the `agent` profile.

ARG NODE_VERSION=24.21.0
ARG PNPM_VERSION=12.3.4

# --- dependencies -----------------------------------------------------------
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
COPY test-agent/ test-agent/
# tsconfig.build.json excludes the tests and the stage acceptance suites, so the
# image cannot run a scenario that creates requests.
RUN pnpm --filter @seeker-vault/test-agent run build

# --- production dependencies ------------------------------------------------
FROM deps AS prod-deps
WORKDIR /app
RUN pnpm install --frozen-lockfile --prod

# --- runtime ----------------------------------------------------------------
FROM node:${NODE_VERSION}-bookworm-slim AS runtime
ENV NODE_ENV=production

RUN groupadd --system --gid 10002 agent \
 && useradd --system --uid 10002 --gid 10002 --home-dir /app --no-create-home agent

WORKDIR /app
COPY --from=prod-deps --chown=root:root /app/node_modules node_modules
COPY --from=prod-deps --chown=root:root /app/test-agent/node_modules test-agent/node_modules
COPY --from=build --chown=root:root /app/test-agent/dist test-agent/dist
COPY --chown=root:root test-agent/package.json test-agent/
COPY --chown=root:root package.json pnpm-workspace.yaml ./

USER agent:agent

ENTRYPOINT ["node", "test-agent/dist/main.js"]
# `tools` lists what the sidecar serves. It reads; it creates no request, asks
# for no signature, and moves no funds. Overridden per run, for example
# `docker compose run --rm test-agent address`.
CMD ["tools"]
