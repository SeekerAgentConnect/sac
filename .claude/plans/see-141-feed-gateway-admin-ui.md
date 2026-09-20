# SEE-141 — a password-protected Feed Gateway admin UI

Ticket: https://linear.app/seekeragentwallet/issue/SEE-141
Branch: `superset/feat/see-141` from `master` (ed16d79). PR into `master`.

## The shape

A third listener in the same process and the same image, served under a configurable path prefix,
disabled entirely when no administrator password hash is configured. Server-rendered HTML with the
gateway's own assets embedded; no framework, no CDN, no browser-side secret.

The publisher administration semantics are the CLI's, reached through the same
`storage.PublisherAdminStore` and the same SQLite writer, so UI and CLI see each other's work.

## Tasks

### Storage and shared algorithms
- [x] `internal/credential`: one place that mints a credential, hashes one and derives the operator's
      handle. `feed-gatewayctl`, the admin UI and `sqlite` all use it — no duplicated algorithm.
- [x] Schema v4: `publisher.host`, an `ALTER TABLE ADD COLUMN`. The live table list does not change,
      so `TestTheStoreKeepsOnlyPublicFeedState` still pins the same six tables.
- [x] `storage.Registration{ServerID, Label, Host}`; `Publisher.Host`.
- [x] `Register` returns `ErrPublisherExists` instead of silently adding a credential to an existing
      publisher; `Register`/`AddCredential` return the new credential's ID.
- [x] `Publications(ctx, channel)` for the operator's view of how much a channel holds.

### Configuration
- [x] `config.Admin`: `BROADCAST_ADMIN_PASSWORD_HASH` (the switch), `BROADCAST_ADMIN_ADDRESS`,
      `BROADCAST_ADMIN_PATH`, `BROADCAST_ADMIN_SESSION_MINUTES`, `BROADCAST_ADMIN_LOGIN_RATE`,
      `BROADCAST_ADMIN_LOGIN_BURST`. All-or-nothing, every problem reported at once, same style as
      `Stream` and `Relay`.

### The admin surface (`internal/admin`)
- [x] PBKDF2-HMAC-SHA256 from `crypto/pbkdf2` (standard library; no new module dependency), encoded
      `pbkdf2-sha256$<iterations>$<salt>$<hash>`, verified in constant time.
- [x] In-memory bounded sessions: opaque 32-byte token in an HttpOnly cookie, stored by SHA-256,
      absolute expiry, real logout, bounded count.
- [x] Per-session CSRF token on every mutating form, compared in constant time; `POST` only.
- [x] Login rate limiting per caller through the gateway's own `Limiter` and trusted-proxy policy.
- [x] Pages: login, the publisher list with its empty state, one publisher's detail, the
      once-only credential page, and the destructive-removal confirmation.
- [x] Security headers: CSP `default-src 'none'`, no framing, no referrer, `no-store`.
- [x] Administrative events logged (action, target, outcome) and never a secret.

### Wiring, deployment, docs, tests
- [x] `gateway.Build` builds the admin handler; `Gateway.Run` opens the third listener.
- [x] `feed-gatewayctl password` prints a hash to configure; `register --host`; `list` shows hosts.
- [x] `deploy/feed/compose.yaml`, `deploy/ingress/feed/Caddyfile`, `deploy/seeker-gateway.yaml`,
      both `.env.example`s, `Dockerfile` EXPOSE. No credential or owner domain committed.
- [x] Tests: admin package unit + HTTP tests, boundary tests for anonymous/publisher access,
      CLI/UI agreement, a real running gateway walked end to end.
- [x] Docs: `feed-gateway/README.md`, `docs/wiki/feed-gateway.md`, `docs/development/feed-gateway.md`,
      `docs/guides/server-development.md` (the third-party walkthrough), `docs/testing/see-141.md`,
      `docs/changelog/`, `CODEBASE.md`.

## Review

Done, verified against a real running gateway and through the repository's own feed ingress. The
evidence is [`docs/testing/see-141.md`](../../docs/testing/see-141.md).

**Decisions worth naming:**

- **A third listener, not a path on the read one.** The same argument the codebase already makes for
  the publisher API: two sockets make the separation survive a routing mistake, and a third makes it
  survive one more. It also lets a deployment keep the page off the internet entirely.
- **In-memory sessions rather than a signed cookie**, which is why no session signing key is
  configured. A signed cookie cannot be withdrawn, so logout would only ask the browser to forget
  something that still verifies. The cost is one more login after a restart.
- **Administrative events go to the log, not a table.** They are not public-feed state, and the
  store's boundary test pins that the live schema is only public-feed state.
- **PBKDF2 from `crypto/pbkdf2`** so the gateway's three-module dependency list is unchanged.
- **A dot-separated hash encoding**, after `docker compose config` showed a `$`-separated one being
  eaten by Compose's variable interpolation.
- **`register` now refuses a duplicate identity** on both surfaces, rather than silently adding a
  credential to an existing publisher. `loadtest` registers each publisher once, so it is unaffected.
- **`internal/gateway/secrets.go` removed.** It had been unreferenced since SEE-130 and duplicated
  the credential helpers this ticket asked not to duplicate.
