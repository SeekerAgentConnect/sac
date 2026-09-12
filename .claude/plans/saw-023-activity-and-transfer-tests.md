# SAW-023 — Transfer integration tests and local activity history (SEE-32)

Depends on SAW-022. Two halves that meet in the middle: an agent that can drive a transfer end to
end in a test, and a record on the phone that says what was actually done with the owner's money.

## What the ticket asks

- [x] Extend test-agent with transfer creation and status retrieval. Require explicit
      wallet/network/amount inputs for financial tests.
- [x] A standard Activity list/details screen storing request source, reviewed operation, outcome,
      signature, and a network-correct explorer link.
- [x] Deterministic tests using fixture RPC/MWA responses, plus opt-in network checks. Default
      automated checks must not spend mainnet funds or depend on a funded personal wallet.
- [x] Restart and repeated-result handling against the same history record. Connection IDs, wallet
      addresses, clusters, and asset identities in storage keys.
- [x] Scenarios: SOL and SPL transfer, missing associated account, failed transaction, rejection,
      duplicate result.
- [x] The history shows the correct cluster and never labels a message signature as a confirmed
      payment.
- [x] The app stays usable when a history refresh or an explorer launch fails.

## Decisions

- **The history is its own store, not a view of the answers.** `ResultStore` drops a settled answer
  after a week and drops every answer when its connection is removed. A record of what was spent
  must outlive both, so `activity/storage/ActivityStore.kt` keeps its own file per request and
  nothing prunes it. The owner can clear it themselves, which is the only way it goes.
- **One writer.** Every place the repository writes an answer also writes the record, through one
  private `save()`, so a record can't be forgotten at one call site and written at another.
- **The reviewed operation is the request's own terms.** Wallet, network, recipient, amount in base
  units, and mint. The phone refuses to approve a transfer whose bytes don't match those terms
  (SAW-020), so recording the terms records what was reviewed. Decimals are not stored: the base
  units are what the transaction carries, and a decimal count read from a mint months ago is not
  worth the risk of showing a wrong amount.
- **A message signature is not a payment.** The record says which kind of signature it holds, and
  only a transaction signature gets an explorer link. The screen says so in words too.
- **The explorer is a link, never a fetch.** The app hands the URL to the system browser. It opens
  no connection to any host but its own sidecars, and `StageBoundaryTest` now proves exactly that
  rather than banning the string `solana.com`.
- **`transfer` in the test agent stops guessing.** A financial request names its wallet and its
  network explicitly, and the agent refuses to fall back on `vault_get_address` for either.

## Steps

### The test agent

- [x] `--network`, and `--wallet` and `--network` both required for `transfer`.
- [x] `status <id>`: the outcome in one line of JSON, with the cluster and, for a transfer only, an
      explorer URL. Exit 0 settled and good, 10 not settled yet, 11 settled badly.
- [x] `sidecar/src/testing/process.ts`: a `solanaRpcUrl` option, so a spawned sidecar reads the
      fake chain.
- [x] `test-agent/src/stage4.acceptance.ts`, run by `pnpm test:transfer`: the scenarios above
      against a real sidecar process and a fake RPC, with the Connect phone client standing in for
      the phone and its wallet.
- [x] The opt-in network check, off unless `SEEKER_VAULT_NETWORK_CHECKS=1`: read-only, devnet only,
      and it sends nothing.
- [x] `cli.test.ts`: the new refusals and `status`.

### The phone

- [x] `activity/ActivityRecord.kt`, `activity/storage/ActivityStore.kt`, `activity/ActivityLog.kt`.
- [x] `ConnectionRepository` writes every answer through one `save()`.
- [x] `activity/ActivityViewModel.kt`, `ActivityScreen.kt`, `ActivityDetailsScreen.kt`,
      `ActivityText.kt`, `Explorer.kt`, `res/values/strings_activity.xml`.
- [x] A row on Connections, a route, and the ViewModel in `MainActivity`.
- [x] Tests: the store, the log across a restart and a repeated result, the ViewModel, both
      screens, the explorer, and the boundary.

### Documentation

- [x] `docs/testing/stage-4.md`, `test-agent/README.md`, `docs/guides/transfers.md`.
- [x] `CODEBASE.md`, `README.md`, `AGENTS.md`, `docs/security.md`, `docs/development/android.md`,
      `docs/development/sidecar.md`, `docs/changelog/2026-09-12.md`.

## Review

Everything above is done, and two things changed along the way.

**The history had to be able to say "I couldn't read this".** The first `ActivityStore` returned an empty list for a directory it couldn't list, which is what `File.listFiles()` gives you for an unreadable directory and for one that isn't there. `ActivityViewModelTest` caught it: a history that can't be read was silently wiping the records already on screen and presenting an empty list as the truth. It now throws for a directory that exists and can't be listed, returns empty only for one that isn't there yet, and the screen keeps what it last read and says the list may not be the whole history. An unreadable record and no record must never look the same.

**The stage boundary's ban on `solana.com` had to become a better check.** It existed to prove the app has no chain endpoint, and a link to the public explorer is not one. Banning the string would have meant either no explorer link or a weaker guarantee, so the guard was replaced with two that prove the actual point: the explorer address appears in exactly one file, which holds no HTTP client, and the app's HTTP clients exist only in the two sidecar transports and the one client they share. A new file that talks to a host is now a test failure that names itself.

**Eight deliberate breaks, each restored:** a link that ignores the record's cluster; a message signature counted as a transaction; a repeated result adding a second record; an approval the sidecar never accepted being recorded; an unreadable history reading as empty; the app fetching the explorer itself; the CLI guessing the wallet and cluster again; and `status` always exiting 0. Every one failed the tests that claim to catch it.

**Caveats.** No device was attached, so checks 31 to 40 in `docs/testing/stage-4.md` are NOT RUN. No automated check sent a transaction to any cluster, and the opt-in devnet case was not run either — it needs a real endpoint. The history stores base units and not a token's decimals, on purpose: a decimal count read months ago could show a wrong amount, and the base units are what the transaction carried.
