# Stage 4 tests

Stage 4 is the first stage where funds can move. SAW-019 stores a transfer request and builds the unsigned transaction, SAW-020 teaches the phone to read that transaction itself, SAW-021 lets the owner approve one and their wallet sign and send it, SAW-022 follows it to the network, and SAW-023 closes the stage: the regression tests for that whole path, and the owner's own record of what was done.

SAW-017's lifecycle questions — rotation, backgrounding, a killed process, a restarted sidecar — have their own page, [`wallet-lifecycle.md`](wallet-lifecycle.md), which also carries the device checks for SAW-021 and SAW-022.

## Nothing here spends anything

**No automated check in this repository sends a transaction to any cluster, on mainnet or anywhere else, and none needs a funded wallet.**

- The sidecar has no way to broadcast: only `sidecar/src/solana/` reaches a chain, only read-only JSON-RPC methods exist there, and `sidecar/src/stage-boundary.test.ts` names every one of them.
- The app has no chain endpoint at all, and `StageBoundaryTest` proves it: the only hosts it opens a connection to are the sidecars it is paired with, and the block explorer is a link handed to the browser, never a fetch.
- The chain the tests read is `sidecar/src/testing/chain.ts`. `FakeChain` answers the builder directly, and `startFakeRpc` serves the same state as ordinary Solana JSON-RPC on loopback, so a sidecar process can be pointed at it without a network.
- The wallet is a throwaway Ed25519 key pair in the test process (`sidecar/src/testing/wallet.ts`) and, on the phone, `FakeWalletAdapter`. They make real signatures; they are not a wallet app.

The one check that talks to a real network is opt-in, reads, and is described at the end of this page.

## Automated checks

`pnpm check` runs the sidecar's and the test agent's tests, `pnpm check:android` runs the app's JVM and Robolectric tests, and `pnpm test:transfer` runs the acceptance scenario end to end. CI runs them.

| Area | What the tests cover | Where |
| --- | --- | --- |
| The chain client | Every read the sidecar makes, the errors each one maps to, and that a URL that can hold an API key never reaches a log or a message | `sidecar/src/solana/rpc.test.ts` |
| The transaction builder | SOL and SPL transfers, a recipient with no token account, a Token-2022 mint, an NFT, a token account given as a recipient, a frozen account, too little held, and a cluster that doesn't match the request | `sidecar/src/solana/transfer.test.ts` |
| Preparation | A new version per preparation, the content hash, the fee and rent, the blockhash window, and an approval refused once it runs down | `sidecar/src/requests/preparation.test.ts` |
| The phone's own parser | The shared fixtures, decoded on the phone, including the adversarial ones that are perfectly valid transactions and simply aren't the one that was asked for | `TransactionFixturesTest`, `transactions/` tests |
| Confirmation | A delayed confirmation, a chain failure, an RPC timeout, a signature not yet visible, a mismatched transaction, and a signature that can no longer land | `sidecar/src/requests/confirmation.test.ts`, `sidecar/src/solana/confirmation.test.ts` |
| The agent's transfer command | A transfer that names its wallet and cluster; one that names neither, or only one; a cluster the CLI doesn't know; an amount that isn't base units; a retry under the same key; and a refusal from the sidecar | `test-agent/src/cli.test.ts` |
| The agent's `status` | An acknowledgement, a cancelled request, a signed message, and a pending transfer, each with its exit code and its view — and no explorer link on a message signature | `test-agent/src/cli.test.ts` |
| The whole path | The scenario table below | `test-agent/src/stage4.acceptance.ts` (`pnpm test:transfer`) |
| The history's storage | Every field across a restart, a token transfer on its own cluster, a damaged file skipped, newest first, clearing, and IDs that could name a path | `ActivityStoreTest` |
| What the history records | One transfer through each step it takes; the same result three times; a restart in between; a message signature; an acknowledgement; a rejection; an outcome nobody knows; an answer that never reached the server; and an approval the server never accepted, which is recorded as nothing at all | `ActivityLogTest` |
| The history through the app | A sent transfer recorded with its cluster and its transaction ID, confirmed onto the same record, and a signed message that is never a payment | `InboxViewModelTest` |
| The history outliving the answer | An answer pruned a week after it settled, with its record read back whole on a restart | `ConnectionRepositoryTest` |
| The explorer link | The cluster in the link for each network, no link for a message signature, and no link without a signature or without a cluster | `ExplorerTest` |
| The screens | The list, the empty state, a history that can't be read, clearing only after a confirmation, the record in full, the cluster named on every transfer, the explorer offered only for a sent transaction, and the words that say a message signature is not a payment | `ActivityScreenTest`, `ActivityDetailsScreenTest`, `ActivityViewModelTest`, `ConnectionsScreenTest` |
| The stage boundary | Storage only in the three storage packages; the explorer address in one file, which holds no HTTP client; the app's HTTP clients only where they talk to a sidecar; and the sidecar's chain methods, which only read | `StageBoundaryTest`, `sidecar/src/stage-boundary.test.ts` |

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

## Verification record

SAW-023, 2026-09-12:

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, and `tsc`. 384/384 sidecar tests and 29/29 test-agent tests pass, 2 of the latter new. |
| `pnpm check:generated` | PASS: generated code and fixtures are up to date. |
| `pnpm check:android` | PASS: Spotless, lint, both APKs, and 421/421 unit tests, 42 of them new. |
| `pnpm test:transfer` | PASS: 7/7, with the opt-in devnet case skipped. **No transaction was sent to any cluster.** |
| `pnpm test:hello`, `pnpm test:queue`, `pnpm build` | PASS: 9/9, 7/7, and both packages build. |
| `SEEKER_VAULT_NETWORK_CHECKS=1 pnpm test:transfer` | NOT RUN: it needs a real devnet endpoint, and nothing here reached one. |
| Device checks 31–40 | NOT RUN: no device was attached. |
