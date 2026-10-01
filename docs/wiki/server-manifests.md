# Server manifests, connection modes, and plugin compatibility (SEE-88)

Until Stage 7.1 a connection was one thing: a paired Node sidecar the phone holds a credential for.
Stage 7.1 added a public publisher, broadcasting proposals through the shared gateway. SEE-130
retired the short-lived third gateway-private mode, leaving direct and gateway-feed as the only
active modes. The phone learns which kind it is talking to from the server's validated statement
and never by guessing.

That statement is a **server manifest**. It says who the server is, which phone–server contract it speaks, what revision its settings are at, which transport it uses, where it is reached, and which bundled client plugins its operations need. The phone validates it, caches it, matches its requirements against the plugins compiled into the build it is running, and says plainly whether it supports the server — before anything from that server can be executed.

A publisher does not write one by hand: the templates build and publish it from their configuration, which [`docs/guides/server-development.md`](../guides/server-development.md) walks through.

## The document

[`packages/protocol/proto/seekervault/server/v1/manifest.proto`](../../packages/protocol/proto/seekervault/server/v1/manifest.proto). It is a separate package from `seekervault.request.v1` because every kind of server publishes one, including the Go gateway (SEE-90) and the publisher templates (SEE-95, SEE-96), which serve no `RequestService` and speak no MCP.

| Field | What it is |
| --- | --- |
| `server_id` | The server's lasting ID, a lowercase UUID: the one in its pairing code, or the publisher's own |
| `protocol_version` | Which phone–server contract it speaks. `1` is Stage 7.1; zero is never published |
| `settings_revision` | Changes whenever anything else in the manifest does, and never goes backwards |
| `mode` | `direct` or `gateway_feed`. Never absent, and never inferred |
| `required_plugins` | Plugin IDs with the contract range each one is needed at |
| `environments` | `production`, `sandbox`, or both — which the server *serves*; the connection records which one it *keeps* ([environments.md](environments.md)) |
| `display_name` | The name the server calls itself. Optional, bounded, and never verified |
| `direct` / `feed` | One reference, selected by the mode: a URL, or a gateway origin and channel |
| `direct.supported_networks` / `feed.supported_networks` | The Solana networks the server's wallet operations run on (SEE-174). Inside the reference, not beside it; see [Supported networks](#supported-networks) |

The `oneof` is the last field group in the message on purpose. Where a oneof's bytes land in a serialized message is not settled by the protobuf spec — one runtime writes it in field-number order, another writes it after the fields around it — and last is the position every runtime agrees on, which is what lets the cross-runtime fixtures compare bytes at all.

**What is not in it is the point.** There is no field that installs code, asks for a permission, carries or relaxes a policy, or names a wallet endpoint, and no field that could grow into one: a check in `StageBoundaryTest` reads the proto and fails if the field set changes. `supported_networks` is on that list as a statement the phone filters wallet profiles by: it names no wallet, selects none, and cannot bind a connection to one. What the phone will do with a server is decided by the build it is running and by the owner.

## The two connection modes

| | **Direct** | **Gateway feed** |
| --- | --- | --- |
| Whose server | The owner's own (`servers/mcp-server/`) | A developer's public publisher |
| How it is added | A pairing code, `pnpm pair` | A public feed reference |
| Credential on the phone | One issued by the sidecar | **None** |
| Who sees a request | Only the owner who paired | Every subscriber of the channel |
| Who the phone calls | The server itself | The gateway, never the publisher |
| What the server learns | That one phone is paired and the results it receives | Nothing about any phone |

A phone holds any mixture of the two at once, and none affects another. A direct request is never
converted into a broadcast one. Pairing is transport authorization only; it selects no wallet and
authorizes no signing. `docs/architecture.md` has the whole picture.

## What the phone checks, and why

