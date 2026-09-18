# Render deployment: check and fix the Docker image for render.com

**Ask:** "check and fix deploy for render, i need docker image to run my gateway on render.com".
Branch `superset/feat/see-84` (working tree; nothing committed by this task).

## Where this starts

`deploy/render/` arrived untracked, written outside this repository against commit `0e5dd09`: a
single-container image (sidecar + Caddy under a small Node supervisor), a Render Caddyfile, an
entrypoint, and a README written for an "archive" rather than this checkout. Its verification
record said the Docker build, the Caddy config, and the smoke test were NOT RUN.

## Findings

- The Dockerfile, entrypoint, Caddyfile, and supervisor are correct against the sidecar as it
  stands: entry point `sidecar/dist/main.js`, config names `SIDECAR_HOST`/`SIDECAR_PORT`/
  `MCP_ALLOWED_HOSTS`/`SIDECAR_PUBLIC_URL`/`DATABASE_PATH`, `/healthz` on the sidecar, the route
  list identical to `gateway/Caddyfile.public` minus the LiveCommand diagnostic.
- Base images exist: `node:24.21.0-bookworm-slim`, `caddy:2.10.2-alpine`.
- `SIDECAR_PUBLIC_URL` had to be typed by hand although Render provides `RENDER_EXTERNAL_URL`.
- A configuration failure printed one generic line and hid the reason.
- The README told the operator to run the pairing CLI from Render Shell as whatever user the
  shell gives (root), which would leave root-owned SQLite WAL files that uid 10001 cannot open.
- No Blueprint and no image pipeline: only a manual `docker push` to a personal Docker Hub.

## Implementation

- [x] Build the image natively and run the smoke test the README describes (all checks passed).
- [x] `supervisor.mjs`: default the public URL from `RENDER_EXTERNAL_URL`; print the real reason
      on a refused configuration (the messages name variables, never values).
- [x] `render.yaml`: a Blueprint that builds from the repository — Docker runtime, `/data` disk,
      `/healthz`, generated `MCP_TOKEN`/`PHONE_TOKEN`, devnet RPC.
- [x] `.github/workflows/build-render.yml`: build and push `linux/amd64` to GHCR on a version tag
      or by hand, pinned actions like `ci.yml`.
- [x] `deploy/render/pair.sh` → `render-pair`: pairing from Render's Shell tab with the derived
      public URL, as uid 10001 (found by the smoke test: `docker exec` bypasses the supervisor, so
      the CLI printed a loopback pairing URL).
- [x] `deploy/render/README.md`: rewritten for this repository — Blueprint path first, prebuilt
      image second, pairing from Render Shell as uid 10001 via `gosu`, the verification record as
      actually run.
- [x] Cross-build `linux/amd64` locally. First attempts failed: pnpm's Rust runtime aborts under
      Docker Desktop's QEMU (`unexpected error when polling the I/O driver`). Fixed by pinning the
      `deps`/`build`/`prod-deps` stages to `$BUILDPLATFORM` (the two native addons ship linux-x64
      prebuilds); the amd64 image passed the smoke test under emulation.
- [x] Push `docker.io/brenat/seeker-agent-connect:render-v1` to Docker Hub (asked for on
      2026-09-18), and make `build-render.yml` manual-only: the owner's Actions minutes are used up.
- [x] Docs: changelog entry, a Render section in `docs/guides/self-hosting.md`, `CODEBASE.md`.

## Review

- The image build, the running container, routing, the Host check, capabilities, the runtime
  uid, the pairing CLI, and a graceful `SIGTERM` were verified locally on `arm64`; see the
  README's record, for both the native and the amd64 image.
- NOT RUN: a deployment on Render itself (disk mount ownership under Render, health checks from
  Render's probes, pairing a Seeker over the `onrender.com` name). Those need an account.
- Unrelated uncommitted work left untouched: `gateway/compose.yaml` and
  `gateway/compose.caddy-fix.yaml` (adding `NET_BIND_SERVICE` to the Compose gateway).
