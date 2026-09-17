# SEE-88 — Server manifests, per-connection modes, and plugin compatibility checks

Stage 7.1, third child of SEE-85. Branch `superset/feat/see-85`.

## What this task is, and what it is not

A connection is currently one thing: a paired Node sidecar the phone holds a credential for. Stage
7.1 adds a second kind — a publisher's broadcast feed read through the shared gateway — and the
phone has to know which kind it is talking to, *from the server's own validated statement* and never
by guessing. This adds that statement (a **server manifest**), stores the mode it selects per
connection, and matches the plugins it requires against the ones this build carries, so the app can
say plainly whether it supports a server before anything is executable.

It is not a plugin download, not a marketplace, not a global mode switch, and not an implicit
installation. A manifest is bounded declarative data: it names identity, protocol, revision, mode,
one endpoint or channel reference, and the plugin IDs it needs. It cannot install code, ask for a
permission, weaken the owner's rules, or choose a wallet endpoint.

## Where the seams already are

| Concern | Owner today |
| --- | --- |
| Pairing a direct server | `pairing/uri.ts` + `connections/PairingCode.kt`, `PairingService.Pair` |
| Capability discovery | `PairingService.GetConnectionCapabilities`, `sync/UpdateTransport.discover`, `UpdateAvailability` |
| Connection record | `connections/Connection.kt`, `connections/storage/ConnectionStore.kt` (one JSON file, `version: 1`) |
| Whether the phone may call a server | `Connection.usable`, read by 30 call sites in every sidecar path |
| Bundled plugins | `plugins/PluginRegistry.kt`, `PluginDescriptor.contract`, `SUPPORTED_PLUGIN_CONTRACTS` |
| Execution | `inbox/InboxViewModel.approve`, `approveTransfer`, `prepare`, with `SigningProblem` |
| The sidecar's identity | `storage/pairing-store.ts`, the `server` singleton table |

`UpdateCapability` is the precedent this follows: a versioned statement a server makes about itself,
discovered over an authenticated RPC, with `UNIMPLEMENTED` meaning *an older server that predates
it* and a typed availability enum on the phone rather than a boolean.

## Design

**1. The manifest is a protocol message — `seekervault/server/v1/manifest.proto`.** A new package,
because the document is not part of the phone↔sidecar durable workflow: the Go gateway (SEE-90) and
the publisher templates (SEE-95, SEE-96) publish one too. `ServerManifest` carries `server_id`,
`protocol_version`, `settings_revision`, `mode`, a `oneof` of `DirectServer { url }` or
`GatewayFeed { gateway_url, channel }`, `required_plugins` (`plugin_id` + `min_contract` /
`max_contract`), `environments`, and a bounded `display_name`. One version number, not two: it says
which phone↔server contract the server speaks, the way `UpdateCapability.protocol_version` does.

**2. The sidecar serves its own — `PairingService.GetServerManifest`.** Authenticated with the
phone credential and scoped to the caller's connection, like `GetConnectionCapabilities`. It always
declares `direct`, its own public URL, no required plugins, and `production`. An older sidecar
answers `UNIMPLEMENTED`, which is the **legacy-direct** path: no manifest, behaviour exactly as it
is today. The revision is real, not a constant: migration 6 adds `manifest_revision` and
`manifest_fingerprint` to the `server` table, and startup bumps the revision when the content the
manifest is built from changes.

**3. The phone validates before it stores, and stores the mode.** `servers/` is a new package of
data and pure functions: the validated `ServerManifest` model, `manifestFrom` with one typed
`ManifestProblem` per rule, and `FeedReference` for `seekervault://feed?v=1&gateway=…&server=…`.
The rules: a lowercase-UUID identity that must equal the one the phone already trusts, a supported
protocol version, a positive revision, an explicit mode, an HTTPS (or permitted-loopback) endpoint
whose origin is the paired server's, a channel the manifest's own server owns
(`channelFor(server_id)`), well-formed bounded plugin IDs, and a printable bounded name. Nothing is
assumed: a connection with no manifest is `direct`, and **no manifest ever turns a paired direct
connection into a feed.**

**4. Support is derived, never stored.** `serverSupport(mode, record, registry, environment)` is a
pure function over the compiled `PluginRegistry`, so a build that adds `jupiter.swap` changes what
the owner sees without rewriting anything on disk — and a stored verdict can never outlive the
build that made it. States, in the order they are reported: `ManifestRefused`,
`ProtocolUnsupported`, `EnvironmentUnsupported`, `PluginMissing`, `PluginIncompatible`,
`Supported`, plus `LegacyDirect` and `Unknown`. Only `Supported` and `LegacyDirect` are executable.

