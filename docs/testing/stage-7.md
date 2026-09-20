# Stage 7 tests

Stage 7 packages what the earlier stages built for the owner's own infrastructure. SAW-034 containerizes the sidecar and the test agent and gives them a Compose stack that builds from a checkout, SAW-035 puts a TLS gateway in front of it and separates the public endpoints from the private ones ([`../guides/self-hosting.md`](../guides/self-hosting.md)), SAW-036 adds the optional OAuth profile a hosted MCP client needs ([`../integrations/claude.md`](../integrations/claude.md)), SAW-037 finishes the test agent and the Hermes integration against that stack ([`../../test-agent/README.md`](../../test-agent/README.md), [`../integrations/hermes.md`](../integrations/hermes.md)), and SAW-038 turns the whole of it into a guide somebody else can follow ([`../guides/self-hosting.md`](../guides/self-hosting.md)).

Nothing in this stage changes what the software does. An authorized client is a client that may ask; every request still waits for the owner's hand on their own wallet. The sidecar still holds no key and signs nothing, still binds loopback, and still serves no transfer tool without `SOLANA_RPC_URL`. Starting the stack creates no request, spends nothing, and launches no LLM.

## SAW-034 — the Compose stack

### What is checked without a Docker daemon

These run anywhere, and ran here.

| Check | What it establishes | Result |
| --- | --- | --- |
| `docker compose config` | The stack parses, every variable interpolates, `MCP_TOKEN` and `PHONE_TOKEN` are required before a container starts, and the resolved services are the ones intended | PASS |
| `docker compose config --services` | A default start is `sidecar` and `gateway` only | PASS |
| `docker compose --profile agent config --services` | `test-agent` appears only when its profile is named | PASS |
| The published port, in the resolved config | `127.0.0.1:8080` forwards to the gateway's `8081`. The sidecar's `8080` is published nowhere | PASS |
| `pnpm check` | Format, lint, types, and the Node test suites, the stage-boundary checks among them | PASS |
| `pnpm --filter … run build` for both packages | The compile step each image's build stage runs succeeds, and emits `mcp-server/dist/cli.js` and `test-agent/dist/main.js` | PASS |
| `node mcp-server/dist/cli.js start`, run natively with the image's environment | The image's `CMD` is a working entry point: the sidecar starts, reports its database and that no phone is paired, and listens | PASS |
| The healthcheck command from `compose.yaml`, against that process | `GET /healthz` answers `200` and `{"status":"ok"}`, and the command exits `0` | PASS |
| `SIGTERM` to that process | The shutdown `main.ts` performs on the signal a container sends: it logs the cancellation, then `stopped`, and exits `0` | PASS |
| `node test-agent/dist/main.js tools` with no configuration | The default container command acts on nothing it was not given: it names the missing variables, echoes no token, and exits `2` | PASS |

The four native checks are worth being precise about: they ran the **compiled artefacts the images contain**, with the image's environment, on the host rather than inside a container. They establish that the entry points, the healthcheck command, and the graceful-shutdown path are right. They establish nothing about the image build, the base images, the volume, or the namespace share.

The profile check is the one that matters for the stage's promise. A service in a Compose profile runs only when that profile is named, so no ordinary `docker compose up` can start the test agent, and nothing that could create a request or reach a wallet is in the default set.

### What needs a Docker daemon, and is NOT RUN

The environment this task was implemented in had no reachable Docker daemon. Docker Desktop was running, and the client was installed, but its socket belongs to a different macOS user account than the one the session ran as, and that account's `~/.docker` is not readable. There is no other container runtime on the machine. A build was therefore never attempted, and no container has run.

Running these needs either a shell under the account that owns Docker Desktop, or access to its socket granted to the account running the checks.

**These are outstanding. None of them may be recorded as passing on the strength of the checks above.**

