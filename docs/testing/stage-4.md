# Stage 4 tests

Stage 4 is the first stage where funds can move. SAW-019 stores a transfer request and builds the unsigned transaction, SAW-020 teaches the phone to read that transaction itself, SAW-021 lets the owner approve one and their wallet sign and send it, SAW-022 follows it to the network, SAW-023 adds the regression tests for that whole path and the owner's own record of what was done, and SAW-024 closes the stage: the walkthrough for sending a real transfer from the Seeker, and the honest account of what has and has not been run on one.

SAW-017's lifecycle questions — rotation, backgrounding, a killed process, a restarted sidecar — have their own page, [`wallet-lifecycle.md`](wallet-lifecycle.md), which also carries the device checks for SAW-021 and SAW-022.

## Nothing here spends anything

**No automated check in this repository sends a transaction to any cluster, on mainnet or anywhere else, and none needs a funded wallet.**

- The sidecar has no way to broadcast: only `mcp-server/src/solana/` reaches a chain, only read-only JSON-RPC methods exist there, and `mcp-server/src/stage-boundary.test.ts` names every one of them.
- The app has no chain endpoint at all, and `StageBoundaryTest` proves it: the only hosts it opens a connection to are the sidecars it is paired with, and the block explorer is a link handed to the browser, never a fetch.
- The chain the tests read is `mcp-server/src/testing/chain.ts`. `FakeChain` answers the builder directly, and `startFakeRpc` serves the same state as ordinary Solana JSON-RPC on loopback, so a sidecar process can be pointed at it without a network.
- The wallet is a throwaway Ed25519 key pair in the test process (`server-sdk/src/testing/wallet.ts`) and, on the phone, `FakeWalletAdapter`. They make real signatures; they are not a wallet app.

The one check that talks to a real network is opt-in, reads, and is described at the end of this page.

## Automated checks

`pnpm check` runs the sidecar's and the test agent's tests, `pnpm check:android` runs the app's JVM and Robolectric tests, and `pnpm test:transfer` runs the acceptance scenario end to end. CI runs all three, `pnpm test:transfer` among them (`.github/workflows/ci.yml`, the node job). CI never sets `SEEKER_VAULT_NETWORK_CHECKS` and never sets `SOLANA_RPC_URL`, so its transfer run uses the fake RPC on loopback and the throwaway wallet key, reaches no cluster, and spends nothing.