**5. `usable` keeps every sidecar path direct-only.** `Connection.usable` already means "the phone
can still call this connection's sidecar", and it is the gate on refresh, sync, push registration,
wallet publication and every approval. Adding `mode == Direct` to it keeps a feed out of all of
them without touching a single one, which is what "mixed-mode connections work independently"
means in this codebase.

**6. Caching by validated identity and revision.** The connection holds a `ServerRecord`:
`Unknown`, `Legacy`, `Known(manifest)` or `Refused(problem)`. A resolution whose identity or
revision matches the stored one changes nothing; a higher revision replaces it; a *different
identity*, a *lower revision*, a *changed endpoint* and a *changed mode* are refused and recorded —
the credential keeps going to the URL it was paired with, and nothing is silently redirected.

**7. The gateway is a seam, not a stub.** `servers/FeedGateway` is the one way a feed's manifest is
resolved, and the phone never asks the publisher for it. This build carries no implementation,
because the gateway is SEE-90; `addFeed` reports that plainly rather than pretending. Tests use a
fake gateway and assert that no call reached the publisher's own server.

## Items

- [x] `proto/seekervault/server/v1/manifest.proto`, `GetServerManifest` on `PairingService`,
      `pnpm generate`
- [x] Sidecar: `manifest.ts`, migration 6, the stored revision, the RPC, and its tests
- [x] `servers/ServerManifest.kt`: the validated model, `manifestFrom`, `ManifestProblem`
- [x] `servers/FeedReference.kt`: the feed reference and `channelFor`
- [x] `servers/ServerSupport.kt`: the derived states over the compiled registry
- [x] `servers/FeedGateway.kt`: the seam SEE-90 implements
- [x] `connections/`: `mode` + `ServerRecord` on `Connection`, `ConnectionStore` version 2 that
      still reads a version 1 file as legacy direct, `ConnectionGateway.serverManifest`, resolution
      and caching in `ConnectionRepository`, `addFeed`
- [x] `inbox/InboxViewModel`: an unsupported server is viewable and never executable
- [x] UI: the mode and the support state on the connections list and details, in the approved
      design's own components
- [x] `proto/fixtures/seekervault/server/v1/ServerManifest/*`, read by both runtimes
- [x] Tests: validation, support states, the store's legacy path, resolution and caching, mixed
      modes, execution gating, the real sidecar's manifest, `StageBoundaryTest`
- [x] Docs: `docs/wiki/server-manifests.md`, `docs/protocol.md`, `docs/development/sidecar.md`,
      `docs/development/android.md`, `docs/architecture.md`, `docs/security.md`, `AGENTS.md`,
      `CODEBASE.md`, `docs/changelog/2026-09-17.md`
- [x] `pnpm check`, `pnpm check:generated`, `pnpm check:android`, the acceptance suites, and
      deliberate breaks

## Acceptance, from the ticket

- [x] Legacy direct pairing still works with existing stored connections
- [x] A gateway feed is added using its gateway reference without contacting the publisher server
- [x] Tests cover a missing Prediction plugin when Swap is installed, version mismatches,
      malformed manifests, endpoint changes and cached settings refresh
- [x] Unsupported operations stay non-executable and cannot trigger a wallet interaction
- [x] Mixed-mode connections work independently; configuration from one server cannot affect another
- [x] Protocol and UI-state documentation uses the approved design rather than introducing a visual
      redesign

## Review

**What landed.** One new protocol package, one new Android package, one new sidecar module, and a
stored revision.

`proto/seekervault/server/v1/manifest.proto` is the document: identity, the protocol version the
server speaks, a settings revision, the mode, one reference (`DirectServer` or `GatewayFeed`), the
plugin IDs it requires with the contract range each needs, the environments it serves, and a
bounded display name. `PairingService.GetServerManifest` serves it from the sidecar under the
phone's own credential; an older sidecar answers `UNIMPLEMENTED`, which is the legacy-direct path.

The sidecar's revision is real. `sidecar/src/manifest.ts` builds the manifest from configuration
and fingerprints the content; migration 6 adds `manifest_revision` and `manifest_fingerprint` to
the `server` table, and `PairingStore.settingsRevision` bumps the revision exactly when the
fingerprint changes. Restarting with the same settings republishes the same revision; changing the
public URL moves it up by one; it never moves down.

