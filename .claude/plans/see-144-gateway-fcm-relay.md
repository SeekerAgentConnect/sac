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

- [ ] 1. Storage: schema v5, capability columns, installation/binding stores + tests
- [ ] 2. `relay.Direct` device dispatch (shared token cache, separate routing) + tests
- [ ] 3. `internal/pushrelay`: the versioned HTTP contract, auth, quotas, coalescing + tests
- [ ] 4. Config: relay limits, lifetimes; wiring in `gateway.Build` and `main.go`
- [ ] 5. Admin: capabilities on register, relay credential rotation/revocation, relay status
- [ ] 6. Server SDK: `RelaySender`, explicit relay target type, per-connection handle storage
- [ ] 7. MCP adapter: relay configuration, exclusive with direct Firebase
- [ ] 8. Android: installation enrollment, target rotation, binding lifecycle, retry queue
- [ ] 9. Docs: server-development guide, feed-gateway wiki, firebase guide, deployment, changelog
- [ ] 10. Verify: `go test ./...`, `pnpm` checks, Android unit tests; record PASS/FAIL/NOT RUN

## Review

(filled in at the end)
