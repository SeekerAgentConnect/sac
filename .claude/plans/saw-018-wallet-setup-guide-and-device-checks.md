# SAW-018 — Write the wallet setup guide and run Stage 3 device checks (SEE-26)

Stage 3's closing task. Everything it asks for is documentation and verification, plus the one
automated confirmation that the stage needs no funds, swaps, agent keys, or custom biometrics.

## Implementation

- [x] `docs/guides/wallet-setup.md`: the whole owner-facing round trip — open Seed Vault Wallet,
      connect the app, check the address and network, sign the example message, disconnect — with an
      explicit rule that no seed phrase or private key is ever asked for, pasted, or exported.
- [x] Record the tested wallet: its app version and the network path it actually serves, and say
      that a fake adapter may exercise error handling but never stands in for the Seeker Wallet
      acceptance check (R8).
- [x] `docs/integrations/hermes.md`: a signing section — create the request, review on the phone,
      read the result back, and verify the signature, with the rejection paths.
- [x] `docs/testing/stage-3.md`: SAW-018's device script and a verification record that keeps the
      automated results and the physical-Seeker results apart.

## Tests and checks

- [x] The Hermes → manual signature → Hermes round trip on the Seeker (device).
- [x] The same, rejected at the app and at the wallet (device).
- [x] No funds, swaps, agent keys, or custom biometrics — as an automated boundary check on both
      sides, not a claim in prose.
- [x] `pnpm check`, `pnpm check:generated`, `pnpm test:hello`, `pnpm test:queue`, `pnpm check:android`.
- [x] A deliberate break makes each new check fail, and is restored.

## Review

Done, except the half that needs hardware.

- **`docs/guides/wallet-setup.md`** is the whole round trip in five numbered steps: open Seed Vault
  Wallet, connect, check the address and network from both sides, sign the example message and read
  the signature back, disconnect. A section at the top says that nothing here ever asks for a seed
  phrase, a recovery phrase, or a private key, and that anything which does isn't part of this
  project. A table at the bottom records the wallet under test — version, device, the network path it
  accepted, and what it shows while signing — and says a development wallet never substitutes for the
  Seeker acceptance check (R8).
- **`docs/integrations/hermes.md`** gains section 5, the signing trip: capabilities, address, the
  request, the answer on the phone, the result, and verifying the signature against
  `signed_message_base64` with a verifier of your own. It states that these results come from
  `pnpm agent` and the automated tests, not from a Hermes session, because inventing a transcript
  would be indistinguishable from faking one. Two refusal rows joined the failures table.
- **`docs/testing/stage-3.md`** gains the SAW-018 coverage table, the Hermes device script as steps
  24 to 33 (including rejection on the phone and rejection inside the wallet), a "what this stage
  never needs" section with the evidence for each item, and a verification record split into
  automated (all PASS) and physical Seeker (all NOT RUN).
- **Two new boundary tests** turned the funds/swaps/keys/biometrics claim into a check:
  `sidecar/src/stage-boundary.test.ts` pins every registered MCP tool to the seven that exist and
  fails on a chain RPC host, a broadcast API, or any private key, secret key, or keypair in a shipped
  source; `StageBoundaryTest.nothingSpendsSwapsOrAsksForABiometricOfItsOwn` fails on a transaction
  API, a biometric or device-credential API, or a biometric library on the classpath. Each scan has a
  positive control so it can't pass by finding nothing.

### What ran

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: 283/283 sidecar, 23/23 test agent |
| `pnpm check:generated` | PASS: no `.proto` changed, output matches a fresh generation |
| `pnpm test:hello` / `pnpm test:queue` | PASS: 9/9 and 7/7 |
| `pnpm check:android` | PASS: Spotless, 294/294 unit tests, lint clean, both APKs |
| Deliberate breaks | Four, each failing only the intended test and restored byte for byte: an RPC host in `mcp-tools.ts`; a tool registered under an unlisted name; a `BiometricPrompt` mention in `InboxViewModel.kt`; a `sendTransaction` mention in `MwaWalletAdapter.kt`. |
| Markdown links | PASS: every relative link and heading anchor in the repository resolves |
| The owner's checks on the Seeker, steps 1 to 33 | **NOT RUN**: no device was attached |

Stage 3's acceptance is therefore still open: the guide is reproducible and every automated check
passes, but the owner's own wallet has not signed anything, and no mock or emulator counts.
