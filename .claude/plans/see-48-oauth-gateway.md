# SEE-48 — SAW-036: a compatible OAuth gateway for hosted MCP clients

**Ticket:** SEE-48 (SAW-036), child of SEE-45 (Stage 7). Branch `superset/feat/see-45`.

## What the ticket asks

An operator who self-hosts this sidecar should be able to connect a hosted Claude client to it
over OAuth, using an authorization server that already exists — not one built here. OAuth stays
on the agent-facing MCP endpoint, never on the phone's API, and no route may bypass it.

## The decision

The MCP specification (2025-11-25, R16) makes **the MCP server an OAuth 2.1 resource server**: it
publishes protected-resource metadata, validates the access token, and refuses tokens that were
not issued for it. That work belongs where the MCP endpoint is — in the sidecar — and the
authorization server stays an existing product the operator chooses. Nothing here issues a token,
registers a client, or shows a consent screen.

The gateway's job is unchanged and small: route the metadata document publicly and pass the
`Authorization` header through untouched.

## Implementation

- [x] `sidecar/src/oauth.ts`: the protected-resource metadata document, the `WWW-Authenticate`
      challenge, and an access-token verifier (issuer, audience, expiry, signature over the
      authorization server's JWKS, optional scope). Discovery of the JWKS URL from the issuer when
      it is not configured.
- [x] `sidecar/src/config.ts`: `MCP_OAUTH_ISSUER` (the switch), `MCP_OAUTH_RESOURCE`,
      `MCP_OAUTH_JWKS_URL`, `MCP_OAUTH_SCOPE`; a setting that only makes sense with an issuer is a
      configuration error without one.
- [x] `sidecar/src/mcp-endpoint.ts`: when OAuth is configured, `/mcp` takes an access token; the
      static `MCP_TOKEN` keeps working only for a loopback `Host`, which is the stack's own private
      endpoint and can't be reached from the internet.
- [x] `sidecar/src/server.ts`: serve the metadata at both well-known paths, and say at startup
      whether OAuth is on.
- [x] `gateway/`: route the metadata document in both Caddyfiles; `compose.oauth.yaml` as the
      optional profile, composable with the public overlay; `.env.example` and README.

## Tests and checks

- [x] `sidecar/src/testing/authorization-server.ts`: a fake authorization server (metadata, JWKS,
      minted tokens) so the whole path can be exercised without a provider account.
- [x] `sidecar/src/oauth.test.ts` and endpoint tests: a real MCP session authorized by an access
      token; no token; expired; wrong audience; wrong issuer; a signature from another key; an
      unsupported algorithm; a missing scope (403 `insufficient_scope`); the metadata document at
      both paths and its absence when OAuth is off; `MCP_TOKEN` refused under the public host.
- [x] The phone's API never accepts an access token (`roles.test.ts`).
- [x] `stage-boundary.test.ts`: OAuth lives in one file, only the MCP endpoint consults it.
- [x] `pnpm check`.

## Documentation

- [x] `docs/integrations/claude.md` — what the operator must obtain, the exact values, the
      connector steps, the failure modes, and a record table.
- [x] `docs/security.md` — the authorization boundary.
- [x] `docs/guides/self-hosting.md`, `gateway/README.md`, `docs/testing/stage-7.md`,
      `docs/changelog/2026-09-17.md`, `CODEBASE.md`, `AGENTS.md`.

## Honest limits

The acceptance is a real Claude round trip against a real authorization server on a real domain.
That needs a public host, a provider account, and the phone; it is NOT RUN here and the ticket
says plainly that a metadata endpoint returning JSON is not a passed integration test.

## Review

**What shipped.** The sidecar became the OAuth 2.1 resource server the MCP specification
describes, and nothing more. `sidecar/src/oauth.ts` holds all of it: the RFC 9728 metadata
document, the `WWW-Authenticate` challenge, and token validation — asymmetric signature over the
authorization server's published keys, `iss`, `aud`, `exp`, and scopes — with `jwks_uri`
discovered from the issuer when it is not configured. `gateway/compose.oauth.yaml` is the optional
overlay, and both Caddyfiles route the discovery document.

**Three decisions worth recording.**

1. *Enforcement at the MCP server, not at the gateway.* The specification puts protected-resource
   metadata, audience validation and token validation on the MCP server, and Caddy cannot validate
   a JWT without a custom build — which would have meant a configuration nothing here could test.
   Doing it in the sidecar made the whole thing testable in `pnpm check`, against a fake
   authorization server that mints real signed tokens. The authorization server itself is still
   somebody else's product: nothing here issues, registers, or consents.

2. *`MCP_TOKEN` keeps the private endpoint, and loses the public one.* While OAuth is on, the
   static token opens `/mcp` only under a loopback `Host`. That is the stack's own endpoint inside
   the container network namespace, which Compose cannot publish and the public gateway closes for
   any host it does not serve — so there is no second way in that skips the authorization server,
   and the health check, the `agent` profile and the test agent keep working unchanged. The
   alternative, refusing `MCP_TOKEN` outright, would have broken all three for no security gain.

3. *JWT validation only; no introspection.* An opaque token cannot be checked without asking the
   authorization server on every call, and that is a dependency and a latency this endpoint does
   not need. The cost is that revoking a grant stops the *next* token rather than the current one;
   that is written down in `docs/security.md` and `docs/integrations/claude.md` together with the
   answer — short token lifetimes — rather than left for an operator to discover.

**What the checks caught.** Removing the audience check makes `oauth.test.ts` fail, which is how
the audience test was proven to bite. Running the shipped configurations behind real TLS also
caught the one thing the unit tests could not: that the discovery document has to be routed
explicitly in both Caddyfiles, since the public configuration refuses every path it does not name.

**What is still outstanding.** The acceptance — a hosted Claude client's own round trip — is NOT
RUN, and so are denied consent, revocation at a real authorization server, and any commercial
provider's tokens. `docs/testing/stage-7.md#saw-036` lists each one with what it needs, and
`docs/integrations/claude.md` ends with an empty record table for the operator who runs it.
