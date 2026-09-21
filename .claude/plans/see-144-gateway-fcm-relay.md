# SEE-144 — Gateway FCM relay for independently hosted direct MCP servers

Base: `superset/feat/see-141`. Branch: `superset/feat/see-144-2`.

## Goal

An independently hosted direct MCP server, holding **no Firebase credential**, can wake a
backgrounded SAC app through the operator's gateway. The gateway relays a fixed, content-free
`request_invalidation`. It never proxies MCP, requests, approvals, signatures or results.

## Boundary (from the ticket)

- SAC keeps pairing, streaming, syncing and deciding **directly** with the external server.
- Firebase credentials stay with the gateway operator; the server gets only a scoped relay
  credential and an opaque push handle.
- Public-feed topic delivery is unchanged.
- No account system, no developer signup.

## Design

### Capabilities (admin)
`publisher` gains `publish_enabled` / `relay_enabled`; `publisher_credential` gains `capability`
(`publish` | `relay`). A publishing credential never gains relay permission and vice versa.
Existing rows migrate to `publish` + `publish_enabled = 1`, so SEE-141 behaviour is unchanged.

### Records (schema v5)
- `relay_installation` — opaque id, **hashed** installation secret (the ownership proof), current
  FCM target (recoverable, access-controlled, never logged), `seen_at_ms`, `revoked_at_ms`.
- `relay_binding` — **hashed** opaque push handle, installation, server, connection ref,
  `expires_at_ms`, `revoked_at_ms`, aggregate counters.

### Surfaces
- Phone-facing, on the existing **read** listener (`/relay/v1/…`): enroll, replace target,
  create binding, revoke binding, reconcile.
- Server-facing, on the existing **publisher** listener (`/relay/v1/notify`): scoped relay
  credential + handle → fixed invalidation.
- Admin: capabilities, relay credentials, aggregate relay status. No targets, secrets or handles.

### Dispatch
`relay.Direct` reuses `relay.Credentials` + the token cache, but routes to a device token on its
own path with its own quotas (per-server, per-binding, global). Compare-clear on a rejected target.

## Steps

- [x] 1. Storage: schema v5, capability columns, installation/binding stores + tests
- [x] 2. `relay.Direct` device dispatch (shared token cache, separate routing) + tests
- [x] 3. `internal/pushrelay`: the versioned HTTP contract, auth, quotas, coalescing + tests
- [x] 4. Config: relay limits, lifetimes; wiring in `gateway.Build` and `main.go`
- [x] 5. Admin: capabilities on register, relay credential rotation/revocation, relay status
- [x] 6. Server SDK: `RelaySender`, explicit relay target type, per-connection handle storage
- [x] 7. MCP adapter: relay configuration, exclusive with direct Firebase
- [x] 8. Android: installation enrollment, target rotation, binding lifecycle, retry queue
- [x] 9. Docs: server-development guide, feed-gateway wiki, firebase guide, deployment, changelog
- [x] 10. Verify: `go test ./...`, `pnpm` checks, Android unit tests; record PASS/FAIL/NOT RUN

## Review

### What was built

The relay is three authorities that must meet, each a separate secret held by a separate party:
the operator's registration, the phone's installation secret, and the server's scoped relay
credential. "A server cannot wake a phone that did not agree", "a phone cannot be redirected by
somebody who saw its FCM registration" and "a server cannot use another server's handle" are
therefore properties of the store's queries rather than checks somebody has to remember.

Two decisions worth recording:

- **`fid`, not `token`.** The ticket said to verify the target representation rather than assume
  it. firebase-admin 14.4.0 treats `fid`, `token`, `topic` and `condition` as four alternative
  target fields of one v1 message and passes whichever is set through to `messages:send`
  unchanged (`messaging-internal.js` `validateMessage`). So the gateway's hand-written REST call
  addresses the same opaque installation the sidecar's Admin SDK does, and a cross-language test
  pins it against both sources.
- **A relay handle is not an FCM target.** Separate column, separate type, separate RPC, on all
  three sides. One addresses a device and works for whoever holds it; the other addresses one
  authorization at one gateway. Putting one where the other belongs would wake nobody, and the
  failure would look exactly like a phone that is switched off.

### Bugs found while building

- **A revocation the phone owed was silently forgotten on the next launch.** Android's
  `JSONObject.put(String, Object)` serializes an unrecognized value — a Kotlin `List` — as its
  `toString()`, so the owed set was written as the *string* `"[binding-1]"` and read back as
  nothing. Found by a lifecycle test, fixed by building a `JSONArray`, and pinned by a store
  round-trip test that says why.
- **A storage failure killed the relay's event loop for the rest of the process's life.** One
  unreadable file and the manager stopped reconciling, silently, looking exactly like a build with
  no relay configured. Every event is now handled inside the best-effort guard.

### Not done, and why

Nothing in the ticket's scope was left out. The demo gateway's SQLite file stays ephemeral, which
the ticket explicitly preserves: push bindings do not survive losing it, the documentation says so
in three places, and re-enrollment and rebinding without re-pairing is tested on both sides.
