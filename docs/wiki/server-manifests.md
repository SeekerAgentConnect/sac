# Server manifests, connection modes, and plugin compatibility (SEE-88)

Until Stage 7.1 a connection was one thing: a paired Node sidecar the phone holds a credential for.
Stage 7.1 added a public publisher, broadcasting proposals through the shared gateway. SEE-130
retired the short-lived third gateway-private mode, leaving direct and gateway-feed as the only
active modes. The phone learns which kind it is talking to from the server's validated statement
and never by guessing.

That statement is a **server manifest**. It says who the server is, which phone–server contract it speaks, what revision its settings are at, which transport it uses, where it is reached, and which bundled client plugins its operations need. The phone validates it, caches it, matches its requirements against the plugins compiled into the build it is running, and says plainly whether it supports the server — before anything from that server can be executed.

A publisher does not write one by hand: the templates build and publish it from their configuration, which [`docs/guides/server-development.md`](../guides/server-development.md) walks through.

## The document

[`proto/seekervault/server/v1/manifest.proto`](../../proto/seekervault/server/v1/manifest.proto). It is a separate package from `seekervault.request.v1` because every kind of server publishes one, including the Go gateway (SEE-90) and the publisher templates (SEE-95, SEE-96), which serve no `RequestService` and speak no MCP.

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

The `oneof` is the last field group in the message on purpose. Where a oneof's bytes land in a serialized message is not settled by the protobuf spec — one runtime writes it in field-number order, another writes it after the fields around it — and last is the position every runtime agrees on, which is what lets the cross-runtime fixtures compare bytes at all.

**What is not in it is the point.** There is no field that installs code, asks for a permission, carries or relaxes a policy, or names a wallet endpoint, and no field that could grow into one: a check in `StageBoundaryTest` reads the proto and fails if the field set changes. What the phone will do with a server is decided by the build it is running and by the owner.

## The two connection modes

| | **Direct** | **Gateway feed** |
| --- | --- | --- |
| Whose server | The owner's own (`mcp-server/`) | A developer's public publisher |
| How it is added | A pairing code, `pnpm pair` | A public feed reference |
| Credential on the phone | One issued by the sidecar | **None** |
| Who sees a request | Only the owner who paired | Every subscriber of the channel |
| Who the phone calls | The server itself | The gateway, never the publisher |
| What the server learns | That one phone is paired and the results it receives | Nothing about any phone |

A phone holds any mixture of the two at once, and none affects another. A direct request is never
converted into a broadcast one. Pairing is transport authorization only; it selects no wallet and
authorizes no signing. `docs/architecture.md` has the whole picture.

## What the phone checks, and why

[`servers/ManifestValidation.kt`](../../android/app/src/main/java/io/github/brrenat/seekervault/servers/ManifestValidation.kt) is the only way a manifest becomes something the app will hold, and every rule has one `ManifestProblem` of its own. They fall into three groups.

**Identity.** The manifest has to be about the server this connection already trusts (`other_server`, `bad_server_id`), at the origin it already goes to (`other_endpoint`, `bad_endpoint`), in the mode it is already in (`other_mode`, `no_mode`). Nothing in a manifest can move a connection: it confirms where the phone is talking and can never redirect it. In particular **a connection can never change mode** — a mode is never guessed, and a missing one is refused rather than read as the more permissive case.

**Ownership.** A feed's channel is `server/<server_id>` for the manifest's *own* identity, so a publisher can name only its own channel (`foreign_channel`). The gateway enforces the same rule when it accepts a publication (SEE-90).

**Boundedness.** A revision that can be ordered and doesn't go backwards (`no_revision`, `stale_revision`), at most sixteen well-formed plugin names with usable contract ranges (`bad_plugin`, `duplicate_plugin`, `too_many_plugins`), environments named explicitly (`bad_environment`), and a short, printable name (`bad_name`). A plugin ID is a name: lowercase dot-separated segments, never a URL, a package, or anything loadable.

