# Send SOL or a token

An agent can ask you to send SOL or a classic SPL token from the wallet you connected. This guide covers what happens on the sidecar's side: how a transfer request is stored and how the transaction you will review is built. Reviewing it on the phone, approving it, and watching it land come with the tasks after this one.

Before this, pair the phone ([`pairing.md`](pairing.md)) and connect your wallet ([`wallet-setup.md`](wallet-setup.md)).

## What is true of every transfer

- **Nothing moves without you.** The agent's call stores a request and nothing more. No transaction exists yet, nothing is signed, and nothing reaches the network.
- **The sidecar can't send.** It holds no key, it never signs, and it has no way to broadcast: it reads the chain and builds bytes for your wallet.
- **The transaction is built when you open the request, not when the agent asks.** A Solana transaction is only valid for a minute or two, so it is built fresh at review time, with a blockhash taken then.
- **Amounts are exact whole numbers of base units.** Lamports for SOL (1 SOL is 1,000,000,000), and the mint's own base units for a token. The sidecar reads a token's decimals from its mint on chain; it never trusts a ticker or a name.
- **What you approve is what gets signed.** Your approval names the exact version and the SHA-256 of its bytes. If the sidecar builds a newer version, the old approval no longer works.

## Point the sidecar at a network

The sidecar needs a Solana JSON-RPC endpoint to read from. Put it in the root `.env`:

```dotenv
SOLANA_RPC_URL=https://api.devnet.solana.com
# Optional: how long one chain call may take, 1000 to 60000 ms. Empty means 10000.
SOLANA_RPC_TIMEOUT_MS=
```

- **Start on devnet.** Devnet SOL is free (`solana airdrop 1 <your address>`), and a mistake costs nothing. Move to mainnet only once the whole round trip has worked.
- **The endpoint decides the network.** The sidecar reads its genesis hash and refuses to prepare anything whose network doesn't match, so a devnet endpoint can never build a mainnet transfer.
- **Without `SOLANA_RPC_URL` there are no transfers at all.** `vault_transfer` is not served, `vault_get_capabilities` leaves `transfer` out of `operations`, and the app is told `CHAIN_UNAVAILABLE` if it asks to prepare one. The sidecar offers only what it can actually do.
- **Keep the URL out of version control.** A hosted endpoint's URL often carries an API key. `.env` is ignored by git, and the sidecar never writes the URL to a log or an error message.

## The agent asks

```console
$ pnpm agent capabilities
{"approval":"manual","signing":"wallet","operations":["sign_message","transfer"],"wallet_connected":true,…}

$ pnpm agent address
{"wallet":"G4bAtd9o…","network":"devnet","bound_at":"2026-09-12T09:30:00.000Z"}

$ pnpm agent transfer 3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh 25000000
idempotency key: transfer-1f0a…
The owner reviews it on their Seeker; nothing is signed or sent until they approve.
{"request_id":"f7e6d5c4-…","action":"transfer","status":"PENDING","wallet":"G4bAtd9o…",…}
```

For a token, add its mint: `pnpm agent transfer <recipient> 1500000 --mint EPjFWdd5…`. Hermes and other agents call the MCP tool `vault_transfer` for the same thing; it answers at once with a request ID and never waits for you.

- **The recipient is a wallet address,** never a token account. For a token, the sidecar finds the recipient's associated token account itself, and creates one if they have none.
- **A retry with the same idempotency key returns the same request,** so a repeated call can't pay twice. A retry that changes the amount or the recipient is refused with `IDEMPOTENCY_CONFLICT`.
- **The wallet and network must be the ones `vault_get_address` returns.** Anything else is refused with `WALLET_MISMATCH`, and nothing is stored.

## What the sidecar builds

When the app asks to prepare the request, the sidecar reads the chain and builds one unsigned transaction:

| | Native SOL | A classic SPL token |
| --- | --- | --- |
| **Instructions** | One System `Transfer` | `TransferChecked`, and before it `CreateIdempotent` when the recipient has no token account yet |
| **Accounts** | Your wallet and the recipient | Both associated token accounts, the mint, and your wallet as the authority |
| **Extra cost** | None | The rent for a token account it creates, reported as `rent_lamports` |

It also returns the network fee it estimated, how long the transaction can still land, and the SHA-256 of its bytes. Your wallet is the fee payer and the only signer, and every signature slot is empty.

## What it refuses, and why

| It says | Because |
| --- | --- |
| This is a Token-2022 mint | A Token-2022 extension can change what a transfer does after you have reviewed it — a fee taken on transfer, a hook that runs other code. Only classic SPL tokens are supported. |
| This looks like an NFT | A mint with no decimals and a supply of one isn't an amount you can review as one. |
| The recipient is a token account | Tokens sent to a token account address directly, or SOL sent to one, can't be spent by their owner. Give the owner's own address. |
| You have no token account for this mint | Your wallet holds none of that token. |
| You hold less than the request sends | The transfer would fail on chain; the sidecar doesn't build one that can't work. |
| The account is frozen | The mint's freeze authority has frozen it, and no transfer will go through. |
| The endpoint serves another network | `SOLANA_RPC_URL` points at a different cluster from the one your wallet is bound to. |

The first six leave the request as it is; fix the request, or the wallet, and ask again. A `CHAIN_UNAVAILABLE` answer is different: the endpoint simply didn't answer, nothing about the request is wrong, and the same call works once it does.

## Two clocks to keep apart

- **The request's own deadline** (`expires_in_seconds`, a day by default) is how long you have to decide. When it passes, the request is `EXPIRED` and nothing was signed.
- **The transaction's blockhash window** is roughly a minute or two, and starts when the sidecar builds the transaction. When it runs low, your approval is refused with `STALE_PREPARATION` and the app prepares a new version for you to review. That is deliberate: it is better to review again than to sign something that can no longer land.

## Where this stops for now

SAW-019 covers the sidecar: the request, and the transaction built for review. The phone's own inspection of those bytes, approving them through your wallet, and following the transaction to confirmation come with SAW-020, SAW-021, and SAW-022.
