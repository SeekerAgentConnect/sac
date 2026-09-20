# Send SOL or a token

An agent can ask you to send SOL or a classic SPL token from the wallet you connected. This guide covers the whole path: how a transfer request is stored, how the transaction is built, what your phone makes of it, what your approval binds, how the transaction is followed to the network, and what your phone keeps as the record of it.

Before this, pair the phone ([`pairing.md`](pairing.md)) and connect your wallet ([`wallet-setup.md`](wallet-setup.md)). To send one for the first time, the order to do it in is [Your first transfer, step by step](#your-first-transfer-step-by-step).

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

$ pnpm agent transfer 3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh 25000000 \
    --wallet G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW --network devnet
idempotency key: transfer-1f0a…
The owner reviews it on their Seeker; nothing is signed or sent until they approve.
{"request_id":"f7e6d5c4-…","action":"transfer","status":"PENDING","wallet":"G4bAtd9o…",…}
```

For a token, add its mint: `--mint EPjFWdd5…`. Hermes and other agents call the MCP tool `vault_transfer` for the same thing; it answers at once with a request ID and never waits for you.

- **`--wallet` and `--network` are required, and the test agent fills in neither.** A payment names the wallet it comes from and the cluster it goes out on, or it isn't sent. Read both with `pnpm agent address` and pass them (SAW-023).
- **The amount is a whole number of base units.** `1.5` is refused before the call is made: a decimal is the mistake that sends a billionth of what was meant.

- **The recipient is a wallet address,** never a token account. For a token, the sidecar finds the recipient's associated token account itself, and creates one if they have none.
- **A retry with the same idempotency key returns the same request,** so a repeated call can't pay twice. A retry that changes the amount or the recipient is refused with `IDEMPOTENCY_CONFLICT`.
- **The wallet and network must be the ones `vault_get_address` returns.** Anything else is refused with `WALLET_MISMATCH`, and nothing is stored.

## What the sidecar builds

When the app asks to prepare the request, the sidecar reads the chain and builds one unsigned transaction:

| | Native SOL | A classic SPL token |
| --- | --- | --- |
| **Instructions** | One System `Transfer` | `TransferChecked`, and before it `CreateIdempotent`, always |
| **Accounts** | Your wallet and the recipient | Both associated token accounts, the mint, and your wallet as the authority |
| **Extra cost** | None | The rent for a token account it creates, reported as `rent_lamports`, and nothing when the recipient already has one |

`CreateIdempotent` is in every token transfer, not only the ones that create an account. It costs nothing when the account exists, and it is what makes the chain check who the destination belongs to: that program refuses the whole transaction unless the account is the recipient's for that mint. Your phone reaches no chain, and a token account's owner can be changed after its address is derived, so without that instruction the phone would be reading an address and calling it a person. It refuses to ([`../security.md`](../security.md#inspecting-a-transfer)).

It also returns the network fee it estimated, how long the transaction can still land, and the SHA-256 of its bytes. Your wallet is the fee payer and the only signer, and every signature slot is empty.

## What it refuses, and why

| It says | Because |
| --- | --- |
| This is a Token-2022 mint | A Token-2022 extension can change what a transfer does after you have reviewed it — a fee taken on transfer, a hook that runs other code. Only classic SPL tokens are supported. |
| This looks like an NFT | A mint with no decimals and a supply of one isn't an amount you can review as one. |
| The recipient is a token account | Tokens sent to a token account address directly, or SOL sent to one, can't be spent by their owner. Give the owner's own address. |
| You have no token account for this mint | Your wallet holds none of that token. |
| You hold less of the token than the request sends | The transfer would fail on chain; the sidecar doesn't build one that can't work. This is read from your token account: **SOL is not checked this way**, and neither is the fee. |
| The account is frozen | The mint's freeze authority has frozen it, and no transfer will go through. |
| The endpoint serves another network | `SOLANA_RPC_URL` points at a different cluster from the one your wallet is bound to. |

The first six leave the request as it is; fix the request, or the wallet, and ask again. Nothing here reads your SOL balance — the sidecar has no `getBalance` call — so too little SOL for the amount, the fee, or a new token account's rent is something the network finds out when the transaction runs. A `CHAIN_UNAVAILABLE` answer is different: the endpoint simply didn't answer, nothing about the request is wrong, and the same call works once it does.

## Two clocks to keep apart

- **The request's own deadline** (`expires_in_seconds`, a day by default) is how long you have to decide. When it passes, the request is `EXPIRED` and nothing was signed.
- **The transaction's blockhash window** is roughly a minute or two, and starts when the sidecar builds the transaction. When it runs low, your approval is refused with `STALE_PREPARATION` and the app prepares a new version for you to review. That is deliberate: it is better to review again than to sign something that can no longer land.

## What you see, and what you approve

When you open a pending transfer, the phone asks for a fresh transaction and **reads it here** before showing you anything ([`../security.md`](../security.md#inspecting-a-transfer)). Everything above the divider — the amount in base units, the recipient, the token's mint, the account it goes into, the wallet that pays and signs, the blockhash — is read out of the bytes your wallet would sign. The server's fee and rent estimate is shown apart from those and labelled as the server's, because a fee can't be read out of a transaction. The agent's note sits further down under its own "not verified" label.

- **Policy says "Not evaluated."** Per-connection rules are Stage 5. Until they exist the screen says so rather than calling anything allowed, and every transfer needs your approval either way.
- **Approve and send appears only for a transaction this phone read whole** and found to match the request. If anything is unread or doesn't match, there is no button at all — not a button that refuses — and you can reject it.
- **Read it again** asks the server for a new version and reviews that one instead. Each version is reviewed on its own.
- **The account that signs is the account you reviewed.** Your wallet authorizes this app again each time it opens, and if what it authorizes then isn't the account on the review screen — you switched accounts in the wallet, or revoked this app for that one — nothing is signed and nothing is sent. The app says the wallet changed and asks you to connect it again, and the request waits for a fresh review.

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

A script can read the same answer with its own exit code:

```console
$ pnpm --silent agent status f7e6d5c4-…
{"request_id":"f7e6d5c4-…","action":"transfer","status":"CONFIRMED","terminal":true,"network":"devnet",
 "signature":"5Yb4Dn9m…","signature_is_transaction":true,"confirmation":"finalized","slot":310000001,
 "checked_with":"api.devnet.solana.com",
 "explorer_url":"https://explorer.solana.com/tx/5Yb4Dn9m…?cluster=devnet"}
```

It exits 0 once the request ended the way it was asked for, 10 while no outcome is established — `PENDING`, `PROCESSING`, `SUBMITTED`, and `UNKNOWN` alike — and 11 once it ended any other way. `UNKNOWN` counts as unsettled on purpose: a script that treats it as a failure is a script that pays twice.

## The Activity record

Every request you answer is written to **Activity**, reachable from the Connections screen. It is your own record, and it is not the answer the server is owed: an answer is dropped a week after it settles and when you remove its connection, and a record is not.

Each record holds who asked, the terms you reviewed — the wallet, the recipient, the amount in base units, the asset — the network, how it ended, and the signature.

- **The network is on every transfer, always.** A signature means nothing without it: the same 64 bytes on another cluster are another transaction, or none at all.
- **A message signature is never shown as a payment.** A signed message carries a 64-byte signature too, and the record says in words that it moved nothing, that no network has it, and that no explorer can show it. There is no link on one.
- **View on Solana Explorer** hands the address to your browser, on the record's own cluster. The app itself opens no connection to the explorer or to any chain; the only hosts it talks to are the sidecars you paired it with.
- **Amounts are kept in base units.** SOL is also shown the readable way, because its decimals are fixed. A token's decimals belong to its mint and are read fresh when you review a transfer; a count stored months ago could show you the wrong amount, so the record keeps the number the transaction actually carried.
- **The rules review keeps its context, not your rules.** Activity records the effective Global or Connection source for each check, the separate Global daily and Connection daily results, any unreadable document scope, and whether you went ahead anyway. It stores stable codes, not allowlists, thresholds, addresses, or daily totals; records written before those source fields existed remain readable.
- **Clear** removes every record on this phone, after asking. It changes nothing on any network and nothing on any server: a transaction that went through stays on the network.

## Your first transfer, step by step

Everything above is what the parts do. This is the order to do them in the first time, on the phone
you actually have. Each step either succeeds or tells you why not; don't skip one because the next
looks like it would work anyway.

Have the sidecar running, the phone paired ([`pairing.md`](pairing.md)), and a terminal on the
sidecar's machine for `pnpm agent`.

**1. Find out which network your wallet serves.** Open **Wallet** in the app, pick **Devnet**, and
tap **Connect wallet**.

- If the wallet connects on devnet, use devnet for everything below. A mistake there costs nothing.
- If the app says **The wallet doesn't serve this network**, that wallet has no devnet, and your
  only real-wallet path is mainnet: read [If your wallet serves only
  mainnet](#if-your-wallet-serves-only-mainnet) before going on.

Which networks a wallet offers is a property of that wallet and its version, not of this app. On the
owner's Seeker, on 2026-09-12, **Seed Vault Wallet connected on devnet**, and the transfer that
followed was finalized there — [the wallet under test](wallet-setup.md#the-wallet-under-test) records
it. That is one wallet on one device on one date, so the app still assumes nothing and step 1 is
still worth doing: write what your own device does into the same table.

**2. Point the sidecar at the same cluster.** In the root `.env`:

```dotenv
SOLANA_RPC_URL=https://api.devnet.solana.com
```

Restart the sidecar, and check that it now offers transfers at all:

```console
$ pnpm agent capabilities
{"approval":"manual","signing":"wallet","operations":["sign_message","transfer"],…}
```

If `operations` has no `transfer`, the sidecar has no endpoint; nothing else below will work. The
two networks have to be the same one: the sidecar reads the endpoint's genesis hash and refuses to
build anything for a cluster that isn't the one your wallet is bound to. That refusal is the guard
that makes step 1 and step 2 safe to get wrong.

**3. Read the sending address, and check it in three places.**

```console
$ pnpm agent address
{"wallet":"G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW","network":"devnet","bound_at":"…"}
```

The address here, the one on the app's **Wallet** screen, and the account shown in the wallet app
itself must be the same, character for character, and the network must be the one from step 1. If
any two disagree, connect the wallet again rather than going on.

**4. Choose the recipient, and check it the same way.** For a first transfer, send to **a second
account in your own wallet**, so that whatever moves stays yours.

- Copy the address out of the wallet; don't retype it. A Solana address with one character changed
  is usually still a valid address, and there is nobody to ask for it back.
- Give the **owner's** address, never a token account. For a token the sidecar finds or creates the
  recipient's token account itself.
- Compare the whole string once it's in the command, not the first and last four characters. The
  phone shows you the recipient again before you approve, and that is the comparison that counts.

**5. Get test funds, if the cluster has any.**

| Cluster | Where funds come from |
| --- | --- |
| Devnet | `solana airdrop 1 <your address> --url https://api.devnet.solana.com`, or <https://faucet.solana.com>. Free, and worth nothing. |
| Testnet | The same faucet, rate-limited harder. Also worth nothing. |
| Mainnet | There is no faucet. You fund it yourself, and everything below spends real money. |

Nothing in this repository ever asks for an airdrop: `mcp-server/src/stage-boundary.test.ts` fails if
`requestAirdrop` appears in any shipped source. The faucet is yours to use, from your own terminal.

You need more than the amount you are sending: the network fee comes out of the same account, and a
token transfer to someone with no token account also pays that account's rent. Step 8 shows both
before you approve.

**6. Pick an amount, in base units.** Lamports for SOL, the mint's own base units for a token.
`100000` lamports is 0.0001 SOL — small enough not to matter, large enough to see.

The amount is an exact whole number. `0.0001` is refused before the request is created, and so are
`1e5` and `100_000`: a decimal point is the mistake that sends a billionth of what was meant.

**7. Ask for the transfer.**

```console
$ pnpm agent transfer <recipient> 100000 \
    --wallet G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW --network devnet --key first-transfer-001
{"request_id":"f7e6d5c4-…","action":"transfer","status":"PENDING","wallet":"G4bAtd9o…",…}
```

Keep the `request_id`. **Nothing has happened yet**: no transaction exists, the wallet app has not
opened, and the phone shows a pending request. `--wallet` and `--network` are required and neither
is guessed; `--key` is yours to choose, and repeating the same command with it returns this same
request instead of a second one.

**8. Review it on the phone.** Open the app, then **Pending requests**, then the transfer. The phone
asks the server for a fresh transaction and reads the bytes itself before showing you anything.

Check, against what you typed in step 7:

- the amount, in base units, and the recipient;
- the wallet that pays and signs;
- the token's mint, for a token, and the account it goes into. **Recipient's account** says that
  account is checked as theirs on chain, and created if they have none;
- the network fee and, for a new token account, its rent — both labelled as **the server's
  estimate**, because neither can be read out of a transaction;
- that policy says **Not evaluated**, which is what it will say until Stage 5.

If **Approve and send** isn't there, the phone did not read the transaction whole or it doesn't
match the request. That is not a button to look for elsewhere: reject it, and read
[what it refuses, and why](#what-it-refuses-and-why).

**9. Approve, and approve again in the wallet.** Tap **Approve and send**. Your approval is saved on
the phone, sent to the server, and only then is the wallet opened — with the exact bytes you just
read, never bytes fetched again. Seed Vault Wallet asks you to confirm; **record what it shows you
while it does**, because nothing in this repository can tell you.

Approve there, and the wallet signs **and sends**. The app shows the transaction's ID.

**10. Verify the signature — and don't take anyone's word for it.**

```console
$ pnpm --silent agent status f7e6d5c4-…
{"request_id":"f7e6d5c4-…","action":"transfer","status":"CONFIRMED","terminal":true,"network":"devnet",
 "signature":"5Yb4Dn9m…","signature_is_transaction":true,"confirmation":"finalized","slot":310000001,
 "checked_with":"api.devnet.solana.com","explorer_url":"https://explorer.solana.com/tx/5Yb4Dn9m…?cluster=devnet"}
```

It may read `SUBMITTED` for a few seconds first; that means your wallet sent it and nobody has
looked yet. Open `explorer_url` — check that the page says **Devnet**, and that the amount, the
recipient, and the fee payer are the ones from step 8.

Count the transfer as verified only when **all three** agree: the ID the app showed you, the
`signature` the agent read, and the transaction the explorer shows on that cluster. The server's
`CONFIRMED` is one server's word — it says which host in `checked_with` — and the explorer is the
second opinion. A screenshot of the app is neither.

**11. Check the record, and check there is only one.** Open **Activity** from Connections and find
the transfer: the cluster, the recipient, the amount, and the same signature. Force-stop the app and
open it again — the record is still there. Then look your own address up on the explorer and count
the transactions: **exactly one** should have gone out.

### If your wallet serves only mainnet

Mainnet spends real money and is irreversible. There is no undo, no support desk, and no way to
recall a transfer that went to the wrong address.

So a mainnet check is never a default and never something a script decided for you:

- **You choose it, deliberately.** `.env.example` ships `SOLANA_RPC_URL=` empty, so a fresh clone
  prepares no transaction at all. No `package.json` script and no CI job sets it, and the only check
  in this repository that touches a real network reads devnet, needs no funds, and runs only with
  `SEEKER_VAULT_NETWORK_CHECKS=1`. `mcp-server/src/stage-boundary.test.ts` fails if any of that stops
  being true.
- **Use an amount you would not mind losing entirely.** 100000 lamports — 0.0001 SOL — is enough to
  prove the path. The network fee is usually about 5000 lamports on top.
- **Send it to a second account in your own wallet,** so that the worst case is having paid a fee.
- **Do the whole of steps 3, 4 and 8 slowly.** The addresses are the only part nothing else can
  check for you.
- **Do it once.** One confirmed mainnet transfer answers the question. Repeating it proves nothing
  further and costs more.

Everything else is the same as above, with `--network mainnet` and a mainnet endpoint.

### When it doesn't go as planned

None of these is a failure of the transfer you asked for; each is a place it stopped.

| What you see | What happened | What to do |
| --- | --- | --- |
| **"The server has a newer transaction for this request, or this one can no longer be sent."** Nothing was approved, and the screen has already read it again. | Your review sat long enough for the blockhash to run down, or the server built a newer version meanwhile. The approval binds to a version and a hash, so the old one can't be used. This also happens when another wallet interaction was still open when you tapped: the phone runs one at a time, and it checks the window again once its turn comes. | Review the version now on screen — it is a different transaction — and approve that. Nothing was signed, nothing was sent, and the request is still `PENDING`. |
| The transfer confirms as **failed**, with the network's own reason, usually about lamports. | Not enough SOL. **Nothing reads your balance before you approve**: the sidecar has no `getBalance` call at all, so a SOL amount larger than you hold, and a fee or rent you can't cover, are all found out by the network when the transaction runs. | Fund the account — at least the amount, plus the fee, plus the rent when a token account is created — and have the agent ask again. The failed attempt still cost its fee. Nothing here builds a replacement. |
| The review shows **"Network fee about … SOL, and … SOL for the new token account"**. | The recipient has no account for that mint, so the transaction creates one, and Solana charges rent for it — about 0.002 SOL, paid by you, once per recipient per mint. | Nothing is wrong. Either approve it, or send to someone who already holds that token. The second transfer to the same recipient has no rent in it. |
| **"You declined in the wallet. Nothing was signed."** The agent reads `REJECTED`. | You said no inside the wallet — or **Reject** in the app, which never opens the wallet at all. | Nothing to undo: nothing was signed and nothing reached the network. If it was a mistake, have the agent ask again; the same request can't be revived. |
| **"This phone never learned what the wallet did."** The agent reads `UNKNOWN`. | The app was killed, or the wallet never came back, while the transaction was with it. It may have been sent, or not. | **Don't send it again yet.** There is no signature here to look up, so check your wallet's own history, or your address on the explorer for that cluster, and see whether a matching transfer is there. Then decide. The app and the server never retry this for you, and the wallet is never asked a second time. |

`UNKNOWN` is the only one of these that can't be settled from the phone. Everything else ends in a
state you can read, and none of them leaves a transaction half-sent.

## Where this stops for now

SAW-019 built the transaction, SAW-020 taught the phone to read it, SAW-021 lets you approve one and have your wallet send it, SAW-022 follows it to the network, and SAW-023 keeps the record and gives the whole path its regression tests ([`../testing/stage-4.md`](../testing/stage-4.md)). Policies (Stage 5) and swaps (Stage 6) come later; until then every transfer is one you approved by hand.
