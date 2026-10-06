# Direct Server SDK

The TypeScript Direct Server SDK is the reusable engine behind a server that pairs directly with
Seeker Agent Connect. A backend embeds it in-process: backend code creates and reads private
requests through the library, while the phone uses the mounted Connect API. MCP is one host adapter,
not part of the SDK.

Package: `@seekeragentconnect/server-sdk`

Runtime: Node.js 24.21 or newer within the Node 24 release line

Module format: ESM with TypeScript declarations
Versioning: semantic versioning, independent of every other component; the public baseline is
`0.0.1` (SEE-182).

Releases are published to npm only by `.github/workflows/release.yml`, through npm trusted
publishing with provenance; there is no npm token in the repository. See
[releases.md](releases.md).

## Ownership boundary

The SDK owns:

- direct request identity, validation, idempotency, lifecycle, expiry, cancellation, preparation
  versions, result binding and signature verification;
- the existing SQLite schema and migrations for requests, pairings, wallet bindings, revisions,
  snapshots and update events;
- one-use pairing codes, the one-active-phone rule, phone credential authentication, revocation,
  the direct manifest and the phone's Pairing, Request and Update Connect services;
- the adapter-neutral request API, result observation, the legacy live-command bridge and optional
  content-free invalidation scheduling;
- explicit provider interfaces used by the existing transfer preparation and confirmation logic.

The embedding host owns:

- MCP transport, tools, MCP token/OAuth authentication and MCP error/view translation;
- process entry points, environment loading, signal registration, health endpoints, logging policy,
  deployment listener/TLS choices, operator CLI presentation and Docker packaging;
- concrete Solana RPC, transaction-building and transaction-comparison implementations;
- concrete Firebase delivery and its credentials.

The SDK has no API that submits a phone result, publishes a wallet binding, approves a request,
opens a wallet, signs or broadcasts. Those mutations remain behind the authenticated phone wire
API. Direct phone credentials are stored and checked by the SDK; MCP credentials are never passed
to it.

## Public entry points

`@seekeragentconnect/server-sdk` exports:

- `openDirectServer(options)`: explicitly opens/migrates the configured SQLite file and initializes
  one direct engine. Importing the module does none of those things.
- `DirectServer.requests`: create, read, cancel and observe requests in-process, plus the existing
  read-only wallet/capability and optional preparation/confirmation adapter surface used by hosts.
- `DirectServer.pairing`: issue a one-use code for the configured public origin, inspect the current
  phone and revoke it. Pairing a phone still occurs only through the phone API.
- `DirectServer.phoneHandler(options)`: constructs the phone Connect handler for mounting in an
  existing Node listener. The host supplies the legacy live-command token, update capability and
  whether that listener carries production updates.
- `startPhoneApi(server, options)`: a small HTTP listener helper for an embedded backend or example;
  it starts only when called and has its own idempotent `close()`.
- `DirectServer.beginShutdown()` and `DirectServer.close()`: the first cancels the live command and
  update streams without closing SQLite under an in-flight response; the second drains queued
  invalidations and closes the database. `close()` calls `beginShutdown()` if needed and is
  idempotent.
- `@seekeragentconnect/server-sdk/protocol`: the direct protobuf messages and service descriptors needed
  by callers and phone protocol clients. There are no wildcard exports into private source paths.

Required initialization values are the database path, the externally advertised public origin and
a logger. Request/pairing limits, a clock and listener binding are explicit options. Transfer and
confirmation providers and content-free invalidation delivery are optional; omitting them adds no
provider/Firebase dependency and starts no background work.

### Supported networks (SEE-174)

`OpenDirectServerOptions.supportedNetworks` is the list of `SolanaNetwork` values the direct
manifest publishes as `direct.supported_networks` (inside the reference, so the reference stays the
last thing serialized): the Solana networks this server's wallet operations are
actually configured for. The phone offers only wallet profiles on those networks when a connection
is set up, and signs nothing for a connection whose bound network isn't listed.

- **There is no default.** Omitted or empty, the manifest declares no network. The phone reads that
  as "no networks declared" — never as Mainnet, never as every network — and signs nothing for the
  server until it declares one. A server whose requests never reach a wallet (informational or
  acknowledgement-only) is exactly the server that declares nothing.