[`servers/ManifestValidation.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/servers/ManifestValidation.kt) is the only way a manifest becomes something the app will hold, and every rule has one `ManifestProblem` of its own. They fall into three groups.

**Identity.** The manifest has to be about the server this connection already trusts (`other_server`, `bad_server_id`), at the origin it already goes to (`other_endpoint`, `bad_endpoint`), in the mode it is already in (`other_mode`, `no_mode`). Nothing in a manifest can move a connection: it confirms where the phone is talking and can never redirect it. In particular **a connection can never change mode** — a mode is never guessed, and a missing one is refused rather than read as the more permissive case.

**Ownership.** A feed's channel is `server/<server_id>` for the manifest's *own* identity, so a publisher can name only its own channel (`foreign_channel`). The gateway enforces the same rule when it accepts a publication (SEE-90).

**Boundedness.** A revision that can be ordered and doesn't go backwards (`no_revision`, `stale_revision`), at most sixteen well-formed plugin names with usable contract ranges (`bad_plugin`, `duplicate_plugin`, `too_many_plugins`), environments named explicitly (`bad_environment`), supported networks that are never unspecified, never repeated and at most eight (`bad_network`, [below](#supported-networks)), and a short, printable name (`bad_name`). A plugin ID is a name: lowercase dot-separated segments, never a URL, a package, or anything loadable.

One thing is deliberately **not** a refusal: a `protocol_version` this build doesn't speak. The server made a perfectly good statement about a contract this app can't act on, which is something to tell the owner — the app needs an update — rather than a fault to report against the server. Only version zero, which is never published, is malformed.

## Caching, and what a revision promises

The phone caches the manifest by validated identity and revision:

- the same revision, same content — nothing changes;
- a higher revision — the manifest is read again and replaces what was held;
- a lower revision — refused (`stale_revision`), because a replayed older manifest would otherwise restore settings the server has moved past;
- the same revision with *different* content — refused (`changed_without_revision`). The revision is the server's promise about the content, so the two disagreeing is a contradiction, and the phone keeps neither version because it has no way to tell which one the server meant. Supported networks are content like any other: the same revision with another set of networks is this case.

A server that can't be reached leaves the record exactly as it was: not hearing an answer is not an answer.

The manifest is cached because it is the server's data. **Support is not cached**, ever: a verdict written to disk would outlive the build that reached it, and installing a version of the app that carries a plugin would leave yesterday's "missing" sitting in a file. `serverSupport(record, registry, environment)` is a pure function over the compiled registry and the connection's own environment, called on every read.

## Supported networks

SEE-174 gave every manifest a statement of the **Solana networks** its server's wallet operations
run on: `supported_networks`, a list of `SolanaNetwork` values (`SOLANA_NETWORK_MAINNET` = 1,
`SOLANA_NETWORK_DEVNET` = 2, `SOLANA_NETWORK_TESTNET` = 3; `SOLANA_NETWORK_UNSPECIFIED` = 0 is never
published). The numbers are the ones `seekervault.request.v1.Network` already gives the same
clusters, so a runtime may convert between the two by number; the enum is declared again rather
than imported because the gateway and the publisher templates never compile the private request
contract. "Network" means a Solana cluster and nothing else: no other blockchain can be named.

It is what the phone decides wallets by. Each connection is bound to exactly one saved wallet
profile ([wallet-profiles.md](wallet-profiles.md)), and the phone offers only profiles on a declared
network when the owner chooses one, refuses to bind a profile on any other, and signs nothing for a
connection whose bound network the manifest doesn't list. A multi-network server doesn't make every
action multi-network: the execution provider for each operation is still asked about the feed's own
network when it is prepared ([execution-providers.md](execution-providers.md)).

### What it means

- **It is what the server actually runs against**, not every network the protocol can name. A
  template that swaps through Jupiter says Mainnet, because Jupiter executes nowhere else; the SKR
  staking server says Mainnet because it refuses to start against any other cluster.
- **Empty means "no networks declared"**, and never Mainnet or "every network". It is what every
  manifest from before SEE-174 reads as, and it is the honest answer for a server whose requests
  never reach a wallet — informational or acknowledge-only — which must not claim a network just to
  fill the field. An up-to-date phone shows such a connection, keeps its requests and history
  readable, and signs nothing for it (`WalletReadiness.NetworksUnknown`): the owner is told that the
  server hasn't declared which networks it supports and has to be updated. A connection may still
  be bound to a profile, because a restricted feed proves its reader with one
  ([restricted-feeds.md](restricted-feeds.md#one-wallet-per-feed)).
- **Network is not environment.** `production` is not Mainnet and `sandbox` is not Devnet or
  Testnet: a sandbox deployment may simulate against Mainnet data, and a production one may execute
  on Devnet. The two fields are configured, published and validated separately
  ([environments.md](environments.md)).
- **A server that stops listing a network doesn't move anyone.** A connection bound to a profile on
  a network the new manifest no longer names becomes `NetworkUnsupported`: it stays bound, signs
  nothing, and waits for the owner to choose a profile on a supported network. Nothing is rebound
  automatically.

### Where it lives, and why

The field is inside the reference, not beside it: `DirectServer.supported_networks` (field 2) and
`GatewayFeed.supported_networks` (field 4), `direct.supportedNetworks` or `feed.supportedNetworks` in
JSON:

```json
"feed": {
  "gatewayUrl": "https://gateway.example.com",
  "channel": "server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "supportedNetworks": ["SOLANA_NETWORK_MAINNET", "SOLANA_NETWORK_DEVNET"]
}
```

A new top-level `ServerManifest` field would have had a higher number than the `reference` oneof.
Go writes a oneof after every other field, while protobuf-es and Java write fields in number order,
so the two would serialize the same manifest to different bytes and the cross-runtime fixtures
([`proto/fixtures/seekervault/server/v1/`](../../packages/protocol/proto/fixtures/seekervault/server/v1/),
including `no_networks` for the empty case) would stop matching. Inside the reference, the reference
is still the last thing written in every runtime.

### Validation, per runtime

| Runtime | Unspecified | Repeated | Unknown value | Order | Bound |
| --- | --- | --- | --- | --- | --- |
| Server SDK (`openDirectServer`) | throws at open | throws at open | throws at open | written ascending | — |
| Publisher support (`network.ParseList`) | — | refused at startup | refused at startup | written ascending | — |
| Gateway (`rules.Manifest`) | `bad_network` | `bad_network` | `bad_network` | stored ascending | three known values |
| Android (`ManifestValidation`) | `bad_network` | `bad_network` | skipped | not part of the statement | at most eight |

The canonical order is ascending by value — Mainnet, Devnet, Testnet — and it is not an error to
write another: every writer normalizes it, so the same set in any order is the same manifest and
the same revision. The gateway refuses a value it doesn't know (`GATEWAY_PROBLEM_BAD_NETWORK` = 55,
field `feed.supported_networks`) rather than dropping it, because relaying a narrower list than the
publisher wrote would change the claim without telling anyone, and it carries the list through the
field-by-field rebuild of the feed reference and through the copy a restricted feed's access policy
is stamped onto ([feed-gateway.md](feed-gateway.md)). The phone, reading a manifest a later format
wrote, **skips** a network it doesn't know instead of refusing the manifest: no profile on this
phone can be on that network, so it could never be offered or signed for, and the networks it does
know are still what the server said. Unspecified, a repeat or more than eight entries are
`ManifestProblem.BadNetwork` (`bad_network`).

### Revisions

Unlike the environments, the networks **may change on a higher revision**. Adding Devnet changes what
a server can do, not what an owner already agreed to: each connection is bound to one profile, and a
network that disappears makes that connection `NetworkUnsupported` rather than moving it. A change
must still move `settings_revision`: the server SDK and publisher support fingerprint the manifest
with the list in it, so a changed list is the next revision on the next start and the same list in
any order keeps the revision. An empty list is left out of the fingerprinted document, so a server
that still declares nothing keeps the revision it had before the field existed. At the same
revision, other networks are a contradiction — `revision_conflict` at the gateway and
`changed_without_revision` on the phone.

### Configuration

| Server | Setting | Default |
| --- | --- | --- |
| Server SDK | `supportedNetworks` on `openDirectServer`; `parseSupportedNetworks` reads a `mainnet,devnet` style value ([server-sdk.md](../integrations/server-sdk.md#declaring-networks)) | none |
| General MCP server | `SAC_SUPPORTED_NETWORKS` ([mcp-server.md](../development/mcp-server.md#configuration)) | none |
| SKR staking server | fixed | Mainnet |
| Publisher templates | `PUBLISHER_SUPPORTED_NETWORKS`, narrowing what the template itself runs on ([demos.md](../development/demos.md)) | the demos: `mainnet`, refusing `devnet` and `testnet`; plain `config.Load`: none |
| Load-test publishers | — | none ([load.md](../development/load.md)) |

The names are `mainnet`, `devnet` and `testnet`, comma-separated, lowercase and without aliases, or
`none` to declare none on purpose.

### Legacy data and deployment order

Everything written before SEE-174 reads as "no networks declared": a manifest without the field, a
manifest the phone cached before format 7 of its connection store, and a legacy direct sidecar that
publishes no manifest at all. None of it is refused, and none of it is guessed.

Deploy **servers first, then phones**. Update direct servers and publishers and set their networks;
the gateway relays the field as soon as it is updated. An older phone ignores the field and keeps
signing as it did. An updated phone gates signing on it, so an updated phone meeting a server that
declares nothing is the combination that stops: the connection stays readable and shows the
server-update state until the server publishes a new revision with its networks. A direct server
that receives a wallet binding on a network it doesn't declare — which only an older phone sends —
stores it as before and logs the mismatch.

Deploy **the gateway before its publishers**, and let its rollout finish first. A gateway older than
SEE-174 drops `feed.supported_networks` while it rebuilds the feed reference, so a publisher that
publishes to it during a rolling deployment is answered `unchanged` against what it already held
and records that revision as confirmed: the networks never arrive (SEE-179, `docs/testing/see-179.md`).
Publisher support recovers from that on its next start. A start always sends the manifest again,
and when the gateway refuses it with `stale_revision` or `revision_conflict` it names the revision
it holds (`GatewayErrorDetail.held_revision`); the publisher then publishes at that revision (which
the gateway answers `unchanged` when it already holds these settings) and, if the gateway holds
something else there, at the next one. The same catch-up is what lets a publisher whose database
did not survive a redeployment — every App Platform restart without a volume — move its manifest
forward instead of being refused for ever. Phones pick the new revision up like any other settings
change.

## What the owner is told

[`servers/ServerSupport.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/servers/ServerSupport.kt), in the order the states are reported:

