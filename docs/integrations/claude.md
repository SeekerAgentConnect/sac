# Connecting a hosted Claude client over OAuth

A hosted MCP client runs on somebody else's machine. It cannot read a token out of your `.env`,
and you should not want it to: a shared secret pasted into a hosted product is a secret that
product now holds. OAuth replaces it with something better — a person authorizes the client at
your own authorization server, the client receives a short-lived access token issued **for this
deployment**, and you can take that authorization back without changing a token everything else
uses.

This is optional. An operator running Hermes, or an agent of their own, keeps `MCP_TOKEN` and can
skip this page entirely (`docs/integrations/hermes.md`).

It is also only the front door. Everything behind it is unchanged: a request still waits on the
owner's Seeker, the sidecar still holds no key and signs nothing, and an authorized client still
cannot approve anything. What OAuth decides is who may ask.

## What this deployment does, and what it does not

The MCP specification makes an MCP server an [OAuth 2.1 resource
server](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization), and that is
exactly what the sidecar is:

- it publishes the protected-resource metadata document (RFC 9728) that tells a client where to
  authorize;
- it validates every access token: your authorization server's signature, its issuer, the
  audience — the token must have been issued for **this** MCP server — expiry, and the scopes you
  require;
- it refuses everything else, and it never passes a token on to anything.

It is not an authorization server, and nothing here is going to become one. No `/authorize`, no
`/token`, no client registration, no consent screen, no user database, no client secret. That is a
product you already run or sign up for, and the only thing the sidecar ever reads from it is
public keys.

## What your authorization server has to support

Claude's client does the standard flow, so the requirements are the standard ones. Check these
before you choose, because a login proxy that only puts a sign-in page in front of a URL will not
work, and neither will a provider whose tokens are opaque strings:

