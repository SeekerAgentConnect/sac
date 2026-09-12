# Send SOL or a token

An agent can ask you to send SOL or a classic SPL token from the wallet you connected. This guide covers the whole path up to the moment your wallet sends it: how a transfer request is stored, how the transaction is built, what your phone makes of it, and what your approval binds. Following the transaction to confirmation comes with the task after this one.

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

## What you see, and what you approve

When you open a pending transfer, the phone asks for a fresh transaction and **reads it here** before showing you anything ([`../security.md`](../security.md#inspecting-a-transfer)). Everything above the divider — the amount in base units, the recipient, the token's mint, the account it goes into, the wallet that pays and signs, the blockhash — is read out of the bytes your wallet would sign. The server's fee and rent estimate is shown apart from those and labelled as the server's, because a fee can't be read out of a transaction. The agent's note sits further down under its own "not verified" label.

- **Policy says "Not evaluated."** Per-connection rules are Stage 5. Until they exist the screen says so rather than calling anything allowed, and every transfer needs your approval either way.
- **Approve and send appears only for a transaction this phone read whole** and found to match the request. If anything is unread or doesn't match, there is no button at all — not a button that refuses — and you can reject it.
- **Read it again** asks the server for a new version and reviews that one instead. Each version is reviewed on its own.

Tapping **Approve and send** does three things in this order, and stops at the first that fails:

1. Saves your approval on this phone, with the version, the content hash, and the exact bytes you reviewed.
2. Sends the approval to the server, which moves the request to `PROCESSING`. Your wallet is not opened until it accepts.
3. Opens your wallet with the bytes from step 1 — never bytes fetched again — and your wallet signs **and sends** the transaction.

If the server refuses the approval because a newer version exists or the blockhash has run down, nothing is approved, your wallet is not opened, and the phone reads the request again for you to review. An approval is never carried over to a transaction you didn't see.

## What can happen afterwards

| What the wallet does | What you see | What the agent reads |
| --- | --- | --- |
| Signs and sends it | The transaction's ID | `SUBMITTED` with that signature |
| You decline in it | Declined; nothing was sent | `REJECTED` |
| Refuses before signing | Not sent, with what it said | `FAILED` |
| Never answers, or the app dies while it has it | This phone never learned what the wallet did | `UNKNOWN` |

`UNKNOWN` is not a failure and not a licence to retry. A signed transaction can land after your phone has stopped listening, so the honest answer is that nobody here knows yet. Your wallet is never asked a second time.

`SUBMITTED` means your wallet sent it, not that it succeeded on chain. That is the next question.

## Following it to the network

Tap **Check status** on the request and the server looks the transaction's ID up on the chain (SAW-022). It opens no wallet, signs nothing, and sends nothing again, so you can tap it as often as you like. The agent gets the same answer by reading the request.

| What the network says | What you see | What the agent reads |
| --- | --- | --- |
| It succeeded | "This transfer went through on the network." | `CONFIRMED`, which is terminal |
| It ran and failed | The network's own reason | `FAILED` |
| It hasn't landed yet | Still sent, not confirmed | `SUBMITTED` |
| Its blockhash expired and it never landed | Sent, then failed: nothing was spent | `FAILED` |

Three things are worth knowing about that answer:

- **It is one server's word.** The line says which host looked — "Checked with rpc.example.test" — because there is no second opinion behind a confirmed or failed transfer.
- **The server checks that what it found is what you approved.** It fetches the transaction the chain holds under that ID and compares it, byte for byte, with the one you read. If they differ, it says so and settles nothing, rather than reporting a stranger's transaction as yours.
- **Nothing is ever retried for you.** A failure on chain, an expired blockhash, and an ID the server can't account for are all reported as what they are. If you still want the transfer, the agent asks again and you review a fresh request.

An `UNKNOWN` transfer is the one case nothing here can settle: no ID ever reached this phone, so there is nothing to look up. [`troubleshooting.md`](troubleshooting.md#a-transfer-whose-outcome-is-unknown) says how to find out from your wallet's own history.

## Where this stops for now

SAW-019 built the transaction, SAW-020 taught the phone to read it, SAW-021 lets you approve one and have your wallet send it, and SAW-022 follows it to the network. Policies (Stage 5) and swaps (Stage 6) come later; until then every transfer is one you approved by hand.
