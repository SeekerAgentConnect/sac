# Client configuration from the trusted gateway (SEE-185)

The app's build names exactly **one gateway**, and that gateway's operator decides two things for
every app that trusts it: which **Solana RPC endpoint** each network is read through, and the **SAC
swap service fee** (SEE-173) a feed swap carries. The app fetches both from
`FeedService.GetClientConfig`, caches them, and applies a change without a rebuild or a restart.
There is no RPC or fee screen on the phone, no RPC URL or fee in the APK, and nothing a publisher,
manifest, signal, link or direct server sends can set or override either.

Before SEE-185 the endpoints were build defaults the owner could replace on the phone (Wallet →
Solana RPC, SEE-184) and the fee was compiled into the APK (`seekervault.swapFee.*`). Both are gone;
see [Migrating](#migrating).

## What applies to which connection

| | Direct connection | Feed connection |
| --- | --- | --- |
| Wallet and network | The connection's explicit wallet and network binding, unchanged | The network each signal declares (`action.network`); the wallet is chosen on the phone per operation, for that network |
| Access to a restricted feed | — | A separate access identity, independent of the wallet that executes ([restricted-feeds.md](restricted-feeds.md)) |
| SAC swap fee | Always zero | The gateway's fee for the signal's network (zero unless the operator set one) |
| Preview or execute | Always executes (production only) | The owner's choice per operation on the phone ([environments.md](environments.md)) |
| Solana RPC | The gateway's endpoint for the binding's network | The gateway's endpoint for the signal's network |
| What the gateway learns | Nothing: the config request is empty and carries nothing about a direct request or binding | The feed reads it already serves; the config request itself carries nothing |

## The trusted gateway

`-Pseekervault.gatewayUrl` (release: `SEEKERVAULT_GATEWAY_URL`) compiles one origin into
`BuildConfig.GATEWAY_URL`. It must be an origin — scheme and host, no path, query, user info or
fragment — or the Gradle build fails. Everything the app reads from a gateway comes from there: the
feeds it reads, the Discover catalog ([discover.md](discover.md)), the push relay a direct server
may use, and this configuration. A feed link, Discover card or manifest that names another gateway
is refused (`FeedReferenceProblem.ForeignGateway`); a build with no gateway refuses every one
(`NoGateway`) and prepares no feed operation. A feed stored from a gateway the build no longer
trusts is `ServerSupport.OtherGateway`: readable, never executable.

An empty value is allowed for a checkout build. It reaches no gateway, so no test quietly depends on
somebody else's deployment.

## The wire message

`GetClientConfig` takes an empty request and answers `GetClientConfigResponse { ClientConfig config }`
(`packages/protocol/proto/seekervault/gateway/v1/feed.proto`):

| Field | Meaning |
| --- | --- |
| `revision` | Moves by exactly one with every saved change and never goes backwards. Saving what is already held does not move it |
| `rpc_endpoints[]` | `{ network, url }`, at most one per network, ascending by network. A network with none has no endpoint |
| `swap_fee.bps` | Whole basis points, 0–100. Absent or 0 is no fee |
| `swap_fee.network` | The network the owner and accounts exist on; required when `bps` is not 0 |
| `swap_fee.owner` | The base58 wallet that owns every receiving token account |
| `swap_fee.accounts[]` | `{ mint, token_account }`, one receiving account per output mint, ascending by mint |

A gateway that was never configured answers **revision 0, no endpoints and a zero fee**. That is a
configuration. Having no answer at all is something else, and the app never reads it as a zero fee.

The method is on the gateway's read listener, unauthenticated, rate limited like every other read,
and served with `Cache-Control: no-store`. The answer is the same for every caller and asking
records nothing, so it says something about the deployment and never about the caller.

## Configuring it

The operator sets it on the gateway's admin page or with `feed-gatewayctl`. Both read the held
configuration, replace one half of it, and save the whole through the same store method
(`SaveClientConfig`), which validates it with `rules.ClientConfig`. The two surfaces accept and
refuse exactly the same things.

**Admin page.** `<admin path>/client-config` (`/admin/client-config` by default), **Client
configuration** in the sidebar. It shows the revision being served and the current fee in one
sentence, then two separate forms: three endpoint fields (Mainnet, Devnet, Testnet; empty for none)
with **Save endpoints**, and the fee form (rate, network, owner wallet, and receiving accounts as one
`mint=token_account` per line) with **Save fee**. After a refusal the form keeps what was typed.

**CLI.**

```sh
feed-gatewayctl client-config show                                # the held configuration, as JSON
feed-gatewayctl client-config set-rpc --network mainnet --url https://rpc.example.com
feed-gatewayctl client-config set-rpc --network devnet            # no --url, or an empty one, removes it
feed-gatewayctl client-config set-fee --bps 20 --network mainnet --owner <FEE_WALLET> \
  --account EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v=<FEE_USDC_ACCOUNT>
feed-gatewayctl client-config set-fee --bps 0                     # no fee
```

`--database` (or `BROADCAST_DATABASE_PATH` / `BROADCAST_DATABASE_URL`) names the gateway's database,
as for every other `feed-gatewayctl` command. `set-fee` replaces the whole fee, so `--bps` is
required. Each save prints the new revision, or says the configuration was unchanged and keeps the
revision where it was. The full fee procedure, including preparing the receiving accounts, is in
[swap-fee-config.md](../development/swap-fee-config.md).

No new environment variable is involved. The configuration is a single-row `client_config` table
(SQLite schema version 9; Postgres schema version 5 in the `gateway` schema, with row-level
security enabled like every other table there).

## Validation

A configuration that breaks any rule is **refused whole**: nothing is saved, and no rate or
recipient is adjusted to fit.

| Part | Rule |
| --- | --- |
| Endpoint network | mainnet, devnet or testnet, each at most once |
| Endpoint URL | Absolute `https://`, at most 512 bytes; plain `http://` only to `localhost`, `127.0.0.1` or `[::1]`; no user name or password, no fragment, no whitespace or control characters. A path or query is kept as given |
| Fee rate | Whole basis points from 0 to 100, never clamped |
| Nonzero rate | Needs a network, an owner and at least one account |
| Addresses | The owner, every mint and every account is a base58 public key of 32 bytes |
| Accounts | At most 16; each mint once; an account is neither the owner wallet nor its own mint |
| Anything set | Checked even at 0 bps, so a half-finished fee is caught when it is typed |

The app validates the answer again with the same rules (`config/ClientConfig.kt`
`clientConfigFrom`) and refuses an invalid one whole: it never uses part of it, and an invalid
nonzero fee is not read as a zero one. A debug build also accepts `http:` endpoints, for a local
validator.

## How the app uses it

`config/ClientConfigRepository.kt` holds the policy:

- **Where from.** Only `BuildConfig.GATEWAY_URL`.
- **When.** The cached answer is read when the process starts. The app asks the gateway again at
  first use, whenever it comes back to the foreground, whenever a feed stream (re)connects, and
  before every new operation is prepared.
- **Failure.** With no usable configuration (no gateway in the build, gateway unreachable and
  nothing recent enough cached, or an answer the app refuses) the operation is not prepared and the
  review says why. A failed fetch is never a zero fee, never a guessed endpoint and never a guessed
  network.
- **Updates.** A validated answer that differs from the one held replaces it at once. The RPC
  resolver applies the new endpoints, dropping every cached client and genesis proof, and that
  network's waiting confirmation checks are due again.

### The cache

- **Where.** The last validated answer is kept in `files/config/client.json` with the time it was
  fetched (`config/storage/ClientConfigStore.kt`). A file this build can't read counts as no cache.
- **Preparing with a cached answer.** When the gateway can't be reached, a new operation may be
  prepared with the cached configuration for up to **24 hours** after it was fetched
  (`MAX_CONFIG_AGE`), and not after that. A fetch time more than five minutes in the future is
  treated as a clock that moved, not as a fresh cache.
- **Reads about what was already sent.** Confirmation, history and position reads keep using the
  cached endpoints whatever their age. They only read, and every endpoint must still prove its
  network by genesis hash first ([solana-rpc.md](solana-rpc.md#the-proof-genesis-hash)).

## Fee terms belong to the preparation

Core decides an operation's fee and hands it to the provider as `ActionOperation.serviceFee`
(`ServiceFeeTerms`); a provider reads no configuration of its own.

- **Feed operations:** the gateway's terms for the signal's network (`SwapFeeConfig.termsFor`). A
  fee configured for another network is no fee here, because the same mint on another cluster is
  another asset. Only a Jupiter swap applies the terms. It uses SEE-173's checks unchanged: each
  receiving account is verified on chain before the fee is added, and the review discloses it
  ([jupiter.md](../integrations/jupiter.md#the-sac-service-fee-see-173)).
- **Direct operations:** always `ServiceFeeTerms.None`.

The terms are pinned with the preparation (`OperationReview.preparedFee`). Before the owner's
approval goes to the wallet, and again inside the wallet lock, the held configuration's terms for
that network are compared with the pinned ones. If the rate, owner or any account moved, the
review stops with **"The service fee configured for this app changed after this was prepared"**
(`OperationProblem.FeeTermsChanged`). The preparation is dropped and the owner prepares and reviews
again. An operation that was already sent keeps the receipt recorded in its binding, so History
shows the fee that was approved.

## Security

- **Everything in it is public.** Any client can read every value, including a URL's path and query.
  The gateway refuses a URL with user info or a fragment. A provider key in a path or query is a key
  distributed to every client.
- **No secrets.** If an RPC provider needs a secret key, put a proxy of your own in front of it and
  configure the proxy's URL.
- **One writer.** Only the operator, through the admin page or the CLI. No publisher API, manifest
  field, signal field, feed link, Discover card or direct server answer can set an endpoint or a fee,
  or name a second gateway to fetch them from.
- **Proven before trusted.** An endpoint is used for a network only after its genesis hash names
  that network. One that serves another cluster is refused, and no network falls back to another's
  endpoint.
- **Fee bounds hold on both sides.** The gateway and the app both refuse a rate above 100 bps, and the
  app verifies every receiving account on chain before a swap carries a fee.

## What publishers and signals cannot do

A publisher chooses the network each signal executes on, within its manifest's
`supported_networks` ([server-manifests.md](server-manifests.md#supported-networks)). It cannot
choose the endpoint that network is read through, the fee, the fee's recipient, or whether the
owner previews or executes. A signal that names no network, or one the app does not know, stays
readable and is never executed. Nothing infers one for it.

## Migrating

| Before SEE-185 | Now |
| --- | --- |
| `-Pseekervault.solanaRpc`, `.solanaRpc.mainnet`/`.devnet`/`.testnet`, `SEEKERVAULT_SOLANA_RPC*` | `feed-gatewayctl client-config set-rpc` (or the admin page) |
| `-Pseekervault.swapFee.*`, `SEEKERVAULT_SWAP_FEE_*`, `pnpm check:swap-fee-build` | `feed-gatewayctl client-config set-fee` (or the admin page) |
| `-Pseekervault.relayUrl`, `-Pseekervault.discoveryUrl`, `SEEKERVAULT_RELAY_URL`, `SEEKERVAULT_DISCOVERY_URL` | `-Pseekervault.gatewayUrl` / `SEEKERVAULT_GATEWAY_URL` |
| Wallet → Solana RPC on the phone | Removed; the endpoints are the gateway's |

The release script reports a retired variable that is still set as "retired and ignored" and builds
without it ([releases.md](../development/releases.md#android-build-configuration)). An owner's saved
SEE-184 endpoint settings are no longer read.

## Where the code is

Gateway (`services/gateway`):

- `internal/rules/clientconfig.go`: `ClientConfig` (validation and canonical form), `NetworkName`,
  `NetworkNamed`, `IsAddress`.
- `internal/storage/storage.go`: `StoredClientConfig`, `ClientConfig`, `SaveClientConfig`.
- `internal/storage/sqlite/clientconfig.go`, `internal/storage/postgres/clientconfig.go`: the
  single-row table and its revision.
- `internal/gateway/feed.go`: `Feed.GetClientConfig`.
- `internal/admin/clientconfig.go`, `internal/admin/templates/clientconfig.html`: the admin page.
- `cmd/feed-gatewayctl/main.go`: `client-config show | set-rpc | set-fee`.

Android (`apps/android/app/src/main/java/io/github/brrenat/seekervault`):

- `config/ClientConfig.kt`: `ClientConfig`, `SwapFeeConfig.termsFor`, `clientConfigFrom`.
- `config/ClientConfigRepository.kt`: the refresh, cache and age policy (`MAX_CONFIG_AGE`),
  `ConfigAvailability`, `ConfigProblem`.
- `config/storage/ClientConfigStore.kt`: the cache file.
- `feeds/ConnectFeedGateway.kt`: `clientConfig`, the fetch.
- `rpc/SolanaRpc.kt`: `apply`, the endpoints the configuration names.
- `operations/OperationViewModel.kt`: the fee terms an operation is prepared with and the
  `FeeTermsChanged` check.
- `plugins/ExecutionProvider.kt`: `ActionOperation.serviceFee`, `ServiceFeeTerms`.

Tests and the manual check: [docs/testing/see-185.md](../testing/see-185.md).
