# SEE-143 — Fix PR #41 review findings

Linear: https://linear.app/seekeragentwallet/issue/SEE-143/fix-pr-41-review-findings-feed-status-isolation-upgrade-idempotency

Reviewed at `2b23cca` (also current PR #41 head). No later SEE-139 commits to preserve.

Base: `superset/feat/see-139`. PR into that base, not `master` or `feat/do-deploy`.

## Acceptance (from the ticket)

- [x] 1. HIGH — Feed status isolation
  - [x] GatewayFeed must not inherit ForegroundUpdateManager Revoked/credential state
  - [x] Mode-aware status/problem styling
  - [x] Do not apply feed listener state to Direct connections that share an origin
  - [x] Manager → ViewModel → UI with GatewayFeed + no credential
  - [x] Direct Revoked + feed Live: Home connected, N pending, details non-error
  - [x] Cover Connecting, Reconnecting, Unreachable, NoStream, background/foreground
  - [x] Genuine direct credential revocation still disconnected
- [x] 2. MEDIUM — Publisher idempotency upgrade
  - [x] Preserve replay for pre-upgrade digests (expiry+note)
  - [x] Include non-empty titles for new statements
  - [x] Do not reconstruct create digest from a possibly-edited signal
  - [x] Real pre-upgrade DB fixture + migrate
  - [x] Unchanged signal and edited-after-create both replay
  - [x] Same-key different payload still rejected; different titles distinguished
- [x] 3. MEDIUM — Prediction titles
  - [x] Distinguish multi-market events (e.g. event · market)
  - [x] No app-added category/provider prefixes
  - [x] Provider stays in footer
  - [x] Missing titles and long questions
  - [x] Complete market question preserved when the provider already supplies one
- [x] Carousel first-card insertion regression (SEE-139 verification)
- [x] Device checks NOT RUN
- [ ] Separate commits per finding
- [ ] PR into `superset/feat/see-139`

## Approach

1. Filter live/feed transport by `Connection.mode` in `statusText`, `hasProblem`, and `toHomeServerState`.
2. `Statement()` includes title only when non-empty, matching the pre-upgrade expiry+note digest.
3. `predictionTitle` joins distinct event and market text, keeping market identity inside the 64-byte bound.
4. Add a `centredIndex = 0` carousel insertion test.
