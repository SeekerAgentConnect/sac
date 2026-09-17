# gateway

The self-hosting stack: Docker Compose, the gateway in front of the sidecar, and the deployment's configuration.

**This is the owner's own deployment, and not the broadcast gateway.** Everything here belongs to one owner: their sidecar, their reverse proxy, their paired phone. The shared service a developer's publisher publishes to and every subscribed phone reads from is [`broadcast/`](../broadcast) (SEE-90) — a different service with a different operator, and its own compose stack.

- [`compose.yaml`](compose.yaml) — the stack. The sidecar and the gateway start; the test agent sits behind the `agent` profile and does not.
- [`compose.public.yaml`](compose.public.yaml) — the internet-facing overlay: HTTPS on your own domain, ports 80 and 443.
- [`compose.oauth.yaml`](compose.oauth.yaml) — the optional OAuth overlay, for a hosted MCP client that authorizes a person instead of carrying a shared token.
- [`Caddyfile`](Caddyfile) — the gateway's local-development configuration, plain HTTP on a loopback address.
- [`Caddyfile.public`](Caddyfile.public) — the internet-facing one, with automatic HTTPS and a narrower set of public endpoints.
- [`.env.example`](.env.example) — the deployment's settings. Copy to `.env` here, which git ignores.

Locally:

```sh
cp .env.example .env    # then fill in the two tokens
docker compose up -d --build
```

On the internet, once `GATEWAY_DOMAIN` resolves to the host and `ACME_EMAIL` is set:

```sh
docker compose -f compose.yaml -f compose.public.yaml up -d --build
```

With a hosted MCP client, on top of that, once the authorization server exists ([`docs/integrations/claude.md`](../docs/integrations/claude.md)):

```sh
docker compose -f compose.yaml -f compose.public.yaml -f compose.oauth.yaml up -d --build
```

Two files and two commands, so plain HTTP cannot become the public default by omission. [`docs/guides/self-hosting.md`](../docs/guides/self-hosting.md) is the full guide, [`docs/security.md`](../docs/security.md#the-gateway-saw-035) explains what the public configuration exposes and what it does not, and [`docs/testing/stage-7.md`](../docs/testing/stage-7.md) records which checks have actually been run.

The sidecar binds loopback inside its own network namespace, which the gateway joins, so the only reachable way in is what the gateway publishes. OAuth changes nothing about that: the gateway still adds no credential and removes none, and the sidecar — which is the MCP server, and therefore the resource server — is what validates an access token. Nothing in this repository issues one.