| Area | What the tests cover | Where |
| --- | --- | --- |
| The chain client | Every read the sidecar makes, the errors each one maps to, and that a URL that can hold an API key never reaches a log or a message | `mcp-server/src/solana/rpc.test.ts` |
| The transaction builder | SOL and SPL transfers, a recipient with no token account, a Token-2022 mint, an NFT, a token account given as a recipient, a frozen account, too little held, and a cluster that doesn't match the request | `mcp-server/src/solana/transfer.test.ts` |
| Preparation | A new version per preparation, the content hash, the fee and rent, the blockhash window, and an approval refused once it runs down | `mcp-server/src/requests/transfers.test.ts` |
| The phone's own parser | The shared fixtures, decoded on the phone, including the adversarial ones that are perfectly valid transactions and simply aren't the one that was asked for | `TransactionFixturesTest`, `transactions/` tests |
| Confirmation | A delayed confirmation, a chain failure, an RPC timeout, a signature not yet visible, a mismatched transaction, a signature that can no longer land, and a restart whose `SOLANA_RPC_URL` points at another cluster whose block height is long past the window — which settles nothing | `mcp-server/src/requests/confirmation.test.ts`, `mcp-server/src/solana/confirmation.test.ts` |
| The destination's authority | A classic SPL token account at exactly the recipient's derived address whose authority is now somebody else's: the sidecar refuses to build it, and the phone refuses to call a transaction without the associated-account instruction verified at all | `mcp-server/src/requests/transfers.test.ts`, `TransactionFixturesTest` (`token_destination_authority_changed`) |
| Freshness at the wallet | Time passing the blockhash window while another wallet interaction holds the lock, and while the approval is being committed: the wallet is never called with stale bytes | `InboxViewModelTest` |
| An approval nobody answered | A lost response to the approval: it is kept rather than deleted, and the next delivery reads the request and either drops it (still PENDING) or ends it (PROCESSING) | `InboxViewModelTest` |
| The agent's transfer command | A transfer that names its wallet and cluster; one that names neither, or only one; a cluster the CLI doesn't know; an amount that isn't base units; a retry under the same key; and a refusal from the sidecar | `test-agent/src/cli.test.ts` |
| The agent's `status` | An acknowledgement, a cancelled request, a signed message, and a pending transfer, each with its exit code and its view — and no explorer link on a message signature | `test-agent/src/cli.test.ts` |
| The whole path | The scenario table below | `test-agent/src/stage4.acceptance.ts` (`pnpm test:transfer`) |
| The history's storage | Every field across a restart, a token transfer on its own cluster, a damaged file skipped, newest first, clearing, and IDs that could name a path | `ActivityStoreTest` |
| What the history records | One transfer through each step it takes; the same result three times; a restart in between; a message signature; an acknowledgement; a rejection; an outcome nobody knows; an answer that never reached the server; and an approval the server never accepted, which is recorded as nothing at all | `ActivityLogTest` |
| The history through the app | A sent transfer recorded with its cluster and its transaction ID, confirmed onto the same record, and a signed message that is never a payment | `InboxViewModelTest` |
| The history outliving the answer | An answer pruned a week after it settled, with its record read back whole on a restart | `ConnectionRepositoryTest` |
| The explorer link | The cluster in the link for each network, no link for a message signature, and no link without a signature or without a cluster | `ExplorerTest` |
| The screens | The list, the empty state, a history that can't be read, clearing only after a confirmation, the record in full, the cluster named on every transfer, the explorer offered only for a sent transaction, and the words that say a message signature is not a payment | `ActivityScreenTest`, `ActivityDetailsScreenTest`, `ActivityViewModelTest`, `ConnectionsScreenTest` |
| The stage boundary | Storage only in the three storage packages; the explorer address in one file, which holds no HTTP client; the app's HTTP clients only where they talk to a sidecar; and the sidecar's chain methods, which only read | `StageBoundaryTest`, `mcp-server/src/stage-boundary.test.ts` |

### The acceptance scenario (SAW-023)

`pnpm test:transfer` runs the sidecar as its own process with its own database, the CLI (`pnpm agent`) as a separate process, the Connect phone client as the phone, and a throwaway key pair as the wallet, against the fake RPC.

| # | Scenario | What it must show |
| --- | --- | --- |
| 1 | A SOL transfer | `PENDING`, then `PROCESSING`, `SUBMITTED`, and `CONFIRMED` — and nothing skipped. The link names the cluster the request was bound to. |
| 2 | An SPL transfer to someone with no token account | The preparation creates the account and charges rent for it, and the transfer confirms. |
| 3 | A transaction the chain rejected | `FAILED` with the chain's own error, exit code 11, and no replacement built. |
| 4 | The owner rejecting | `REJECTED`, with no signature and nothing on chain. |
| 5 | The same result twice | One record, one signature, one payment — and the agent's own key still returns that one request. |
| 6 | A restart while a transaction is in flight | The signature survives, and the comparison with the approved transaction is made against the stored preparation afterwards. |
| 7 | The endpoint stopping | Nothing is settled and nothing about the transaction changes; the owner's own check settles it once the endpoint answers again. |

### What the automated checks deliberately can't show

- **No test has ever seen a real wallet send a transaction.** Mobile Wallet Adapter needs an installed wallet and a real activity association. What Seed Vault Wallet shows the owner while it signs, and whether it sends what it was handed, is the owner's check.
- **No test has ever seen a real cluster confirm one.** `FakeChain` answers exactly what a test sets up. Real confirmation timing, a real endpoint's rate limits, and a real blockhash expiring are not modelled.
- **A development wallet never stands in for the acceptance check.** A mock, an emulator, or a successful APK build is recorded as NOT RUN.

