# SEE-46 / SAW-034 — Containerize the sidecar and test agent with Compose

Stage 7 (SEE-45). Base for PR: `master`. Branch: `superset/feat/see-45`.

## Constraint that drives the design

`loadSidecarConfig` requires `SIDECAR_HOST` to be loopback (`127.0.0.1`, `::1`,
`localhost`) and `sidecar/src/stage-boundary.test.ts` guards the surrounding
invariants. Containerizing must not relax that check.

**Resolution:** the gateway shares the sidecar's network namespace
(`network_mode: "service:sidecar"`). The sidecar keeps its loopback bind, the
gateway reaches it over `127.0.0.1:8080` inside that namespace, and only the
gateway's port is published. The sidecar's own port is unreachable from the host
and from every other container — a stronger guarantee than a private network.

## Implementation

- [x] `sidecar/Dockerfile` — multi-stage, pinned Node, `--frozen-lockfile`,
      non-root, `/data` volume, exec-form CMD so node is PID 1 for SIGTERM.
- [x] `test-agent/Dockerfile` — same shape; default command is read-only.
- [x] `.dockerignore` — keep the build context reproducible.
- [x] `gateway/Caddyfile` — reverse proxy to `127.0.0.1:8080`, `Host` rewritten
      so the sidecar's DNS-rebinding check passes without `MCP_ALLOWED_HOSTS`.
- [x] `gateway/compose.yaml` — builds from the checkout; no unpublished image.
- [x] `gateway/.env.example` — deployment settings, placeholders only.
- [x] Test agent behind the `agent` profile, so `up` starts nothing that can
      create a request, spend funds, or launch an LLM.
- [x] `docs/guides/self-hosting.md`, `gateway/README.md`, changelog, CODEBASE.md.

## Verification

- [x] `pnpm check` (format, lint, typecheck, tests) stays green.
- [x] Runtime checks: `docker compose config`, build, health, restart
      persistence, test-agent run — record what actually ran, per the ticket.

## Review

**Done.** No sidecar or test-agent source changed; this task is packaging only.

Added: `sidecar/Dockerfile`, `test-agent/Dockerfile`, `.dockerignore`,
`gateway/compose.yaml`, `gateway/Caddyfile`, `gateway/.env.example`,
`docs/guides/self-hosting.md`, `docs/testing/stage-7.md`,
`docs/changelog/2026-09-17.md`. Rewritten: `gateway/README.md`. Updated:
`CODEBASE.md`.

**The design decision worth re-reading.** `SIDECAR_HOST` must be loopback, and
that was not relaxed. The gateway joins the sidecar's network namespace, so the
sidecar's port sits on a loopback interface nothing else shares — unreachable
from the host and from every other container, not merely unmapped. The published
port belongs to the gateway and is declared on the sidecar service because the
namespace is the sidecar's; `docs/guides/self-hosting.md` says so plainly, since
it reads oddly otherwise.

The gateway does **not** rewrite `Host`. Rewriting it would have made the stack
work by default everywhere while silently disabling the sidecar's DNS-rebinding
check; naming the hostname in `MCP_ALLOWED_HOSTS` keeps the check real.

**What was verified:** `pnpm check` exits 0 (430 sidecar tests, 29 test-agent
tests, format, lint, types, stage boundaries). `docker compose config` parses
and resolves; a default start is `sidecar` + `gateway`, `test-agent` appears
only under `--profile agent`, and the published port forwards to the gateway's
8081, never the sidecar's 8080. The missing-token guard fires before any
container starts.

**What was not verified, and must be.** No Docker daemon was reachable in this
environment — Docker Desktop is running, but under a different macOS user
account, whose socket and `~/.docker` this session cannot read — so nothing was
built and no container has run. Every runtime check is
recorded NOT RUN in `docs/testing/stage-7.md`, with its command, and the Mac
`arm64` and Linux VPS `amd64`/`arm64` results are kept apart — a `buildx`
success would not stand in for either. The ticket asks for the runtime checks
actually performed to be recorded; that record currently says none.

**Open risk:** `caddy:2.10-alpine` was pinned without being pulled, so the tag
itself is unconfirmed. The first `docker compose pull` will settle it.
