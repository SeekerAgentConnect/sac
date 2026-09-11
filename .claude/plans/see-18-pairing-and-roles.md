# SEE-18 / SAW-011 — Implement secure pairing and separate access roles

Linear: https://linear.app/seekeragentwallet/issue/SEE-18 · Branch: `develop` · One PR `develop` → `master` after SEE-21

## Checklist

- [x] Proto: `PairRequest.server_url` and `PairResponse.server_id` (additive), then `pnpm generate`
- [x] Storage: migration v2
  - [x] a `server` table: the sidecar's lasting ID
  - [x] `credential_hash` and `device_name` columns on `connections`
  - [x] a `pairing_tokens` table
  - [x] the SAW-010 stand-in connection revoked, and its PENDING requests cancelled
- [x] `sidecar/src/pairing/`:
  - [x] `uri.ts`: the pairing URI (`seekervault://pair?v=1&url=…&server=…&token=…`), and the server URL rule: HTTPS, or HTTP on loopback only
  - [x] `store.ts`, the `PairingStore`:
    - [x] one-use tokens that expire, stored as hashes, where a new token voids older ones
    - [x] `Pair`, bound to the token's URL; it revokes the previous phone, since there's one active phone
    - [x] credentials stored as SHA-256
    - [x] revocation, which cancels PENDING requests
  - [x] `service.ts`: the Connect `PairingService` (`Pair`, `RevokeConnection`)
  - [x] `cli.ts`: `pnpm pair`, which prints a QR code (uqr) and the URI, plus `pnpm pair status` and `pnpm pair revoke`
- [x] Roles:
  - [x] `RequestService` accepts only an active paired credential.
  - [x] `PHONE_TOKEN` stays with the Stage 1 `LiveCommandService`, as the loopback development exception.
  - [x] `MCP_TOKEN` works on `/mcp` only.
  - [x] Remove the startup stand-in connection.
- [x] Config: `SIDECAR_PUBLIC_URL` (defaults to the loopback URL) and `PAIRING_TOKEN_TTL_SECONDS` (default 600)
- [x] Tests:
  - [x] pairing tokens that are expired, reused, unknown, or used with the wrong URL
  - [x] revoked credentials, re-pairing, and unauthorized result submission
  - [x] the full role matrix: every protected RPC and MCP operation against every credential
  - [x] QR contents: a new QR code never changes an existing connection
  - [x] pairing over HTTPS through a TLS proxy; certificate and hostname checks
  - [x] the v1 fixture migrated to v2
  - [x] logs and CLI status carry no tokens
- [x] Docs:
  - [x] a new `docs/security.md`
  - [x] pairing and the role matrix in `docs/protocol.md`
  - [x] `docs/development/sidecar.md`, README, AGENTS, CODEBASE, `.env.example`, `toolchain.md`, `docs/architecture.md`, the changelog, and the decisions
- [x] Verify: `pnpm check`, `pnpm test:hello`, `pnpm check:generated`, `pnpm check:android`, `pnpm build`, a live `pnpm pair`, and deliberate breaks
- [ ] Commit and push on `develop`, and CI green after the push
- [ ] Linear: tick the SEE-18 checklist, add a summary, and move the issue to In Review

## Design

- **Four credentials, each accepted in one place:**

  | Credential | Issued by | Accepted by |
  | --- | --- | --- |
  | `MCP_TOKEN` | `.env` | `/mcp` |
  | Pairing token | `pnpm pair` (one use, 10 minutes) | `PairingService.Pair` |
  | Phone credential | the `Pair` response | `RequestService`, `PairingService.RevokeConnection` |
  | `PHONE_TOKEN` | `.env` | `LiveCommandService` only (the Stage 1 loopback diagnostic) |
- **The operator CLI writes pairing tokens straight to the database,** and the running sidecar reads them on `Pair`. SQLite's locking makes a second, short-lived process safe.
- **The token is bound to the URL in its QR code.** A `Pair` with a different `server_url` is refused, and the token stays unused. The response carries the lasting `server_id`, so the phone can recognize the same server. Pairing always creates a new connection and never changes an existing one.
- **One active phone.** A new pairing revokes the previous connection and cancels its PENDING requests. Revocation comes from the phone (`RevokeConnection`) or from the operator (`pnpm pair revoke`).
- **TLS:** the sidecar stays on loopback. The phone reaches it through a trusted TLS endpoint, such as Tailscale Serve or Caddy, with normal certificate checks. The public URL must be HTTPS; the only exception is the loopback development URL used with `adb reverse`.

## Review

Everything above is done except the commit, push, and CI, and the Linear update, which follow this review.

- **What changed:**
  - `sidecar/src/pairing/` holds the URI, the `PairingStore`, the Connect `PairingService`, and the `pnpm pair` CLI.
  - `RequestService` now authenticates the paired phone's credential, and SAW-010's stand-in connection is gone.
  - Migration 2 adds `server` and `pairing_tokens`, adds the credential columns, and revokes the stand-in connection.
  - `SIDECAR_PUBLIC_URL` and `PAIRING_TOKEN_TTL_SECONDS` are new.
  - `uqr` 0.1.3 draws the QR code.
- **Verified (PASS):**
  - `pnpm check`: 235/235 sidecar tests, 46 new, and 15/15 in the test agent
  - `pnpm test:hello`: 9/9
  - `pnpm check:generated`, `buf breaking`, `pnpm check:android`, and `pnpm build`
  - a live `pnpm dev:sidecar` with `pnpm pair`, then pairing, status, and revoke, with no secret in the log
  - nine deliberate breaks, each caught and restored
- **NOT RUN:**
  - remote pairing through Tailscale Serve or Caddy, which needs the owner's tailnet or domain; `tls.test.ts` covers the path locally
  - the physical Seeker: the app's pairing screen arrives in SAW-012
- **Caveats:**
  - The QR code scans on a dark terminal background. On a light one, the URI printed under it is the fallback.
  - Anyone who can run `pnpm pair` or write the database acts as the operator. `docs/security.md` says so, and says to keep agents off the sidecar's account.
  - Owners upgrading from SAW-010 must pair again, because migration 2 revokes the stand-in connection and cancels its PENDING requests.
