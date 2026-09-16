# SEE-47 (SAW-035) — Configure the TLS gateway and public/private endpoints

Branch `superset/feat/see-45`, on top of SEE-46's Compose stack. Gateway and
security only: no sidecar or test-agent source changes, no stage-boundary moves.

## What the ticket asks

- [x] Caddy for HTTPS; document domain, DNS, ports, and persistent certificate storage.
- [x] Route MCP and Connect separately, preserving their authentication boundaries, and
      pass through the headers and content types their transports need.
- [x] Clear local-development versus internet-facing configurations. Loopback HTTP must
      not become the production default; never tell anyone to ignore certificate errors.
- [x] Private backend/admin ports off the public interface; request/body limits;
      credentials and pairing secrets redacted from access logs.

## Design

- [x] Two configurations, not one with a permissive default:
  - `gateway/Caddyfile` — local development. Plain HTTP on `:8081` inside the shared
    namespace, published to the host's loopback only. Serves the Stage 1 diagnostic.
  - `gateway/Caddyfile.public` — internet-facing. Automatic HTTPS for `{$GATEWAY_DOMAIN}`,
    HSTS, no Stage 1 diagnostic, `/healthz` only on the container-local port.
- [x] `gateway/compose.public.yaml` overlay selects the public Caddyfile, publishes 80/443,
    requires `GATEWAY_DOMAIN` and `ACME_EMAIL`, and derives `MCP_ALLOWED_HOSTS` and
    `SIDECAR_PUBLIC_URL` from the domain so the two Host-check footguns cannot be missed.
- [x] Default-deny routing: `/mcp`, the four Connect services, and `/healthz` are named
    explicitly; anything else is aborted at the gateway.
- [x] 64 KiB body limit per route, matching what the sidecar itself enforces.
- [x] `Host` still passed through unchanged (the sidecar's DNS-rebinding defence).
- [x] Caddy's admin API stays off, and `log_credentials` stays off so `Authorization`
    never reaches the access log.

## Known limitation to document, not to paper over

The phone's `Subscribe` stream is gRPC over HTTP/2 and terminates at the sidecar's own TLS
listener (Stage 5.2). Behind a TLS-terminating gateway the sidecar speaks HTTP/1.1, so no
update endpoint is configured and none is advertised: pairing simply omits the capability
and the phone keeps the unary/manual path. Say so plainly and point at the sidecar guide.

## Documentation

- [x] `docs/guides/self-hosting.md` — TLS and ports, DNS, certificate storage, the two modes.
- [x] `docs/security.md` — the gateway's public/private split and log redaction.
- [x] `gateway/README.md`, `gateway/.env.example`, `CODEBASE.md`, `docs/changelog/2026-09-17.md`.
- [x] `docs/testing/stage-7.md` — what was actually run for SAW-035.

## Verification

- [x] `caddy validate` both Caddyfiles with the pinned 2.10.2 release.
- [x] Run the development config in front of a real sidecar and check every routing and
      authentication boundary with curl.
- [x] Run the public config with an internally issued certificate and check TLS
      termination, HSTS, the private/public split, and the denial of unknown paths.
- [x] `docker compose config` on both the base file and the overlay.
- [x] `pnpm check`.

## Review

**What was built.** Two gateway configurations rather than one: `Caddyfile` (plain HTTP on a
loopback address, for local work) and `Caddyfile.public` with `compose.public.yaml` (HTTPS on a
domain the operator owns). Going public is a different command, so the development default cannot
become the production one by omission. Both files route `/mcp` and the Connect services separately,
cap each request body at 64 KiB, close everything they do not serve, keep Caddy's admin API off,
and log every request with credentials redacted. The public one additionally drops the Stage 1
diagnostic and `/healthz` from the public listener, sends HSTS, and closes a connection carrying a
`Host` this deployment does not serve.

**Two decisions worth recording.**

1. *The gateway adds and removes nothing.* No `Authorization` is inserted or stripped and `Host` is
   passed through, so every authentication boundary stays the sidecar's own and its DNS-rebinding
   check keeps working. The cost is that a public deployment must name its domain — which the
   overlay now does for it, from `GATEWAY_DOMAIN`, along with `SIDECAR_PUBLIC_URL`.
2. *The live update stream is not carried, and is not pretended to be.* `Subscribe` is gRPC over
   HTTP/2 terminating at the sidecar's own TLS listener. Behind a TLS-terminating gateway the
   sidecar speaks HTTP/1.1, so no update endpoint is configured and pairing advertises none. The
   alternative — pointing the gateway at `SIDECAR_UPDATE_PORT` — would have advertised a loopback
   `http://` origin to a remote phone that release builds refuse outright. Documented instead.

**What was actually verified.** More than SAW-034 managed. Downloading the Caddy release that
matches the pinned image (2.10.2) made it possible to run both shipped configurations in front of a
live sidecar without Docker: `caddy validate` on both, MCP through the gateway with and without a
token, the test agent listing real tools through it, spoofed `Host`/`Origin` still refused by the
sidecar, unknown paths closed, 413 on an oversized body, the admin port refused, `Authorization`
redacted in the log, and — for the public file — TLS with a verified chain, HSTS, the private/public
split, an unserved `Host` closed, and a gateway restart reusing its stored certificate.
`docker compose config` renders both the base file and the overlay, and the overlay refuses to
resolve without `GATEWAY_DOMAIN`. `pnpm check` passes. Along the way the registry confirmed that
`caddy:2.10-alpine`, pinned in SAW-034 and never pulled, exists.

**Two things the checks corrected.** Caddy's global `log` block configures the runtime logger, not
access logging, so the access log SAW-034's comment claimed did not exist; it is now declared per
site. And a site address restricts the `Host` header, not the interface, so the private endpoint
needed an explicit `bind 127.0.0.1` to be the unpublishable thing it is described as.

**Still outstanding.** Everything needing a Docker daemon (none reachable for this account),
certificate issuance and renewal from a public authority, the port 80/443 redirect, the missing-DNS
failure mode, and pairing a physical Seeker over HTTPS. Each is listed with what it needs in
`docs/testing/stage-7.md`.