| Requirement | Why |
| --- | --- |
| JWT access tokens, signed with an asymmetric algorithm (RS\*, PS\*, ES\*, EdDSA), with a published JWKS | The sidecar validates the token itself. It performs no token introspection, so an opaque token cannot be checked |
| OAuth 2.0 authorization server metadata (RFC 8414) or OpenID Connect discovery | Both the client and the sidecar read it; the sidecar takes `jwks_uri` from it unless you set `MCP_OAUTH_JWKS_URL` |
| PKCE with `S256`, advertised as `code_challenge_methods_supported` | A spec-compliant client refuses to start without it |
| Client ID Metadata Documents, or dynamic client registration (RFC 7591) | The hosted client and your authorization server have no prior relationship, so one of these is how it identifies itself. Pre-registering the client by hand also works, if the client offers it |
| An audience — resource indicators (RFC 8707), or any way to put this deployment's MCP URI in the token's `aud` claim | This is the check that stops a token minted for something else from opening your wallet's front door |
| Revocation, and short access-token lifetimes | See [Taking authorization back](#taking-authorization-back) |

Providers that document MCP support, and self-hostable servers such as Keycloak, are the obvious
candidates. This repository does not endorse one, and — see [What has actually been
run](#what-has-actually-been-run) — has not exercised any of them.

Write down four values before going on:

1. **Issuer** — exactly as the tokens spell `iss`, trailing slash included if it publishes one.
2. **JWKS URL** — only if the issuer's metadata document does not publish `jwks_uri`.
3. **Audience** — `https://<your domain>/mcp` unless the server insists on its own identifier.
4. **Scope** — one you create for this, for example `seeker-vault:agent`. Optional, and worth
   having: it is what a client is told to ask for, and what the consent screen describes.

## Setting it up

### 1. Have the public endpoint first

OAuth runs over HTTPS on a real domain: the redirect URIs, the token request, and the MCP calls
themselves. Set up the generic native-TLS direct endpoint in the canonical
[`deploy/README.md`](../../deploy/README.md#3-direct-mcp-over-native-https-and-http2) and check it
works before adding any of this.

### 2. Tell the sidecar about the authorization server

In `deploy/mcp/.env`:

```sh
MCP_OAUTH_ISSUER=https://auth.example.com/realms/seeker
MCP_OAUTH_SCOPE=seeker-vault:agent
# Only if the defaults are wrong for your provider:
# MCP_OAUTH_RESOURCE=https://vault.example.com/mcp
# MCP_OAUTH_JWKS_URL=https://auth.example.com/realms/seeker/protocol/openid-connect/certs
```

Then replace the MCP service and start the independent ingress:

```sh
docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml up -d --build
docker compose --env-file deploy/ingress/direct/.env \
  -f deploy/ingress/direct/compose.yaml up -d
```

`MCP_OAUTH_ISSUER` is the switch. Without it there is no OAuth at all, and the other three
settings are a configuration error rather than settings that quietly do nothing.

The startup log says which it is:

```
[sidecar] MCP OAuth is on: /mcp takes an access token issued by https://auth.example.com/realms/seeker for https://vault.example.com/mcp
```

### 3. Check it yourself, before involving Claude

A metadata endpoint returning JSON proves very little, but a wrong one wastes an afternoon.

```sh
# The document a client reads first. Both of these must answer.
curl -s https://vault.example.com/.well-known/oauth-protected-resource | jq
curl -s https://vault.example.com/.well-known/oauth-protected-resource/mcp | jq

# The challenge. It must name the metadata document and the scope to ask for.
curl -si -X POST https://vault.example.com/mcp -H 'Content-Type: application/json' -d '{}' \
  | grep -i www-authenticate

# Your authorization server's own metadata, as the client will read it.
curl -s https://auth.example.com/realms/seeker/.well-known/openid-configuration | jq \
  '{issuer, jwks_uri, code_challenge_methods_supported, registration_endpoint}'
```

If you can get an access token out of your provider by hand, one more check is worth it:

```sh
curl -si -X POST https://vault.example.com/mcp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'
```

A `401` carries a `WWW-Authenticate` header saying which check failed, in words — see
[When it does not work](#when-it-does-not-work).

### 4. Add the connector in Claude

The exact wording moves between releases, so record what you actually saw (the table at the end of
this page is where it goes). As of writing, in Claude's settings:

1. Open **Settings → Connectors → Add custom connector**.
2. Give it a name and the MCP server URL: `https://vault.example.com/mcp`.
3. Claude requests the endpoint, gets the `401`, reads the metadata document, and finds your
   authorization server. Add it.
4. Connecting it opens your authorization server in a browser. Sign in, and approve the consent
   screen — it should name the client and the scope you configured.
5. Back in Claude, the connector's tools appear. `vault_get_capabilities` is the honest one to try
   first: it says what this sidecar actually serves.
6. Ask for something that creates a real request — a message signature is the cheapest. It should
   arrive on the Seeker and wait there. **Nothing happens until the owner approves it by hand.**

## What an authorized client can and cannot do

- It reaches `/mcp`, and nothing else. The phone's own API — pairing, the durable request service,
  the update stream — takes the phone's credentials, and an access token opens none of it. A
  hosted client never gains a phone approval credential.
- `/healthz` and the Stage 1 `LiveCommandService` diagnostic are not on the public interface at
  all ([`deploy/ingress/direct/Caddyfile`](../../deploy/ingress/direct/Caddyfile)).
- `MCP_TOKEN` still opens the host-loopback endpoint. It does **not** open `/mcp` under the public
  name while OAuth is on, so there is no second way in through the public ingress that skips the
  authorization server.
- Everything the tools do is still a request waiting for the owner. An access token is permission
  to ask.

## Taking authorization back

Access tokens are not checked against your authorization server on every call — that is what
signing them is for — so revoking one takes effect when it expires. Two things follow, and both
are worth doing before you need them:

- Configure **short access-token lifetimes**. Five to fifteen minutes is normal for this, and the
  refresh token is what keeps the client working.
- Revoke at the authorization server: the refresh token and the client's grant. The client can
  then no longer obtain a new access token, and the one it holds stops working when it expires.

If you need a client cut off this second rather than this quarter-hour, stop the stack, or remove
`MCP_OAUTH_ISSUER` and restart — every access token is refused immediately, and `MCP_TOKEN` goes
back to being the way in.

## When it does not work

The refusal says which check failed, both in the response and in
`docker compose --env-file deploy/mcp/.env -f deploy/mcp/compose.yaml logs mcp-server`.
None of these ever quotes the token.

| What you see | What it means |
| --- | --- |
| `the access token was not issued for this MCP server` | The `aud` claim does not carry `MCP_OAUTH_RESOURCE`. The client sent no `resource` parameter, or the authorization server ignored it; configure the audience on the server, or set `MCP_OAUTH_RESOURCE` to what it does issue |
| `the access token was not issued by the configured authorization server` | `iss` and `MCP_OAUTH_ISSUER` differ — usually a trailing slash, or a tenant path |
| `the access token's signature is not from the authorization server's published keys` | The wrong JWKS, or a key that has been rotated out. Check `MCP_OAUTH_JWKS_URL`, or unset it and let discovery find it |
| `the access token is not a JSON Web Token` | The provider issues opaque tokens. The sidecar cannot validate those; the provider usually has a setting that makes them JWTs |
| `the access token uses an algorithm this server does not accept` | Only asymmetric algorithms are accepted. A shared secret is not one |
| `the access token has expired` | Ordinary, if it keeps happening the client is not refreshing; check the clocks too |
| `the access token is missing the scope …` (HTTP 403) | The consent did not include `MCP_OAUTH_SCOPE`. A client re-authorizes when it is told which scope it needs |
| `the authorization server's keys could not be read` | The sidecar could not reach the issuer. It retries on the next request |
| `an access token … is required` with no error | No `Authorization` header arrived at all |
| Claude finds no authorization server | The metadata document is not reachable. It is public by design: check both well-known URLs from outside your network |

## What has actually been run

The whole path was exercised against an authorization server the tests control: the discovery
document through the public TLS gateway, a real MCP session opened with an access token, and every
refusal above (`mcp-server/src/oauth.test.ts`, `docs/testing/stage-7.md#saw-036`).

**No hosted Claude client has connected to it.** That needs a public domain, a real authorization
server, and an account, and it is the one check this integration is actually for — a metadata
endpoint returning JSON is not a passed integration test. Until the table below has a row in it,
this page describes a deployment profile that has been tested in every part except the part that
matters most.

| Date | Client, product and version | Authorization server | What was done | Result |
| --- | --- | --- | --- | --- |
| — | — | — | Connector added, consent approved, tools discovered, one real request answered on the Seeker | NOT RUN |