## The opt-in devnet check

One check talks to a real network. It is off unless `SEEKER_VAULT_NETWORK_CHECKS=1`, it reads devnet, it needs no funded wallet, and it never gets as far as a transaction:

```console
$ SEEKER_VAULT_NETWORK_CHECKS=1 SOLANA_RPC_URL=https://api.devnet.solana.com pnpm test:transfer
```

It proves the one guard no fixture can prove: that the sidecar reads a real cluster's genesis hash and refuses to prepare a transfer whose network doesn't match it. A mainnet-bound request against the devnet endpoint is refused by name, nothing is signed, and nothing is spent.

Without the variable the case is skipped, and the run reports it as skipped rather than as passed.

## The owner's checks on the Seeker

The device checks for approving and sending a transfer, and for following it to the network, are checks 15 to 30 in [`wallet-lifecycle.md`](wallet-lifecycle.md#the-owners-checks-on-the-seeker). The ones below are SAW-023's own and continue that numbering. They are all read-only: they add nothing to spend, and every one of them can be done with the devnet transfer from check 17.

Record PASS, FAIL, or NOT RUN for each. An emulator or a successful APK build never counts.

| # | Step | Expected |
| --- | --- | --- |
| 31 | With nothing answered yet, open **Activity** from Connections | It says nothing has been recorded, and offers no Clear. |
| 32 | Acknowledge a demo request, then open Activity | One record, named by the connection that asked, with "Acknowledged". |
| 33 | Sign a message, then open its record | It shows the signature, labelled as a signature, and says in words that it is not a payment. There is **no explorer button**. |
| 34 | Send a devnet transfer, then open its record | It shows the amount in base units, the recipient, the wallet, **Solana devnet**, and the transaction ID. |
| 35 | Tap **View on Solana Explorer** | The browser opens the explorer on **devnet**, and the transaction it shows is the one that was sent. |
| 36 | Check the same record after tapping Check status | The outcome becomes "Confirmed on the network", with "Checked with …", and there is still exactly one record for that request. |
| 37 | Force-stop the app and open Activity again | Every record is still there, with the same outcomes. |
| 38 | Remove the connection that asked, then open Activity | The records are still there. Removing an agent's connection does not erase what was spent. |
| 39 | Tap **Clear**, then **Cancel** | Nothing is removed. |
| 40 | Tap **Clear**, then **Clear history** | Activity is empty. The transaction is still on the explorer. |

## The real-wallet transfer (SAW-024)

Everything above this line runs against a fake chain, a fake wallet, or both. SAW-024 is the part
that can't: one transfer, from the owner's own Seeker, through Seed Vault Wallet, onto a real
cluster. The owner's walkthrough is [`../guides/transfers.md`](../guides/transfers.md#your-first-transfer-step-by-step);
the checks below are the same path written as a test script.

### Three kinds of coverage, kept apart

| | What it proves | What it can't |
| --- | --- | --- |
| **The automated checks** (`pnpm check`, `pnpm check:android`, `pnpm test:transfer`) | The whole path, every branch of it, deterministically: the builder, the phone's parser, the approval, the submission, the confirmation, the record | Nothing about a real wallet or a real cluster. The chain is `FakeChain`; the wallet is a key pair in the test process, or `FakeWalletAdapter`. |
| **The opt-in devnet check** (`SEEKER_VAULT_NETWORK_CHECKS=1`) | That the sidecar reads a **real** cluster's genesis hash and refuses a transfer bound to another network | Nothing past that refusal. It reads, needs no funds, and never reaches a transaction. |
| **The owner's checks below** | That Seed Vault Wallet signs and sends what it was handed, and that the result the agent reads is the transaction on chain | Nothing automatically. Each one is run by hand and recorded. |

**A development wallet never stands in for the third row.** A mock, an emulator, or a successful APK
build is recorded as NOT RUN, however green it is.

### Choosing the cluster

Check 41 decides everything after it, and its answer is the wallet's, not this repository's.
**It has been answered: Seed Vault Wallet serves devnet.** On 2026-09-12 the owner picked Devnet on
the Wallet screen, the wallet connected, and the transfer that followed was finalized on devnet.
Step 9 of [the Stage 3 checks](stage-3.md#the-owners-checks-on-the-seeker) records the same answer.

That answer is this wallet's, on this device, on that date. Another wallet, or a later build of this
one, is check 41 again.

Had the wallet served only mainnet, checks 42 to 55 would have been a mainnet run, and that is a
decision the owner makes deliberately. It has not been made, and the rules for it stand:

- **Nothing in this repository points at any cluster by itself.** `.env.example` ships
  `SOLANA_RPC_URL=` empty, so a fresh clone prepares nothing; no `package.json` script and no CI job
  sets it; and the one check that touches a real network reads devnet behind
  `SEEKER_VAULT_NETWORK_CHECKS`. `mcp-server/src/stage-boundary.test.ts`, "spending nothing by
  default", fails if any of that changes.
- **The amount is deliberately small** — 100000 lamports, 0.0001 SOL — and goes to a second account
  in the owner's own wallet, so the worst case is a network fee.
- **Once is the whole check.** A second confirmed mainnet transfer proves nothing more.

### The owner's checks: one real transfer

These continue the numbering from check 40. They assume checks 15 to 30 in
[`wallet-lifecycle.md`](wallet-lifecycle.md#transfers-saw-021) have been run, or are run alongside
them — those cover approving, sending, and confirming; these cover choosing the network, checking
the addresses, matching the result against the chain, and proving that a refusal sent nothing.

Record PASS, FAIL, or NOT RUN for each, with the cluster.

| # | Step | Expected |
| --- | --- | --- |
| 41 | On the **Wallet** screen, pick **Devnet** and tap **Connect wallet** | Record what the wallet does. Either it connects, and everything below is devnet; or the app says "The wallet doesn't serve this network", and the only real-wallet path is mainnet. Write the answer into [the wallet under test](../guides/wallet-setup.md#the-wallet-under-test). |
| 42 | Put that cluster's endpoint in `SOLANA_RPC_URL`, restart the sidecar, and run `pnpm agent capabilities` | `operations` contains `transfer`. Without an endpoint it doesn't, and nothing below can run. |
| 43 | Point `SOLANA_RPC_URL` at a **different** cluster from the wallet's, and open a pending transfer on the phone | The app says the endpoint serves another network. No transaction is prepared, nothing is signed, and nothing is spent. Put the right endpoint back. |
| 44 | Fund the wallet on that cluster: `solana airdrop 1 <address> --url <endpoint>` on devnet or testnet; on mainnet, your own funds | The wallet shows a balance. Nothing in this repository requests an airdrop; this is your own command. |
| 45 | Compare the sending address in three places: `pnpm agent address`, the app's **Wallet** screen, and the account in Seed Vault Wallet | All three are the same address, character for character, on the same network. |
| 46 | Copy a second account of your own out of the wallet as the recipient, and run `pnpm agent transfer <recipient> 100000 --wallet <address> --network <cluster> --key device-001` | `PENDING`, with that wallet. **Seed Vault Wallet does not open, and the phone shows no prompt of its own.** |
| 47 | Open the request on the phone | The recipient is the address you copied, character for character; the amount reads 100000 base units; the wallet that pays is the one from check 45; the fee, and any rent, are labelled as the server's estimate; policy says "Not evaluated". |
| 48 | Tap **Approve and send**, and approve in the wallet | Record what Seed Vault Wallet shows while it asks — whether it displays the recipient and the amount. The app comes back with the transaction's ID. |
| 49 | Run `pnpm agent status <id>` until it settles | `SUBMITTED` first, then `CONFIRMED` with `signature`, `confirmation`, `slot`, `checked_with`, and an `explorer_url` carrying the cluster from check 41. Record the signature and the cluster. |
| 50 | Open that `explorer_url` | The explorer names that cluster. The amount, the recipient, and the fee payer are the ones from check 47, and the signature is the one the app showed in check 48. **All three have to agree**: the app, the agent, and the explorer. |
| 51 | Look your own address up on the same explorer and count the transactions it sent | Exactly one, and it is the one from check 50. |
| 52 | Ask for another transfer and tap **Reject** on the phone | No wallet opens at all. The agent reads `REJECTED`. |
| 53 | Ask for another, tap **Approve and send**, and decline inside Seed Vault Wallet | The app says you declined and nothing was signed. The agent reads `REJECTED`, not `FAILED`. Count your address's transactions again: **still exactly one**, from check 51. |
| 54 | Ask for another, open it, leave it for two or three minutes, then tap **Approve and send** | "The server has a newer transaction for this request…". Nothing reaches the wallet, the agent still reads `PENDING`, and a new version is on screen. Approve **that** one, and it goes through. The request has one transaction, not two, and your address now has two in total. |
| 55 | If you hold an SPL token on that cluster: transfer some to an address that holds none of it, then repeat the same transfer to the same address | The first review shows "and … SOL for the new token account"; the second shows only the fee. Both confirm, and the recipient's token account exists afterwards. |

Check 43 costs nothing and is worth running first: it is the guard that makes a wrong endpoint a
refusal rather than a transfer on the wrong cluster.

The unknown-result path is checks 21 and 22 in
[`wallet-lifecycle.md`](wallet-lifecycle.md#transfers-saw-021), and what to do about one is in
[the guide](../guides/transfers.md#when-it-doesnt-go-as-planned). It is not repeated here.

## Verification record: SAW-023

SAW-023, 2026-09-12:

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, and `tsc`. 384/384 sidecar tests and 29/29 test-agent tests pass, 2 of the latter new. |
| `pnpm check:generated` | PASS: generated code and fixtures are up to date. |
| `pnpm check:android` | PASS: Spotless, lint, both APKs, and 421/421 unit tests, 42 of them new. |
| `pnpm test:transfer` | PASS: 7/7, with the opt-in devnet case skipped. **No transaction was sent to any cluster.** |
| `pnpm test:hello`, `pnpm test:queue`, `pnpm build` | PASS: 9/9, 7/7, and both packages build. |
| `SEEKER_VAULT_NETWORK_CHECKS=1 pnpm test:transfer` | NOT RUN: it needs a real devnet endpoint, and nothing here reached one. |
| Device checks 31–40 | **PASS**, 2026-09-12, on the owner's Seeker, using the devnet transfer recorded under SAW-024 below. Activity kept every record across a force-stop and across removing the connection that asked, and the explorer opened on devnet. |

## Verification record: SAW-024

SAW-024, 2026-09-12. It adds no feature, so the two halves are kept apart: what a machine checked,
and what only the Seeker can.

### Automated

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, `tsc`, 389/389 sidecar tests (5 more than before), and 29/29 test-agent tests. |
| `pnpm check:generated` | PASS: SAW-024 changed no `.proto` file, and the committed generated code and fixtures match a fresh generation. |
| `pnpm check:android` | PASS: Spotless, lint, both APKs, and 421/421 unit tests — the same 421 as before, because SAW-024 changed no Kotlin. |
| `pnpm test:hello`, `pnpm test:queue`, `pnpm test:transfer`, `pnpm build` | PASS: 9/9, 7/7, 7/7 with the opt-in devnet case skipped, and both packages build. **No transaction was sent to any cluster.** |
| `SEEKER_VAULT_NETWORK_CHECKS=1 pnpm test:transfer` | NOT RUN: it needs a real devnet endpoint, and nothing here reached one. |
| Nothing spends by default | PASS: five new checks in `mcp-server/src/stage-boundary.test.ts` read `.env.example`, every workspace `package.json`, the CI workflows, the sidecar's shipped sources, and the acceptance suite. |
| Deliberate breaks | Each break failed its own check and nothing else, and each file was restored from the index afterwards:<ul><li>`SOLANA_RPC_URL=https://api.mainnet-beta.solana.com` in `.env.example` failed "ships no endpoint in .env.example".</li><li>A `test:devnet` script setting the same variable failed "runs no script that supplies an endpoint or asks for funds".</li><li>An `env: SOLANA_RPC_URL:` on a CI step failed "runs no CI job that supplies an endpoint or asks for funds".</li><li>A `DEFAULT_ENDPOINT` constant in `mcp-server/src/solana/rpc.ts` failed "names no cluster endpoint in any shipped sidecar source".</li><li>A comment naming `api.devnet.solana.com` above the opt-in gate failed "keeps the one check that reaches a real network behind its variable".</li></ul> |

### On the physical Seeker

Run by the owner on 2026-09-12, on the physical Seeker, with Seed Vault Wallet, on **devnet**.

| Check | Result |
| --- | --- |
| Checks 41 to 55 | **PASS**, every one of them, on devnet. |
| A real transfer through Seed Vault Wallet, and its cluster | **PASS**: one SOL transfer from the owner's wallet to another of their own, **asked for from Hermes**, approved by hand in the app and again in the wallet, and **finalized on devnet in slot 497322461**. The capture is below. |
| Whether Seed Vault Wallet serves devnet | **PASS — it does.** Check 41 connected on devnet, and the transaction that followed settled there. |
| What Seed Vault Wallet shows while signing a transaction | **PASS** as an approval: the owner reviewed the transaction in the wallet and approved it there, and the app came back with the transaction's ID. **The wallet's exact screen text was not captured in this record** — check 48 is where it goes when it is. |
| The agent's result matched against the on-chain transaction | **PASS**: checks 49 to 51. The signature the agent reported is the one devnet finalized, and the `explorer_url` it returned carries `cluster=devnet`. |
| The rejection and stale-preparation walkthroughs | **PASS**: checks 52 to 54, with no unintended submission. |
| Hermes asking for the transfer | **PASS**: the request came from Hermes through `vault_transfer`, not from `pnpm agent`. [Section 6 of the Hermes guide](../integrations/hermes.md#6-send-a-transfer-with-your-wallet) is that path. |
| Mainnet | **NOT RUN**, and not proposed. Nothing here has ever pointed at mainnet, and the owner chooses whether it ever does. |

The request was created from **Hermes**, calling `vault_transfer`; the owner approved it on the Seeker; and the reading below came from `pnpm agent status`, which is why it has that shape:

```json
{
  "request_id": "7797fbc2-60a0-42a1-abb2-80319975438c",
  "action": "transfer",
  "status": "SUBMITTED",
  "terminal": false,
  "updated_at": "2026-09-12T18:39:06.378Z",
  "network": "devnet",
  "wallet": "Bzy2Lso…2B16K54",
  "signature": "2Hn7TF6z9kT5y9h7AWaRLMHF6pgvTLftewTQTdS8Guj6UUQvbuQw4CZHsCZn6eLqkqRJPokaZfCRhwJDKn8Kwv2k",
  "signature_is_transaction": true,
  "confirmation": "finalized",
  "slot": 497322461,
  "checked_at": "2026-09-12T18:39:35.947Z",
  "checked_with": "api.devnet.solana.com",
  "explorer_url": "https://explorer.solana.com/tx/2Hn7TF6z…Kwv2k?cluster=devnet"
}
```

**That capture is a reading taken 29 seconds after the approval, and it is kept exactly as it came
back.** Devnet had already *finalized* the signature while the request still read `SUBMITTED`: the
reading fell inside the window [`confirmation.ts`](../../server-sdk/src/requests/confirmation.ts)
describes, where the endpoint has a status for a signature but has not yet served the transaction
itself, so the server had nothing to compare against the bytes the owner approved and left the
request where it was. **No later `CONFIRMED` reading was captured**, and this record does not claim
one. The sending wallet is the owner's own; its full address is on the explorer page.

**Stage 4's acceptance is met.** A real wallet signed a real transaction, a real cluster finalized
it, the agent's result names that transaction, and the refusal paths sent nothing — on devnet. On
mainnet, nothing has been run and nothing is claimed.
