# Solana RPC on the phone (SEE-184)

The phone reads the Solana chain itself in two places: **before** it lets the owner sign — the
address lookup tables a prediction order names, a swap fee's receiving token account, a staking
position — and **after** the wallet sent a transaction, to follow it to finality
([chain-confirmation.md](chain-confirmation.md)). Since SEE-184 both go through one resolver,
`rpc/SolanaRpc.kt`, and the owner can change where each network is asked without a rebuild.

## Networks run side by side

There is **no app-wide active network**. A mainnet Prediction feed and a devnet demo server work at
the same time, and each read names the network its own operation was bound to:

| Read | The network it is about |
| --- | --- |
| Prediction order: lookup tables | the wallet's network, checked equal to the operation's (`ActionOperation.network`) |
| Swap fee account | the same — the swap's wallet network |
| Prediction sale: lookup tables | the network the position was bought on (`HeldPosition.network`), never the wallet's current one |
| SKR staking position | the network the staking request names |
| Confirmation and history reads | the network pinned in the tracking record when the transaction was sent (`ChainTracking.network`) |

Changing a connection, a wallet or an endpoint never moves an existing transaction to another
network. Provider restrictions are unchanged: configuring a devnet endpoint does not make Jupiter
Prediction, Jupiter swap or SKR staking available on devnet — the provider registry still refuses
an operation on a network the provider doesn't declare.

## Which endpoint

For each network, the first that is set of:

1. **The owner's setting** on this phone (Wallet → **Solana RPC**).
2. **This build's endpoint for that network**: `-Pseekervault.solanaRpc.mainnet` / `.devnet` /
   `.testnet` (`BuildConfig.SOLANA_RPC_MAINNET`/`_DEVNET`/`_TESTNET`).
3. **This build's general endpoint**, `-Pseekervault.solanaRpc` (`BuildConfig.SOLANA_RPC`) — the
   legacy setting. It is used for a network only once its genesis hash proves it serves that
   network, so a general-only build keeps working for the cluster its endpoint really is, and for no
   other.

It is a **selection, not a failover**. An endpoint the owner set is the one used; if it is
unreachable the read fails and says so — it is never quietly replaced by the build's endpoint, the
general one, or another network's.

Nothing a server, feed, manifest, proposal or execution provider sends can set or override any of
these. The only writers are the build and the owner.

## The proof: genesis hash

Before anything read through an endpoint counts for a network, `getGenesisHash` must return that
network's genesis hash (`confirmations/ChainReader.kt` `GENESIS_HASHES`). An endpoint that serves
another cluster is refused with `rpc_wrong_cluster` (`SolanaProblem.WrongNetwork`) — for account
reads and for confirmation reads alike — and is never tried for any other network.

The one exception is a **debug build** with an **explicitly configured** devnet or testnet endpoint
(build default or owner setting), which may serve a genesis hash no cluster has: a local
`solana-test-validator`. Mainnet never gets the exception, and neither does the general endpoint.
A debug build also accepts an `http:` URL in the settings sheet, for a loopback validator; a release
build accepts `https:` only, and never a URL with a username or password.

## Changes take effect at once

Saving or resetting an endpoint, in the same process, without a restart:

- drops every cached client and every cached genesis answer, so the next read proves the endpoint
  again — even one that was proven a moment earlier;
- tells the confirmation tracker which network changed (`ConfirmationTracker.endpointChanged`): that
  network's records whose last check stopped at the endpoint (none set, wrong cluster, unreachable,
  refused, unusable, rate limited) are due at once, through the new endpoint. Other networks'
  records are not touched.

Settings are stored in `files/rpc/settings.json` (`rpc/storage/RpcSettingsStore.kt`) and read when
the process starts, so background work (WorkManager's `ConfirmationWorker`) uses them too.

## When there is no usable endpoint

| Where | Missing | Unreachable / rate limited / refused / unusable | Wrong cluster |
| --- | --- | --- | --- |
| Prediction order or sale | not prepared: `no_rpc_endpoint` | not prepared, with the reason | not prepared: `rpc_wrong_cluster` |
| Swap fee account | the swap is prepared **without** a fee and the review says the fee account couldn't be verified (unchanged since SEE-173) | the same | the same |
| SKR unstake / withdraw | refused: the position can't be read | refused | refused |
| Confirmation | the record waits (`no_rpc_endpoint`) and is re-armed when an endpoint is set | retried with backoff; re-armed at once when the endpoint changes | retried; re-armed when the endpoint changes |

Never a parameter-only review, never a blind signature, never another network's endpoint.

## The settings sheet

Wallet → **Solana RPC** shows one card per network: the host in use and where it comes from
("your setting", "this build's endpoint", "this build's general endpoint"), what asking it found
("Serves Solana devnet.", "It serves Solana mainnet, not devnet…", "It couldn't be reached."), a
field for the owner's own URL, **Check and save** (asks the genesis hash and saves only when it
names the network) and **Use build default** (drops the owner's setting).

Only hosts are ever shown outside the field, stored in tracking or history, or put in a log: a URL
can carry an API key. Build values remain extractable client configuration — anybody with the APK
can read them — so a build should carry only a public endpoint or a key meant for distribution
([releases.md](../development/releases.md#android-build-configuration)).

## Where the code is

- `rpc/SolanaRpc.kt` — `SolanaRpc` (precedence, proof, caches, `onChange`, `check`, `save`,
  `reset`), `RpcDefaults`, `RpcSource`, `RpcCheck`.
- `rpc/storage/RpcSettingsStore.kt` — the owner's settings.
- `rpc/RpcSettingsViewModel.kt`, `rpc/RpcSettingsSheet.kt` — the sheet; the design-system side is
  `RpcEndpointCard` / `SolanaRpcSheet` ([spec](../../design/components/rpc-endpoint-card/spec.md)).
- `solana/SolanaAccounts.kt` — `NetworkAccounts`, the network-bound account-read seam.
- `confirmations/ChainReader.kt` — `ChainEndpoints`, implemented by `SolanaRpc`.

Tests: `rpc/SolanaRpcTest` (real JSON-RPC endpoints with controlled answers: simultaneous
mainnet/devnet routing, wrong-cluster rejection, runtime replacement and proof invalidation,
persistence, precedence, legacy general, local validator), `ConfirmationTrackerTest`'s
`aRecordKeepsItsClusterThroughEveryChangeAndResumesWhenItsOwnEndpointIsRepaired`,
`RpcSettingsActivityTest`, and `StageBoundaryTest.everyChainReadGoesThroughTheOneResolver`. The
manual device check is [docs/testing/see-184.md](../testing/see-184.md).