- **Network is not environment.** The direct manifest's `production` environment says nothing about
  Mainnet; a production server may run on Devnet.
- **Validation is at open.** `SOLANA_NETWORK_UNSPECIFIED`, a value the contract doesn't name and a
  duplicate each make `openDirectServer` throw before the database is opened. Order is not an error:
  the manifest always lists networks in canonical order (Mainnet, Devnet, Testnet), so the same set
  in another order is the same manifest and the same revision.
- **Configuration helpers.** `parseSupportedNetworks(value, source)` reads a comma-separated
  configuration value of the canonical names `mainnet`, `devnet` and `testnet` — lowercase, no
  aliases such as `mainnet-beta` — refuses unknown names, duplicates and empty entries with an
  error naming `source`, and returns a blank value or `none` (the Go templates' spelling) as an
  empty list. `canonicalSupportedNetworks`,
  `solanaNetworkName` and `SOLANA_NETWORK_NAMES` are exported with it; the enum is in `./protocol`.
- **Revision.** The manifest fingerprint covers the whole message, so a changed list moves the
  settings revision up by one on the next open and an unchanged one keeps it. An empty list is
  absent from the fingerprinted JSON, so a server that still declares nothing keeps the revision it
  had before the field existed.
- **Wallet bindings are unchanged.** A connection still has one binding, publishing a new one still
  cancels the PENDING wallet requests it no longer fits, and an action's wallet and network are
  still checked against that binding. A binding on an undeclared network is stored rather than
  refused — only a phone that predates the field publishes one, and refusing it would break that
  phone without making anything safer, because the gate that matters is the updated phone's — and
  the SDK logs a line naming the network and what the server declares.

## Errors, cancellation and resource lifetime

Synchronous request validation and state failures remain `RequestFailure` with the existing
`RequestError` values. Provider unavailability remains retryable `CHAIN_UNAVAILABLE`; unsupported
request data remains `INVALID_PARAMETERS`. The phone services keep their existing Connect status
and `RequestErrorDetail` mapping.

Creating, reading and cancelling requests are synchronous SQLite transactions. Result observers run
only after the matching revision commits and return an unsubscribe function; registering one starts
no polling loop. A host cancellation never approves, signs, retries a wallet interaction or changes
a terminal result. Closing the SDK cancels in-memory streams, stops accepting invalidations, drains
already queued best-effort delivery, then closes SQLite. Durable requests and results remain on
disk unchanged for the next explicit open.

## Persistence and compatibility

The package opens the same database file and the same monotonic schema used by the pre-extraction
sidecar. Operators move no rows and receive no new identity: server ID, pairing tokens, hashed phone
credential, wallet binding, requests, idempotency records, prepared versions, results, revisions,
events, snapshots and FCM target remain in place. Back up and rollback the file as documented in
the direct-server guide; never run an older binary against a database it reports as newer.

The extraction changes source ownership only. It does not add a connection mode, subscriber result
path, wallet feature, new transport or automatic execution.

## Package verification and later release

The repository builds declarations and runtime JavaScript into `dist/`; the package allowlist
contains only `dist/`, this README and the license. `npm pack --dry-run` and `npm pack` are inspected
for those files, then the real tarball is installed in a temporary project outside the repository.
That project imports and type-checks the package, starts the phone API, pairs a protocol client,
creates and observes a request, records the phone result and shuts both listener and SDK down.

SEE-168 turned that audit into a release gate. The package is published to
`@seekeragentconnect/server-sdk`; the version lives in `release/components.json`, a
`server-sdk-v<version>` tag runs `.github/workflows/release.yml`, and that workflow re-runs this
same audit against the tagged commit before it publishes. No publish happens from a pull request
or from a developer's machine. See [releases.md](releases.md).

The MCP servers vendor the SDK's built output and are versioned independently of it: an SDK
release does not force a release of either server. Each MCP package records the SDK it carries in
`dist/vendor/server-sdk/package.json` and in its published manifest's `seekerAgentConnect.serverSdk`
field (`name`, `version`, `contentSha256`).
