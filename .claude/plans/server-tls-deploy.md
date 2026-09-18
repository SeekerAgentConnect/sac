# deploy/server: the sidecar with its own TLS listener, for the live update stream

**Ask (2026-09-18):** "i want streaming updates. create in deploy new folder server. Where write
guide how to setup and docker images if needed." The owner's server is a Linux droplet behind
Tailscale Funnel. Nothing is to be run on that server by me; this is a guide and the pieces it
needs.

## Where this starts

Every shipped deployment (Compose gateway, Render image, and the owner's Funnel HTTPS path) puts an
HTTP/1.1 hop in front of the sidecar, so `UpdateService.Subscribe` — gRPC over HTTP/2 end to end,
at the sidecar's own TLS listener — is never advertised. The sidecar already supports that listener
(`SIDECAR_TLS_CERT_PATH`/`SIDECAR_TLS_KEY_PATH`, `docs/development/sidecar.md`); what is missing is
a deployment that uses it.

## Design

- The sidecar image as it is (`sidecar/Dockerfile`), no gateway. The sidecar binds loopback by
  rule, so the container runs with `network_mode: host`: its port sits on the host's loopback,
  reachable by the host's own processes only. Funnel's raw TCP mode
  (`tailscale funnel --bg --tcp=10000 tcp://localhost:8443`) passes TLS through untouched.
- The certificate is `tailscale cert <node>.<tailnet>.ts.net`, a publicly trusted Let's Encrypt
  identity for the node's name. The sidecar reads it once at start, so renewal = re-issue + restart;
  a script does both and is meant for cron.
- The public origin is `https://<node>.<tailnet>.ts.net:10000`; the sidecar and the phone both
  accept a port. `MCP_ALLOWED_HOSTS` is the hostname.
- What is public on that origin: everything the sidecar serves, each behind its own token, the
  Stage 1 diagnostic and `/healthz` included — there is no gateway to filter routes. Written down
  as the trade.
- `sidecar/Dockerfile` gets the same `--platform=$BUILDPLATFORM` pin as the Render image, so an
  amd64 image builds on this Mac; pushed as `docker.io/brenat/seeker-agent-connect:sidecar-v1`.

## Implementation

- [x] `sidecar/Dockerfile`: pin the install/compile stages to `$BUILDPLATFORM`.
- [x] `deploy/server/compose.yaml`, `.env.template` (the owner asked for that name), `tls-from-tailscale.sh`, `README.md`, with FCM on the server (asked for mid-task).
- [x] Local verification: `docker compose config`; the native image with a self-signed
      certificate — TLS listener up, `h2` negotiated, `/healthz` 200, the log advertising
      production updates at the public origin, the pairing URL carrying the port, healthcheck
      green, clean `SIGTERM`.
- [x] Build `linux/amd64` and push `brenat/seeker-agent-connect:sidecar-v1`.
- [x] Docs: `docs/changelog/2026-09-18.md`, a section in `docs/guides/self-hosting.md`,
      `CODEBASE.md`.

## Review

- Everything runnable without the server was run; the README's record lists it, including what
  was not: the `Host` check (Node's `fetch` drops a custom `Host`), and everything on the server —
  `tailscale cert`, Funnel `--tcp`, a Seeker's stream, a real push.
- The local Compose (v2.15) prints "invalid interpolation format" for a missing required variable
  with the `${VAR:?message}` form, for the existing gateway file as much as for the new one; the
  server's Compose v5 shows the message. Not a file problem.
- `LIVE_COMMAND_TIMEOUT_SECONDS` has no default in the sidecar; `compose.yaml` sets it, a bare
  `docker run` must too (first smoke run failed on exactly that).
- Nothing was run on hermes, per the owner's instruction.