| Check | Command | Status |
| --- | --- | --- |
| Build from a clean checkout | `docker compose build --no-cache` | NOT RUN |
| The stack starts and both containers report healthy | `docker compose up -d && docker compose ps` | NOT RUN |
| The sidecar answers through the gateway | `curl -fsS http://127.0.0.1:8080/healthz` | NOT RUN |
| The sidecar's own port is not reachable from the host | `curl -sS --max-time 5 http://127.0.0.1:8081/healthz` fails to connect | NOT RUN |
| A pending request survives a restart | `run --rm test-agent ack …`, `restart sidecar`, `run --rm test-agent get <id>` | NOT RUN |
| Graceful shutdown | `docker compose stop` leaves no "killed" in the log and the sidecar's SIGTERM line appears | NOT RUN |
| The test agent reaches the real MCP service | `docker compose run --rm test-agent tools` lists the sidecar's tools | NOT RUN |
| Nothing acts on a normal start | `docker compose up -d`, then no request exists and no tool was called | NOT RUN |

### Architectures

The ticket asks for both target architectures to be documented and tested, and is explicit that a `buildx` success is not proof a container ran natively. Both are therefore recorded separately, and both are outstanding.

| Target | What is needed | Status |
| --- | --- | --- |
| Apple silicon Mac, `linux/arm64` | The checks above, run on the Mac under Docker Desktop | NOT RUN |
| Linux VPS, `linux/amd64` | The checks above, run on the VPS itself — not cross-built from the Mac | NOT RUN |
| Linux VPS, `linux/arm64` (Ampere, Graviton) | The same, if that is the host in use | NOT RUN |

The images pin Node and pnpm and install with `--frozen-lockfile`, and nothing in either Dockerfile is architecture-specific, so both are expected to build. Expected is not tested, and this table stays NOT RUN until someone runs it on the metal and writes down what happened.

### Record

| Date | Machine | What was run | Result |
| --- | --- | --- | --- |
| 2026-09-17 | Implementation environment, no Docker daemon | The ten daemon-free checks above | PASS |
| — | Apple silicon Mac | The runtime checks | NOT RUN |
| — | Linux VPS | The runtime checks | NOT RUN |

## SAW-035 — the TLS gateway and the public/private split

### How these were run

No Docker daemon was reachable here either (see above), so the gateway was run the same way the native SAW-034 checks were: the **configurations this repository ships**, served by the Caddy release that matches the pinned image — `caddy 2.10.2`, the version behind `caddy:2.10-alpine` — in front of a real sidecar process started with `node mcp-server/dist/cli.js start`. That exercises the routing, the authentication boundaries, the limits, the logging, and TLS termination itself. It exercises nothing about the image, the volume, or the namespace share.

Two accommodations were needed for the internet-facing configuration, and nothing else in the file changed: `GATEWAY_DOMAIN` was `localhost:9443`, so Caddy issued the certificate from its own local authority instead of asking a public one on a privileged port, and the catch-all site's address moved from `:443` to `:9443` to match. `skip_install_trust` was added so the test did not install a root certificate into the machine's trust store. Certificate issuance from a public authority is therefore still NOT RUN; TLS termination, the certificate's verification by a client, and every route below were run.

### Configuration checks

