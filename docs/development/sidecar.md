# Sidecar

The sidecar is the self-hosted TypeScript/Node server that sits between agents and the phone. It runs both flows from [`docs/protocol.md`](../protocol.md):

- **The Stage 1 live diagnostic,** which stays in memory.
- **The durable request workflow, from Stage 2 on.** It's stored in a local SQLite database; see [storage and lifecycle](#storage-and-lifecycle).

The sidecar listens on loopback only. A phone on another network reaches it through a trusted TLS endpoint; see [`docs/security.md`](../security.md#transport-security).

## Configuration

`pnpm dev:sidecar` reads the git-ignored root `.env`; start from `.env.example`. Variables already set in the environment take precedence over `.env`.

`MCP_ALLOWED_HOSTS`, `MCP_DEMO_TOOLS`, `DATABASE_PATH`, `REQUEST_TTL_SECONDS`, `REQUEST_PENDING_LIMIT`, `SIDECAR_PUBLIC_URL`, `SIDECAR_TLS_CERT_PATH`, `SIDECAR_TLS_KEY_PATH`, `SIDECAR_UPDATE_PORT`, `PAIRING_TOKEN_TTL_SECONDS`, `SOLANA_RPC_URL`, `SOLANA_RPC_TIMEOUT_MS`, and `FCM_PROJECT_ID` are optional, and an empty one counts as unset. The others are required.

| Variable | Meaning | Rules |
| --- | --- | --- |
| `SIDECAR_HOST` | Address to listen on | `127.0.0.1`, `::1`, or `localhost`. Stage 1 never listens beyond the machine. |
| `SIDECAR_PORT` | Port to listen on | 1 to 65535; `.env.example` uses 8080 |
| `MCP_TOKEN` | Bearer token that agents send to `/mcp` | At least 32 characters, only bearer-token characters (letters, digits, and `- . _ ~ + /`), and not the placeholder |
| `PHONE_TOKEN` | Bearer token for the Stage 1 live-test screen, which only `LiveCommandService` accepts. The durable workflow uses the credential from [pairing](#pairing-a-phone) instead. | The same rules as `MCP_TOKEN`, and a different value |
| `LIVE_COMMAND_TIMEOUT_SECONDS` | How long a live command waits for the user's OK | 1 to 3600 |
| `MCP_URL` | Not read by the sidecar; the test agent (SAW-005) uses it | None |
| `MCP_ALLOWED_HOSTS` | Optional. Host names or IP addresses, comma-separated, that `/mcp` accepts in the `Host` and `Origin` headers besides loopback. It's for an agent that reaches the sidecar through a VPN address; see [Hermes over a VPN](../integrations/hermes.md#over-a-vpn-you-already-use). | No scheme, port, or wildcard |
| `MCP_DEMO_TOOLS` | Optional. `true` serves the demo tool `vault_request_ack`, which queues a wallet-free acknowledgement, for development and demos. `.env.example` sets it. | `true` or `false`; unset or empty means `false` |
| `DATABASE_PATH` | Optional. The SQLite file for durable requests | Defaults to `sidecar/data/sidecar.db`, whatever the working directory. A relative path is resolved from the directory the sidecar starts in, and a missing directory is created. |
| `REQUEST_TTL_SECONDS` | Optional. How long a request waits for the owner's decision when its agent doesn't choose | 60 to 604800; defaults to 86400 (a day) |
| `REQUEST_PENDING_LIMIT` | Optional. The most requests that can wait for the owner at once | 1 to 10000; defaults to 100 |
| `SIDECAR_PUBLIC_URL` | Optional. The URL that pairing codes carry: where the phone reaches the sidecar | `https://`, or `http://` on `127.0.0.1`, `localhost`, or `[::1]`. No user name, password, query, or fragment, and a port, if given, from 1 to 65535. Defaults to `http://<SIDECAR_HOST>:<SIDECAR_PORT>`, the development URL over `adb reverse`. For a phone on another network, use a trusted TLS endpoint; see [transport security](../security.md#transport-security). |
| `SIDECAR_TLS_CERT_PATH`, `SIDECAR_TLS_KEY_PATH` | Optional pair. PEM certificate chain and private key for the production listener | Set both or neither. With them, `SIDECAR_PUBLIC_URL` must be HTTPS. The certificate must be publicly trusted by the phone and match that URL's host. The listener negotiates HTTP/2 and HTTP/1.1. Never commit the private key. |
| `SIDECAR_UPDATE_PORT` | Optional loopback development HTTP/2 port | 1 to 65535. It starts a separate cleartext h2c listener and advertises its loopback origin. Do not expose it off-machine or combine it with the TLS paths. Use a second `adb reverse` for a physical phone. |
| `PAIRING_TOKEN_TTL_SECONDS` | Optional. How long a pairing code works | 60 to 3600; defaults to 600 (10 minutes) |
| `SOLANA_RPC_URL` | Optional. The Solana JSON-RPC endpoint transfers are prepared against (SAW-019), and the one a sent transaction's outcome is read from (SAW-022). Without it the sidecar serves no `vault_transfer`, prepares no transaction, and can confirm nothing. | An `http://` or `https://` URL. It may carry an API key, so the sidecar never logs it or puts it in an error message; only its **host** is recorded, as the endpoint a confirmed or failed transfer's word came from. |
| `SOLANA_RPC_TIMEOUT_MS` | Optional. How long one chain call may take | 1000 to 20000; defaults to 10000. A whole operation is bounded too: 20000 ms, however many calls it makes |
| `FCM_PROJECT_ID` | Optional. The Firebase/Google Cloud project for the SAW-054 Firebase Admin sender | A 6–30 character lowercase Google Cloud project ID. Empty means no Firebase Admin app or sender is constructed. Credentials come from Application Default Credentials, not this value; see the [Firebase setup guide](../guides/firebase.md). |

Generate each token with `openssl rand -hex 32`. If the configuration is invalid, the sidecar names every problem and exits with status 1. It never prints a token value.

## Start and stop

```bash
cp .env.example .env    # then replace both token placeholders
pnpm dev:sidecar
```

The expected output is:

```text
[sidecar] requests are stored in /path/to/SeekerAgentWallet/sidecar/data/sidecar.db (schema version 4); server 9fda5035-f3b4-4ec3-a68a-5e6caa02397a; no phone is paired: run pnpm pair
[sidecar] the demo tool vault_request_ack is on (MCP_DEMO_TOOLS=true)
[sidecar] FCM sender is off; FCM_PROJECT_ID is not configured
[sidecar] listening on http://127.0.0.1:8080: MCP at http://127.0.0.1:8080/mcp, phone API at http://127.0.0.1:8080/seekervault.live.v1.LiveCommandService
[sidecar] production updates are not configured
```

The server ID is created with the database and never changes. Once a phone pairs, the first line ends with `paired phone: connection <ID>` instead. Until then, agents can't create requests, and get `NOT_PAIRED`; see [pairing a phone](#pairing-a-phone).

To check that the sidecar is up, run `curl -s http://127.0.0.1:8080/healthz`, which prints `{"status":"ok"}`.

To stop it, press Ctrl+C or send SIGTERM. Stopping happens in this order:

1. The in-flight live command, if any, is cancelled, and the agent gets `CANCELLED`.
2. The Stage 1 stream and every production update stream end; update streams receive `unavailable`.
3. The optional named Firebase Admin app is deleted, the database closes, and the process exits
   within about a second.

A restart loses the in-flight live command by design, and nothing is replayed after it. Durable requests survive a restart unchanged; see [storage and lifecycle](#storage-and-lifecycle).

`pnpm build` compiles the sidecar to `sidecar/dist`. To run that build from the repository root, use `node --env-file-if-exists=.env sidecar/dist/main.js`.

## Pairing a phone

The durable workflow needs a paired phone. [`docs/security.md`](../security.md) explains the model, and [`docs/protocol.md`](../protocol.md#pairing) the format.

| Command | What it does |
| --- | --- |
| `pnpm pair` | Issues a one-use pairing code for `SIDECAR_PUBLIC_URL`, and prints it as a QR code and as a `seekervault://pair?…` URI. The code works for `PAIRING_TOKEN_TTL_SECONDS`, until a newer code voids it, or until it pairs. |
| `pnpm pair status` | Shows the paired phone: its connection ID, its device name, and when it paired |
| `pnpm pair revoke` | Revokes the paired phone's connection, and cancels its PENDING requests |

- **The commands read the same `.env` as the sidecar, and work on its database directly,** whether or not the sidecar is running.
- **Pairing a new phone revokes the one paired now.** `pnpm pair` says which phone that is.
- **The QR code is drawn in block characters, and scans on a dark terminal background.** On a light background, or if it doesn't scan, enter the URI printed under it.
- **Only `pnpm pair` prints a secret,** the code's token. `status` and `revoke` print none, and the sidecar logs none.
- **A usage or configuration error exits with status 2.** An invalid `SIDECAR_PUBLIC_URL` is reported, and no code is issued.
- **The app pairs from Add connection** ([`docs/guides/pairing.md`](../guides/pairing.md)). Any other Connect client can pair too. For example, with the token from the code:

  ```bash
  curl -s -X POST http://127.0.0.1:8080/seekervault.request.v1.PairingService/Pair \
      -H 'Content-Type: application/json' -H "Authorization: Bearer $PAIRING_TOKEN" \
      -d '{"serverUrl":"http://127.0.0.1:8080","deviceName":"curl"}'
  ```

  The answer holds `connectionId`, `phoneToken`, and `serverId`.

For example, with the token elided:

```text
$ pnpm pair
Scan this with seeker-vault on the phone to pair it with https://mac.tailnet.ts.net.
The code works once, until 15:19:50 (10 minutes).

<the QR code>

Or enter the code by hand:
seekervault://pair?v=1&url=https%3A%2F%2Fmac.tailnet.ts.net&server=9fda5035-f3b4-4ec3-a68a-5e6caa02397a&token=…

Keep the code private: whoever pairs with it first becomes the paired phone.
$ pnpm pair status
Paired phone: connection de03846e-d435-4705-b2e3-ec67da539f12 ("Seeker"), paired 2026-09-11T13:09:50.437Z.
$ pnpm pair revoke
Revoked connection de03846e-d435-4705-b2e3-ec67da539f12 ("Seeker"), paired 2026-09-11T13:09:50.437Z. Its credential no longer works, and 0 pending requests were cancelled.
```

## Endpoints

| Path | Caller | Authentication | Purpose |
| --- | --- | --- | --- |
| `GET /healthz` | Anything on the machine | None | Liveness check: `{"status":"ok"}` |
| `/mcp` | Agents | `Authorization: Bearer <MCP_TOKEN>` | MCP Streamable HTTP with sessions: the live tool `vault_display_command`, the durable tools `vault_sign_message`, `vault_get_capabilities`, `vault_get_address`, `vault_get_request`, and `vault_cancel_request`, plus `vault_request_ack` with `MCP_DEMO_TOOLS=true` |
| `/seekervault.live.v1.LiveCommandService/WatchCommands` | The live-test screen | `Authorization: Bearer <PHONE_TOKEN>` | Connect server stream of live commands |
| `/seekervault.live.v1.LiveCommandService/AcknowledgeCommand` | The live-test screen | `Authorization: Bearer <PHONE_TOKEN>` | Connect unary call that acknowledges a command |
| `/seekervault.request.v1.PairingService/Pair` | A phone that's pairing | `Authorization: Bearer <pairing token>`, from `pnpm pair` | Exchanges the pairing token for a connection and its credential |
| `/seekervault.request.v1.PairingService/GetConnectionCapabilities` | The paired phone | `Authorization: Bearer <phone credential>` | Reports the caller's optional production-update capability without requiring re-pairing (SAW-048) |
| `/seekervault.request.v1.PairingService/RevokeConnection` | The paired phone | `Authorization: Bearer <phone credential>` | Revokes the caller's own connection |
| `/seekervault.request.v1.RequestService/ListPending`, `GetRequest`, `PrepareRequest`, `SubmitResult`, `CheckStatus`, and `PublishWallet` | The paired phone | `Authorization: Bearer <phone credential>` | Connect unary calls of the durable workflow, and the wallet the owner selected (SAW-015) |
| `/seekervault.update.v1.UpdateService/Subscribe` | The paired phone, configured update origin | `Authorization: Bearer <phone credential>` | Bidirectional gRPC/HTTP2 replay and live durable request changes |
| `/seekervault.update.v1.UpdateService/Sync` | The paired phone, configured update origin | `Authorization: Bearer <phone credential>` | Unary gRPC/HTTP2 frozen reconciliation and bounded read-only confirmation |

### Production update listener

An update endpoint is opt-in. Without either configuration below, `PairResponse.updates` and `GetConnectionCapabilitiesResponse.updates` are absent, and the existing manual/unary workflow remains available.

For production, put a publicly trusted PEM identity on the sidecar host and configure one secure listener:

```dotenv
SIDECAR_HOST=127.0.0.1
SIDECAR_PORT=8443
SIDECAR_PUBLIC_URL=https://vault.example.com:8443
SIDECAR_TLS_CERT_PATH=/run/secrets/fullchain.pem
SIDECAR_TLS_KEY_PATH=/run/secrets/privkey.pem
```

This changes the main listener to TLS with ALPN `h2` and `http/1.1`. `Subscribe` uses genuine gRPC over HTTP/2; `/healthz`, `/mcp`, pairing, Stage 1, and the existing unary RequestService continue over HTTP/1.1 or HTTP/2 on the same origin. If a TCP or gRPC-aware proxy fronts the listener, it must preserve HTTP/2 to it. An HTTP/1-only reverse proxy cannot carry `Subscribe` and is not a fallback transport.

For loopback development only, leave the TLS paths empty and choose a second port:

```dotenv
SIDECAR_PORT=8080
SIDECAR_UPDATE_PORT=8081
```

The main server stays at `http://127.0.0.1:8080`; the advertised update origin is `http://127.0.0.1:8081` and accepts h2c. With a USB-connected phone, reverse both ports (`adb reverse tcp:8080 tcp:8080` and `adb reverse tcp:8081 tcp:8081`). Cleartext HTTP/2 is never a remote deployment option.

The bearer credential is still the connection's phone token. The MCP token, pairing token, Stage 1 token, a revoked token, and another connection ID cannot open `Subscribe` or `Sync`. A second subscription for the same connection cancels the first. Pairing replacement and explicit revocation publish the terminal revocation event and close the stream; shutdown cancels all streams and destroys their HTTP/2 sessions.

`pnpm test:updates` joins these production listeners to the Android synchronization and lifecycle implementation through real sidecar processes and MCP calls. It also runs the secure-listener, replay, paging, confirmation, isolation, and cleanup cases below. This suite remains separate from the Stage 1 `pnpm test:hello` diagnostic. For a physical phone, follow the [MacBook-to-Seeker runbook](../guides/live-background-updates.md).

The sidecar checks requests to `/mcp` as follows, following the MCP transport specification's defense against DNS rebinding:

- **Request target:** a target that isn't a path, such as `//[`, gets 400, and the sidecar keeps running.
- **Host header:** must be a loopback name (`127.0.0.1`, `localhost`, or `[::1]`) or an `MCP_ALLOWED_HOSTS` entry, on any port so that SSH tunnels and port forwards work. Otherwise it returns 403.
- **Origin header:** if present, it must also be a loopback origin or an `MCP_ALLOWED_HOSTS` entry. Otherwise it returns 403.
- **Token:** a missing or wrong token gets 401 with `WWW-Authenticate: Bearer`.
- **Body size:** a POST body over 64 KiB gets 413. When the length is declared, the sidecar answers without reading the body, and closes the connection.
- **Session:** an unknown session ID gets 404, and the client then starts a new session.
- **Request format:** the MCP SDK checks the `Accept` and `Content-Type` headers and the protocol version.

On the phone API:

- **Token:** each service takes only its own credential, as the [role matrix](../protocol.md#roles) shows. Any other token, a revoked credential, or none fails with `unauthenticated`. The MCP token is refused on every phone RPC, and every phone-side token is refused on `/mcp`.
- **Size:** each message is limited to 64 KiB, and a larger one fails with `resource_exhausted`.
- **Connection:** the phone credential authenticates as its own connection. Every `RequestService` and capability-discovery call must name that connection, in `connection_id` or `ref`. A call that names another connection gets `not_found`.
- **Errors:** every `PairingService` and `RequestService` error carries a `RequestErrorDetail`; see [request errors](../protocol.md#request-errors).

**Tokens and logs:** tokens are read only from the `Authorization` header, and never logged. `MCP_TOKEN` and `PHONE_TOKEN` are compared in constant time. Pairing tokens and phone credentials are looked up by their SHA-256 hash, which is all the database keeps. Log lines carry command and request IDs, sizes, and states. They never carry command text, request text, or notes.

## The MCP tools

### vault_display_command

`vault_display_command` takes `{"text": string}`. To call it from the command line, run `pnpm agent hello "Hello Seeker"`; see [`test-agent/README.md`](../../test-agent/README.md). To connect Hermes, on the Mac or on a VPS, see [`docs/integrations/hermes.md`](../integrations/hermes.md).

- **When the phone acknowledges the command,** the result carries `structuredContent: {"id": "<command UUID>", "result": "OK"}`, plus the same JSON as text.
- **When the command fails,** the result has `isError: true`, and its text starts with the error code:
  - `OFFLINE:`
  - `BUSY:`
  - `INVALID_TEXT:`
  - `TIMEOUT:`
  - `CANCELLED:`

  Failures carry no `structuredContent`, because MCP clients validate it against the success schema.
- **Set the client's request timeout above `LIVE_COMMAND_TIMEOUT_SECONDS`.** The MCP TypeScript SDK's default is 60 seconds.

The call is cancelled when the agent cancels it (`notifications/cancelled`) or when the agent's HTTP connection drops before the answer.

### The durable request tools

`vault_request_ack` queues display-only text for the owner to acknowledge. `vault_get_request` reads a request, and `vault_cancel_request` withdraws one. The full contract, including the request view and every error, is in [`docs/protocol.md`](../protocol.md#agent-api-mcp).

- **Every call answers at once.** Creation stores the request and returns it as PENDING. At that point the owner hasn't seen it yet, let alone approved it. The agent reads the outcome later with `vault_get_request`.
- **Retries are safe.** The same `idempotency_key` with the same text returns the original request, in whatever state it's in now. The same key with different text fails with `IDEMPOTENCY_CONFLICT`.
- **`vault_request_ack` is demo-only.** It involves no wallet, and the sidecar serves it only with `MCP_DEMO_TOOLS=true`. Without it, `tools/list` leaves it out, a call to it fails as an unknown tool, and the server's instructions don't mention it. The startup log says which. `vault_sign_message` (SAW-016) is always served, `vault_transfer` (SAW-019) whenever `SOLANA_RPC_URL` is set, and swaps arrive with Stage 6.
- **Hermes gets every tool** from `examples/hermes.config.yaml`; see [queued requests](../integrations/hermes.md#4-queued-requests-create-now-read-the-result-later).

### vault_get_address

`vault_get_address` takes no input and returns the wallet the owner connected on their phone: `{"wallet": "<base58>", "network": "mainnet" | "devnet" | "testnet", "bound_at": "<RFC 3339>"}`. Run it with `pnpm agent address`.

- **It's read-only and always served,** in demo mode or not.
- **It never invents an address.** With no phone paired it fails with `NOT_PAIRED`, and with no wallet connected with `WALLET_NOT_CONNECTED`. The sidecar holds no keys and makes no wallet.
- **The phone publishes the binding** with `RequestService.PublishWallet` (SAW-015); see [the wallet binding](../protocol.md#the-wallet-binding) and [`docs/guides/wallet-setup.md`](../guides/wallet-setup.md).

### vault_sign_message

`vault_sign_message` queues a message for the owner's wallet to sign: `{wallet, message, idempotency_key, note?, expires_in_seconds?}`, answered at once with the request as PENDING. Run it with `pnpm agent sign "<text>"`.

- **It creates a request and nothing else.** No wallet is contacted until the owner approves it on their phone. Being stored is not approval and not a signature.
- **The message is text:** `message`, whose UTF-8 encoding is signed as it is. 1 to 4096 bytes; empty is `INVALID_PARAMETERS`. There is no public way to queue bytes that aren't text, so the owner can always read what they are approving. `SignMessageAction` keeps a `data` form in the contract for a later stage, and no tool served here creates one.
- **The wallet must be the owner's.** Another one is `WALLET_MISMATCH`, and none connected is `WALLET_NOT_CONNECTED`.
- **The result carries the signed bytes.** A COMPLETED request has `signature` (base58), `wallet`, and `signed_message_base64`, so the agent verifies the signature itself. The sidecar verifies it too, before accepting it from the phone, and refuses anything that isn't that wallet's signature over those bytes. It signs nothing: see [message results](../protocol.md#message-results) and [`docs/guides/message-signing.md`](../guides/message-signing.md).

### vault_transfer

`vault_transfer` queues a transfer of SOL or a classic SPL token: `{wallet, network, recipient, amount, token_mint?, idempotency_key, note?, expires_in_seconds?}`, answered at once with the request as PENDING. Run it with `pnpm agent transfer <recipient> <amount>`, and `--mint <address>` for a token.

- **It is served only with `SOLANA_RPC_URL` set.** Without an endpoint the sidecar couldn't prepare the transaction, so it doesn't offer the tool, leaves `transfer` out of `operations`, leaves `confirmed_with` out of the capabilities, and answers `PrepareRequest` and `CheckStatus` for a transfer with `CHAIN_UNAVAILABLE`.
- **Confirmation runs when somebody asks (SAW-022).** There is no background worker. Reading a SUBMITTED transfer with `vault_get_request` checks the chain, at most once every two seconds per request; `RequestService.CheckStatus` checks whenever the owner asks. Both go through `ConfirmationTracker`, which reports CONFIRMED only when the transaction on chain under the reported signature is byte for byte the one the approval named. See [`docs/protocol.md`](../protocol.md#confirmation).
- **It creates a request and nothing else.** No transaction is built, signed, or sent. The only thing it reads from the chain is the mint, so an agent hears about an unsupported token before the owner ever sees the request.
- **`amount` is always base units:** lamports for SOL, or the mint's base units for a token, as decimal digits from 1 to the u64 maximum. The decimals come from the mint at preparation, never from a name or a ticker.
- **`recipient` is a wallet address,** never a token account; the associated token account is derived, and created when the recipient has none.
- **Only classic SPL tokens.** A Token-2022 mint, an NFT, an uninitialized mint, or an account that is missing or frozen is refused with `INVALID_PARAMETERS`, and the reason says which.
- **The transaction itself** is built by `RequestService.PrepareRequest`; see [transfers](../protocol.md#transfers-saw-019) and [`docs/guides/transfers.md`](../guides/transfers.md).

### vault_get_capabilities

`vault_get_capabilities` takes no input, never fails, and says what this sidecar actually serves:

```json
{
  "approval": "manual",
  "signing": "wallet",
  "operations": ["sign_message", "transfer"],
  "wallet_connected": true,
  "max_message_bytes": 4096,
  "max_note_bytes": 1024,
  "max_pending_requests": 20,
  "min_expires_in_seconds": 60,
  "max_expires_in_seconds": 604800
}
```

- **`approval` is always `manual`,** and there is no way to ask for anything else.
- **`signing` is always `wallet`:** the owner's own wallet signs, and the sidecar holds no key.
- **`operations` lists only what is implemented here,** so an agent treats anything missing as unavailable. `ack` appears only in demo mode, and `transfer` only with a chain endpoint configured.
- **`max_pending_requests` is `REQUEST_PENDING_LIMIT`,** and the expiry bounds are the ones every creation tool takes. Run it with `pnpm agent capabilities`.

## Storage and lifecycle

Durable requests and production update state live in one SQLite file, `DATABASE_PATH` (by default `sidecar/data/sidecar.db`). While the sidecar runs, SQLite keeps `-wal` and `-shm` files next to it. `.gitignore` covers all three (`*.db`, `*.db-*`).

### How requests are stored

- **The driver is Node's built-in `node:sqlite`,** so there's no native build and no extra dependency.
- **The database runs in WAL mode with `synchronous = FULL`,** so a commit is on disk before it returns.
- **Each operation is one transaction (`BEGIN IMMEDIATE`) that commits before the sidecar answers.** The agent's tool result and the phone's RPC response never report a change that a crash could still lose. Operations are synchronous, so two never interleave.
- **Only `sidecar/src/storage/` touches SQLite or the file system.** It alone imports them, and it alone runs SQL. `request-store.ts` and `pairing-store.ts` commit lifecycle changes, while `update-store.ts` appends their complete revisioned forms, bounds replay to 512 events per connection, and freezes two-minute paginated snapshots on disk. `tls.ts` reads the configured PEM bytes. `stage-boundary.test.ts` checks the boundary.
- **Publication is part of the source transaction.** Creation, cancellation, expiry, accepted owner/wallet results, confirmation attempts/results, wallet-binding cancellations, and revocation append only if their request/connection update commits. Duplicate/idempotent calls append nothing.
- **A cursor is process- and connection-bound.** A restart, malformed cursor, another connection, or a cursor older than retained replay requires `Sync`. The stream registers before taking its barrier, so mutations committed after the barrier are replayed or delivered live rather than falling between snapshot and subscription.
- **Sync pages do not drift.** Page one freezes every current PENDING request plus the named nonterminal Activity records in SQLite. Later mutations do not rewrite remaining pages; tokens expire after two minutes and after a process restart. Responses shrink below 65,536 encoded bytes, and no unbounded request list is retained in heap memory.
- **Confirmation is bounded observation.** Of the supplied SUBMITTED/UNKNOWN transfers, each Sync checks at most four concurrently through `ConfirmationTracker`; the durable rotation moves deferred records into later runs. It uses the same cluster and approved-byte verification as Check status and can only record that result. It never prepares, signs, simulates, sends, or resubmits.
- **Run one sidecar per database file.**

These are the tables at schema version 3:

| Table | Holds |
| --- | --- |
| `server` | The sidecar's lasting ID, created with the database |
| `connections` | Each phone that paired: its connection ID, the SHA-256 of its credential, its device name, when it paired and was revoked, and the wallet address and network it published. At most one isn't revoked. |
| `pairing_tokens` | Each pairing token's SHA-256, its server URL, when it was issued, expires, and was used, and the connection it created |
| `requests` | Each request: its connection, kind, action (as Protobuf binary), note, state, times, and outcome |
| `idempotency_keys` | Each key's request and action fingerprint, across the whole sidecar |
| `results` | Every result the phone submitted and the sidecar accepted, so that a repeat changes nothing |
| `prepared_transactions` | Prepared transaction versions. A SUBMITTED transfer's approved version is read back from here when the chain is checked (SAW-022). |

To look inside, run `sqlite3 sidecar/data/sidecar.db "SELECT request_id, kind, state FROM requests"`. States are `RequestState` numbers, from 1 (PENDING) to 10 (UNKNOWN).

### Migrations

- **`sidecar/src/storage/migrations.ts` lists numbered migrations,** and `PRAGMA user_version` records the last one applied.
- **At startup, the sidecar applies the missing ones in order,** each in its own transaction, so a failure leaves the database at the last complete version.
- **A database from a newer sidecar is refused,** and the sidecar exits rather than guess.
- **A shipped migration is never edited;** a schema change is a new migration. `sidecar/src/storage/fixtures/schema-v1.sql` freezes a v1 database, and `database.test.ts` opens it with the current code.
- **Migration 2 adds pairing (SAW-011):** the `server` and `pairing_tokens` tables, and the credential and device name columns on `connections`. SAW-010's stand-in connection has no credential, so the migration revokes it and cancels its PENDING requests. Pair the phone after upgrading.
- **Migration 3 adds the wallet binding (SAW-015):** `wallet_address`, `wallet_network`, and `wallet_bound_at_ms` on `connections`. All three are set together or all NULL, which means no wallet is connected. No key material and no wallet authorization token is ever stored; `wallet_address` is a public key. An upgraded database starts with no binding, so connect the wallet in the app after upgrading.

### The lifecycle in the sidecar

- **Creation:**
  - validates the action, the note, the idempotency key, and the lifetime
  - applies the idempotency key
  - checks `REQUEST_PENDING_LIMIT`
  - stores the request as PENDING. Its `expires_at` is the time of creation plus `expires_in_seconds`, or plus `REQUEST_TTL_SECONDS` if the agent gave none.

  Storing a request isn't the owner's approval.
- **Expiry** has no timer. Every operation first moves each PENDING request that's past its `expires_at` to EXPIRED, so no answer ever shows an overdue request as PENDING.
- **Cancellation** by the agent works only while the request is PENDING. A repeat returns the cancelled request.
- **Results** from the phone go through the lifecycle's rules. A result identical to an accepted one changes nothing. A result that doesn't fit the current state gets `INVALID_STATE`, with the request as it is.
- **Pending pages** are ordered by `created_at`, then `request_id`, and the page token names the last request returned.

### Restarts, backups, and a fresh start

- **Nothing runs at startup.** No request is rebuilt, re-executed, replayed, or expired early. Requests wait in their states for the phone and the agent, and the paired phone stays paired. The live diagnostic still loses its in-flight command.
- **To back up,** stop the sidecar, then copy the database file together with its `-wal` file, if there is one.
- **To start over,** stop the sidecar and delete `sidecar/data/sidecar.db*`. Every request, the pairing, and the server ID are gone, and the phone must pair again.

## Examples

To run these from a shell, export the tokens first: `set -a; source .env; set +a`.

A request without the MCP token:

```console
$ curl -si -X POST http://127.0.0.1:8080/mcp -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' -d '{"jsonrpc":"2.0","id":1,"method":"ping"}'
HTTP/1.1 401 Unauthorized
Content-Type: application/json
WWW-Authenticate: Bearer realm="seeker-vault"

{"jsonrpc":"2.0","error":{"code":-32000,"message":"a valid MCP token is required"},"id":null}
```

A request from a non-loopback origin, for example a web page after DNS rebinding:

```console
$ curl -s -X POST http://127.0.0.1:8080/mcp -H "Authorization: Bearer $MCP_TOKEN" \
    -H 'Origin: http://evil.example' -H 'Content-Type: application/json' -d '{}'
{"jsonrpc":"2.0","error":{"code":-32000,"message":"the Origin header is not a loopback origin or an MCP_ALLOWED_HOSTS entry"},"id":null}
```

The phone API called with the MCP token:

```console
$ curl -s -X POST http://127.0.0.1:8080/seekervault.live.v1.LiveCommandService/AcknowledgeCommand \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $MCP_TOKEN" \
    -d '{"acknowledgement":{"id":"x","result":"ACKNOWLEDGEMENT_RESULT_OK"}}'
{"code":"unauthenticated","message":"a valid phone token is required"}
```

Live tool errors, as the agent sees them:

```text
OFFLINE: no phone is watching; open the live-test screen and connect
BUSY: another live command is in flight
INVALID_TEXT: text is empty
TIMEOUT: no acknowledgement within 60 seconds
CANCELLED: the command was cancelled: the phone disconnected
```

Durable tool errors, as the agent sees them:

```text
IDEMPOTENCY_CONFLICT: idempotency_key "deploy-1" was already used for request 3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c with different parameters
PENDING_LIMIT: the phone already has 100 pending requests, which is the limit; wait for the owner to decide some, or cancel ones you no longer need
INVALID_STATE: the request is COMPLETED, so it can no longer be cancelled
NOT_FOUND: no such request
```

Acknowledgement errors, as the phone sees them:

- `deadline_exceeded`: "the command timed out before it was acknowledged"
- `canceled`: "the command was cancelled before it was acknowledged"
- `not_found`: "the sidecar is not tracking this command"

Typical log lines:

```text
[sidecar] phone connected
[sidecar] MCP session opened
[sidecar] command 1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed sent (12 bytes)
[sidecar] command 1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed acknowledged
[sidecar] request 3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c stored (ack)
[sidecar] request 3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c: acknowledgement, now COMPLETED
[sidecar] rejected POST /mcp: a valid MCP token is required
[sidecar] phone paired: connection de03846e-d435-4705-b2e3-ec67da539f12; revoked connection 5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f
[sidecar] rejected Pair: UNAUTHENTICATED
[sidecar] rejected ListPending: missing, wrong, or revoked phone credential
[sidecar] connection de03846e-d435-4705-b2e3-ec67da539f12 revoked by the phone; 2 pending requests cancelled
```

## Code and tests

| File | Role |
| --- | --- |
| `sidecar/src/main.ts` | Entry point: loads the configuration, starts the server, and stops it on SIGINT or SIGTERM |
| `sidecar/src/server.ts` | The HTTP/1 or TLS HTTP/2+HTTP/1 listener, optional h2c development listener, `/healthz`, `/mcp`, phone APIs, and graceful session cleanup |
| `sidecar/src/mcp-endpoint.ts` | MCP sessions, the tools, the body limit, and the Host, Origin, and token checks |
| `sidecar/src/phone-api.ts` | The Connect `LiveCommandService` |
| `sidecar/src/live/bridge.ts` | The in-memory waiter: one watcher, one in-flight command, deadline timers, and cancellation |
| `sidecar/src/live/command.ts` | The protocol rules from SAW-002 |
| `sidecar/src/requests/action.ts` | The durable request's parameters (SAW-009): each action kind's fields, base-unit amounts, base58 addresses, and exact message bytes |
| `sidecar/src/requests/identity.ts` | Connection scope for references, idempotency keys, and action fingerprints (SAW-009) |
| `sidecar/src/requests/lifecycle.ts` | The durable lifecycle (SAW-009): the transition table, the phone's results, the approval binding, and expiry |
| `sidecar/src/requests/failure.ts` | `RequestFailure`, the error that every durable operation refuses with |
| `sidecar/src/storage/request-store.ts` | `RequestStore` (SAW-010), which applies those rules in SQLite transactions, and the wallet binding (SAW-015) |
| `sidecar/src/storage/update-store.ts` | Durable per-connection sequences/replay and disk-frozen Sync snapshots (SAW-049) |
| `sidecar/src/storage/tls.ts` | Reads the configured production PEM identity inside the audited file-system boundary (SAW-049) |
| `sidecar/src/updates/service.ts` | Authenticated bidirectional Subscribe, unary Sync, stream ownership/liveness, and bounded confirmation (SAW-049) |
| `sidecar/src/push/fcm.ts` | The optional Firebase Admin messaging sender (SAW-054). It uses Application Default Credentials, logs nothing, and has no request-lifecycle caller until later Stage 5.3 tickets. |
| `sidecar/src/requests/mcp-tools.ts` | The durable MCP tools and the request view (SAW-010), `vault_get_address` (SAW-015), and `vault_sign_message` and `vault_get_capabilities` (SAW-016) |
| `sidecar/src/requests/signature.ts` | Ed25519 verification (SAW-016): the sidecar checks a wallet's signature, and never makes one |
| `sidecar/src/requests/preparation.ts` | `TransactionPreparer` (SAW-019): checks an asset before a request is stored, and builds and records the next prepared version |
| `sidecar/src/solana/rpc.ts` | The chain client (SAW-019): the read-only JSON-RPC calls a preparation needs. It remains the only code that reaches a blockchain. The separately confined optional FCM sender can reach Firebase only once a later ticket gives it a caller. |
| `sidecar/src/solana/transfer.ts` | Building a transfer (SAW-019): the network check, what is supported, and the unsigned transaction |
| `sidecar/src/solana/token.ts`, `addresses.ts`, `network.ts` | The SPL Token layouts and instructions, the program addresses and the associated-token-account derivation, and each network's genesis hash |
| `sidecar/src/requests/phone-service.ts` | The Connect `RequestService` (SAW-010), which takes the paired phone's credential (SAW-011) |
| `sidecar/src/storage/database.ts` | Opening the database, migrations, and transactions (SAW-010) |
| `sidecar/src/storage/migrations.ts` | The numbered schema migrations, including durable update revision/replay/snapshot tables (SAW-010, SAW-011, SAW-049) |
| `sidecar/src/pairing/uri.ts` | The pairing code's URI, and the server URL rule (SAW-011) |
| `sidecar/src/storage/pairing-store.ts` | `PairingStore` (SAW-011): pairing tokens, pairing, phone credentials, revocation, and the server ID |
| `sidecar/src/pairing/service.ts` | The Connect `PairingService` (SAW-011) |
| `sidecar/src/pairing/cli.ts` | `pnpm pair`, `pnpm pair status`, and `pnpm pair revoke` (SAW-011) |

The SAW-009 modules are pure rules, which `storage/request-store.ts` applies. The contract they implement is in [`docs/protocol.md`](../protocol.md#stage-2-durable-requests).

`pnpm check` runs these tests:

- **`src/updates/service.test.ts`, `store.test.ts`, and `confirmation.test.ts`** test the actual TLS/h2 and loopback h2c listeners, preserved HTTP/1 routes, durable publication, replay, frozen paging, restart/gap recovery, isolation, replacement/revocation/cleanup, and bounded byte-verified confirmation.
- **`pnpm test:updates`** runs those sidecar suites and the Android `Stage52AcceptanceTest`/gRPC tests. The joined cases use real sidecar processes, the MCP SDK client, the production h2c listener, and the production Android transport, repository, persistent cache, and foreground owner. Stage 1 still has its own acceptance command.

- **`src/live/bridge.test.ts`** tests the waiter with mocked timers.
- **`src/server.test.ts`** tests the real server with the MCP SDK client and a Connect client. It covers:
  - Firebase-off startup constructs no Admin app, while configured startup owns and closes exactly one injected sender without logging its project
  - acknowledgement, OFFLINE, BUSY, and INVALID_TEXT
  - timeout, including a late acknowledgement
  - cancellation, both by the agent and by a dropped agent connection
  - the phone disconnecting, and the phone being replaced by a newer stream
  - missing, wrong, and swapped tokens
  - the Host and Origin checks
  - `/healthz`
  - that no token or command text reaches the logs
- **`src/push/fcm.test.ts`** tests exact message hand-off, opaque message IDs, idempotent Admin-app deletion, and initialization/cleanup without loading or contacting credentials.
- **`src/restart.test.ts`** runs `src/main.ts` as a real process. It stops the process with SIGTERM and then with SIGKILL during a command, and checks that the original caller fails and nothing is replayed after the restart.
- **`src/storage/request-store.test.ts`** tests the request store on an in-memory database with a controlled clock. It covers:
  - creation and validation
  - idempotent retries and conflicts
  - the pending limit, and expiry at the exact deadline
  - results and their repeats
  - cancellation and its races
  - connection scope, and pages while states change
  - a restart that reopens the file
- **`src/requests/endpoints.test.ts`** runs the durable workflow end to end, with the MCP SDK client and a Connect client. It covers:
  - the tools and the round trip
  - retries and cancellation
  - simultaneous cancellations and acknowledgements
  - repeated results and the pending limit
  - the size limits
  - the phone's authentication and scope
  - the demo tool, served only with `MCP_DEMO_TOOLS`
  - on a fake clock (`SidecarOptions.now`): expiry on both endpoints at the exact deadline, and a pairing code refused from its exact expiry on
- **`src/requests/restart.test.ts`** kills the sidecar with SIGKILL right after a tool answers and right after the phone's result is confirmed. It checks that both survived, along with the phone's pairing, and that nothing ran at startup.
- **`src/pairing/`** tests pairing (SAW-011):
  - `uri.test.ts`: the pairing URI and the server URL rule
  - `src/storage/pairing-store.test.ts`: expired, reused, unknown, and wrong-URL pairing tokens; one active phone; revocation; a new code never changing an existing connection; and only hashes in the database
  - `roles.test.ts`: every RPC and MCP method against every credential, including a revoked one; results from the wrong credential; and no secret in the log
  - `tls.test.ts`: pairing through a TLS endpoint, and refusals for an untrusted certificate and for another host's certificate
  - `cli.test.ts`: `pnpm pair` and its `status` and `revoke` commands, with no credential in their output
- **`src/storage/database.test.ts`** tests the schema, the pragmas, the frozen v1 fixture and its migration to v2, a database from a newer sidecar, migration failures, and transaction rollback.
- **The SAW-009 rule tests** are `src/requests/action.test.ts`, `identity.test.ts`, `lifecycle.test.ts`, `fixtures.test.ts`, and `live-compat.test.ts`.
- **The SAW-019 transfer tests** are:
  - `src/solana/token.test.ts`: the instruction bytes, the account layouts, and the associated-token-account derivation, each against the program's documented layout
  - `src/solana/rpc.test.ts`: what the client reads, and how it reports a JSON-RPC error, an HTTP error, an answer that isn't JSON or isn't the right shape, and a timeout — none of which ever names the endpoint
  - `src/solana/transfer.test.ts`: SOL and token amounts, decimals, the created token account and its rent, the fee and the blockhash window, and every refusal (wrong network, Token-2022, NFT, missing or frozen accounts, a recipient that is a token account, too small a balance, a bad address, a zero or overflowing amount, and an endpoint that stopped answering). Each case deserializes the built transaction and checks its fee payer, signer set, and instructions.
  - `src/requests/transfers.test.ts`: the tool and `PrepareRequest` end to end against a fake chain — a new version per preparation, an old approval refused, no submission, and a failed preparation that leaves the request as it was
- **`src/stage-boundary.test.ts`** checks that there's no wallet package beyond the chain client, no key generation and no signing, no tool the stage doesn't serve, and that nothing outside `src/storage/` imports the file system or SQLite or runs SQL, and nothing outside `src/solana/` imports the chain client.

`pnpm test:queue` runs the Stage 2 acceptance scenario (SAW-014) with two sidecar processes that restart; see [`docs/testing/stage-2.md`](../testing/stage-2.md#the-acceptance-scenario-saw-014), which also holds its report. `src/testing/process.ts` starts the processes, with `MCP_DEMO_TOOLS` if asked. `src/testing/clock.ts`, loaded with `--import`, runs a restarted process's clock ahead, for time that passed while it was down.

`pnpm test:transfer` runs the Stage 4 acceptance scenario (SAW-023): one sidecar process, the test agent's CLI as another, the Connect phone client as the phone, a throwaway key pair as the wallet, and `src/testing/chain.ts`'s `startFakeRpc` as the chain. `startSidecarProcess` takes a `solanaRpcUrl` so a spawned sidecar reads it. **Nothing in that run reaches a cluster and nothing in it spends anything**; the one case that talks to devnet reads, needs no funds, and is skipped unless `SEEKER_VAULT_NETWORK_CHECKS=1`. See [`docs/testing/stage-4.md`](../testing/stage-4.md).

## Verification record: SAW-003

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with the versions in [`toolchain.md`](toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: 65/65 tests, including 13 integration tests and 2 restart tests |
| Real MCP SDK client with a Connect phone client | PASS. The call completes only after the phone acknowledges the same command ID. OFFLINE fails at once, and BUSY and INVALID_TEXT are returned. TIMEOUT refuses the late acknowledgement. CANCELLED is returned on agent cancellation, a dropped agent connection, a phone disconnect, and phone replacement. |
| Wrong and swapped tokens | PASS. A wrong token or the phone token on `/mcp` gets 401. A missing token, a wrong token, or the MCP token on the phone API gets `unauthenticated`, for `AcknowledgeCommand` too. |
| Restart during a command | PASS. After SIGTERM the caller gets `CANCELLED`; after SIGKILL the caller's request fails. After the restart the phone receives only `ready`, the old ID is `not_found`, and a new call works. |
| Live `pnpm dev:sidecar` | PASS: the curl examples above produced the documented responses, SIGINT stopped the sidecar cleanly, and no token appeared in the log |
| Deliberate breaks | Each of these failed the matching integration tests: skipping the token check, disabling cancellation, dropping the Origin check |
| `pnpm build`, `pnpm check:generated`, `pnpm check:android` | PASS |
| Physical Seeker | NOT RUN: the Android screen arrives in SAW-004 |

## Verification record: SAW-010

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with Node 24.21.0 (its `node:sqlite` is SQLite 3.53.4), pnpm 12.3.4, and the other versions in [`toolchain.md`](toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, and `tsc`. 189/189 sidecar tests pass, 42 more than before: the store, the database, the endpoints, the restart, and the configuration. The test agent's 15/15 pass too. |
| Restart persistence | PASS. `src/requests/restart.test.ts` killed the sidecar with SIGKILL twice: right after `vault_request_ack` answered, and right after the phone's acknowledgement was confirmed. After each restart:<ul><li>the request, and then its COMPLETED result, were there</li><li>the connection kept its ID</li><li>a retry with the same key returned the same request</li><li>nothing ran at startup</li></ul> |
| The lifecycle in the store | PASS, in `store.test.ts` and `endpoints.test.ts`:<ul><li>A duplicate creation replays the original, and a changed retry gets IDEMPOTENCY_CONFLICT.</li><li>A repeated result changes nothing, and a conflicting one gets INVALID_STATE.</li><li>Expiry happens exactly at `expires_at`.</li><li>In eight simultaneous cancel-and-acknowledge pairs, each had exactly one winner.</li><li>Pages neither repeated nor skipped a request while states changed.</li><li>PENDING_LIMIT held.</li><li>Oversized requests got INVALID_PARAMETERS, 413, or `resource_exhausted`.</li></ul> |
| Migration | PASS. The frozen v1 fixture has migration 1's schema, and the current code opens it with its requests, idempotency records, and results intact. A database from a newer sidecar is refused and left untouched. A failing migration rolls back to the last complete version. |
| `pnpm test:hello` | PASS: the 9/9 Stage 1 acceptance cases, unchanged |
| `pnpm check:android` | PASS: 61/61 unit tests. `ConnectLiveCommandTransportTest` now gives the sidecar a throwaway database. |
| `pnpm build` | PASS: `sidecar/dist` includes `storage/` and `requests/`. The built sidecar starts, answers `/healthz`, and logs its connection. |
| `pnpm check:generated` | PASS: the protocol didn't change |
| Deliberate breaks | Each break failed the matching tests, and each file was restored byte for byte afterwards:<ul><li>expiry one millisecond late</li><li>a changed retry reusing the original request</li><li>a repeated result not recognized</li><li>a page repeating its last request</li><li>one request accepted past the limit</li><li>an edit to the shipped migration</li><li>the database kept in memory</li><li>a 128 KiB body limit</li></ul> |
| Physical Seeker | NOT RUN: SAW-010 has no phone-side code. The Android inbox arrives in SAW-013. |

## Verification record: SAW-011

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, and the other versions in [`toolchain.md`](toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, and `tsc`. 235/235 sidecar tests pass, 46 more than before, and the test agent's 15/15 pass too. |
| Pairing tokens | PASS, in `store.test.ts`. An expired token (at exactly its expiry), a reused one, an unknown one, and a missing one all get the same UNAUTHENTICATED. A wrong URL gets INVALID_PARAMETERS and leaves the token usable. A newer code voids an older one. |
| Roles | PASS, in `roles.test.ts`. The credentials were none, `MCP_TOKEN`, a pairing token, the paired phone's credential, a revoked credential, and `PHONE_TOKEN`. Each one was tried against every RPC of `PairingService`, `RequestService`, and `LiveCommandService`, and against `initialize`, `tools/list`, and every tool on `/mcp`. Each credential opened exactly its own role, and no MCP tool pairs, prepares, submits, or revokes. |
| Revocation and re-pairing | PASS:<ul><li>A new pairing revokes the previous phone and cancels its PENDING requests, and overdue ones expire instead.</li><li>`RevokeConnection` and `pnpm pair revoke` stop the credential at once.</li><li>A new code for another URL creates a new connection, and never changes the old one.</li></ul> |
| TLS | PASS, in `tls.test.ts`. Pairing works through an HTTPS endpoint in front of the loopback sidecar, with a certificate the client trusts. An untrusted certificate, and a certificate for another host name, fail before the token is sent, and the token still pairs afterwards. |
| Migration | PASS. The frozen v1 fixture migrates to v2: its data stays, its stand-in connection is revoked, and its PENDING request is CANCELLED. |
| Live `pnpm dev:sidecar` and `pnpm pair` | PASS:<ul><li>`pnpm pair` printed the QR code and the URI, and a Connect client paired with the printed token.</li><li>The same token was then refused.</li><li>The credential listed requests, and `PHONE_TOKEN` was refused.</li><li>`pnpm pair status` and `pnpm pair revoke` worked, and the revoked credential was refused.</li><li>No token appeared in the log, or in the status and revoke output.</li></ul> |
| `pnpm test:hello` | PASS: the 9/9 Stage 1 acceptance cases, unchanged |
| `pnpm check:android` | PASS. The regenerated `PairRequest`, `PairResponse`, and `PairingServiceClient` compile, and the unit tests, lint, and both APKs pass. |
| `pnpm check:generated`, `buf breaking` | PASS. The generated code is current, and the proto change only adds fields. |
| `pnpm build` | PASS: `sidecar/dist` includes `pairing/` |
| Deliberate breaks | Each break failed the pairing tests, and each file was restored byte for byte afterwards:<ul><li>a pairing token that still works at its expiry</li><li>a used pairing token that works again</li><li>`Pair` ignoring the token's URL</li><li>a revoked credential that still authenticates</li><li>revocation that leaves PENDING requests</li><li>a new pairing that keeps the previous phone</li><li>`RequestService` accepting any bearer token once a phone is paired</li><li>plain HTTP allowed off loopback</li><li>`Pair` logging the bearer token</li></ul> |
| Remote pairing through Tailscale Serve or Caddy | NOT RUN: it needs the owner's tailnet or domain. `tls.test.ts` covers the same path with a local TLS endpoint. |
| Physical Seeker | NOT RUN: the app's pairing screen arrives in SAW-012. |

## Verification record: SAW-019

Run on 2026-09-12 on macOS 26.5.2 (Apple silicon), with Node 24.21.0, pnpm 12.3.4, buf 1.72.0, and the other versions in [`docs/development/toolchain.md`](toolchain.md). No real cluster was reached: every chain call in every check goes to a fake JSON-RPC server on loopback.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, and `tsc`. 359/359 sidecar tests pass, 47 of them new: the chain client, the SPL encodings, the transfer builder, the preparation end to end, the approval margin, and `SOLANA_RPC_URL`. The test agent's 27/27 pass too, 4 of them new for `pnpm agent transfer`. |
| `pnpm check:android` | PASS: Spotless, 294/294 unit tests, lint with no issues, and the debug and instrumentation APKs. Kotlin compiles `PreparedTransaction.fee_lamports` and `.rent_lamports` and the new `REQUEST_ERROR_CHAIN_UNAVAILABLE`; `RequestProtocolFixturesTest` reads both new fields, including the u64 maximum. |
| `pnpm check:generated` | PASS: the committed TypeScript, Kotlin, Java, and `.binpb` fixtures match a fresh generation |
| `pnpm test:hello` | PASS: the 9/9 Stage 1 acceptance cases on a simulated device, unchanged |
| `pnpm test:queue` | PASS: the 7/7 Stage 2 acceptance cases, unchanged |
| `pnpm build` | PASS: `sidecar/dist` includes `solana/` and `requests/preparation.js` |
| Deliberate breaks | Each break failed the matching tests, and each file was restored byte for byte afterwards:<ul><li>Encoding `TransferChecked` as instruction 3 failed `token.test.ts` and the token case in `transfer.test.ts`.</li><li>An `assertNetwork` that accepts any genesis hash failed both wrong-network cases.</li><li>A preparation that always numbers itself version 1 failed the new-version and superseded-approval tests.</li><li>A `vault_transfer` that skips the asset check let a Token-2022 mint and an NFT through, failing three tool tests.</li><li>Putting the endpoint URL into a `ChainUnavailable` message failed the test that no error names it.</li><li>Adding a `sendTransaction` method to the chain client failed the stage-boundary test, which names every method that client may call.</li></ul> |
| Physical device | NOT RUN: SAW-019 is the sidecar's side, and adds no device behaviour. The owner's own transfer on the Seeker is SAW-024. |
| Mainnet | NOT RUN, and never by default: no check contacts a cluster, and none can spend. |