On the phone, `servers/` is data and pure functions. `manifestFrom` validates every rule with its
own `ManifestProblem`, `serverSupport` derives the state from the compiled registry and is never
stored, and `FeedReference` parses `seekervault://feed` by the same rules the pairing code uses.
`Connection` gained `mode` and a `ServerRecord`, held together by one invariant: a feed always has
a validated manifest, and a manifest a connection holds always agrees with its mode.
`ConnectionStore` is at version 2 and still reads a version 1 file — as a direct connection whose
server has not been asked for a manifest yet, which is what it is. It is `Unknown` rather than
`Legacy` on purpose: the server may well publish one, and the next refresh finds out.

**Three decisions worth recording.**

1. *Support is derived, and a verdict is never written to disk.* A stored "unsupported" would
   outlive the build that made it: installing a version of the app that carries `jupiter.swap`
   would leave the old verdict sitting in a file. The manifest is cached, because it is the
   server's data; the match against this build's registry is recomputed every time it is read.
2. *`usable` is the mode gate.* It already meant "the phone can still call this connection's
   sidecar", and it is the condition on refresh, synchronization, push registration, wallet
   publication and every approval path. Adding `mode == Direct` to it excluded feeds from all of
   them at once, instead of thirty independent checks that could each be forgotten — and a feed's
   own reachability is a separate question that SEE-90 answers.
3. *A refused manifest is not a legacy server.* A server that publishes nothing is the documented
   legacy path and stays fully executable. A server that publishes something this phone refuses to
   read — a foreign channel, another origin, an identity that isn't the one it paired with — is
   viewable and not executable. The difference is that one of them never made a claim.
4. *A revision is a promise about content, so the two can contradict each other.* Validation knows
   only the revision the phone holds, so the last rule lives where the cache does: content that
   changed while the revision stood still is refused as `changed_without_revision`, and the phone
   keeps neither version, because it has no way to tell which one the server meant.
5. *Whether cleartext is permitted is decided where a URL enters the phone.* A manifest's endpoint
   has to *equal* the origin the connection already uses, which was checked against the platform's
   network security policy when the pairing code or the feed reference was read. Asking the
   platform again inside the validator would let two builds disagree about a connection that
   already exists, and would have refused every loopback development server — the rule applied to
   the document is the shape of a URL, and equality carries the rest.

**What the guards now hold.** `StageBoundaryTest` gained one check with three parts. `servers/`'s
imports are an exact list — the pairing code's URL and ID rules, the plugin registry and the names
a manifest may use for it, and the protocol message. Nothing in its code (comments removed) names a
wallet, a transport, an HTTP client, a store, an approval, or so much as `suspend`, so reading a
manifest is a decision made from data with no way to act on the answer. And the proto file itself
is read: the field set is named in the check, so a field that could carry a permission, a policy or
a wallet endpoint has to be added there first. The existing plugin-importer check now excludes
`servers/` as a second owner of the registry and names two more importers on purpose:
`ConnectionsViewModel.kt`, which shows the owner whether their servers are supported, and
`ConnectionStore.kt`, which reads a cached manifest's plugin names back off disk.

**What the phone cannot do yet, and why.** This build resolves no feed: `FeedGateway` is the seam,
and the gateway that answers it is SEE-90. `addFeed` says so rather than failing obscurely, and
there is no owner-facing "add a feed" flow, because there is nothing for it to resolve against —
the screen that adds one arrives with the gateway. A feed that *is* stored renders as what it is:
its mode, its support state, and no pretence of a credential it never had.

**Verification.** `pnpm check` (487 sidecar tests, 36 test-agent tests, 0 failures),
`pnpm check:generated`, `pnpm check:android` (Kotlin formatting, 978 Android unit tests, Android
lint, and the debug and instrumentation APKs), `pnpm test:hello`, `pnpm test:queue`,
`pnpm test:transfer`, `pnpm test:updates` and `pnpm test:push` all pass. 79 new tests.

| Break | Failed |
| --- | --- |
| `servers/` naming `ConnectionRepository` | the new boundary check |
| a `wallet_endpoint` field in the manifest proto | the new boundary check |
| accepting a manifest that names another origin | `ServerManifestTest` and `ConnectionManifestTest` |
| letting a missing plugin execute | `ServerSupportTest` and two `InboxViewModelTest` cases |
| refusing a version 1 connection file | the legacy-store test |
| a constant settings revision | the sidecar's revision test |

Each break was time-limited, restored from a copy, and compared with `cmp`.

**Caveats.** **Physical-device checks: NOT RUN.** The owner-visible change is two status lines and
one notice in place of an approval, on paths a device would reach only through a server that
publishes a manifest — and this build carries no plugin for any manifest to require, so there is
nothing a device could show that Robolectric did not. **No Docker daemon was reachable**, so nothing
was built or run as a container. This build resolves no feed: `FeedGateway` is the seam and the
gateway is SEE-90, so a feed exists in tests and in the data path but not yet through a screen.
