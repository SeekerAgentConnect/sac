# Stage 7 tests

Stage 7 packages what the earlier stages built for the owner's own infrastructure. SAW-034 containerizes the sidecar and the test agent and gives them a Compose stack that builds from a checkout, and SAW-035 puts a TLS gateway in front of it and separates the public endpoints from the private ones ([`../guides/self-hosting.md`](../guides/self-hosting.md)).

Nothing in this stage changes what the software does. The sidecar still holds no key and signs nothing, still binds loopback, and still serves no transfer tool without `SOLANA_RPC_URL`. Starting the stack creates no request, spends nothing, and launches no LLM.

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
| `pnpm --filter … run build` for both packages | The compile step each image's build stage runs succeeds, and emits `sidecar/dist/main.js` and `test-agent/dist/main.js` | PASS |
| `node sidecar/dist/main.js`, run natively with the image's environment | The image's `CMD` is a working entry point: the sidecar starts, reports its database and that no phone is paired, and listens | PASS |
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

No Docker daemon was reachable here either (see above), so the gateway was run the same way the native SAW-034 checks were: the **configurations this repository ships**, served by the Caddy release that matches the pinned image — `caddy 2.10.2`, the version behind `caddy:2.10-alpine` — in front of a real sidecar process started from `sidecar/dist/main.js`. That exercises the routing, the authentication boundaries, the limits, the logging, and TLS termination itself. It exercises nothing about the image, the volume, or the namespace share.

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

The pairing code's own side of that was checked: `node sidecar/dist/pairing/cli.js`, run with the environment the overlay sets, prints a `seekervault://pair?…` code carrying `https://<domain>`. What has not been done is giving that code to a phone.

### Record

| Date | Machine | What was run | Result |
| --- | --- | --- | --- |
| 2026-09-17 | Implementation environment, no Docker daemon, Caddy 2.10.2 in front of a real sidecar | Both configurations, every check in the three tables above | PASS |
| — | A host with a public domain | Public certificate issuance, renewal, and the redirect | NOT RUN |
| — | Physical Seeker | Pairing and an authenticated request over HTTPS | NOT RUN |
