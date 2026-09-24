# SEE-146 — a standalone SKR staking MCP server, and the approval path that reviews it

Ticket: https://linear.app/seekeragentwallet/issue/SEE-146
Branch: `feat/see-146`, based on `feat/see-145`. PR base is `feat/see-145`, never `master`.

## What the ticket asks for

A second, independently deployable MCP server in this repository that speaks SKR staking, connects
to the phone as its own SAC connection, and reaches the owner through the review and wallet path
that already exists. Five tools: one read and four that create approval requests. The server never
signs, never sends and never approves.

## What was established before any code was written

Verified against mainnet-beta on 2026-09-24, and against the program's own on-chain Anchor IDL
(`4aAEUKCcju9iAEAgdeaNz4RC7sCPv63q5g714nw4QY68`, whose decompressed JSON is byte-equal to the
React Native sample's copy):

- Program `SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ`, **mainnet-beta only**. All five addresses
  return "not found" on devnet, so the ticket's warning not to assume an official devnet is correct
  and this server declares mainnet and nothing else.
- `stake_config`, `stake_vault` and `guardian_pool` are all program-derived, and each derivation
  reproduces the live address exactly. `user_stake` derivation was checked against a real staker's
  account. Only the program ID and the SKR mint are roots of trust.
- `unstake` takes **shares (u128)**, not SKR. `withdraw` and `cancel_unstake` take no argument at
  all. `withdraw` requires no signature from the owner — it is a crank — so the owner's signature
  on it is the fee payer's.
- The cooldown is `StakeConfig.cooldown_seconds`, currently 172800 (48 h). It is read from chain.
- The documented "unstaking again combines the amounts and resets the cooldown" is only true while
  the cooldown is still running. Once it has completed the program refuses with `WithdrawRequired`
  (6017), so the owner must withdraw first. The review says which of the two they are in.
- A `tokens -> shares -> tokens` round trip loses up to one base unit, because the program floors.
  Full-position unstaking must pass `UserStake.shares` exactly rather than reconverting.
- `solana-mobile/react-native-samples` carries **no licence file**, so none of its source is copied.
  Discriminators, layouts and seeds are derived and each derivation is asserted against the IDL and
  against live accounts.

Evidence lives in the verification record, `docs/testing/see-146.md`.

## Shape

```
agent ──MCP──▶ skr-staking-server ──server-sdk──▶ phone ──MWA──▶ wallet
                    │                                 │
                    │ builds unsigned bytes           │ decodes those bytes itself and
                    │ from fresh chain state          │ checks them against the request
                    └── never signs, never sends ─────┘
```

The server prepares; the phone verifies. Nothing the server says about a transaction is evidence
about it — the phone re-derives every address it checks and reads every instruction.

## Stages

### Stage A — the protocol (the smallest change that carries four actions)

- [x] `proto/seekervault/request/v1/request.proto`: one `StakingAction staking = 5;` in the `Action`
      oneof, with a `StakingOperation` enum. One message, not four, because the four share a wallet,
      a network and at most an amount; and no program, mint or pool on the wire, because the phone
      must use its own constants or the verification is worthless.
- [x] `pnpm generate`, and cross-runtime fixtures for the new kind.
- [x] server-sdk: `invalidActionReason`, `actionBinding`, the four kind lists and `TRANSITIONS` in
      `lifecycle.ts`, `unpreparableReason`, `boundNetwork`, `checkable`, `checkableBySync`,
      `privateRequest`'s capability union and `title`.
- [x] server-sdk: a `StakingProvider` preparation seam beside `TransferProvider`, wired through
      `OpenDirectServerOptions`, `TransactionPreparer` and `phone-service.prepareRequest`.

### Stage B — the server (`skr-staking-server/`)

- [x] `src/skr/`: the program's identity and derivations, the account decoders, the share
      arithmetic, the four instruction builders. Tested against real accounts and a real mainnet
      `stake` transaction, byte for byte.
- [x] `src/skr/provider.ts`: chain reads, the five operations' preconditions, and preparation.
- [x] `src/requests/tools.ts`: the five MCP tools.
- [x] `src/config.ts`, `src/cli.ts`, `src/server.ts`: configuration, the executable, the listener.
- [x] Its own package gate, Dockerfile, compose project and `.env.example`.

### Stage C — the phone

- [x] `android/.../skr/`: the program's constants, its instruction readers, and `inspectStaking` —
      an independent reading of the bytes, not a check of the server's claims.
- [x] The direct request path: preparation and inspection for a staking action, which today stops
      at `if (request.transfer() == null) return`.
- [x] Review, wallet hand-off, approval and result for the four actions, including the distinction
      between Unstake and Withdraw and the cooldown reset warning.
- [x] Sandbox stays as it is: a direct connection is production by invariant, and the tests say so
      rather than inventing a simulation for it.

### Stage D — documents

- [x] `docs/wiki/skr-staking.md`, `docs/integrations/skr-staking.md`,
      `docs/development/skr-staking-server.md`, `docs/testing/see-146.md` (the manual lifecycle
      guide and the verification record), `docs/changelog/2026-09-24.md`, `CODEBASE.md`, `README.md`.

## Decisions worth writing down

- **Four operations, one wire message.** `StakingAction{wallet, network, operation, amount}`. Amount
  is required for stake and unstake and must be empty for cancel and withdraw, because those two act
  on the whole pending position and an amount would imply a choice the program does not offer.
- **No program, mint, pool or account on the wire.** A server that could name the program it wants
  called is a server that can redirect an approval. The phone compiles in the program ID and the
  mint, derives the rest, and refuses anything else.
- **Unstake is requested in SKR and carried out in shares.** The server converts against fresh
  state; the phone checks the shares in the bytes are worth no more than the SKR that was approved,
  and that a full unstake burns exactly the shares the account holds.
- **No staking feed provider.** "Shared staking feeds" are out of scope, so nothing is registered in
  the proposal registry and no `ActionPayload` is added.
- **Sandbox is not invented for direct connections.** `Connection.kt` requires a direct connection to
  be production, and that invariant is older than this ticket. What the tests demonstrate is that
  the sandbox promise is unchanged and that no staking path can sign or broadcast without the owner.

## Review

Everything the ticket asks for is in, and the stages above are all done. What changed after the
plan was written:

- **Stage B's packaging was finished last.** The server had its code and tests before it had a
  `Dockerfile`, a `.env.example`, a `README`, a `LICENSE` or a compose project. All five are here
  now, and `scripts/check-deployments.mjs` carries a `skr staking` preset plus two assertions that
  are really about the ticket's "runs independently": the server's `.env.example` may not reuse one
  of the general server's variable names, and its default port may not be the general server's.
- **Four checks were missing from the phone's inspection and were added.** An amount on a cancel or
  a withdrawal was accepted and ignored, where the protocol says it must be refused; the guardian
  pool's own `guardian` field was never compared against the derived one; the instructions' fixed
  account positions — the program's own ID, its event authority, the token and system programs —
  were carried past unchecked; and `checkOthers` ended in a dead `if (reading == null) return`
  whose parameter had no other use. `StakingFinding.UnexpectedAccount` is the one new finding.
- **Three boundary tests and one fixture test had to be told about the new package.**
  `readingATransactionStaysInOnePackageAndOnlyReads` now admits `skr/` beside `jupiter/` as a
  *caller* of the one decoder and still refuses a second parser; the policy package's exact import
  list gained the two reads it makes of `skr/`; a comment in `SkrProgram.kt` was reworded because
  `nothingSpendsSwapsOrAsksForABiometricOfItsOwn` greps raw lines for `mainnet-beta` and a guard
  against a hardcoded endpoint is worth more than that phrasing. `RequestProtocolFixturesTest` now
  covers the two staking fixtures the protocol commit added.
- **The fake gateway did not know about staking.** It refused to prepare a non-transfer request and
  refused to accept an approval for one, so the first ViewModel test showed `NotApproved` rather
  than a wallet hand-off. Both now treat a staking action exactly as they treat a transfer,
  including the stale-preparation refusal.

## What was not done, and why

- **No physical-device run and no real mainnet action.** Both are ticket constraints. The owner-run
  lifecycle guide is `docs/testing/see-146.md`, including the 48-hour wait.
- **`docker build` and `pnpm check:deployments` were not run**: no Docker daemon on this host. The
  Dockerfile and compose project mirror `mcp-server`'s, which is verified in CI.
- **No npm package gate.** `mcp-server` has one (`pnpm test:mcp-server-package`) because it is
  published as an executable tarball. This server is deployed from source or as an image, which is
  what the ticket asks for, so a tarball gate would be a gate over a thing that does not ship.
