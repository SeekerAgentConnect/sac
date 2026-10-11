# SAC swap service fee: gateway configuration (SEE-173, SEE-185)

The app can add an integrator fee to feed swaps routed through Metis (Jupiter's v1 Swap API). The
swap program credits it to public token accounts the operator controls. Since SEE-185 the fee is
part of the trusted gateway's **client configuration**: the gateway's operator sets it, and every app
whose build names that gateway fetches it ([wiki/client-config.md](../wiki/client-config.md)). No fee
is compiled into the APK, and a change applies without a rebuild. **A gateway that was never
configured charges no fee**, and zero is the default.

This page is the operator's reference: the fields, their bounds, how to prepare the receiving
accounts, how to set the fee, and how to check a fee-bearing swap on mainnet. What the phone does
with the fee is in [integrations/jupiter.md](../integrations/jupiter.md#the-sac-service-fee-see-173).

## Where the fee applies

| Operation | Fee |
| --- | --- |
| A `jupiter.swap` signal from a feed, on the fee's network, whose output mint has a receiving account | The configured rate |
| A feed swap into a mint with no receiving account | None. The review says "not charged on this pair" |
| A feed swap on a network other than the fee's | None |
| A Jupiter Prediction order | None. The order schema has no integrator fee parameter |
| Any operation of a direct connection | Always none (`ServiceFeeTerms.None`) |

The fee is scoped to **one network**, the one its owner and accounts exist on. The same mint on
another cluster is a different asset with a different account, so a fee configured for mainnet is no
fee on devnet. Jupiter swaps run only on mainnet, so in practice a fee is configured for `mainnet`.

## Everything here is public

The gateway serves the fee to any client that asks, anonymously. The rate and the addresses are
public values, and anyone can read them. Never put a seed phrase, a private signing key or a secret
Jupiter or RPC credential anywhere in the client configuration. The receiving token accounts are
ones you create beforehand. Neither the app nor its users can create or fund them, and a user is
never charged for setting them up.

Feed publishers, manifests, signals, direct servers, QR codes and Jupiter's own answers cannot change
the rate or the recipient. The only writer is the gateway's operator.

## Fields

| Field | Admin page | CLI | Meaning |
| --- | --- | --- | --- |
| Rate | **Rate, in whole basis points (0–100)** | `--bps` (required) | Whole basis points of the swap's output: `0`–`100` (1%). `20` = 0.2%. `0` is no fee |
| Network | **Network** (None, mainnet, devnet, testnet) | `--network` | The network the owner and accounts exist on. Required when the rate is not 0 |
| Owner | **Owner wallet** | `--owner` | The public wallet address that owns every receiving token account |
| Accounts | **Receiving accounts**, one `mint=token_account` per line | `--account <mint>=<token account>`, once per mint | The account that receives the fee **in that mint** |

`set-fee` replaces the whole fee, which is why `--bps` is required. `--bps 0` with nothing else
removes the fee.

**Bounds and validation.** The gateway refuses the whole fee, and saves nothing, when the rate is
not a whole number from 0 to 100; when a nonzero rate has no network, no owner or no account; when
the network is not mainnet, devnet or testnet; when any address is not a base58 public key of 32
bytes; when an entry is not `mint=account`; when more than 16 accounts are listed; when a mint is
listed twice; or when an account is the owner wallet itself or the mint itself. Anything set is
validated even at rate 0, so a half-finished fee is caught when it is typed. A rate above the bound
is refused, never saved as the bound. The program's own field would accept up to 255 bps; the bound
is 100, and the app enforces it again when it reads the configuration.

**Mint/account policy.** The fee is taken in the swap's **output** mint, and only when that mint has
an entry. A wallet address is never treated as a token account for every pair. Pairs whose output
mint has no entry swap with no fee. Configure the output tokens your users receive most, typically
USDC and wrapped SOL (`So1111…112`, which also covers swaps into native SOL).

Supported: classic SPL Token accounts only. A Token-2022 account, or a pair whose accounts the swap
inspector does not read, carries no fee.

## Preparing the receiving accounts

Do this once, before you set a nonzero rate, from the fee wallet (with the Solana CLI and
`spl-token`, or any wallet that creates token accounts):

```bash
# The associated token account of the fee wallet for each mint you list.
spl-token create-account EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v --owner <FEE_WALLET>   # USDC
spl-token create-account So11111111111111111111111111111111111111112 --owner <FEE_WALLET>    # wrapped SOL
spl-token address --token EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v --owner <FEE_WALLET> --verbose
```

Each account must exist on the fee's network, be owned by the Token program, hold the listed mint,
belong to the configured owner, and be initialized and not frozen. The phone checks exactly that,
through the gateway's RPC endpoint for that network, before every fee-bearing quote. If any of it
fails, or the endpoint cannot answer, the swap is prepared **without** a fee and the review says the
fee account could not be verified. Do not close the accounts while the fee is configured.

## Setting the fee

With the CLI, against the gateway's database (`--database`, or `BROADCAST_DATABASE_PATH` /
`BROADCAST_DATABASE_URL`):

```bash
feed-gatewayctl client-config set-fee --bps 20 --network mainnet --owner <FEE_WALLET> \
  --account EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v=<FEE_USDC_ACCOUNT> \
  --account So11111111111111111111111111111111111111112=<FEE_WSOL_ACCOUNT>
feed-gatewayctl client-config show                  # what every app reads now, as JSON
feed-gatewayctl client-config set-fee --bps 0       # no fee
```

Or on the admin page: **Client configuration** in the sidebar (`<admin path>/client-config`), the
**SAC swap service fee** form, **Save fee**. The page shows the revision being served and the fee in
one sentence.

Each saved change moves the configuration's revision by one. Saving what is already held is
reported as unchanged and keeps the revision. The RPC endpoints are a separate form and command
(`set-rpc`), and saving one never touches the other.

## When a change reaches a phone

The app asks the gateway again on first use, when it returns to the foreground, when a feed stream
(re)connects, and before every new operation is prepared. A new fee therefore applies to the next
operation an owner prepares, with no app update.

An operation already in review is bound to the terms it was prepared under. Before the approval goes
to the wallet, and again inside the wallet lock, the app compares those terms with the
configuration it holds for that network. If the rate, owner or any account moved, the review stops
with "The service fee configured for this app changed after this was prepared"
(`OperationProblem.FeeTermsChanged`), drops the preparation, and the owner prepares and reviews
again. An operation already sent keeps the receipt recorded in its binding.

If the app cannot reach the gateway, it prepares a new operation with its cached configuration for
up to 24 hours after that configuration was fetched, and then stops preparing until the gateway
answers. A failed fetch is never read as a zero fee
([wiki/client-config.md](../wiki/client-config.md#how-the-app-uses-it)).

## What the user sees

Before signing: **Swap routing: Metis · Powered by Jupiter**; the SAC service fee rate "taken from
what you receive"; the provider's estimate in the output token; the recipient token account; the
priority fee separately; and the minimum received, net of every fee. With no fee the review shows
the service fee as 0% with "network and pool costs still apply", never "free". The wallet hand-off
repeats the routing and the fee; History keeps the approved rate, estimate, token and recipient,
labelled **estimated at review**.

## Manual mainnet verification (opt-in, spends real funds)

Automated checks never spend anything. To confirm a fee is actually credited:

1. On the gateway the app's build names, set a mainnet RPC endpoint
   (`feed-gatewayctl client-config set-rpc --network mainnet --url <endpoint>`) and a small fee
   (e.g. `--bps 20`) with a USDC account, as above. Check it with `client-config show`.
2. Note the fee account's balance: `spl-token balance --address <FEE_USDC_ACCOUNT>`.
3. Open the app (bring it to the foreground so it fetches the configuration). From a separate wallet
   holding a little SOL, act on a mainnet SOL → USDC swap signal for a small amount. On the review,
   record the rate, the estimated fee and the recipient; approve and sign.
4. Once History shows **Confirmed on the network**, open the transaction on the explorer and check
   the token balance changes: the fee account's USDC increases by about the estimated fee (the chain
   takes the rate of the actual route output, so it can differ slightly from the estimate).
5. Re-read the balance from step 2 to confirm the difference.

A configuration, a fixture or an unsigned transaction is not evidence of a collected fee; only this
is.

## Before SEE-185

The fee used to be compiled into the APK through `seekervault.swapFee.bps`, `.owner` and `.accounts`
(or `SEEKERVAULT_SWAP_FEE_*`) and checked by `pnpm check:swap-fee-build`. All of that is removed. The
release script reports a `SEEKERVAULT_SWAP_FEE_*` variable that is still set as retired and ignored
([releases.md](releases.md#android-build-configuration)). Move the values to the gateway with
`client-config set-fee`, adding `--network mainnet`.
