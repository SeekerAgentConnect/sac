# Record the owner's device verification of Stages 1–4

The owner ran the device checks on the physical Seeker on 2026-09-12 and reports every numbered
check in Stages 1 to 4 as PASS, including one real SOL transfer on **devnet** through Seed Vault
Wallet, approved by hand in the app and in the wallet.

## The evidence

`pnpm agent status 7797fbc2-60a0-42a1-abb2-80319975438c`, captured 2026-09-12T18:39:35.947Z:

```json
{"request_id":"7797fbc2-60a0-42a1-abb2-80319975438c","action":"transfer","status":"SUBMITTED",
 "terminal":false,"updated_at":"2026-09-12T18:39:06.378Z","network":"devnet",
 "wallet":"Bzy2Lso…2B16K54",
 "signature":"2Hn7TF6z9kT5y9h7AWaRLMHF6pgvTLftewTQTdS8Guj6UUQvbuQw4CZHsCZn6eLqkqRJPokaZfCRhwJDKn8Kwv2k",
 "signature_is_transaction":true,"confirmation":"finalized","slot":497322461,
 "checked_at":"2026-09-12T18:39:35.947Z","checked_with":"api.devnet.solana.com",
 "explorer_url":"https://explorer.solana.com/tx/2Hn7…Kwv2k?cluster=devnet"}
```

This capture is 29 seconds after approval, in the window the confirmation code describes: devnet had
**finalized** the signature, and the server had not yet matched the on-chain transaction against the
approved bytes, so the request still read `SUBMITTED`. **No later `CONFIRMED` reading was captured**,
and the record says so rather than implying one.

## Rules this has to respect

- `AGENTS.md`: device checks are recorded as PASS, FAIL, or NOT RUN. Nothing is upgraded past what
  the owner reported, and nothing that was not observed is invented — not the wallet's screen text,
  not a CONFIRMED reading, not a mainnet run.
- **Devnet only.** Nothing here has pointed at mainnet, and that stays true.
- Checks that are not Seeker checks stay NOT RUN: Hermes on a real VPS through `ssh -R`,
  `pnpm test:hello --device`, and `SEEKER_VAULT_NETWORK_CHECKS=1 pnpm test:transfer`.

## Items

- [x] Pull `master` (merged) and work there
- [x] `docs/testing/stage-1.md` — owner's Seeker column, verification record
- [x] `docs/testing/stage-2.md` — owner's Seeker column, verification record, acceptance report
- [x] `docs/testing/stage-3.md` — SAW-015/016/018 records, the network question answered
- [x] `docs/testing/stage-4.md` — SAW-023/024 records, the cluster question answered, acceptance met
- [x] `docs/testing/wallet-lifecycle.md` — the owner's lifecycle checks
- [x] `docs/testing/hello-world.md` — the Stage 1 device run
- [x] `docs/guides/wallet-setup.md` — the wallet under test: version, network, what it shows
- [x] `docs/guides/transfers.md` — the walkthrough no longer branches on an unknown network
- [x] `docs/integrations/hermes.md` — what has and has not been driven by Hermes
- [x] `docs/guides/macbook-seeker-quickstart.md`, `docs/protocol.md`, `README.md`, `CODEBASE.md`
- [x] `docs/changelog/2026-09-12.md` — the device verification entry
- [x] `pnpm check` passes (396 sidecar, 29 test-agent). `pnpm check:generated` could not run: `buf` needs the network and this machine's TLS verification is failing (`x509: OSStatus -26276`), which also broke `git fetch` and `gh`. No `.proto` changed — the diff is Markdown only.
- [x] `pnpm check:android` not re-run: no Kotlin changed
- [x] Commit on `master`
- [x] Linear: SEE-7, SEE-14, SEE-15, SEE-21, SEE-22, SEE-26, SEE-27, SEE-33

## Review

**What the record now says, and what it deliberately doesn't.**

Every device row across Stages 1 to 4 that the owner's run covers is PASS, dated 2026-09-12, with
the cluster named. Four things stayed NOT RUN because nobody ran them, and saying otherwise would
have been the easy lie this repository has spent four stages not telling:

- **Mainnet**, in any form.
- `SEEKER_VAULT_NETWORK_CHECKS=1 pnpm test:transfer` — it reads a real endpoint, needs no funds, and
  still nobody has pointed it at one.
- `pnpm test:hello --device`, and Hermes over `ssh -R` to a real VPS (the owner's Hermes reaches the
  Mac over Tailscale).
The transfer itself was asked for **from Hermes**, calling `vault_transfer`, and approved on the
Seeker — the full agent-asks-owner-approves chain, not `pnpm agent` standing in for an agent. The
`pnpm agent status` capture is a later reading of that same request.

**Two observations the device could have given and didn't.** Seed Vault Wallet's version string, and
the exact wording of its signing screens. Those rows say "not captured" rather than PASS or NOT RUN,
because the check was run and the observation simply wasn't written down. Steps 19 and 28 and check
48 are where they go if anyone repeats it.

**The capture is kept as it came back.** `pnpm agent status` read `SUBMITTED` while devnet had
already finalized the signature — the reading landed in the window where the endpoint has a status
but has not served the transaction, so the server had nothing to compare against the approved bytes.
The record says that, and does not claim a `CONFIRMED` reading nobody captured.

**Historical records were left historical.** The per-ticket verification tables in
`docs/development/*.md` and `docs/protocol.md` describe what a particular run checked on a particular
day; rewriting them would turn a log into a claim. The stage pages, the README, and `CODEBASE.md`
carry the current position instead, and the changelog gained a dated "Verified on the device" entry
rather than edits pretending the earlier entries knew.
