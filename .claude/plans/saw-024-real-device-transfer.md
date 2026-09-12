# SAW-024 — Document and validate a real-device transfer (SEE-33)

Stage 4's last task. It adds no feature: it writes the owner's walkthrough for sending a real
transfer from the Seeker, says plainly what has and has not been run on a device, and makes the
"nothing spends by default" promise a check rather than a sentence.

## What the ticket asks

- Numbered steps: choose the supported network, inspect the source and recipient addresses, obtain
  test funds where they exist, request a transfer, approve it, verify its signature.
- Use Stage 3's wallet/network findings. **Do not claim a devnet transfer through Seed Vault Wallet
  was tested unless it was.** Keep mock and devnet coverage apart from the final real-wallet check.
- Mainnet validation is selected and approved by the owner, with a deliberately small amount. No CI
  job and no setup command spends real funds by default.
- Document: refresh after a stale preparation, not enough SOL for fees, the cost of creating a token
  account, a rejection in the wallet, and recovering from an unknown result.
- Checks: a real transfer through Seed Vault Wallet on the Seeker, its outcome and cluster recorded;
  the agent's result matched against the on-chain transaction; the rejection and stale-preparation
  walkthroughs run without an unintended submission.

## Items

- [x] `docs/guides/transfers.md`: a numbered walkthrough, `#### 1` to `#### 11`, from choosing the
      network to matching the signature on an explorer.
- [x] The same guide: the mainnet rule, in its own subsection — deliberate, small, an address you
      control, and nothing in the repository pointing there by itself.
- [x] The same guide: the five situations, with what the screen actually says in each.
- [x] The same guide: what the sidecar does **not** check — it reads no balance, so the fee, the
      rent, and a SOL amount larger than the wallet holds are all found out on chain.
- [x] `docs/testing/stage-4.md`: SAW-024's device checks, continuing the numbering at 41, split
      into the transfer itself, the rejection walkthrough, the stale-preparation walkthrough, and
      the mainnet check that is the owner's to choose.
- [x] The same page: what is covered by a fake chain, what by an opt-in devnet read, and what only
      the Seeker can show — kept apart, and the verification record.
- [x] `docs/integrations/hermes.md`: a transfer section, `vault_transfer` in the example config, the
      transfer failures, and a plain statement that none of it has been driven by Hermes.
- [x] `docs/guides/wallet-setup.md`: the wallet under test gains the rows Stage 4 needs — the
      network the wallet served for a transfer, and what it shows while signing one.
- [x] `docs/guides/troubleshooting.md`: the two entries the transfer section is missing — the stale
      preparation, and not enough SOL for the fee.
- [x] A check: `sidecar/src/stage-boundary.test.ts` proves no default spends — `.env.example` ships
      no endpoint, no `package.json` script names one, no CI job sets one, and the one check that
      touches a network is behind `SEEKER_VAULT_NETWORK_CHECKS`.
- [x] `docs/changelog/2026-09-12.md`, `CODEBASE.md`, `README.md` if the stage's status changes.
- [x] Run `pnpm check`, `pnpm check:generated`, `pnpm check:android`, `pnpm test:hello`,
      `pnpm test:queue`, `pnpm test:transfer`, `pnpm build`.
- [x] Break the new check deliberately, and restore it.

## Review

SAW-024 adds no feature. Everything it claims is either a sentence someone can follow or a check
that fails when the sentence stops being true; nothing in the app or the sidecar behaves
differently.

**What the writing had to get right.** Two things were nearly said wrongly and were caught by
reading the code rather than the existing docs:

- **The sidecar reads no balance.** There is no `getBalance` call anywhere in
  `sidecar/src/solana/rpc.ts` — the eight methods it has are named in the stage boundary. So "the
  sidecar doesn't build a transfer that can't work" was only ever true of a *token* balance, read
  out of the token account. A SOL amount larger than the wallet holds, a fee it can't cover, and
  the rent for a token account are all found out on chain. `transfers.md`'s refusal table said
  otherwise by omission, and now says which.
- **No claim of a devnet transfer through Seed Vault Wallet.** Stage 3's step 9, which records what
  networks the wallet actually serves, is still NOT RUN. The walkthrough therefore *branches* on
  the answer rather than assuming one, and the mainnet-only path is written out in full instead of
  being left as an exercise.

**The check that replaced a promise.** "No CI or setup command spends real funds by default" was a
sentence in three documents. It is now five assertions: `.env.example` must leave `SOLANA_RPC_URL`
empty; no workspace script and no CI workflow may set it or name a cluster endpoint, a faucet, or
an airdrop; no shipped sidecar source may name a cluster host; and every mention of a real cluster
in `stage4.acceptance.ts` must sit after the `SEEKER_VAULT_NETWORK_CHECKS` gate. A cluster named in
a *comment* is fine — `.env.example`'s own comment names one, to say what an endpoint looks like —
so the first check reads assignment lines, not the whole file.

**Five deliberate breaks, five different failures.** An endpoint in `.env.example`, a `test:devnet`
script, an `env:` on a CI step, a `DEFAULT_ENDPOINT` in `rpc.ts`, and a cluster named above the
gate. Each failed its own check and no other, and each file was restored from the index.

**What is still not done, and can't be done here.** No wallet has sent a transaction to any cluster
for this repository. Checks 41 to 55 are NOT RUN, Stage 4's acceptance is not met, and nothing in
the repository has ever pointed at mainnet. That is the honest state, and it is what the ticket,
the README, the changelog, and the stage-4 record all say.
