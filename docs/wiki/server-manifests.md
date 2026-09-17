# Server manifests, connection modes, and plugin compatibility (SEE-88)

Until Stage 7.1 a connection was one thing: a paired Node sidecar the phone holds a credential for. Stage 7.1 adds a second kind — a developer's publisher, broadcasting proposals to everyone subscribed through the shared gateway — and the phone has to know which kind it is talking to, from the server's own validated statement and never by guessing.

That statement is a **server manifest**. It says who the server is, which phone–server contract it speaks, what revision its settings are at, which transport it uses, where it is reached, and which bundled client plugins its operations need. The phone validates it, caches it, matches its requirements against the plugins compiled into the build it is running, and says plainly whether it supports the server — before anything from that server can be executed.

## The document

[`proto/seekervault/server/v1/manifest.proto`](../../proto/seekervault/server/v1/manifest.proto). It is a separate package from `seekervault.request.v1` because every kind of server publishes one, including the Go gateway (SEE-90) and the publisher templates (SEE-95, SEE-96), which serve no `RequestService` and speak no MCP.

| Field | What it is |
| --- | --- |
| `server_id` | The server's lasting ID, a lowercase UUID: the one in its pairing code, or the publisher's own |
| `protocol_version` | Which phone–server contract it speaks. `1` is Stage 7.1; zero is never published |
| `settings_revision` | Changes whenever anything else in the manifest does, and never goes backwards |
| `mode` | `direct` or `gateway_feed`. Never absent, and never inferred |
| `required_plugins` | Plugin IDs with the contract range each one is needed at |
| `environments` | `production`, `sandbox`, or both (SEE-97) |
| `display_name` | The name the server calls itself. Optional, bounded, and never verified |
| `direct` / `feed` | One reference, selected by the mode: a URL, or a gateway origin and a channel |

The `oneof` is the last field group in the message on purpose. Where a oneof's bytes land in a serialized message is not settled by the protobuf spec — one runtime writes it in field-number order, another writes it after the fields around it — and last is the position every runtime agrees on, which is what lets the cross-runtime fixtures compare bytes at all.

**What is not in it is the point.** There is no field that installs code, asks for a permission, carries or relaxes a policy, or names a wallet endpoint, and no field that could grow into one: a check in `StageBoundaryTest` reads the proto and fails if the field set changes. What the phone will do with a server is decided by the build it is running and by the owner.

## The two kinds of server

| | **Direct** | **Gateway feed** |
| --- | --- | --- |
| Whose server | The owner's own (`sidecar/`) | A developer's publisher (SEE-95, SEE-96) |
| How it is added | A pairing code, `pnpm pair` | A feed reference, through the gateway |
| Credential | The phone holds one, from pairing | **None.** There is nothing to authenticate to |
| Who sees a request | Only the owner who paired | Every subscriber of the channel |
| Who the phone calls | The server itself | The gateway, never the publisher |
| What the server learns | That one phone is paired | Nothing about any phone |

A phone holds any mixture of the two at once, and neither affects the other. A private request is never converted into a broadcast one. `docs/architecture.md` has the whole picture.

## What the phone checks, and why

[`servers/ManifestValidation.kt`](../../android/app/src/main/java/io/github/brrenat/seekervault/servers/ManifestValidation.kt) is the only way a manifest becomes something the app will hold, and every rule has one `ManifestProblem` of its own. They fall into three groups.

**Identity.** The manifest has to be about the server this connection already trusts (`other_server`, `bad_server_id`), at the origin it already goes to (`other_endpoint`, `bad_endpoint`), in the mode it is already in (`other_mode`, `no_mode`). Nothing in a manifest can move a connection: it confirms where the phone is talking and can never redirect it. In particular **a paired direct connection can never become a feed** — a mode is never guessed, and a missing one is refused rather than read as the more permissive case.

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

The manifest is cached because it is the server's data. **Support is not cached**, ever: a verdict written to disk would outlive the build that reached it, and installing a version of the app that carries a plugin would leave yesterday's "missing" sitting in a file. `serverSupport(record, registry, environment)` is a pure function over the compiled registry, called on every read.

## What the owner is told

[`servers/ServerSupport.kt`](../../android/app/src/main/java/io/github/brrenat/seekervault/servers/ServerSupport.kt), in the order the states are reported:

| State | What it means | Executable |
| --- | --- | --- |
| `ManifestRefused` | The server published something this phone refused | no |
| `ProtocolUnsupported` | The server speaks a contract this build doesn't: update the app | no |
| `EnvironmentUnsupported` | The server doesn't serve the environment the app runs in | no |
| `PluginMissing` | This build carries no plugin with a required ID | no |
| `PluginIncompatible` | It carries one, at a contract version the server doesn't work with | no |
| `Supported` | Everything the server requires is here | yes |
| `LegacyDirect` | A direct server that publishes no manifest, and so requires nothing | yes |
| `Unknown` | Not asked yet | yes |

Missing is reported before incompatible because they are different things to be told and the first is the plainer fact. Whether a plugin serves a particular operation in a particular environment is a question about one request, not about the server, and the registry answers it per request (SEE-86).

`Unknown` being executable is deliberate rather than lenient. It can only describe a direct connection the owner paired and could always act on, its requests are the actions the app carries out itself, and a server that published nothing it needs cannot be found wanting for it. An operation a plugin *would* serve still resolves to nothing without one.

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

**This build resolves no feed.** [`FeedGateway`](../../android/app/src/main/java/io/github/brrenat/seekervault/connections/FeedGateway.kt) is the one seam a feed's manifest comes through, and the gateway that answers it is SEE-90. `ConnectionRepository.addFeed` reports `NoGateway` rather than pretending a feed resolved, and there is no owner-facing screen for adding one yet, because there is nothing for it to resolve against. What the data path does is already held by tests against a fake gateway: the identity, the origin and the channel are checked before anything is written, no call reaches a publisher, no credential is created, and the feed survives a restart as the feed it is.

## Where the mode lives

`Connection.mode` is stored, and one invariant holds the record together: a feed always has a validated manifest, and a manifest a connection holds always agrees with its mode. `ConnectionStore` is at version 2; a version 1 file is still read.

`Connection.usable` — which already meant "the phone can still call this connection's sidecar" — now also requires the direct mode. That one condition is the gate on refresh, synchronization, push registration, wallet publication and every approval path, so a feed is excluded from all of them at once instead of in thirty places that could each be forgotten. What a feed's own reachability means is the gateway's question, and SEE-90 answers it.

A feed holds no credential and never did, which is not the same as one having gone missing, so the connection screens read the mode first and say what it is.

## What this is not

- Not a runtime plugin download, and not a marketplace. A plugin is compiled into the build ([`docs/wiki/client-plugins.md`](client-plugins.md)); a manifest names one, and one this build doesn't carry is reported, never fetched.
- Not an implicit installation, and not a permission request. A manifest cannot ask for anything.
- Not a global mode switch. The mode is per connection, and one server's configuration cannot affect another's.
- Not a visual redesign. The states above are said in the approved design's own components (`docs/design/README.md`): the connection's status line, and one line in place of the approval.