| Check | What it establishes | Result |
| --- | --- | --- |
| `caddy validate` on `gateway/Caddyfile` | The development configuration is valid for the pinned Caddy version | PASS |
| `caddy validate` on `gateway/Caddyfile.public` | The internet-facing one is valid, and Caddy reports it will serve HTTPS on 443 and add the HTTP→HTTPS redirect | PASS |
| `docker compose config` with the overlay | The overlay replaces the mounted Caddyfile, publishes 80 and 443, and sets `MCP_ALLOWED_HOSTS` and `SIDECAR_PUBLIC_URL` from `GATEWAY_DOMAIN` | PASS |
| The same without `GATEWAY_DOMAIN` | Compose refuses before a container starts, naming the variable and where to set it | PASS |
| The registry, for the pinned base image | `caddy:2.10-alpine` exists (Docker Hub's tag list). The image still has not been pulled | PASS |

### The development configuration, in front of a real sidecar

| Check | Result |
| --- | --- |
| `GET /healthz` through the gateway | `200`, `{"status":"ok"}` |
| `POST /mcp` with no token | `401` with `WWW-Authenticate: Bearer` |
| `POST /mcp` with `MCP_TOKEN` | `200`, `text/event-stream`, `Mcp-Session-Id` passed back, a real `initialize` result |
| `node test-agent/dist/main.js tools` through the gateway | The sidecar's full tool list |
| The same with a wrong token | Refused: "the sidecar rejected MCP_TOKEN (HTTP 401)" |
| A Connect unary call with no credential | `401` from the sidecar |
| A Connect server-streaming call | Byte-for-byte the same response through the gateway as straight at the sidecar |
| `Host: evil.example.com` on `/mcp` | `403` from the sidecar — the header arrives unchanged and its DNS-rebinding check still runs |
| `Origin: https://evil.example.com` on `/mcp` | `403`, the same way |
| An unknown path, and an unknown Connect service | The connection is closed; nothing is forwarded |
| A 70 KB body | `413` at the gateway, one hop before the sidecar |
| Caddy's admin API on port 2019 | Refused: `admin off` opens no such port |
| The access log | One line per request, `"Authorization": ["REDACTED"]`, the token absent from the whole log, `/healthz` skipped |

### The internet-facing configuration

| Check | Result |
| --- | --- |
| `POST /mcp` with the token, over TLS | `200` over HTTP/2, `ssl_verify_result=0` — a verified chain, not an ignored one |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |
| The `Server` header | Removed |
| `POST /mcp` with no token | `401` |
| Pairing and `RequestService` with no credential | `401` each — public, and still authenticated by the sidecar |
| `UpdateService` | `404` from the sidecar: routed, and deliberately not served behind a TLS-terminating gateway |
| The Stage 1 `LiveCommandService` diagnostic | The connection is closed; it never reaches the sidecar |
| `GET /healthz` on the public listener | The connection is closed |
| An unknown path | The connection is closed |
| A valid TLS connection carrying a `Host` this deployment does not serve | The connection is closed, rather than Caddy's default empty `200` |
| A 70 KB body, on `/mcp` and on a Connect route | `413` at the gateway |
| The private endpoint, from inside the network namespace | `/healthz` `200`, `/mcp` `200`, everything else closed |
| The private endpoint, from the machine's LAN address | Refused: it binds the namespace's loopback only |
| A client that does not trust the issuing authority | Refuses the certificate (`curl` exit `60`). Nothing anywhere tells anyone to override that |
| Stopping and restarting the gateway | Requests fail while it is down and succeed afterwards; the stored certificate is reused, not re-issued |
| The access log | `"Authorization": ["REDACTED"]`, the token absent |

### What is NOT RUN

| Check | What it needs | Status |
| --- | --- | --- |
| A certificate from a public authority, and its renewal | A real domain, a DNS record, and ports 80 and 443 open | NOT RUN |
| The HTTP→HTTPS redirect on ports 80 and 443 | The same, and privileged ports | NOT RUN |
| The failure modes of a missing DNS record or a blocked port 80 | The same. Deliberately not exercised against a live certificate authority from here | NOT RUN |
| Pairing the physical Seeker over the HTTPS endpoint, and an authenticated request from it | A public endpoint and the phone | NOT RUN |
| The whole stack in containers, with the gateway's own image | A Docker daemon | NOT RUN |

The pairing code's own side of that was checked: `node mcp-server/dist/cli.js pair`, run with the environment the overlay sets, prints a `seekervault://pair?…` code carrying `https://<domain>`. What has not been done is giving that code to a phone.

### Record

| Date | Machine | What was run | Result |
| --- | --- | --- | --- |
| 2026-09-17 | Implementation environment, no Docker daemon, Caddy 2.10.2 in front of a real sidecar | Both configurations, every check in the three tables above | PASS |
| — | A host with a public domain | Public certificate issuance, renewal, and the redirect | NOT RUN |
| — | Physical Seeker | Pairing and an authenticated request over HTTPS | NOT RUN |

## SAW-036 — the OAuth profile for a hosted MCP client

### How these were run

Two layers, and they are worth keeping apart.

The first is automated and repeatable: `mcp-server/src/oauth.test.ts` runs a real sidecar against a
fake **authorization server** (`mcp-server/src/testing/authorization-server.ts`) that publishes
discovery metadata and a JWKS and mints real signed tokens. Those are the resource server's own
checks against real signatures, and `pnpm check` and CI run them on every change. They need no
account anywhere.

The second is the same hand-run arrangement SAW-035 used: the **configurations this repository
ships**, served by `caddy 2.10.2` — the version behind `caddy:2.10-alpine` — in front of a real
sidecar started from `mcp-server/src/cli.ts`, with `MCP_OAUTH_ISSUER` pointed at that same fake
authorization server. The accommodations were SAW-035's, and nothing else in the files changed:
`GATEWAY_DOMAIN` was `vault.localhost:9443` so Caddy issued from its own local authority, the
catch-all site moved from `:443` to `:9443` to match, and `skip_install_trust` kept the test out of
the machine's trust store.

What neither layer is: a hosted Claude client. No commercial authorization server was involved
either. The ticket is explicit that a metadata endpoint returning JSON is not a passed integration
test, and the table at the end of this section says so.

### Configuration checks

| Check | What it establishes | Result |
| --- | --- | --- |
| `caddy validate` on both Caddyfiles | The discovery route is valid in the development and the internet-facing configuration | PASS |
| `caddy fmt --diff` on both | Both files are canonical | PASS |
| `docker compose config` with all three files | The OAuth overlay composes on top of the public one and sets the four `MCP_OAUTH_*` variables | PASS |
| The same without `MCP_OAUTH_ISSUER` | Compose refuses before a container starts, naming the variable and where to set it | PASS |
| `pnpm check` | Format, lint, types, and 454 sidecar tests — 24 of them added here — plus the stage-boundary checks | PASS |

### The resource server's own checks (automated)

Each of these is a test that fails if the check is removed; the audience one was confirmed by
deleting the check and watching it fail.

| Check | Result |
| --- | --- |
| A real MCP session opened with an access token, listing the sidecar's own tools | PASS |
| The protected-resource metadata document, at the root and at the path-inserted well-known URI | PASS |
| That document is absent — 404 — when no authorization server is configured | PASS |
| No token: `401` with a challenge naming the metadata document and the scope to ask for | PASS |
| A token for another resource (`aud`) | `401 invalid_token` |
| A token from another issuer | `401 invalid_token` |
| An expired token | `401 invalid_token` |
| A token signed with a key the authorization server does not publish | `401 invalid_token` |
| A token signed with a shared secret (HS256) instead of a published key | `401 invalid_token` |
| An opaque token that is not a JWT | `401 invalid_token` |
| A valid token without the required scope | `403 insufficient_scope`, with the scope named |
| `MCP_TOKEN` under the deployment's public host name, while OAuth is on | `401` |
| `MCP_TOKEN` under a loopback host — the stack's own private endpoint | `200` |
| An access token against the phone's API (`RequestService`, `PairingService`) | Refused; it is an agent's credential |
| The keys found from the issuer alone, through RFC 8414 metadata, and read once | PASS |
| The same through OpenID Connect discovery, under an issuer with a path | PASS |
| The log: the reason for each refusal, and no token value anywhere | PASS |
| The stage boundary: token validation in one file, three files know OAuth exists, nothing issues or exchanges a token | PASS |

### The shipped configurations, behind TLS

| Check | Result |
| --- | --- |
| The discovery document through the public HTTPS gateway | `200` over HTTP/2, naming the authorization server, this resource, and the scope |
| The path-inserted well-known URI, the same way | `200` |
| The discovery document on the private endpoint | The connection is closed; it is not routed there |
| `POST /mcp` with no token | `401`, `WWW-Authenticate` naming `https://<domain>/.well-known/oauth-protected-resource/mcp` |
| `POST /mcp` with an access token | `200`, a real `initialize` result from the sidecar |
| Expired, wrong audience, unknown key, opaque | `401` each, with the reason in `error_description` |
| A valid token missing the scope | `403`, `error="insufficient_scope"` |
| `MCP_TOKEN` under the public name | `401`: there is no second way in |
| `MCP_TOKEN` on the private endpoint, while OAuth is on | `200` — the health check and the `agent` profile keep working |
| `node test-agent/src/main.ts tools` through the public HTTPS endpoint, authorized only by an access token | The sidecar's full tool list: a real MCP client, not a curl probe |
| The same with an expired token | Refused: "the sidecar rejected MCP_TOKEN (HTTP 401)" |
| The access log | `"Authorization": ["REDACTED"]`; no access token and no `MCP_TOKEN` in it |

### What is NOT RUN

| Check | What it needs | Status |
| --- | --- | --- |
| A hosted Claude client: the connector added, consent approved, tools discovered, and a real request answered on the Seeker | A public domain, a real authorization server, and an account. **This is the ticket's acceptance** | NOT RUN |
| The client's product and version, and the steps as the product actually words them | The same | NOT RUN |
| Denied consent | A real consent screen; the fake authorization server has none | NOT RUN |
| Revocation at the authorization server, and the access token expiring after it | A real authorization server. What the sidecar does with an expired token is covered above | NOT RUN |
| Client ID Metadata Documents, or dynamic client registration | Both are the authorization server's side of the flow, and nothing here implements either | NOT RUN |
| Any commercial or self-hosted provider's own tokens | An account, or a Keycloak of one's own | NOT RUN |
| The profile in containers, with the gateway's own image | A Docker daemon | NOT RUN |

### Record

| Date | Machine | What was run | Result |
| --- | --- | --- | --- |
| 2026-09-17 | Implementation environment, no Docker daemon | The automated suite, and both layers of checks above | PASS |
| — | A host with a public domain and a real authorization server | A hosted Claude client's round trip | NOT RUN |

## SAW-037 — the test-agent CLI and the Hermes integration

### What changed, and what it is checked against

The CLI already drove every tool the sidecar serves. SAW-037 adds the parts a script needs — a
bounded wait, an `outcome` word to branch on, and a `swap` command that says swaps are not served —
and puts the two diagnostics behind development mode, so a client in a deployment cannot mistake an
acknowledgement for a signature or a payment.

All of it is checked by tests that run in `pnpm check` and CI, against a real sidecar process with
a Connect client standing in for the phone. Nothing here needs the Seeker, and nothing here counts
as the Seeker.

### The automated checks

| Command | What it covers | Result |
| --- | --- | --- |
| `pnpm check` | Format, lint, types, 454 sidecar tests and 36 test-agent tests | PASS |
| `pnpm test:hello` | The Stage 1 acceptance suite, 9 cases | PASS |
| `pnpm test:queue` | The Stage 2 acceptance suite, 7 cases | PASS |
| `pnpm test:transfer` | The Stage 4 acceptance suite, 7 cases | PASS |

| The CLI's own cases (`test-agent/src/cli.test.ts`) | Result |
| --- | --- |
| `wait` returns when the owner answers, and prints one JSON document | PASS |
| `wait` gives up at its deadline: exit 10, `timed_out: true`, and the request untouched afterwards | PASS |
| `wait` on a request that already ended: one read, exit 11, `outcome: "failed"` | PASS |
| `--wait` prints the `request_id` on stderr before it starts waiting | PASS |
| `--for` and `--every` outside their bounds, and `--wait` where it means nothing: exit 2, nothing on stdout | PASS |
| `hello` and `ack` without development mode: exit 2, and the message says an acknowledgement is neither a signature nor a payment | PASS |
| `--demo` runs one anyway; `MCP_DEMO_TOOLS=yes` is a configuration error | PASS |
| `swap`: exit 3, naming `vault_swap`, and nothing queued | PASS |
| `swap` without `--wallet` and `--network`: exit 2, before a session is opened | PASS |

### From the checkout, through the packaged gateway

The stack's own `gateway/Caddyfile` was served by `caddy 2.10.2` — the release behind the pinned
image — in front of a real sidecar, and the CLI was run from the checkout against that gateway, the
way the container does from inside the stack.

| Check | Result |
| --- | --- |
| `capabilities` through the gateway | The sidecar's real capabilities document |
| `swap …` | Exit 3: "the MCP server does not offer vault_swap; swaps are a later stage" |
| `hello` with no development mode | Exit 2, with the diagnostic's explanation |
| `ack` with `MCP_DEMO_TOOLS=true`, no phone paired | Exit 9, `NOT_PAIRED` — the sidecar's own refusal, unchanged |
| `wait <unknown id>` | Exit 9, `NOT_FOUND` |

### What is NOT RUN

| Check | What it needs | Status |
| --- | --- | --- |
| The CLI from its container, matching the checkout | A Docker daemon. The image builds from this checkout and runs the same code; that is an expectation, not a result | NOT RUN |
| A durable request created by **real Hermes** through the packaged stack, and answered on the Seeker | Hermes, the stack in containers, and the phone. **This is the ticket's acceptance** | NOT RUN |
| Hermes against the public HTTPS endpoint (`examples/hermes.config.hosted.yaml`) | A public domain and a real certificate | NOT RUN |
| `--wait` and `wait` with a physical Seeker answering | The phone | NOT RUN |
| A swap through any client | Stage 6. No sidecar serves `vault_swap`, which is what the command reports | NOT RUN |

Hermes's earlier results stand and are unchanged: the live diagnostic and the durable tools were
run with Hermes v0.21.1 in Stage 1 and Stage 2 ([`../integrations/hermes.md`](../integrations/hermes.md)).
What has never been run with Hermes is the packaged stack.

### Record

| Date | Machine | What was run | Result |
| --- | --- | --- | --- |
| 2026-09-17 | Implementation environment, no Docker daemon | The automated suites, and the CLI through the packaged gateway | PASS |
| — | A host running the stack, with Hermes and the Seeker | One durable request from Hermes, answered on the phone | NOT RUN |

## SAW-038 — the self-hosting and operations guide

### What was checked, and how

A guide cannot be proved correct by reading it. Two of the ticket's three checks are automated and
run in `pnpm check`; the third needs a Docker daemon and is NOT RUN.

### Backup and restore, run for real

`mcp-server/src/backup.test.ts` performs the procedure the guide gives an operator, against a real
sidecar and a real database file, in a throwaway directory. It is the ticket's second check, and it
passed.

| Check | Result |
| --- | --- |
| A hot backup (`VACUUM INTO`) taken from a second connection while the sidecar is serving | One consistent file; the sidecar keeps answering, and the copy opens at the same schema version |
| An answer recorded **after** the backup, then the backup restored | The answered request is PENDING again; the untouched one is unchanged |
| The server ID across the restore | The same. A restored backup is not a new deployment |
| What ran on the way up | Nothing. No submission, no retry, no execution in the log, and the restored sidecar has no chain endpoint at all |
| The phone re-delivering the answer it still holds | Settles the request again — the same answer, and no wallet is involved |
| A database from a newer sidecar | Refused, naming both versions and saying what to do |

### The guide against the files it describes

`mcp-server/src/self-hosting-guide.test.ts` is the ticket's third check: every name on the page is
held to the shipped files, so a rename cannot quietly leave the guide behind.

| Check | Result |
| --- | --- |
| Every `MCP_*`, `SIDECAR_*`, `GATEWAY_*`, `ACME_*`, `SOLANA_*`, `FCM_*` … setting the guide names is one the stack reads | PASS |
| Every `docker compose` command names a Compose file that exists, a service that exists, and a profile that exists | PASS |
| The ports: the published mapping, the private endpoint, and the two public ones | PASS |
| The paths: `mcp-server/dist/cli.js pair` against the image's `WORKDIR` and a real entry point, `/data/sidecar.db`, and the volume's full name | PASS |
| Every relative link on the page, and every link to one of its own headings | PASS |

Deliberately breaking the guide — an invented setting, a link to a file that is not there — fails
those checks, which is how they were confirmed to bite.

### What is NOT RUN

| Check | What it needs | Status |
| --- | --- | --- |
| Following the guide end to end on a fresh checkout, using only its prerequisites | A Docker daemon. Every command's spelling is checked above; none of them has been executed | NOT RUN |
| The restore's file ownership — that a database copied back in is writable by the sidecar's account (uid 10001) | A Docker daemon. The recipe sets the owner explicitly and says how to check it; `backup.test.ts` covers the SQLite side, not the container's file ownership | NOT RUN |
| The same on a Linux VPS, with a real domain and certificate | A VPS and a domain | NOT RUN |
| Pairing a physical Seeker by scanning the printed code | The phone | NOT RUN |
| Screenshots | There are none in the guide; it is text and commands throughout | — |

### Record

| Date | Machine | What was run | Result |
| --- | --- | --- | --- |
| 2026-09-17 | Implementation environment, no Docker daemon | The backup/restore suite and the guide-consistency suite | PASS |
| — | A fresh machine with Docker | The guide followed end to end | NOT RUN |
