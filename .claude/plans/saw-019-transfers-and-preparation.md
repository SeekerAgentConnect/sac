# SAW-019 (SEE-28) — Transfer requests and fresh transaction preparation

**Branch:** `superset/feat/see-27-stage-4` (fast-forwarded onto Stage 3's `develop`)
**Acceptance:** the sidecar can prepare a supported transfer for review without ever signing or
submitting it.

## Design

- The sidecar gains one place that reaches a chain: `sidecar/src/solana/`. Nothing else imports a
  chain RPC, and nothing anywhere signs or sends.
- `@solana/web3.js` is used only for address maths and message compilation (`PublicKey`,
  `TransactionMessage`, `VersionedTransaction`, `SystemProgram`). The JSON-RPC client is our own, so
  every chain call, timeout, and error is explicit and testable offline.
- SPL instructions are encoded here rather than pulled from `@solana/spl-token`: the phone parses
  exactly these bytes in SAW-020, and the encodings are frozen by tests with real vectors.
- The request stays an *action*. `PrepareRequest` builds a fresh unsigned transaction each time,
  as a new version, and the old approval can no longer be used.

## Implementation

- [x] `proto/`: `PreparedTransaction.fee_lamports` and `.rent_lamports`;
      `REQUEST_ERROR_CHAIN_UNAVAILABLE`. Regenerate and add fixtures.
- [x] `sidecar/src/solana/addresses.ts`: program addresses, the associated-token-account PDA.
- [x] `sidecar/src/solana/token.ts`: `transfer_checked` and the idempotent ATA creation, and the
      mint and token-account layouts.
- [x] `sidecar/src/solana/rpc.ts`: the JSON-RPC client (genesis hash, account info, latest
      blockhash, block height, fee for message, rent exemption), with a `ChainUnavailable` error.
- [x] `sidecar/src/solana/network.ts`: the genesis hash of each network, so a wrong RPC is caught.
- [x] `sidecar/src/solana/transfer.ts`: `assertSupportedAsset` (classic SPL only: no Token-2022, no
      NFT) and `buildTransfer` (fresh blockhash, resolved decimals and token accounts, ATA creation
      only when needed, fee and rent estimates).
- [x] `sidecar/src/requests/preparation.ts`: the preparer the phone API calls.
- [x] `RequestStore.storePrepared`: a new version per preparation, PENDING transfers only.
- [x] `lifecycle.ts`: refuse an approval whose prepared transaction is at or near its blockhash
      expiry (STALE_PREPARATION).
- [x] `phone-service.ts`: serve `PrepareRequest` for transfers.
- [x] `mcp-tools.ts`: `vault_transfer`, and `transfer` in `vault_get_capabilities.operations`, both
      only when an RPC is configured.
- [x] `config.ts` + `.env.example`: `SOLANA_RPC_URL`, `SOLANA_RPC_TIMEOUT_MS`.
- [x] `test-agent`: a `transfer` command.
- [x] Guards: the sidecar's `stage-boundary.test.ts` lifts the transfer limit on purpose, and
      `roles.test.ts` gains `vault_transfer`.

## Tests

- [x] `solana/token.test.ts`: instruction bytes, layouts, ATA vectors.
- [x] `solana/rpc.test.ts`: error mapping, timeouts, RPC failure.
- [x] `solana/transfer.test.ts`: SOL and token amounts, decimals, missing token accounts,
      Token-2022, NFTs, the wrong network, a recipient that is a token account, RPC failure; the
      built transaction is deserialized and its instructions, fee payer, recipients, and signer set
      are checked.
- [x] `requests/preparation.test.ts` / `endpoints.test.ts`: preparation creates no submission, each
      preparation is a new version, and an old approval is refused.

## Docs

- [x] `docs/protocol.md` (transfer and preparation), `docs/guides/transfers.md`,
      `docs/development/sidecar.md`, `README.md`, `AGENTS.md`, `CODEBASE.md`, `docs/changelog/`.

## Review

Done as planned, with these decisions worth writing down:

- **`@solana/web3.js` for address maths and message compilation, and nothing else.** The JSON-RPC client is ours (`solana/rpc.ts`), so every chain call, timeout, and error message is explicit and testable offline, and the stage-boundary test can name every method the sidecar may call. `@solana/spl-token` was installed and then dropped: it pulls `bigint-buffer`, a native build, and encoding two instructions by hand is both smaller and exactly the bytes SAW-020 will parse.
- **The asset is checked when the request is created, not only when it is prepared.** A Token-2022 mint or an NFT is refused while the agent is still listening, instead of leaving a PENDING request the owner can never approve. The lifecycle has no PENDING → FAILED transition, so refusing late would have meant either a dead request or a state-machine change beyond this task.
- **The wrong network is caught by genesis hash, not by configuration.** An operator can point `SOLANA_RPC_URL` anywhere; comparing the cluster's genesis hash with the request's network is the only check that can't be got wrong.
- **A new `CHAIN_UNAVAILABLE` error.** A transient endpoint failure isn't the request's fault, and an agent needs to tell "retry this unchanged" from "this can never work".
- **`TransactionPreparer.prepareTransfer`, not `prepare`.** The stage-boundary test forbids `.prepare(` outside `src/storage/`, since that is SQLite's. Renaming the method kept the guard strict rather than loosening its regex.
- **The approval margin is 15 s.** An approval that arrives with less of the blockhash window left would have the wallet sign something that can no longer land; the phone prepares again instead.

Not in this task, by design: the phone parses none of these bytes yet (SAW-020), approves no transfer (SAW-021), and follows nothing to confirmation (SAW-022). The Android stage boundary is unchanged.

Checks, all on 2026-09-12: `pnpm check` (359 sidecar, 27 test-agent), `pnpm check:android` (294), `pnpm check:generated`, `pnpm test:hello` (9), `pnpm test:queue` (7), `pnpm build`, and six deliberate breaks that each failed the right test. The record is in `docs/development/sidecar.md`.
