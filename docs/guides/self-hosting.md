# Self-hosting the sidecar with Docker Compose

The stack in [`gateway/compose.yaml`](../../gateway/compose.yaml) runs the sidecar on your own machine — a Mac, or a Linux VPS — behind a gateway, with durable requests on a volume that survives a restart. It has two configurations: plain HTTP on your own machine while you are working locally, and HTTPS on a domain you control when you put it on the internet ([Going public](#going-public-tls-dns-and-ports)). Everything builds from this checkout: there is no image to pull from us, no account to make with us, and no service of ours in the path.

What this gives you is the server half of Seeker Agent Connect. The phone still reviews and approves every request, and the wallet still signs; the sidecar holds no key and signs nothing ([`docs/architecture.md`](../architecture.md)).

**This is the private kind of server**, paired to one owner's phone. The other kind broadcasts to everybody subscribed and is deployed separately, from `broadcast/` and `publisher/`: if that is what you are building, [`server-development.md`](server-development.md) is its numbered walkthrough and this page is not about it.

Everything builds from this checkout, and nothing in the path is ours. That is not the same as needing nothing: a public deployment needs a domain and a certificate authority, transfers need somebody's Solana RPC endpoint, and a hosted client needs an authorization server. [What this needs from outside](#what-this-needs-from-outside) is the whole list, with what is optional marked as optional.

**Starting the stack does nothing on its own.** It opens a port and waits. It creates no request, asks for no signature, moves no funds, and runs no LLM. The test agent is not started by `docker compose up` at all.

## Before you start

- Docker Engine 24 or newer with the Compose v2 plugin. `docker compose version` should print `v2.x`.
- `git`, and a clean checkout of this repository. Nothing else: the images build Node, the workspace, and the sidecar from source.
- For pairing, the app on the Seeker, and — on your own machine — `adb` to forward the port ([`macbook-seeker-quickstart.md`](macbook-seeker-quickstart.md)). The stack runs perfectly well before a phone is paired; it simply has nobody to ask.
- To put it on the internet, the domain and the open ports in [Going public](#going-public-tls-dns-and-ports).

## What this needs from outside

Nothing here talks to a service of ours, and nothing phones home. Other people's infrastructure is
still involved, and it is better named than discovered later.

| | When | What it is |
| --- | --- | --- |
| Docker, and two base images | Always | `node` and `caddy`, pulled from Docker Hub the first time you build. Both are pinned by tag in the Dockerfiles and `compose.yaml`. |
| The npm registry | At build time | The workspace installs with `--frozen-lockfile`, so a build resolves exactly the tree `pnpm-lock.yaml` names. Nothing is fetched at run time. |
| A machine | Always | Your Mac, or a VPS you rent. A VPS provider can read the disk it gives you; the database on it holds request history and the phone's credential hash, and no wallet key. |
| A domain name, and DNS | Only to go public | You buy it from a registrar and point a record at the host. |
| A certificate authority | Only to go public | Caddy's default is Let's Encrypt, with ZeroSSL as its fallback. It creates an account tied to `ACME_EMAIL` and answers a challenge on port 80. |
| A Solana RPC endpoint | Only for transfers | `SOLANA_RPC_URL`. Public endpoints are rate-limited; most people end up with a provider and an API key, and that key is somebody's account. **Without it the sidecar serves no transfer tool at all** — that is the default, and message signing needs no endpoint. |
| An OAuth authorization server | Only for a hosted MCP client | A product you run (Keycloak and its kind) or sign up for. [`docs/integrations/claude.md`](../integrations/claude.md) says what it must support. Hermes and your own agents need none of this. |
| A Firebase project | Only for push wake-ups | `FCM_PROJECT_ID`, plus Google credentials in the deployment's environment ([`firebase.md`](firebase.md)). Everything works without it; the phone simply syncs when it is opened, refreshed, or on its periodic job. |

The two that carry a secret are the RPC endpoint (its URL can contain an API key, which is why the
sidecar never logs it) and the Firebase credentials (which never go in `.env`). Everything else is
a name, an address, or a port.

## Deploy it on your own machine

A Mac or a Linux desktop, reached over loopback. Nine steps from a clean checkout to a request
waiting on the phone, and none of them moves any money.

1. **Get the checkout.**

   ```sh
   git clone https://github.com/BrRenat/SeekerAgentWallet.git
   cd SeekerAgentWallet/gateway
   ```

2. **Make the deployment's settings.**

   ```sh
   cp .env.example .env
   openssl rand -hex 32   # MCP_TOKEN
   openssl rand -hex 32   # PHONE_TOKEN
   ```

   Put those two values in `.env`. `MCP_TOKEN` is what an agent authenticates with; `PHONE_TOKEN`
   belongs to the Stage 1 live diagnostic. The sidecar refuses to start while either still holds
   its placeholder, is shorter than 32 characters, or matches the other. Everything else in the
   file is documented in place and can stay as it is for a first run.

3. **Build and start.**

   ```sh
   docker compose up -d --build
   ```

   The first build compiles the workspace and takes a few minutes; later builds reuse the
   dependency layer.

4. **Check it came up.**

   ```sh
   docker compose ps
   curl -fsS http://127.0.0.1:8080/healthz
   ```

   `docker compose ps` shows `sidecar` and `gateway`, both healthy, and nothing else. `/healthz`
   answers `{"status":"ok"}`: it is the one unauthenticated route, and everything else needs a
   credential.

5. **Print a pairing code.**

   ```sh
   docker compose exec sidecar node mcp-server/dist/cli.js pair
   ```

   It prints a QR code in the terminal and the same code as text. It is one use, it lasts ten
   minutes by default, and it is the one place this system ever shows a token, because showing it
   is how pairing works.

6. **Pair the phone.** On the Seeker, open Seeker Agent Connect, go to **Connections → Add
   connection**, and scan the QR. [`pairing.md`](pairing.md) covers what the phone stores and what
   to do if the code expires first. On your own machine the code carries a loopback URL, so the
   phone reaches the sidecar over `adb reverse tcp:8080 tcp:8080`
   ([`macbook-seeker-quickstart.md`](macbook-seeker-quickstart.md)); a phone on another network
   needs [the public configuration](#going-public-tls-dns-and-ports) instead.

7. **Check the pairing from the operator's side.**

   ```sh
   docker compose exec sidecar node mcp-server/dist/cli.js pair status
   ```

8. **Point an agent at it.** `http://127.0.0.1:8080/mcp`, with `MCP_TOKEN` as a bearer token. See
   [Connect an agent](#connect-an-agent).

9. **Make one first request, which moves no money.**

   ```sh
   docker compose run --rm test-agent capabilities
   docker compose run --rm test-agent sign "first request" --wait --for 600
   ```

   `capabilities` only reads: it prints what this sidecar actually serves. `sign` asks the owner's
   wallet to sign a short message — a real durable request, answered by hand on the phone, that
   moves nothing and touches no chain. It needs a wallet connected in the app
   ([`wallet-setup.md`](wallet-setup.md)); without one it exits 9 and says so. `--wait` keeps
   reading until the owner answers or ten minutes pass, and giving up changes nothing.

Stop the stack with `docker compose down`. Your requests and pairing credentials stay on the
`sidecar-data` volume. `docker compose down -v` deletes that volume, and with it the paired phone
and every stored request — see [Deleting things on purpose](#deleting-things-on-purpose).

## What is running, and what is reachable

Two containers, and one published port.

| | |
| --- | --- |
| **sidecar** | The Node server. Listens on `127.0.0.1:8080` **inside its own network namespace**. |
| **gateway** | Caddy, listening on `:8081` in that same namespace, passing requests to the sidecar. |

The gateway joins the sidecar's network namespace (`network_mode: "service:sidecar"` in `compose.yaml`). That is what lets the sidecar keep the loopback-only bind it has had since Stage 1 while still being reachable by something. The consequence is worth stating plainly: **the sidecar's port is not reachable from your host, and not reachable from any other container** — not merely unmapped, but on a loopback interface nothing else shares. The only way in is the gateway.

Because the namespace belongs to the sidecar service, the published port is declared there too. `GATEWAY_PORT` (default `8080`) is the host port, and it forwards to the gateway's `8081`, never to the sidecar's `8080`.

`GATEWAY_BIND` defaults to `127.0.0.1`, so the port stays on the machine itself. That is the right setting for a Mac, and for a VPS you reach over a VPN or an SSH tunnel. Leave it there: this configuration speaks plain HTTP, and plain HTTP belongs on a loopback address. To put the stack on the internet, use the HTTPS configuration below rather than opening this port to the world.

## Reaching it from somewhere else

The sidecar checks the `Host` and `Origin` headers on `/mcp` against loopback, as a defence against DNS rebinding. The gateway passes those headers through unchanged rather than rewriting them, so the check stays a real check. When an agent reaches the stack by a hostname, name that hostname:

```sh
MCP_ALLOWED_HOSTS=vault.example.com
```

Comma-separated, no scheme and no port. Leave it empty while everything arrives over loopback.

For the phone, set `SIDECAR_PUBLIC_URL` to the URL a pairing code should carry. It has to be `https://`, or `http://` on a loopback host for `adb reverse`. A phone on another network needs a trusted TLS endpoint in front, which is what the next section sets up; [`pairing.md`](pairing.md) covers pairing itself.

## Going public: TLS, DNS, and ports

The internet-facing stack is the same `compose.yaml` plus an overlay, run as a separate command:

```sh
docker compose -f compose.yaml -f compose.public.yaml up -d --build
```

Two files, two commands, and no switch that could be left in the wrong position: plain HTTP cannot become the public default by forgetting something. The overlay mounts [`Caddyfile.public`](../../gateway/Caddyfile.public) instead of [`Caddyfile`](../../gateway/Caddyfile), publishes ports 80 and 443, and derives the two settings a public deployment must not get wrong from the domain itself.

### What you need before the first start

- **A domain name you control**, with an `A` record — and an `AAAA` record if the host has IPv6 — pointing at this host. The certificate authority resolves that name and connects back to it, so it has to be right before Caddy asks for a certificate, not after.
- **Ports 80 and 443 reachable from the internet.** 443 serves HTTPS. 80 answers the certificate authority's HTTP challenge and redirects anything that arrives in plaintext. A firewall, a cloud security group, or a router that swallows either one means no certificate. Nothing else needs to be open.
- **A contact address** for the expiry and problem notices the certificate authority sends.

Put the first and the last in `gateway/.env`:

```dotenv
GATEWAY_DOMAIN=vault.example.com
ACME_EMAIL=ops@example.com
```

The overlay refuses to start without either, and names the one that is missing. From `GATEWAY_DOMAIN` it also sets `MCP_ALLOWED_HOSTS` — the `Host` header `/mcp` will accept — and `SIDECAR_PUBLIC_URL`, the HTTPS URL a pairing code carries. Both are things a public deployment has to get right and neither has to be typed twice.

Caddy obtains the certificate on the first start and renews it on its own afterwards; nothing needs to be scheduled. [Automatic HTTPS](https://caddyserver.com/docs/automatic-https) describes what it does.

### Certificate storage

The account key and the certificates live on the `gateway-data` volume, under `/data/caddy`. **Keep that volume.** `docker compose down` keeps it and `docker compose down -v` deletes it; a deployment that keeps losing it asks for a new certificate every time it starts, and a certificate authority's rate limits will eventually refuse. A restart that keeps the volume reuses the certificate it already has.

### What is public, and what is not

| Path | On the internet | Authenticated by |
| --- | --- | --- |
| `/mcp` | yes | `MCP_TOKEN`, or an OAuth access token when the OAuth profile is on |
| `/.well-known/oauth-protected-resource` | yes, and only while the OAuth profile is on | nothing; a client reads it before it has a credential |
| `/seekervault.request.v1.PairingService/*` | yes | a one-use pairing token |
| `/seekervault.request.v1.RequestService/*` | yes | the paired phone's own credential |
| `/seekervault.update.v1.UpdateService/*` | yes, but not served here — see below | the paired phone's own credential |
| `/seekervault.live.v1.LiveCommandService/*` | **no** | the Stage 1 diagnostic's development token |
| `/healthz` | **no** | nothing; it says only `{"status":"ok"}` |
| anything else | **no** | — |

Each of those keeps its own credential. The gateway adds no authentication and removes none: it never inserts an `Authorization` header and never strips one, so the boundaries in the [role matrix](../protocol.md#roles) are still the sidecar's own, exactly as they are without a gateway. `Host` reaches the sidecar unchanged too, which is what keeps its DNS-rebinding check a real check.

Everything in the last four rows is refused at the gateway and never forwarded. `/healthz` moves to an endpoint that binds the network namespace's loopback address, so it cannot be published to the host at all — the health check and the test agent reach it, nothing outside does:

```sh
docker compose exec gateway wget -qO- http://127.0.0.1:8081/healthz
```

The `GATEWAY_PORT` mapping from the development configuration is still declared in this mode and is simply unused; nothing listens behind it.

### Live updates are not carried by this gateway

The phone's live update stream is gRPC over HTTP/2 end to end, and it terminates at the sidecar's *own* TLS listener ([production update listener](../development/mcp-server.md#production-update-listener)). Behind a gateway that terminates TLS the sidecar speaks HTTP/1.1, so no update endpoint is configured — and nothing is advertised that this endpoint could not deliver: pairing simply omits the update capability, the phone never tries to open a stream, and its manual refresh and background synchronization keep working.

If you want the live stream, give the sidecar a publicly trusted PEM identity of its own as the sidecar guide describes, and put only a pass-through or HTTP/2-preserving proxy in front of it. An HTTP/1.1 reverse proxy is not a fallback transport for that stream, and `SIDECAR_UPDATE_PORT` is cleartext HTTP/2 for `adb reverse` on one machine — never for a public deployment. [`deploy/server/`](../../deploy/server/README.md) is that deployment, ready to run: the sidecar alone on its own TLS listener, with `tailscale cert` for the certificate and Tailscale Funnel's TCP mode in front.

### Pair the phone over HTTPS

With the stack up and the certificate issued, print a pairing code from inside the sidecar container:

```sh
docker compose exec sidecar node mcp-server/dist/cli.js pair
```

The code carries `https://<your domain>`, because the overlay set `SIDECAR_PUBLIC_URL` from the domain. Scan it from **Connections → Add connection**. [`pairing.md`](pairing.md) covers the rest, including `status` and `revoke`.

### A hosted MCP client: the optional OAuth profile

A hosted client — Claude's custom connectors are the one this was written for — runs on somebody
else's machine, and pasting `MCP_TOKEN` into it hands that product a secret everything else uses.
The third overlay replaces it with OAuth: a person authorizes the client at an authorization
server you choose, and the sidecar accepts the short-lived token it was issued.

```sh
docker compose -f compose.yaml -f compose.public.yaml -f compose.oauth.yaml up -d --build
```

It is optional, and off by default. Running Hermes or an agent of your own needs none of it.

Nothing in this repository issues a token: the sidecar publishes the discovery document and
validates what arrives, and the authorization server is a product you already run or sign up for.
[`docs/integrations/claude.md`](../integrations/claude.md) is the whole setup — what that server
has to support, the four values to write down, how to check it with `curl` before involving
Claude, and what each refusal means. While the profile is on, `MCP_TOKEN` no longer opens `/mcp`
under the public name; it still opens the stack's own private endpoint, which is what the health
check and the test agent use.

### Never work around a certificate warning

If the phone or an agent refuses the certificate, the certificate is the problem — the refusal is the check working. Do not reach for a self-signed certificate, a private CA, a pinning exception, or a "trust this anyway" setting: release builds of the app do not offer one, and the way to make a phone accept an untrusted certificate is to weaken exactly the check that makes this endpoint safe to expose. Fix the name, the DNS record, or the port instead, and let Caddy issue a real certificate.


## Deploy it on a Linux VPS

The same stack, on a machine on the internet, answering for a domain you own. It differs from the
walkthrough above in three places: the overlay, the two settings the domain provides, and the fact
that a mistake here is reachable by everyone rather than by you.

1. **Prepare the host.** Install Docker Engine with the Compose plugin, and `git`. Nothing else is
   needed on the host: no Node, no pnpm, no build tooling.

2. **Open exactly two ports.** 80 and 443, in the provider's firewall or security group and in the
   host's own (`ufw allow 80,443/tcp`, or its equivalent). Port 80 answers the certificate
   authority's challenge and redirects; 443 serves everything. **Do not open 8080**: that is the
   local-development port, it speaks plain HTTP, and the public configuration does not use it.

3. **Point the domain at the host**, with an `A` record — and an `AAAA` record if the host has
   IPv6. Check it from somewhere else before going on: `dig +short vault.example.com` must return
   this host's address. The certificate authority resolves that name and connects back to it.

4. **Get the checkout and the settings.**

   ```sh
   git clone https://github.com/BrRenat/SeekerAgentWallet.git
   cd SeekerAgentWallet/gateway
   cp .env.example .env
   openssl rand -hex 32   # MCP_TOKEN
   openssl rand -hex 32   # PHONE_TOKEN
   ```

   In `.env`, set those two, and the two the public overlay requires:

   ```dotenv
   GATEWAY_DOMAIN=vault.example.com
   ACME_EMAIL=ops@example.com
   ```

   `MCP_ALLOWED_HOSTS` and `SIDECAR_PUBLIC_URL` are derived from the domain by the overlay. Do not
   set them by hand.

5. **Start it with the public overlay.**

   ```sh
   docker compose -f compose.yaml -f compose.public.yaml up -d --build
   ```

   This is the command that makes it public, and it is a different command from the one above on
   purpose.

6. **Watch the certificate arrive.**

   ```sh
   docker compose logs -f gateway
   ```

   Caddy asks, answers the challenge, and logs `certificate obtained successfully`. From your own
   machine, `curl -fsS https://vault.example.com/healthz` should **fail to connect**: `/healthz` is
   deliberately not public. What should work is the endpoint an agent uses, which answers `401`
   without a credential:

   ```sh
   curl -si -X POST https://vault.example.com/mcp | head -1
   ```

7. **Check the stack from inside**, where the private endpoint lives:

   ```sh
   docker compose exec gateway wget -qO- http://127.0.0.1:8081/healthz
   ```

8. **Pair the phone over HTTPS.** Print a code, and scan it from the app:

   ```sh
   docker compose exec sidecar node mcp-server/dist/cli.js pair
   ```

   The code carries `https://vault.example.com`, because the overlay set `SIDECAR_PUBLIC_URL` from
   the domain. The phone can now reach this deployment from anywhere, over a certificate it trusts
   on its own — see [Pair the phone over HTTPS](#pair-the-phone-over-https).

9. **Point an agent at `https://vault.example.com/mcp`** — see [Connect an agent](#connect-an-agent)
   — and make the same first request as step 9 above. It still moves no money, and it still waits
   for the owner's hand on their own wallet.

Two things that are worth doing on a VPS and are nobody else's job: keep the host patched, and keep
a backup of the database somewhere that is not the same disk ([Backing up and
restoring](#backing-up-and-restoring)).

## Deploy it with the live update stream

The stack above terminates TLS in front of the sidecar, so it does not carry the phone's update
stream. [`deploy/server/`](../../deploy/server/README.md) is the layout that
does: the sidecar alone, with a certificate of its own, bound to the server's loopback and reached
through a raw TCP forward — Tailscale Funnel's `--tcp` mode, with `tailscale cert` providing the
certificate for the node's name. The README is the numbered guide, including push (FCM) on the
server. What it gives up is the gateway's route filter: every endpoint the sidecar serves is on
that origin, each behind its own token.

## Connect an agent

There are two ways in, and a deployment serves one of them at a time on its public name.

**Hermes, or any agent you run yourself — the default.** It sends `MCP_TOKEN` as a bearer token,
and needs nothing else: no authorization server, no account anywhere.
[`examples/hermes.config.yaml`](../../examples/hermes.config.yaml) is the entry for a stack on the
same machine, [`examples/hermes.config.hosted.yaml`](../../examples/hermes.config.hosted.yaml) the
one for a deployment on its own domain, and
[`docs/integrations/hermes.md`](../integrations/hermes.md#9-hermes-against-the-packaged-stack)
walks through both. Merge the one entry into your existing `~/.hermes/config.yaml`; nothing here
asks you to replace that file.

**A hosted client such as Claude — optional, and off unless you turn it on.** A hosted product
cannot be given `MCP_TOKEN`, so it authorizes a person at an authorization server you choose, and
the sidecar validates the access token it was issued. That is the third overlay, and
[`docs/integrations/claude.md`](../integrations/claude.md) is the whole setup.

While the OAuth profile is on, `MCP_TOKEN` no longer opens `/mcp` under the public name. That is
what makes them alternatives rather than two doors: pick the one your agent can actually use. Both
reach exactly the same tools, and neither can answer a request — that is still the owner's, by
hand, on their own phone.

### Running with no agent endpoint at all

MCP is one way an agent reaches this sidecar, not what the sidecar is (SEE-87,
[`docs/wiki/mcp-adapter.md`](../wiki/mcp-adapter.md)). Setting `MCP_ENABLED=false` in
`gateway/.env` serves no `/mcp`:

```bash
# in gateway/.env
MCP_ENABLED=false
```

`/mcp` then answers 404, and so do both OAuth metadata paths — not served rather than locked,
because a challenge would suggest some credential would open one. No MCP setting is required: a
leftover `MCP_TOKEN`, `MCP_ALLOWED_HOSTS` or `MCP_DEMO_TOOLS` is ignored and named in the startup
log, so you needn't delete a token you may want back. An OAuth profile is refused outright, because
it would advertise authorization for an endpoint this deployment does not serve.

Everything else is exactly the same: pairing and its one-use code, the phone's credential and what
it may reach, stored requests and their identity, production updates, push, `/healthz`, and the
rule that the wallet is asked only after the owner approves by hand. The test-agent container is an
MCP client, so it has nothing to talk to in this mode — and in this stage nothing else creates
requests, so the phone sees an empty inbox. Leave the setting alone unless you are deliberately
running the core without an agent endpoint.

## The test agent

The test agent is a CLI. It is in the `agent` Compose profile, which means `docker compose up` never starts it — a service in a profile runs only when that profile is named. That is deliberate: no ordinary start of this stack can create a request or reach a wallet.

Run it a command at a time:

```sh
docker compose run --rm test-agent tools      # list the MCP tools the sidecar serves
docker compose run --rm test-agent address    # the wallet the owner connected, if any
```

Both only read. It reaches the sidecar through the gateway, the way an outside agent does, rather than going around it.

The commands that create a request — `ack`, `sign`, `transfer` — are deliberate acts, and each one puts something on the owner's phone to approve. `transfer` additionally needs `SOLANA_RPC_URL` set and requires `--wallet` and `--network` explicitly; it guesses neither. Without `SOLANA_RPC_URL` the sidecar serves no transfer tool at all, which is the default. [`test-agent/README.md`](../../test-agent/README.md) documents every command and its exit codes.

## Persistence

Requests, the paired phone's credential, and the server ID live in SQLite on the `sidecar-data` volume, at `/data/sidecar.db`. Nothing durable is written into the container's own filesystem, which the sidecar's account cannot write to anyway.

To check it for yourself: create a pending request, restart, and read it back.

```sh
docker compose run --rm test-agent ack "restart check"   # prints a request id; needs MCP_DEMO_TOOLS=true
docker compose restart sidecar
docker compose run --rm test-agent get <id>              # still PENDING
```

`MCP_DEMO_TOOLS=true` is what serves `vault_request_ack`, and it is for development only. It reaches the test agent too, which needs it — or `--demo` — before it will run either of its two diagnostics at all. A deployment leaves it `false` and uses a real request instead, such as the `sign` in step 9.

Copying that file somewhere safe is [Backing up and restoring](#backing-up-and-restoring); what a copy of it can and cannot bring back is [What recovery can and cannot do](#what-recovery-can-and-cannot-do).

## Operating it

### Logs

```sh
docker compose logs -f sidecar
docker compose logs -f gateway
```

The sidecar prints its configuration summary at startup — which tools it serves, whether an RPC
endpoint is configured, whether OAuth is on, where the database is and its schema version — then a
line per lifecycle event. The gateway prints one line per request.

What never appears in either, by design: a token, a phone credential, a pairing code, an access
token, the text of a command or a note, the `SOLANA_RPC_URL` value (it can carry an API key), or an
FCM target. A refusal names the reason, never the credential. Caddy redacts `Authorization` from
its access log, and the option that would stop it doing so is deliberately absent from both
Caddyfiles.

Log rotation belongs to Docker, not to this stack: set `max-size` and `max-file` on the daemon's
logging driver if these are long-running containers.

### Rotating credentials

| What | How | What it breaks |
| --- | --- | --- |
| `MCP_TOKEN` | Change it in `gateway/.env`, then `docker compose up -d` | Every agent, at once. Update each one's configuration. The old value stops working the moment the container restarts. |
| `PHONE_TOKEN` | The same | Only the Stage 1 live diagnostic. |
| The phone's credential | It cannot be rotated in place: [revoke and pair again](#revoking-the-phone-and-pairing-again) | That connection, and its pending requests. |
| A pairing code | Nothing to do — it is one use and expires in ten minutes | Nothing. |
| A Solana RPC key | Change `SOLANA_RPC_URL`, then `docker compose up -d` | Nothing stored; preparation uses the new endpoint from then on. |
| An OAuth client | At the authorization server, not here | Only that client. The sidecar holds no client credential to rotate. |

Compose recreates a container when its environment changes, so `up -d` is enough; there is no need
to `down` first, and no need to rebuild for a settings change.

### Revoking the phone, and pairing again

```sh
docker compose exec sidecar node mcp-server/dist/cli.js pair status   # which phone is paired
docker compose exec sidecar node mcp-server/dist/cli.js pair revoke   # revoke it
docker compose exec sidecar node mcp-server/dist/cli.js pair          # a new code
```

Revoking cancels that connection's pending requests and stops its credential working at once. Do
it when the phone is lost, sold, or replaced. Then print a new code and scan it from the app's
**Connections → Add connection**, and remove the stale connection on the phone.

One sidecar has one active phone: pairing a new one revokes the previous connection rather than
running two ([`docs/security.md`](../security.md#one-active-phone-per-sidecar)).

### Updating

```sh
git pull
docker compose up -d --build
```

Read [the changelog](../changelog/) first. The images rebuild from the new checkout, the containers
are replaced, and the volumes stay — which is where your requests, your pairing, and your
certificate live. Database migrations run when the sidecar opens the database, before it listens.

Going **backwards** is the case to know about: a database that a newer sidecar has already migrated
is refused by an older one, with a message naming both versions. That is deliberate — guessing at a
schema it does not know would be worse. Restore the backup you took before updating.

### Migrations

Migrations run at startup, in order, each in its own transaction with its version number, so an
interrupted update leaves the database at the last complete version rather than half-way through
one. The first startup line names the schema version in force. Nothing has to be run by hand, and
there is no separate migration command to forget.

### Backing up and restoring

The whole of the sidecar's durable state is one SQLite file on the `sidecar-data` volume, at
`/data/sidecar.db`. It holds pending and answered requests with their history, the phone's
connection and the **hash** of its credential, the server ID, the FCM target if one is registered,
and the bookkeeping the phone's sync uses. It holds **no wallet key, no private key of any kind,
and nothing the phone keeps for itself**.

Back it up while the stack runs — one consistent file, with no write-ahead log to reason about:

```sh
docker compose exec sidecar node -e "const{DatabaseSync}=require('node:sqlite');const d=new DatabaseSync('/data/sidecar.db');d.exec(\"VACUUM INTO '/data/backup.db'\");d.close()"
docker compose cp sidecar:/data/backup.db ./sidecar-$(date +%F).db
docker compose exec sidecar rm /data/backup.db
```

Or take it cold. A clean stop closes the database, which leaves no write-ahead log beside the
file, so the file on its own is the whole backup:

```sh
docker compose stop sidecar
docker compose cp sidecar:/data/sidecar.db ./sidecar-$(date +%F).db
docker compose start sidecar
```

Restoring is the direction that needs care about **ownership**. The sidecar runs as uid 10001, and
`/data` belongs to that account; a file copied in as root is a file the sidecar cannot write, and
SQLite needs to write even to read a database with a journal. So restore through the volume, and
set the owner explicitly:

```sh
docker compose down
docker run --rm -v seeker-agent-wallet_sidecar-data:/data -v "$PWD":/backup debian:bookworm-slim \
  sh -c 'rm -f /data/sidecar.db-wal /data/sidecar.db-shm \
         && cp /backup/sidecar-2026-09-17.db /data/sidecar.db \
         && chown 10001:10001 /data/sidecar.db'
docker compose up -d
```

The volume is named after the project, which `compose.yaml` fixes as `seeker-agent-wallet`, and
`docker volume ls` confirms it. Removing the write-ahead log matters when the stack was killed
rather than stopped: a log left over from the old database must not be replayed onto the restored
one.

If you would rather use `docker compose cp`, which does not set ownership for you, put the file in
place with the sidecar stopped and then fix the owner before starting it:

```sh
docker compose stop sidecar
docker compose cp ./sidecar-2026-09-17.db sidecar:/data/sidecar.db
docker compose run --rm --user root --entrypoint chown sidecar 10001:10001 /data/sidecar.db
docker compose start sidecar
```

Either way, check the result before trusting it: `docker compose exec sidecar ls -l /data` should
show the database owned by `sidecar`, and the startup log should name a schema version rather than
a permission error.

Then check what you restored:

```sh
docker compose exec sidecar node mcp-server/dist/cli.js pair status
docker compose run --rm test-agent get <a request id you know>
```

Keep backups off this machine, and treat them as sensitive: no credential can be read out of one,
but the request history describes what the owner was asked to do and when.

`mcp-server/src/backup.test.ts` runs exactly this procedure — a hot backup, an answer recorded after
it, a restore — and asserts what the next section promises.

### Deleting things on purpose

- **`docker compose down -v`** deletes both volumes. `sidecar-data` takes every stored request and
  the paired phone with it, and the phone must be paired again. `gateway-data` takes the
  certificate and the certificate authority account key with it, so the next start asks for a new
  certificate — and a certificate authority's rate limits are real. Take a backup first, or use
  `docker compose down`, which keeps both.
- **Uninstalling the app** removes everything the phone keeps: its connections and their
  credentials, the owner's Activity records, and the rules they wrote. None of it is backed up by
  Android, on purpose, and no backup of the sidecar can bring any of it back. Revoke the connection
  first if the phone is going somewhere.
- **Deleting the checkout** loses nothing durable. The images rebuild from a fresh clone.

## What recovery can and cannot do

**No wallet key is in this stack, so nothing here can lose or recover funds.** The sidecar holds no
key, signs nothing, and has no way to send a transaction; the wallet on the phone is the owner's
and its recovery is the wallet's business, not this guide's. A backup of this stack is a backup of
*what was asked and what was answered*, and nothing else.

What lives where:

| | Where it is | What a sidecar backup does for it |
| --- | --- | --- |
| Requests, their history, and their outcomes | The sidecar's database | Restores it |
| The phone's connection, as a credential hash, and the server ID | The sidecar's database | Restores it |
| The owner's answers, their Activity records, the rules they wrote, the wallet they selected | The phone only | Nothing. The phone is the only copy, and nothing backs it up |
| The certificate and the ACME account | The `gateway-data` volume | Nothing — back that volume up separately, or let Caddy issue again |
| Wallet keys | The owner's wallet, and nowhere else | Nothing. There is nothing here to restore |

**Restoring an older backup moves the sidecar's record backwards, and that is all it does.** A
request that was answered after the backup was taken is PENDING again, because the answer is not in
the file. Nothing is re-executed: no wallet is opened, no transaction is rebuilt, nothing is
re-signed, and nothing is sent. The sidecar could not do any of those things if it wanted to.

What actually happens next is the owner's phone re-delivering the answer it still holds, which
settles the request again — the same answer, not a new one. A transfer is the case worth being
precise about: if the wallet already sent the transaction, that signature is on the chain whatever
this database says, and a status check reads the chain and settles the request from what it finds.
It never builds a replacement transaction. So a restored backup cannot make a payment happen twice;
it can only lose the record of one, and reading the request back restores the record.

Three smaller things follow from the same fact:

- **A backup from before the phone was paired** does not know that phone's credential. The phone
  gets an authentication error and has to be paired again; its own Activity records survive on the
  phone.
- **Requests whose deadline passed while the stack was down** are EXPIRED the next time anything
  reads them. Restoring does not revive a request past its expiry.
- **A backup restored onto a newer sidecar** migrates forward on the first start. The other
  direction is refused, as [Updating](#updating) describes.

## How the images are built

Both images are multi-stage, and both build from the repository root, because the pnpm workspace's lockfile and catalog resolve each package's dependencies.

- Node and pnpm are pinned to the versions `.nvmrc` and `package.json` name, and dependencies install with `--frozen-lockfile`, so a build of one commit resolves one tree.
- Dependencies install before any source is copied, so editing a source file does not re-resolve them.
- The runtime stage installs again with `--prod`, so no compiler, linter, or test tooling ships.
- Each container runs as its own unprivileged account with a fixed uid (`10001` for the sidecar, `10002` for the test agent) — outside the range a first human account on a Linux host takes, so a volume's ownership is the same everywhere. The application tree is root-owned and not writable by that account; only `/data` is.
- Both drop every Linux capability and set `no-new-privileges`.
- Node is PID 1, so it receives `SIGTERM` itself. `main.ts` cancels the in-flight live command, ends the streams, stops listening, and closes SQLite; Compose allows 30 seconds for that.

## Architectures

The images are built for the architecture of the machine that builds them, from the same Dockerfiles:

- **Apple silicon Mac** (`linux/arm64` under Docker Desktop)
- **Linux VPS** (`linux/amd64`, the common case; `linux/arm64` on an Ampere or Graviton host)
- **A server that only pulls images** (`linux/amd64` built elsewhere). `mcp-server/Dockerfile`, `broadcast/Dockerfile` and `publisher/Dockerfile` run their install and compile stages on the building machine's own platform and assemble only the runtime stage for the target, so an Apple silicon Mac builds them with `docker buildx build --platform linux/amd64` — see [`deploy/server/GUIDE.md`](../../deploy/server/GUIDE.md).

A successful `docker buildx` for a platform is not evidence that the container runs there. [`docs/testing/stage-7.md`](../testing/stage-7.md) records which runtime checks were actually performed on which machine, and which are still outstanding.

## Troubleshooting

**The stack will not start, and the log names a variable.** The sidecar validates its whole configuration before it listens and names every problem it found, without ever echoing a token. Fix them in `gateway/.env` and `docker compose up -d` again.

**`docker compose up` reports a missing `MCP_TOKEN` or `PHONE_TOKEN`.** Those two are required by `compose.yaml` itself, so the error arrives before a container starts. Copy `.env.example` to `.env` and fill them in.

**An agent gets a rejection mentioning the `Host` header.** The hostname it used is not loopback and not in `MCP_ALLOWED_HOSTS`. Add it.

**Port 8080 is already taken** — often by an MCP server started with `pnpm dev:mcp-server`. Stop that one, or set `GATEWAY_PORT` to something else.

**The gateway container keeps restarting.** It shares the sidecar's namespace, so it cannot start before the sidecar is healthy. Read `docker compose logs sidecar` first.

**The certificate never arrives.** `docker compose logs gateway` says which step failed. The usual causes are a DNS record that does not resolve to this host yet, port 80 or 443 blocked before it reaches the machine, or a `GATEWAY_DOMAIN` that does not match the record. Caddy keeps retrying, so fix the cause and leave it running. Do not put a self-signed certificate in its place.

**Port 80 or 443 is already in use.** Another web server or reverse proxy on the host has it. Stop that one, or give it the domain and let it forward to this stack; two things cannot both answer the certificate authority on port 80.

**A request gets no response at all — the connection simply closes.** That is the gateway refusing a path it does not serve, or a `Host` that is not `GATEWAY_DOMAIN`. On the internet-facing configuration only `/mcp`, pairing, and the phone's request and update services are forwarded; `/healthz` and the Stage 1 diagnostic are deliberately not. Check the URL, and check the hostname the client used.

**Claude cannot find an authorization server.** The discovery document is public by design, and the client reads it before it has any credential. Check it from outside your network: `curl -s https://<domain>/.well-known/oauth-protected-resource`. A 404 means the OAuth profile is not on — `MCP_OAUTH_ISSUER` is unset, or the third overlay was left off the command.

**Every access token is refused, and the reason names a claim.** The refusal says which check failed, in words, both in the `WWW-Authenticate` header and in `docker compose logs sidecar`. [`docs/integrations/claude.md`](../integrations/claude.md#when-it-does-not-work) has each one and what to change; a trailing slash on the issuer and an audience the authorization server never set are the two common ones.

**The test agent stopped working after turning OAuth on.** It reaches `/mcp` through the stack's private endpoint, which still takes `MCP_TOKEN`; `AGENT_MCP_URL` must be the loopback one (`http://127.0.0.1:8081/mcp`), not the public domain. Under the public name only an access token is accepted.

**The phone or an agent reports an untrusted certificate.** Read the gateway's log and fix the certificate. There is no setting here that turns the check off, and adding one would remove the only thing that makes a public endpoint safe.

[`troubleshooting.md`](troubleshooting.md) covers the development setup, and [`docs/development/mcp-server.md`](../development/mcp-server.md) the sidecar's configuration variable by variable.
