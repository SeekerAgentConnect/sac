# Solana RPC on the phone (SEE-184, SEE-185)

The phone reads the Solana chain itself in two places: **before** it lets the owner sign — the
address lookup tables a prediction order names, a swap fee's receiving token account, a staking
position — and **after** the wallet sent a transaction, to follow it to finality
([chain-confirmation.md](chain-confirmation.md)). Since SEE-184 both go through one resolver,
`rpc/SolanaRpc.kt`. Since SEE-185 the endpoint each network is asked at comes from the trusted
gateway's client configuration ([client-config.md](client-config.md)), so the gateway's operator can
change it without a rebuild.

## Networks run side by side

There is **no app-wide active network**. A mainnet Prediction feed and a devnet demo server work at
the same time, and each read names the network its own operation was bound to:

| Read | The network it is about |
| --- | --- |
| Prediction order: lookup tables | the operation's network (`ActionOperation.network`): for a feed signal, the network the signal declares, which the wallet is chosen for (SEE-185) |
| Swap fee account | the same — the swap's network, which must also be the fee's network |
| Prediction sale: lookup tables | the network the position was bought on (`HeldPosition.network`), never the wallet's current one |
| SKR staking position | the network the staking request names |
| Confirmation and history reads | the network pinned in the tracking record when the transaction was sent (`ChainTracking.network`) |

Changing a connection, a wallet or an endpoint never moves an existing transaction to another
network. Provider restrictions are unchanged: configuring a devnet endpoint does not make Jupiter
Prediction, Jupiter swap or SKR staking available on devnet — the provider registry still refuses
an operation on a network the provider doesn't declare.

## Which endpoint

For each network, the endpoint the trusted gateway's client configuration names for it
(`FeedService.GetClientConfig`, set by the operator with `feed-gatewayctl client-config set-rpc` or
the admin page). That is the **only source**: there is no build default, no owner setting on the
phone, and no fallback. A network the gateway configures no endpoint for has none, and its reads
fail and say so. They never reach another network's endpoint.

Nothing a server, feed, manifest, proposal or execution provider sends can set or override an
endpoint. The only writer is the gateway's operator, and the app reads only the one gateway its
build names.

The app caches the configuration and refreshes it on first use, on foreground, when a feed stream
(re)connects and before every new operation is prepared. A new operation is prepared only with a
configuration fetched within the last 24 hours. Reads about what was already sent (confirmations,
history, positions) use the cached endpoints whatever their age, because they only read and every
endpoint must still pass the proof below ([client-config.md](client-config.md#how-the-app-uses-it)).

## The proof: genesis hash

Before anything read through an endpoint counts for a network, `getGenesisHash` must return that
network's genesis hash (`confirmations/ChainReader.kt` `GENESIS_HASHES`). An endpoint that serves
another cluster is refused with `rpc_wrong_cluster` (`SolanaProblem.WrongNetwork`) — for account
reads and for confirmation reads alike — and is never tried for any other network.

The one exception is a **debug build**'s devnet or testnet endpoint, which may serve a genesis hash
no cluster has: a local `solana-test-validator`. Mainnet never gets the exception. A debug build also
accepts an `http:` endpoint in the configuration, for a loopback validator (the gateway accepts plain
`http://` only to `localhost`, `127.0.0.1` or `[::1]`); a release build accepts `https:` only. The
gateway and the app both refuse a URL with a username, password or fragment.

## Changes take effect at once

When the app validates a configuration whose endpoints differ from the ones in use,
`SolanaRpc.apply` replaces them in the same process, without a restart:

- it drops every cached client and every cached genesis answer, so the next read proves the endpoint
  again, even one that was proven a moment earlier. A proof that was in flight during the change is
  not kept;
- it tells the confirmation tracker which networks changed (`ConfirmationTracker.endpointChanged`):
  that network's records whose last check stopped at the endpoint (none configured, wrong cluster,
  unreachable, refused, unusable, rate limited) are due at once, through the new endpoint. Other
  networks' records are not touched.

The configuration is cached in `files/config/client.json` and read when the process starts, so
background work (WorkManager's `ConfirmationWorker`) uses the same endpoints.

## When there is no usable endpoint

| Where | The gateway configures no endpoint | Unreachable / rate limited / refused / unusable | Wrong cluster |
| --- | --- | --- | --- |
| Prediction order or sale | not prepared: `no_rpc_endpoint` | not prepared, with the reason | not prepared: `rpc_wrong_cluster` |
| Swap fee account | the swap is prepared **without** a fee and the review says the fee account couldn't be verified (unchanged since SEE-173) | the same | the same |
| SKR unstake / withdraw | refused: the position can't be read | refused | refused |
| Confirmation | the record waits (`no_rpc_endpoint`) and is re-armed when the gateway configures one | retried with backoff; re-armed at once when the endpoint changes | retried; re-armed when the endpoint changes |

Never a parameter-only review, never a blind signature, never another network's endpoint.

Before any of this, a new feed operation needs a usable client configuration: one fetched now, or a
cached one fetched within 24 hours. Without one (no gateway in the build, the gateway unreachable for
longer, or an answer the app refuses) the operation is not prepared and the review says why.

## What is shown and stored

Only hosts are ever shown, stored in tracking or history, or put in a log. The endpoints themselves
are public client configuration: any client of the gateway can read them in full, path and query
included. An operator whose provider needs a secret key puts a proxy of their own in front of it and
configures the proxy's URL ([client-config.md](client-config.md#security)).

## Where the code is

- `rpc/SolanaRpc.kt` — `SolanaRpc` (the gateway's endpoints, proof, caches, `apply`, `onChange`).
- `config/ClientConfigRepository.kt` — where the endpoints come from, and when they are refreshed
  ([client-config.md](client-config.md)).
- `solana/SolanaAccounts.kt` — `NetworkAccounts`, the network-bound account-read seam.
- `confirmations/ChainReader.kt` — `ChainEndpoints`, implemented by `SolanaRpc`.

The SEE-184 owner setting is removed: Wallet → Solana RPC, `rpc/RpcSettingsViewModel.kt`,
`rpc/RpcSettingsSheet.kt`, `rpc/storage/RpcSettingsStore.kt` and the design system's
`RpcEndpointCard`. A phone that saved endpoints under SEE-184 no longer reads them.

Tests: `rpc/SolanaRpcTest` (real JSON-RPC endpoints with controlled answers: simultaneous
mainnet/devnet routing, wrong-cluster rejection, runtime replacement and proof invalidation, local
validator), `ConfirmationTrackerTest`'s
`aRecordKeepsItsClusterThroughEveryChangeAndResumesWhenItsOwnEndpointIsRepaired`, and
`StageBoundaryTest.everyChainReadGoesThroughTheOneResolver`. The manual device check is
[docs/testing/see-185.md](../testing/see-185.md).
