# SEE-128 architecture baseline and migration map

Status: SEE-129 baseline, recorded from `superset/feat/see-128` at
`a4feaa1551d974d4f23e6e5ea2a6301a79078035` on 2026-09-19 through 2026-09-20.

Implementation status: SEE-130 applied the step-2 retirement, SEE-131 extracted the reusable
TypeScript Direct Server SDK, SEE-132 relocated and packaged the self-hosted MCP application,
SEE-133 isolated the public feed gateway, SEE-134 split `publisher/` into `publisher-support/`
and two independent demos, and SEE-135 established the canonical deployment projects. Direct and
gateway-feed are the only active modes. SEE-136 reconciles the final documentation and joined
verification; unavailable live checks remain explicitly NOT RUN in its evidence record.

Authority: [SEE-128](https://linear.app/seekeragentwallet/issue/SEE-128/refactor-sac-into-a-typescript-server-sdk-self-hosted-mcp-public-feeds),
[SEE-129](https://linear.app/seekeragentwallet/issue/SEE-129/18-establish-the-architecture-baseline-and-exact-migration-map),
and the docs-only architecture reference [PR #36](https://github.com/BrRenat/SeekerAgentWallet/pull/36).
SEE-128 supersedes PR #36 wherever the old three-mode architecture differs from the fixed two-mode
target.

The baseline sections describe the tree SEE-129 recorded; the implementation records near the end
describe subsequent child work. No child has published a package or reset direct data. SEE-132
stops at `npm pack`, exact-tarball isolated installs, and local Docker preparation.

## Fixed destination and scope fence

There are exactly two supported end states:

1. **Direct:** a phone pairs with one independently operated server, receives that server's private
   requests, and returns the result to that same server. The reusable TypeScript server behavior is
   in `server-sdk/`; MCP is one separately runnable adapter in `mcp-server/`.
2. **Public feed:** a publisher sends a common request once to `feed-gateway/`; any subscribed phone
   may read it, but each owner's review, execution, result, and history stay on that phone. The
   gateway receives no subscriber identity or result.

The target roots are `server-sdk/`, `mcp-server/`, `feed-gateway/`, `demo-copytrading/`,
`demo-prediction/`, `proto/`, `android/`, and `deploy/`. A small, non-deployable
`publisher-support/` build dependency is allowed below because both demos currently depend on the
same durable outbox and publication rules; it is not a sidecar, service, or published client
product.

The removed mode is only the shared gateway acting as the private invitation, device binding,
request router, and result return path. Direct pairing and direct results, publisher authentication,
public manifests and requests, snapshot reads, stream tickets, optional topic push, and shared
request types remain.

## 1. Baseline inventory

The repository uses pnpm because `pnpm-lock.yaml` is present. The package manager and runtime pins
are pnpm 12.3.4 and Node 24.21.0 (`package.json`, `.nvmrc`); TypeScript is 6.0.3,
`@modelcontextprotocol/sdk` is 1.30.0, and protobuf-es is 2.14.1. Both current Go modules require Go
1.27.1. Android has `app` and `designsystem` modules, uses Java compatibility 17, and CI uses JDK 21.

| Current concern | Current implementation and entry points | Exact destination | Owner child |
| --- | --- | --- | --- |
| Reusable direct server | `server-sdk/`; public `@seeker-vault/server-sdk` and `./protocol` exports, explicit lifecycle, direct persistence and phone services | `server-sdk/`; transport-neutral TypeScript library | SEE-131 (implemented) |
| MCP application | `mcp-server/src/cli.ts`, `server.ts`, `mcp-endpoint.ts`, `auth.ts`, `oauth.ts`, `config.ts`, `requests/mcp-tools.ts`, `solana/`, packaging script, standalone Compose and `mcp-server/Dockerfile` | `mcp-server/`; executable npm/CLI and Docker app consuming `server-sdk/` | SEE-132 (implemented) |
| Shared public gateway | Go module `broadcast/`; `cmd/broadcast` and `cmd/broadcastctl` | `feed-gateway/`, with the public reader, authenticated publisher, SQLite, stream, and optional push only | SEE-133 |
| Hosted gateway-private mode | `onboarding.proto`, private methods in `publish.proto`, `broadcast/internal/gateway/{invitation,device,private_publisher}.go`, `broadcast/internal/store/private.go`, publisher gateway SDK, and Android invitation/device routing | Removed from active APIs and runtime; only compatibility reservations and one-way local retirement remain | SEE-130 |
| Direct reverse proxy | Misleadingly named `gateway/`; Caddy and Compose around `sidecar` | Optional direct ingress/operator assets under `deploy/`; the product executable/image belongs to `mcp-server/` | SEE-132 and SEE-135 |
| CopyTrading publisher | `publisher/cmd/copytrading`, `cmd/copytrading-admin`, shared `publisher/internal/*`, Compose/Caddy | Independently buildable and runnable `demo-copytrading/` | SEE-134 |
| Prediction publisher | `publisher/cmd/prediction`, prediction discovery/Jupiter packages, shared `publisher/internal/*`, separate Compose overlay | Independently buildable and runnable `demo-prediction/` | SEE-134 |
| Publisher common implementation | One copy in `publisher/internal/{publish,store,manifest,signals,api,...}` | Narrow non-deployable `publisher-support/`; no command, image, listener, or separately shipped feed-publisher client | SEE-134 |
| Wire contracts | `proto/seekervault/{request,server,gateway,proposal,plugin,...}` | `proto/`; remove private gateway services/methods, reserve retired identifiers, retain common/direct/public contracts | SEE-130 and SEE-133 |
| Phone | `android/app`, generated Java/Kotlin, `designsystem` | `android/`; exactly Direct and Public feed connection behavior | SEE-130 |
| Integration/load harnesses | `test-agent/`, `loadtest/`, fixtures, generation scripts | Updated in place to prove the two-mode boundary; never promoted to a third runtime | SEE-130 through SEE-136 as owned below |

### Dependencies and generated code

- pnpm workspace members are `server-sdk`, `mcp-server` and `test-agent`. The MCP source and test
  agent depend on the SDK through the workspace protocol. The staged MCP manifest vendors the SDK's
  built public runtime and contains no workspace/file/registry dependency on the unpublished SDK.
- `scripts/generate.mjs` uses `buf.gen.server-sdk.yaml` for direct protobuf-es code,
  `buf.gen.mcp-server.yaml` for the host's proposal fixture code, and `buf.gen.yaml` for Android code.
  `buf.gen.go.yaml`, `buf.gen.publisher.yaml`, `buf.gen.loadtest.yaml`, and
  `buf.gen.centrifugo.yaml` write the Go and broker surfaces. Generated files are never edited by
  hand. Later children continue to change templates and regenerate atomically.
- `buf.gen.publisher.yaml` currently includes `onboarding.proto` because `publisher/sdk/gateway.go`
  implements gateway-private onboarding. That input disappears with the SDK; retained publisher
  writes continue to generate from `publish.proto` after its private RPCs are removed.
- `buf.gen.yaml` deliberately excludes `publish.proto`: neither a phone nor the direct server is a
  publisher. That boundary remains.
- Root `pnpm run build` recursively builds pnpm packages. Go checks are separate scripts:
  `check:broadcast`, `check:publisher`, and `check:loadtest`; Android is `check:android`.
- `.github/workflows/ci.yml` has Node, broadcast, publisher, Android, and emulator jobs. The later
  directory split must rename the Go jobs and cache paths, add independent demo checks, and retain
  the emulator round trip without turning it into a physical-device claim.

## 2. Current end-to-end paths and trust boundaries

### Direct MCP to phone to the same server

1. An MCP client calls `/mcp` in `mcp-server/src/mcp-endpoint.ts`. `MCP_TOKEN`, or an OAuth access token
   validated by `oauth.ts`, authenticates the agent-facing boundary. Host/origin checks and the body
   limit apply before tool dispatch.
2. `mcp-server/src/requests/mcp-tools.ts` converts the tool call to the public SDK `AgentRequests`
   interface. The rules, lifecycle and `RequestStore` live in `server-sdk/src/` and durably write
   the request and idempotency record to the direct SQLite file.
3. The paired phone calls the SDK's direct Connect services in `server-sdk/src/pairing/`,
   `requests/phone-service.ts`, `updates/service.ts`, and `phone-api.ts`. The per-phone credential,
   stored only as a SHA-256 hash by the server, authenticates this boundary. An FCM message, when
   configured, is only a content-free invalidation; it is not request delivery.
4. Android fetches and validates the request through `ConnectConnectionGateway` and
   `ConnectionRepository`. The owner reviews it, explicitly approves it, and the app invokes Mobile
   Wallet Adapter. Seed Vault Wallet owns keys and signing; the app and server never do.
5. Android stores the local outcome/history and submits the result to that same direct server.
   `RequestStore` verifies the binding/signature/lifecycle and persists the result. The MCP client
   reads it through the same server. A background task may synchronize state, but never opens the
   wallet, signs, submits a transaction, or approves a request.

The authoritative request lifecycle, idempotency, pairing, and agent-visible result are the direct
server's SQLite records. Android is authoritative for the owner's local execution/history and the
wallet remains authoritative for keys. No shared gateway is in this path.

### CopyTrading to public subscribers

1. `publisher/cmd/copytrading` accepts an authenticated JSON signal API request; its
   `PUBLISHER_API_TOKEN` protects the developer-facing write API. `publisher/internal/store` writes
   the signal and publication revision in one transaction to the CopyTrading SQLite file.
2. `publisher/internal/publish` drains that durable state to the gateway's `PublisherService` on
   the publisher listener. `BROADCAST_CREDENTIAL` is the publisher credential; the gateway stores
   only its hash and scopes it to the publisher/server ID.
3. `feed-gateway/internal/gateway` validates the manifest/common request and writes the public document,
   sequence, and `notice` outbox record atomically to the gateway's local SQLite file. This database
   is the feed authority.
4. `feed-gateway/internal/dispatch` sends an invalidation/document to Centrifugo using the HTTP API
   configured by `BROADCAST_STREAM_URL` and `BROADCAST_STREAM_API_KEY`. Centrifugo, not the gateway,
   owns `CENTRIFUGO_ENGINE_REDIS_ADDRESS`; Redis supplies broker recovery/cache and is not durable
   feed authority. Optional FCM topic push is another content-free/public invalidation.
5. A phone adds a public feed reference with no subscriber credential or device registration. It
   reads the unauthenticated `FeedService` snapshot, obtains a signed stream ticket, consumes the
   Centrifugo stream, and maintains its cursor and proposal state locally. Review, wallet execution,
   result, and history are device-local and are never uploaded to the gateway or publisher.

### Prediction to public subscribers

The publication path and trust boundaries after the local database are identical to CopyTrading.
The source differs: `publisher/cmd/prediction` polls a configured provider through
`publisher/internal/discovery` and `publisher/internal/jupiter`, applies its filters, and persists
market, discovery, signal, idempotency, and outbox state in the Prediction SQLite file. Provider
outage does not withdraw a request. The gateway and phone do not learn which provider produced it,
and phones still return no subscriber result.

## 3. Gateway-private removal map

The classification below is normative. A later child must not delete a shared item merely because
gateway-private code also uses it.

### Removable active behavior

| Layer | Current implementation | Required change and destination |
| --- | --- | --- |
| Protocol | All of `proto/seekervault/gateway/v1/onboarding.proto`: `InvitationService`, `DeviceService`, invitations, device bindings/results, and private request records | Remove the active definitions in SEE-130; reserve retired field/enum names and numbers where protobuf permits it, and denylist removed top-level/procedure names in compatibility tests where it does not |
| Publisher protocol | `Create/Get/RevokeInvitation`, `Create/Get/CancelPrivateRequest`, and `RevokePrivateConnection` plus their request/response messages in `publish.proto` | Remove from `PublisherService`; retain public publish/cancel methods and statuses. Protobuf cannot reserve a service method or top-level message name, so freeze their fully qualified names in a no-reuse compatibility test and reserve field identifiers inside any surviving messages |
| Manifest mode | `CONNECTION_MODE_GATEWAY_PRIVATE = 3`, `GatewayPrivate`, and `ServerManifest.gateway_private = 10` | No active third mode; reserve numeric value 3 and field number/name 10 so old bytes cannot be reinterpreted |
| Gateway listeners and handlers | `BROADCAST_CLIENT_ADDRESS`, client mux/listener, private publisher methods, `internal/gateway/{invitation,device,private_publisher}.go`, private auth paths | Delete in `feed-gateway/`; expose only read and publisher surfaces |
| Gateway persistence | Schema-v2 `invitation`, `device_binding`, `private_request`; `internal/store/private.go` | One-way schema migration retires and removes these sensitive routing records after an operator backup; no credential conversion |
| Gateway validation | Private branches in `internal/rules/{manifest,request}.go` | Delete gateway-private routing validation; retain public/common request validation |
| Publisher SDK/examples | `publisher/sdk/gateway.go`, its tests, `examples/gateway-onboarding`, `examples/gateway-website`, and gateway-onboarding docs | Remove; demos publish common requests with ordinary authenticated HTTP/Connect calls |
| Android onboarding | `InvitationReference.kt`, `InvitationGateway.kt`, invitation routes/screens/text, and associated wiring | Remove active invitation UI and network calls; replace persisted legacy connections with an explicit non-runnable retired record/state |
| Android private routing | `ConnectionMode.GatewayPrivate`, private branches in `Connection`, `ConnectionGateway`, `ConnectionRepository`, `ProposalRepository`, manifest validation, operation flow, sync, and application wiring | Remove fetch/result/revoke behavior. A retired record must never be treated as Direct or Feed and must never execute or send a result |
| Proxy/deployment | Invitation/Device/private publisher routes in `broadcast/Caddyfile*`, `deploy/server/Caddyfile*`; port 8092 and `BROADCAST_CLIENT_ADDRESS` in Compose/env/docs | Remove public/private-client routing and the client listener without changing public read/publisher routing |
| Tests/docs | Gateway onboarding/privacy tests, Android gateway-invitation/private-request tests, private examples and guides | Replace with two-mode boundary, migration, and negative-route coverage; do not silently delete compatibility evidence |
| Generated code | Onboarding and private publisher/manifest outputs under `broadcast/internal/gen`, `publisher/internal/gen`, `mcp-server/src/gen`, and Android generated trees | Change proto/templates first, then regenerate with `pnpm run generate`; `check:generated` must be clean |

The concrete Android production files with active branches include
`connections/{Connection,ConnectionGateway,ConnectionRepository,ProposalRepository,ConnectConnectionGateway}.kt`,
`connections/storage/ConnectionStore.kt`, `servers/{ServerManifest,ManifestValidation}.kt`,
`MainActivity.kt`, `SeekerVaultApp.kt`, `SeekerVaultApplication.kt`, connection screens/view models/text,
and operation/inbox policy handling. The focused tests include `GatewayInvitationActivityTest`,
`GatewayInvitationTest`, `GatewayPrivateRequestTest`, `InvitationReferenceTest`, relevant cases in
`AddConnectionRouteTest`, `ConnectionStoreTest`, `ServerManifestTest`, `ProposalRepositoryTest`, and
`StageBoundaryTest`.

### Shared and retained

- `proto/seekervault/request/v2/request.proto` remains, including `PrivateAudience` and
  `RESULT_MODE_RETURN_TO_ORIGIN`. They express a source-scoped direct request/result contract and
  are not a mandate for gateway routing. Common public feed requests keep device-local result
  handling.
- Direct `PairingService`, the phone credential, direct `RequestService.SubmitResult`, request
  lifecycle/idempotency, signature and preparation verification, and direct result polling remain.
- Public `FeedService` manifests/list/get/snapshot methods, stream tickets, and feed topics remain.
  Legacy proposal aliases are handled by their owning migration child, not assumed private.
- Publisher registration and hashed publisher credentials, authenticated public publish/cancel,
  public documents, channel sequence, and the `notice` outbox remain.
- `feed-gateway/internal/{dispatch,stream,relay}` and the public branches of `gateway` and `rules`
  remain alongside the storage contract and its SQLite adapter under `feed-gateway/`.
- Android feed references, snapshots, cursor/recovery, optional topic hints, local proposals/common
  requests, plugins, owner review, local execution, and activity history remain.

### Migration-only compatibility

- Protobuf enum/field identifiers that represented gateway-private behavior are reserved wherever
  the language supports `reserved`; removed service procedures and top-level messages are protected
  by a checked no-reuse list because protobuf has no reservation syntax for them. Old bytes may be
  recognized only to reject or retire them, never to revive a third mode.
- Android reads existing connection files before the mode is removed from its active model. The
  new storage version converts an old gateway-private entry to a clearly labelled retired entry,
  preserves non-secret server/label/history context, deletes its encrypted device credential, and
  excludes it from every network, sync, review, execution, result-delivery, and notification path.
- The UI tells the owner that the hosted private connection is retired and that a fresh direct
  pairing is required if the source offers a direct server. It does not rewrite the endpoint,
  exchange credentials, or infer a feed.
- Gateway schema migration requires a verified SQLite backup, removes the three private tables and
  their indexes in one transaction, increments the schema, and leaves public rows untouched. There
  is no safe mapping from an invitation/device credential/private result to a direct server.

## 4. Server SDK versus MCP application

The split follows ownership, not current file boundaries.

| `server-sdk/` owns | `mcp-server/` owns |
| --- | --- |
| Request identities, lifecycle, idempotency, pending limits, expiry, cancellation, result/binding/signature verification | MCP transport/session setup, tool names and MCP schemas, MCP error/view translation |
| SQLite opening/migrations and request, pairing, update, manifest, prepared transaction, and result stores | Process startup/shutdown, HTTP listener, health endpoint, app configuration, logging, executable npm/CLI entry |
| Direct pairing URI/service, one-use tokens, one active phone policy, credential hashing/revocation | `MCP_TOKEN`, optional OAuth resource-server validation, host/origin/body protections and discovery endpoint |
| Phone-facing Connect services, direct manifest, foreground updates, sync snapshots/events, optional content-free phone invalidation interface | Composition of the SDK's phone services with the MCP endpoint and optional FCM implementation |
| Adapter-neutral `AgentRequests` API and explicit interfaces for preparation, confirmation, clock, storage, push, and provider capabilities | MCP adapters and product/provider behavior: current address/capabilities/sign-message/transfer tools, Solana RPC/transfer/confirmation composition, demo-only tools |
| Core tests and exported TypeScript API; npm metadata prepared for future publication | Standalone Dockerfile, operator config, pairing/operator CLI wiring, and self-hosting docs |

The SDK must not import `@modelcontextprotocol/sdk`, MCP auth/OAuth, an executable config parser,
Solana business/tool naming, or an app listener. The MCP app must not duplicate lifecycle, pairing,
SQLite, idempotency, verification, or update logic. `AgentRequests` is the existing seam to preserve;
`requests/developer-api.ts` is folded into a clearly named SDK adapter only if still needed by a
non-MCP consumer.

The package plan is a private workspace dependency first: `mcp-server` depends on
`server-sdk` using the pnpm workspace protocol, each has its own `package.json`, build/typecheck/test
surface, and the lockfile stays at the repository root. Package and executable metadata are made
packable/testable in SEE-131/SEE-132, but no npm publication occurs in SEE-128.

The two package acceptance boundaries remain distinct. SEE-131 installs the real packed SDK
tarball in a clean project outside the workspace. SEE-132 makes the packed executable MCP artifact
self-contained with respect to that still-unpublished SDK (for example, by bundling the built SDK
into the application artifact): its isolated install cannot require a registry SDK release, sibling
tarball, Docker, TypeScript build, or repository checkout. The MCP runtime keeps its database and
credentials in an explicit stable data directory outside npm installation/cache paths, retains its
HTTP route, and does not claim an unsupported agent-launched stdio transport.

## 5. Independent demo build strategy

The current `publisher/` module compiles four commands into one image and shares one set of internal
packages. The target deliberately separates deployables while keeping the hard persistence rules in
one source location:

```text
publisher-support/        Go module; no cmd/, main package, Dockerfile, listener, or deployment
  gateway/                ordinary authenticated HTTP/Connect PublisherService calls
  publish/                retry/refuse classification and durable drainer
  store/                  deployment stamp, manifest, signal, idempotency and outbox revisions
  manifest/, signals/     common feed document rules
  gen/                    publisher-side generated public contracts

demo-copytrading/         own go.mod, cmd/copytrading, cmd/copytrading-admin, config, API, image,
                          Compose/Caddy/docs and copytrading.db
demo-prediction/          own go.mod, cmd/prediction, discovery/Jupiter/market store extension,
                          config, image, Compose/Caddy/docs and prediction.db
```

Each demo's `go.mod` explicitly requires `publisher-support`; repository builds use a checked-in
workspace/replace arrangement, while a copied demo pins an explicit compatible module revision.
`go build ./...`, its tests, and its Docker build start from the demo directory and do not build or
start the other demo. The CopyTrading image contains no prediction binary/data/provider code; the
Prediction image contains no CopyTrading admin/API binary.

`publisher-support` is a source dependency, not a deployed sidecar and not a required public
`feed-publisher-client` product. Its gateway package is only the demos' ordinary HTTP/Connect
implementation. There is one durable publication/outbox implementation, not two subtly different
copies. Each demo still owns a separate SQLite file, publisher ID/environment stamp, API token,
publisher credential, configuration, and process lifecycle.

Later checks become at least `check:publisher-support`, `check:copytrading`, and
`check:prediction`, with independent CI jobs or explicit independent build steps. The existing
publisher-to-real-gateway contract test moves to support/feed-gateway integration coverage rather
than making one demo build the other.

## 6. Directory, build, image, workflow, and documentation moves

| Current | Target | Compatibility/identity rule |
| --- | --- | --- |
| `broadcast/` Go module, image, service and `broadcastctl` | `feed-gateway/`, renamed gateway command/operator CLI/image/service | Preserve server IDs, publisher IDs, feed references, public URL semantics, DB/volume contents, protocol package names, ports 8090/8091 where practical, uid 10001, and `/data/broadcast.db` compatibility. Rename config prefixes only with aliases/deprecation, never silently |
| `gateway/` direct Caddy/Compose | `deploy/direct/` or the direct portion of `deploy/` | It is ingress for `mcp-server`, not the public feed gateway. Preserve `.env` meaning, direct sidecar data volume, public URL/OAuth behavior, and operator upgrade instructions |
| `sidecar/` | `server-sdk/` plus `mcp-server/` | Keep the direct database schema/data, server ID, pairings, phone credentials, requests/results, manifest/update revisions, public URL behavior, and port defaults. The MCP image can retain a compatibility tag during transition |
| `publisher/` | `publisher-support/`, `demo-copytrading/`, `demo-prediction/` | Preserve each demo's publisher/server ID, environment stamp, database path/volume data, gateway origin and credential. No identity is regenerated because a folder changed |
| `deploy/server/compose.yaml` | Base public-feed stack | Starts `feed-gateway`, Centrifugo, Redis, and proxy only; no demo or MCP server required |
| `deploy/server/compose.direct.yaml` | Optional direct overlay/profile | Starts `mcp-server` and preserves `sidecar-data`; it remains independent of feeds and demos |
| `deploy/server/compose.demos.yaml` | Optional demo overlays or separate demo deployments | CopyTrading and Prediction can be selected independently and each depends only on public feed endpoints plus its support library at build time |
| `scripts/check-broadcast.mjs`, `check-publisher.mjs`, generation/integration/load scripts | Names and paths matching new roots | Preserve command aliases for one migration window where useful; add independent demo commands and prove the base feed stack has no demos/MCP |
| `.github/workflows/ci.yml` | SDK, MCP app, feed gateway, support, each demo, Android, integration/emulator jobs | Cache each actual module, build each demo independently, and keep generated-code verification centralized |
| `docs/development/{sidecar,broadcast,publisher}.md`, wiki/guides/integrations | SDK, MCP server, feed gateway, separate demo, deployment, upgrade and data-retention docs | Update links and terminology in the owning child; explicitly mark old gateway-private guides retired rather than leaving runnable instructions |

The current standalone deployment identities are `seeker-broadcast` with `broadcast-data`,
`proxy-data`, and `proxy-config`; `seeker-publisher` with `publisher-data`; `seeker-prediction` with
`prediction-data`; and direct `seeker-agent-wallet` with `sidecar-data` plus Caddy volumes. The
combined server stack uses `seeker-agent-wallet-server`, `broadcast-data`, demo-specific data/Caddy
volumes, and optional `sidecar-data`. Later Compose migrations use explicit `name`, `volume.name`,
or documented one-time volume reassignment so a service rename does not create an empty database.
No upgrade instruction uses `docker compose down -v`.

`network_mode: service:*` is a current deployment convenience, not an architectural dependency.
SEE-135 gives each process an addressable network endpoint, keeps Redis private to Centrifugo, and
keeps secrets on the process that consumes them. `BROADCAST_PUBLIC_URL` belongs to the feed gateway;
`BROADCAST_STREAM_URL` belongs to the gateway-to-Centrifugo API client;
`CENTRIFUGO_ENGINE_REDIS_ADDRESS` belongs only to Centrifugo; direct `SIDECAR_PUBLIC_URL` (renamed
with compatibility if desired) belongs to the MCP/direct server.

## 7. Persisted data, migration, rollback, and identities

This inventory describes schema, ownership, and paths only; SEE-129 did not open or copy any live
operator database or secret.

| Owner | Current durable data | Migration/retention contract |
| --- | --- | --- |
| Direct server | SQLite schema v6 at `DATABASE_PATH` (`/data/sidecar.db` in Compose): lasting server row; pair tokens; hashed phone credential and connection/wallet binding; requests, idempotency, prepared transactions, results; manifest/update revision, events, snapshots/items/deferred state; FCM token | Move the file/volume intact to `mcp-server`; SDK migrations remain monotonic and transactional. Preserve server ID, active direct pairing, credentials, pending/completed requests, results, update cursor/history. Back up with the documented hot SQLite procedure. Rollback uses the pre-upgrade DB and old image, never a downgraded binary against a newer schema |
| Public gateway | SQLite schema v2 at `BROADCAST_DATABASE_PATH` (`/data/broadcast.db`): retained `publisher`, hashed `publisher_credential`, `manifest`, `proposal`, `channel_sequence`, `notice`; obsolete `invitation`, `device_binding`, `private_request` | Rename/mount the same local file under `feed-gateway`. Back it up; migrate transactionally to a new schema that keeps every public row/revision/outbox entry and drops only the three private tables/indexes. Old binaries refuse a newer schema; rollback restores the v2 backup. Redis is never a substitute for this file |
| CopyTrading | Publisher SQLite schema v2, currently `/data/publisher.db` standalone and `/data/copytrading.db` combined: deployment stamp, manifest, signal, idempotency and outbox revisions | Mount the same data in `demo-copytrading`; preserve publisher/server ID, environment, gateway URL after explicit operator update, revisions and pending publication. Do not share this file with Prediction |
| Prediction | Publisher SQLite schema v2 at `/data/prediction.db`: the same publication records plus market/discovery state | Mount the same data in `demo-prediction`; preserve identity, filters/discovery state, proposals and pending publications. Do not share this file with CopyTrading |
| Android connections | Atomic JSON connection files in `filesDir/connections`, currently version 4; AES-256-GCM credential files version 1 in `noBackupFilesDir/credentials`, bound to connection ID | Preserve Direct and Feed entries/credentials. Decode old gateway-private entries into retired non-executable records, delete their unusable device credentials, preserve display/history context, and require fresh direct pairing. Never reinterpret the token or URL |
| Android local outcomes | Results v5 in `filesDir/results`, proposals v3 in `filesDir/proposals`, activity v1 in `filesDir/activity`, sync v1 in `filesDir/sync`, feed cursors v1 in `filesDir/feeds`, plus local policy/settings | Preserve direct results and public feed subscriptions, cursors, proposal decisions, and activity history. Legacy private request/result bytes may remain only as inert local history or be converted to an explicitly retired representation; no retry/resubmit/resign occurs |

All backups are operator-controlled and must preserve file ownership/mode appropriate to the fixed
unprivileged container UID. Inventory output and docs must never print raw phone, publisher, MCP,
OAuth, Firebase, or broker credentials. Publisher and phone credentials are not convertible across
trust domains: migration preserves valid credentials only for their same retained endpoint.

Rollback is per boundary: stop the new process, restore the matching pre-migration file/volume and
old configuration, and start the old image. A rollback never manufactures gateway-private
credentials after their tables have been retired; operators who might roll back take and retain the
pre-migration encrypted backup before the one-way schema step.

## 8. Behavior that later children must preserve

- **Device count:** `PairingStore` currently permits exactly one active phone per direct server.
  Pairing another revokes the previous credential and cancels its pending requests. SEE-128 adds no
  account system or multi-tenancy and must preserve this behavior unless a separate ticket changes
  it.
- **OAuth:** the current MCP server is an OAuth resource server only. `MCP_OAUTH_ISSUER` enables
  asymmetric JWT/JWKS issuer, audience, expiry, and scope validation; optional resource/JWKS/scope
  settings refine it. The app does not issue tokens, register clients, host consent, or store user
  accounts. With OAuth enabled, `MCP_TOKEN` remains loopback-only. The split keeps this in
  `mcp-server`, not the SDK or feed gateway.
- **Direct operations:** currently supported MCP behavior includes address and capabilities reads,
  message signing, durable get/cancel/acknowledgement tooling, and transfers only when a Solana RPC
  is explicitly configured. The server prepares and verifies; it never holds a key, signs, approves,
  or submits. Confirmation is requested explicitly and uncertainty is not rewritten as success or
  failure.
- **Public operations:** public feeds carry manifests and common requests/proposals. A publisher
  authenticates once per write; readers are anonymous. Tickets authorize broker subscription, not
  result upload. Optional public topic push carries no subscriber ID.
- **Network/config:** SQLite remains a local durable file. Feed read, publisher, Centrifugo API,
  Redis, external feed origin, direct phone origin, MCP auth, OAuth issuer, provider endpoints, and
  push credentials stay separately configured at the process that consumes them.
- **Foreground/background:** direct foreground gRPC/update streams plus unary recovery are
  authoritative; public snapshot plus stream cursor/recovery are authoritative. WorkManager and
  FCM can trigger synchronization only. No background/push path opens a wallet, signs, submits,
  retries an uncertain approval, or widens a request.
- **Owner decision:** manual approval, exact request-to-wallet binding, local policy review,
  idempotency, and explicit uncertain states remain. Migration never re-signs or re-submits an
  existing request.

## 9. Baseline verification and future regression gates

The local SEE-129 results are recorded below after running the exact commands. A physical-device
check is intentionally not part of this child: the ticket forbids installing or running the Android
app on a real device, and this documentation-only change cannot justify touching one.

| Command/check | Result | Evidence/meaning |
| --- | --- | --- |
| Tool versions | **PASS** | Node 24.21.0; pnpm 12.3.4; Go 1.27.1 darwin/arm64; Java launcher 19.0.2 with the repository's Java 21 Gradle daemon criterion; Gradle 9.7.1/Kotlin 2.4.0; Docker client 20.10.22; Git 2.50.1 |
| `pnpm run check` | **PASS** | Prettier/Buf format, ESLint/Buf lint, TypeScript types; sidecar 497/497 and test-agent 39/39 tests passed |
| `pnpm run check:generated` | **PASS** | Generated code and fixtures match the current proto/templates |
| `pnpm run check:broadcast` | **PASS** | Current Go gateway formatting, vet, tests, and build passed |
| `pnpm run check:publisher` | **PASS** | Current publisher formatting, vet, tests, all commands, SDK, and gateway contract passed |
| `pnpm run check:loadtest` | **PASS** | The load harness formats, vets, builds, and passes its deterministic unit tests; full live load scenarios were not run |
| `pnpm run check:android` | **PASS** | 153 Gradle tasks completed: JVM/Robolectric tests, lint, golden verification, APK and test APK; no device install |
| `pnpm run test:hello` | **PASS** | Nine simulated-device direct diagnostic cases passed; the command itself states that this is not a physical Seeker check |
| `pnpm run test:integration` | **PASS with one NOT RUN leg** | Cross-component direct/public run, direct acceptance, and Android cross-component cases passed. Centrifugo stream leg: **NOT RUN**, because `SEEKERVAULT_CENTRIFUGO` was not set |
| `pnpm run build` | **PASS** | Current sidecar and test-agent pnpm package builds passed |
| Physical Seeker install/run | **NOT RUN** | Explicitly prohibited for SEE-129; no physical-device claim |
| Live Solana/provider/FCM/full broker-load checks | **NOT RUN** | No credentials/endpoints requested; the baseline uses deterministic local fakes/captures. The opt-in devnet case reported SKIP, and the broker integration leg reported NOT RUN rather than PASS |

Later-child focused gates:

- SEE-130: removed gateway-private procedure/route negative tests, schema-v2 private-row retirement
  with public-row preservation, Android connection v1-v4 retirement fixtures, direct-result and
  feed-local-result invariants, generated contracts, and no active third mode.
- SEE-131: SDK unit/type/build/pack tests, direct schema fixtures, adapter-neutral boundary tests, and
  a consumer fixture that imports only the packed SDK.
- SEE-132: MCP endpoint/tool/OAuth/pairing/direct result integration, executable npm and Docker
  smoke tests, direct database upgrade, and no feed dependency.
- SEE-133: feed gateway Go checks, focused SQLite storage-boundary/atomic-outbox fixtures,
  publisher/read auth separation, Centrifugo/Redis integration, load isolation, generation, and a
  base gateway with no demo or MCP dependency.
- SEE-134: each demo's independent `go build ./...`, tests and Docker build; shared support tests;
  separate databases/identities; a real local feed-gateway publication path; the other demo absent
  from the build context/runtime.
- SEE-135: Compose config for base/direct/each-demo combinations, fresh and existing-volume upgrade
  rehearsals, process addressability, configurable remote Redis, backup/restore, and clean base
  startup with no demo or MCP app.
- SEE-136: the complete direct/feed/isolation/migration/package/deployment regression matrix, all
  four clean-setup guide walks, stale-path/API/capability scans, final two-mode docs, and explicit
  NOT RUN evidence for any unauthorized or unavailable live/physical check.

### SEE-130 implementation record

SEE-130 removed the private invitation/device procedures, private publisher calls, the gateway's
third listener and proxy routes, generated bindings, publisher SDK helpers/examples, and every
Android network/execution branch for that mode. Removed protobuf identities are reserved and a
descriptor-level deny-list prevents their return; generic direct request/result contracts and
`RETURN_TO_ORIGIN` remain.

Broadcast schema version 3 transactionally removes legacy mode-3 manifests and drops
`private_request`, `invitation`, then `device_binding`. Its fixture starts from schema version 2 and
proves public publisher credentials, feed manifest/proposal bytes, channel sequence and pending
notice survive, then opens the upgraded file again. Operators must back up `broadcast.db` plus its
`-wal` and `-shm` files while the gateway is stopped before upgrade. Rollback means stopping the new
binary and restoring that complete pre-v3 backup before starting the old binary; schema v3 cannot
recreate private credentials or rows and is intentionally refused by an old schema-v2 binary.

Android connection storage version 5 converts only literal legacy `gateway_private` records to an
inert no-mode retirement record. It preserves identity, owner label, former origin and timestamps,
deletes the obsolete device credential, marks waiting result delivery undeliverable, removes
unfinished proposals, clears sync/notification ownership, and leaves Activity history plus all
Direct/Feed records unchanged. Re-running the cleanup is idempotent. A fresh direct pairing code is
required; no old origin, token or identity is converted.

The exact PASS/FAIL/NOT RUN matrix is in [`docs/testing/see-130.md`](../testing/see-130.md). SEE-131
preserved that retirement and did not reinterpret a legacy credential.

### SEE-131 implementation record

SEE-131 moved the direct lifecycle, SQLite stores/migrations, pairing and phone services, update
engine, manifest, signature verification, live bridge and content-free invalidation scheduler into
the root `server-sdk/` workspace package. The schema and all durable identities are unchanged: an
existing direct database reopens with the same server ID, paired phone, wallet binding, requests,
idempotency keys, results and update revisions. Rollback therefore remains the existing database
backup procedure; an older binary must never open a schema it reports as newer.

The current sidecar remains in place for SEE-132. It owns MCP/OAuth, process configuration, TLS and
health listeners, concrete Solana/Firebase adapters, Docker packaging and operator CLI presentation,
but composes the direct engine only through `@seeker-vault/server-sdk` and its documented
`./protocol` export. Boundary tests reject private source imports or a second lifecycle/store.

The private `0.1.0` ESM package declares Node `>=24.21.0 <25`, exact runtime dependencies, two
exports and an allowlist of built output, README and license. `pnpm test:server-sdk-package` runs
real `npm pack --dry-run` and `npm pack`, compares and audits their file lists, installs the tarball
outside the workspace without a link, type-checks both exports, proves import alone opens nothing,
and exercises pairing, observation, duplicate result delivery, close and restart. No npm package,
credential or automatic publish workflow was created. The exact evidence is in
[`docs/testing/see-131.md`](../testing/see-131.md).

### SEE-132 implementation record

SEE-132 moved the self-hosted application from `sidecar/` to `mcp-server/` without changing the
direct schema or moving MCP concerns into the SDK. `src/cli.ts` is the one source, npm and Docker
composition/lifecycle entry; the old source command remains only as the documented
`pnpm dev:sidecar` alias. The standalone image starts no gateway, feed or demo.

`@seeker-vault/mcp-server` 0.1.0 declares the `seeker-agent-connect-mcp` executable and Node
`>=24.21.0 <25`. Source compiles against `@seeker-vault/server-sdk` public entries. Its staging step
copies that built public runtime under `dist/vendor/server-sdk` and rewrites only emitted public
package imports, so the tarball is self-contained without an unavailable workspace, file or
registry dependency. It is not published. `pnpm test:mcp-server-package` audits dry/real packs and
then exercises the exact tarball through clean local, global-style and transient installs outside
the workspace.

Writable npm/source state now defaults to
`~/.seeker-agent-connect/mcp-server/direct-server.db`; an absolute data/config directory is
configurable. Existing source data and Docker `/data/sidecar.db` remain byte-compatible and are
migrated only while all formats are offline. A process lock refuses simultaneous application
owners while leaving the pairing operator command usable. Backup/upgrade/rollback mappings and
the complete evidence matrix are in [`mcp-server/README.md`](../../mcp-server/README.md) and
[`docs/testing/see-132.md`](../testing/see-132.md).

### SEE-133 implementation record

SEE-133 moved the one shared public gateway from `broadcast/` to the canonical root
`feed-gateway/` Go module, renamed its server/operator commands and generated-code/check/CI paths,
and updated the existing demos and integration/load callers in place. It did not extract a demo,
restore a gateway-private route, move direct/MCP behavior, or perform the portable deployment
redesign assigned to later children.

Business, RPC, cursor, and outbox delivery code now depend on the focused contracts in
`internal/storage`; `internal/storage/sqlite` alone owns SQL, migrations, local connections, and
transaction mechanics. The existing schema-v3 file, `/data/broadcast.db`, `broadcast-data` volume,
`BROADCAST_*` variables, protocol packages, server/publisher identities, ports, and uid 10001 remain
compatible. Publication decisions, document/sequence/outbox writes, and the success/fan-out boundary
remain one durable transaction. Focused tests inject a failure after all writes but before commit,
and a sent delivery whose notice acknowledgement is lost, proving rollback and safe duplicate
replay.

The independent Dockerfile and README describe source/image startup, health, publisher
registration, raw Connect JSON manifest/create/update/withdraw/read calls, public versus internal
origins, deterministic revision/error/retry rules, operator logs and all configuration, local file
ownership, update/backup/restore/rollback, and the distinction between current single-host SQLite
and a future adapter plus explicit data migration. Centrifugo remains an external delivery process;
Redis belongs only to Centrifugo and is never gateway storage. Exact evidence is in
[`docs/testing/see-133.md`](../testing/see-133.md).

The next owner is SEE-134. It may extract the demos and their smallest shared support boundary, but
must not perform SEE-135's portable deployment redesign or weaken the feed gateway's public-only
and durable-storage boundaries.

### SEE-134 implementation record

SEE-134 replaced the single `publisher/` module with three root modules: the non-deployable source
library `publisher-support/`, and the independently buildable, imageable and runnable
`demo-copytrading/` and `demo-prediction/`. Each demo's `go.mod` requires the library and resolves
it in a repository checkout through a checked-in `replace` to `../publisher-support`; a copy taken
out of the repository brings that directory or pins an explicit revision, which each demo's README
documents. Neither demo imports, builds or starts the other, and each demo's own
`internal/boundary` package asserts it over that module's source.

Three deviations from §5's one-line sketch were recorded rather than forced, and each is stated in
the code where it applies:

1. **The market/discovery store extension stayed in `publisher-support/store`.** `markets.go`
   writes the market row and the signal in one transaction through the durable engine's own
   unexported `createIn`/`updateIn`/`cancelIn`. Moving it into `demo-prediction` would have meant
   either duplicating that engine or giving up the atomicity the code exists to guarantee, both of
   which SEE-134 forbids. A new `publisher-support/markets` package holds only the persisted record
   types, so nothing about a provider reaches the library or the CopyTrading image; the filters,
   the reconciler and the Jupiter client are `demo-prediction`'s.
2. **The business-API frame stayed shared, in `publisher-support/api`.** Both demos serve the same
   authorization, routing, strict decoding and refusal shape; copying roughly a thousand lines into
   each would be the duplication step 2 forbids. What differs is who writes a demo's signals, which
   the existing `Authorship` seam already models, and the frame no longer imports the provider or
   the reconciler: `Cycles.Filters()` answers a described map. Each demo still owns its listener,
   its token, its identity, its credential, its database and its authorship declaration.
3. **The operator CLI's implementation moved to `publisher-support/publisherctl`**, with a
   three-line `main` in each demo, because both demos answer the same API and neither may import
   the other. The library ships no command of its own.

`internal/publish` split into `gateway/` (the authenticated Connect client) and `publish/` (the
durable drainer), as §5 describes. Generated code moved to `publisher-support/gen`, regenerated
rather than rewritten, from the renamed `buf.gen.publisher-support.yaml`. The two exported test
packages `publishertest` and `demotest` replaced three separate fake gateways and two harnesses; no
command imports them, and a boundary test fails if one does.

Identities and durable data were preserved rather than renamed. `demo-copytrading` keeps the
Compose project `seeker-publisher`, the `publisher-data` volume and `/data/publisher.db`;
`demo-prediction` keeps `seeker-prediction`, `prediction-data` and `/data/prediction.db`;
SEE-135 replaced `deploy/server` with independent portable projects. It does not silently rename
the old combined data: `deploy/README.md` maps `seeker-agent-wallet-server_copytrading-data` together
with `/data/copytrading.db`, `seeker-agent-wallet-server_prediction-data`,
`seeker-agent-wallet-server_broadcast-data`, and `seeker-agent-wallet-server_sidecar-data` to the
new presets as explicit operator choices. Standalone defaults retain their established physical
volume names. No publisher ID, environment stamp, credential, pairing, or revision is regenerated
because orchestration moved.

`pnpm check:publisher` became `pnpm check:publisher-support`, `pnpm check:copytrading` and
`pnpm check:prediction` (with `pnpm check:demos` for all three, one module at a time), and CI's one
publisher job became three, each demo's job also building that demo's image. Exact evidence is in
[`docs/testing/see-134.md`](../testing/see-134.md).

SEE-135 now owns the canonical `deploy/` boundary: four independent application presets, separate
feed/direct ingress projects, and isolated Tailscale/Funnel examples. No preset uses
`network_mode`, Redis is relocatable through Centrifugo configuration, and every SQLite owner stays
local with an explicit volume/file identity. Runtime Docker and physical-device evidence remains
outside this host's available checks and is recorded in `docs/testing/see-135.md`.

## 10. Ordered child ownership and handoff

| Order | Child | Must deliver | Must not steal from later work |
| --- | --- | --- | --- |
| 1 | SEE-129 | This baseline, exact migration/data/rollback map, honest check evidence | No runtime, schema, deployment, data, or package changes |
| 2 | SEE-130 | Remove gateway-private protocol, gateway/publisher SDK, Android mode/UI/storage/network paths and deployment routes; reserve/denylist wire identities; retire persisted private connections/rows while preserving direct/public data and history | No `broadcast/` rename or SDK/app split; no credential/endpoint conversion and no third active mode |
| 3 | SEE-131 | Extract reusable TypeScript `server-sdk/`, preserve direct schemas and API behavior, pack/consumer evidence | No MCP application product or deployment rewrite |
| 4 | SEE-132 | Standalone `mcp-server/` app/CLI/npm metadata/Docker, OAuth/MCP/providers, direct ingress migration | No npm publication; no public feed/demo coupling |
| 5 | SEE-133 | Rename/isolate public-only `feed-gateway/`, focus the SQLite storage boundary, preserve authenticated publication, snapshots, tickets, streams, push and public data | No demo/MCP dependency, subscriber identity/result upload, or database-engine change |
| 6 | SEE-134 | Extract both independent root-level demos and the smallest non-deployable shared support boundary | Neither demo may import/build/start the other; do not duplicate durable store/outbox or create another service/client product |
| 7 | SEE-135 | Separate portable orchestration from optional ingress/host configuration; preserve volumes/identities and make process endpoints configurable | No automatic volume reset, mandatory demo/MCP, network SQLite, or mandatory network-namespace sharing |
| 8 | SEE-136 | Reconcile all active docs/paths/APIs and execute the complete two-mode regression and clean-guide matrix | No package publication, unrun-check claims, parent completion with unresolved failures, or new architecture |

Every child records changed modules/configuration, API/schema effects, data migration and rollback,
exact commands as PASS/FAIL/NOT RUN, limitations, and the next handoff. Later work continues on the
single SEE-128 PR and does not merge or complete the parent until the parent acceptance criteria are
actually met.

## Baseline conclusions

There is no critical contradiction between the current tree and SEE-128. The main migration risks
are identifiable and bounded: preserving direct and public data while retiring sensitive private
gateway rows; splitting the TypeScript app without duplicating lifecycle/storage; splitting the two
demos without duplicating the durable publisher/outbox; and renaming services without orphaning
volumes or identities. The map assigns each to a child and defines its rollback boundary.