| State | What it means | Executable |
| --- | --- | --- |
| `ManifestRefused` | The server published something this phone refused | no |
| `ProtocolUnsupported` | The server speaks a contract this build doesn't: update the app | no |
| `EnvironmentUnsupported` | The server doesn't serve the environment this connection keeps (SEE-97) | no |
| `PluginMissing` | This build carries no plugin with a required ID | no |
| `PluginIncompatible` | It carries one, at a contract version the server doesn't work with | no |
| `Supported` | Everything the server requires is here | yes |
| `LegacyDirect` | A direct server that publishes no manifest, and so requires nothing | yes |
| `Unknown` | Not asked yet | yes |

Missing is reported before incompatible because they are different things to be told and the first is the plainer fact. Whether a plugin serves a particular operation in a particular environment is a question about one request, not about the server, and the registry answers it per request (SEE-86).

`Unknown` being executable is deliberate rather than lenient. It can only describe a direct
connection the owner paired and could always act on; gateway connections always arrive with a
validated manifest. An operation a plugin *would* serve still resolves to nothing without one.
Server support is also not the whole answer to "can this be signed": since SEE-174 a connection
signs only when its own wallet profile is ready, which needs a network the manifest declares
([Supported networks](#supported-networks), [wallet-profiles.md](wallet-profiles.md#readiness)).

### Viewing without executing

A server this build doesn't support is still readable. A request from one is shown in full, can be rejected, and says why it cannot be approved — the affirmative answer is not offered, nothing is prepared for it, and no wallet is opened. It is not a warning the owner can overrule: there is nothing on this phone that would carry the operation out, so there is no tick beside it.

**No silent downgrade.** An unsupported operation never falls back to signing a raw message or a transaction the app couldn't account for. That rule is older than this change — a transaction the phone can't read whole has never had an Approve button (`docs/security.md#inspecting-a-transfer`) — and this adds the server-level half of it.

## Legacy direct

A sidecar from before Stage 7.1 answers `UNIMPLEMENTED` to `GetServerManifest`. That is a documented path, not a defect: the server publishes no manifest, it requires nothing, and the phone keeps calling it exactly as it always has. A connection stored by an older build of the app reads back the same way — as a direct connection whose server has not been asked yet — and the next refresh finds out whether its server publishes one. Pairing, the credential, the request store, the lifecycle, the wallet rules and the owner's policies are untouched by any of this. The one thing such a server can no longer do with an up-to-date phone is have anything signed: without a manifest it declares no Solana network, and the phone gates signing until the sidecar is updated to one that declares its networks ([Supported networks](#supported-networks)).

## Adding a feed

A feed is added from a reference:

```
seekervault://feed?v=1&gateway=https://gateway.example.com&server=<server ID>
```

It is read by the same rules a pairing code is (`FeedReferences`, which shares the pairing code's own query parser), and unlike a pairing code it **carries no secret** — a feed is a broadcast, so a reference can be printed in a README and holding one grants nothing. The publisher's own address is deliberately absent: the phone resolves the manifest through the gateway, and there is nothing in a reference to contact.

**The existing Add connection screen starts this path.** It scans or accepts a pasted reference,
shows the gateway origin, server ID and public/no-credential boundary for confirmation, and only then
calls `ConnectionRepository.addFeed`. [`FeedGateway`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/connections/FeedGateway.kt) is still the one seam through which the manifest arrives, and SEE-91's [`ConnectFeedGateway`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/feeds/ConnectFeedGateway.kt) implements it. The identity, origin and channel are checked before anything is written; no call reaches a publisher and no credential is created. A newly stored feed is observed immediately by the authoritative snapshot, foreground stream and optional topic-subscription owners, without restarting the app. There is deliberately still no Android intent filter for a `seekervault://feed` deep link. The owner flow and every result are in [`feed-onboarding.md`](feed-onboarding.md); the publisher's side is [`docs/guides/server-development.md#5-connect-the-app`](../guides/server-development.md#5-connect-the-app).

## Where the mode lives

`Connection.mode` is stored, and one invariant holds the record together: every active gateway
connection has a validated feed manifest, and a manifest a connection holds always agrees with its
mode. `ConnectionStore` is at version 7 (version 7, SEE-174, adds the connection's wallet profile and the manifest's supported networks). Versions 2–4 containing the retired literal
`gateway_private` are rewritten to an inert retirement marker without a mode, credential, or cached
manifest. Other older files remain readable, with a connection written before the environment
version treated as production.

`Connection.usable` still means "the phone can call this connection's sidecar" and therefore
requires direct mode. Feed work checks exactly `GatewayFeed`; retired records match neither path.
That keeps sidecar synchronization, wallet publication and direct push registration away from a
feed and prevents a retired record from fetching, executing, or returning anything.

A feed holds no credential and never did. A retired record is visibly inert and offers a fresh
direct pairing flow; it is not treated as a missing direct credential.

## What this is not

- Not a runtime plugin download, and not a marketplace. A plugin is compiled into the build ([`docs/wiki/client-plugins.md`](client-plugins.md)); a manifest names one, and one this build doesn't carry is reported, never fetched.
- Not an implicit installation, and not a permission request. A manifest cannot ask for anything.
- Not a global mode switch. The mode is per connection, and one server's configuration cannot affect another's.
- Not a visual redesign. The states above are said in the approved design's own components (`docs/design/README.md`): the connection's status line, and one line in place of the approval.