One thing is deliberately **not** a refusal: a `protocol_version` this build doesn't speak. The server made a perfectly good statement about a contract this app can't act on, which is something to tell the owner — the app needs an update — rather than a fault to report against the server. Only version zero, which is never published, is malformed.

## Caching, and what a revision promises

The phone caches the manifest by validated identity and revision:

- the same revision, same content — nothing changes;
- a higher revision — the manifest is read again and replaces what was held;
- a lower revision — refused (`stale_revision`), because a replayed older manifest would otherwise restore settings the server has moved past;
- the same revision with *different* content — refused (`changed_without_revision`). The revision is the server's promise about the content, so the two disagreeing is a contradiction, and the phone keeps neither version because it has no way to tell which one the server meant.

A server that can't be reached leaves the record exactly as it was: not hearing an answer is not an answer.

The manifest is cached because it is the server's data. **Support is not cached**, ever: a verdict written to disk would outlive the build that reached it, and installing a version of the app that carries a plugin would leave yesterday's "missing" sitting in a file. `serverSupport(record, registry, environment)` is a pure function over the compiled registry and the connection's own environment, called on every read.

## What the owner is told

[`servers/ServerSupport.kt`](../../android/app/src/main/java/io/github/brrenat/seekervault/servers/ServerSupport.kt), in the order the states are reported:

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

### Viewing without executing

A server this build doesn't support is still readable. A request from one is shown in full, can be rejected, and says why it cannot be approved — the affirmative answer is not offered, nothing is prepared for it, and no wallet is opened. It is not a warning the owner can overrule: there is nothing on this phone that would carry the operation out, so there is no tick beside it.

**No silent downgrade.** An unsupported operation never falls back to signing a raw message or a transaction the app couldn't account for. That rule is older than this change — a transaction the phone can't read whole has never had an Approve button (`docs/security.md#inspecting-a-transfer`) — and this adds the server-level half of it.

## Legacy direct

A sidecar from before Stage 7.1 answers `UNIMPLEMENTED` to `GetServerManifest`. That is a documented path, not a defect: the server publishes no manifest, it requires nothing, and the phone keeps calling it exactly as it always has. A connection stored by an older build of the app reads back the same way — as a direct connection whose server has not been asked yet — and the next refresh finds out whether its server publishes one. Pairing, the credential, the request store, the lifecycle, the wallet rules and the owner's policies are untouched by any of this.

## Adding a feed

A feed is added from a reference:

```
seekervault://feed?v=1&gateway=https://gateway.example.com&server=<server ID>
```

It is read by the same rules a pairing code is (`FeedReferences`, which shares the pairing code's own query parser), and unlike a pairing code it **carries no secret** — a feed is a broadcast, so a reference can be printed in a README and holding one grants nothing. The publisher's own address is deliberately absent: the phone resolves the manifest through the gateway, and there is nothing in a reference to contact.

**The existing Add connection screen starts this path.** It scans or accepts a pasted reference,
shows the gateway origin, server ID and public/no-credential boundary for confirmation, and only then
calls `ConnectionRepository.addFeed`. [`FeedGateway`](../../android/app/src/main/java/io/github/brrenat/seekervault/connections/FeedGateway.kt) is still the one seam through which the manifest arrives, and SEE-91's [`ConnectFeedGateway`](../../android/app/src/main/java/io/github/brrenat/seekervault/feeds/ConnectFeedGateway.kt) implements it. The identity, origin and channel are checked before anything is written; no call reaches a publisher and no credential is created. A newly stored feed is observed immediately by the authoritative snapshot, foreground stream and optional topic-subscription owners, without restarting the app. There is deliberately still no Android intent filter for a `seekervault://feed` deep link. The owner flow and every result are in [`feed-onboarding.md`](feed-onboarding.md); the publisher's side is [`docs/guides/server-development.md#5-connect-the-app`](../guides/server-development.md#5-connect-the-app).

## Where the mode lives

`Connection.mode` is stored, and one invariant holds the record together: every active gateway
connection has a validated feed manifest, and a manifest a connection holds always agrees with its
mode. `ConnectionStore` is at version 5. Versions 2–4 containing the retired literal
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
