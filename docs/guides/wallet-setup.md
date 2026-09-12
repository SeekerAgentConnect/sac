# Connect your wallet

This guide connects the wallet you already have to the app, so agents can read your address and ask you to sign with it. It goes the whole way round: open Seed Vault Wallet, connect the app, check the address and network, sign the example message, and disconnect again. Nothing here creates a wallet, and the app never sees your seed phrase or your keys.

Before this, pair the phone with at least one sidecar: [`pairing.md`](pairing.md).

## You will never be asked for your seed phrase

**No step in this repository asks you to type, paste, photograph, or export a seed phrase, a recovery phrase, or a private key.** Not this guide, not the app, not the sidecar, not an agent. Your keys stay inside the wallet app on the phone, which never hands them out, and nothing here has anywhere to put them.

If a screen, a page, or an agent ever asks you for one, it isn't part of this project. Stop, and don't type it.

| | Where it goes |
| --- | --- |
| Your wallet's **address** (a public key) and the **network** you picked | Kept on the phone, and published to every sidecar you've paired with, so agents can read it |
| The wallet's **authorization** for this app | Kept on the phone only, encrypted, never backed up, never sent to a sidecar or written to a log |
| Your **seed phrase** or **private keys** | Never asked for, never seen, never stored |

The sidecar holds no keys and makes no wallet of its own. Until you connect one, an agent that asks for your address is told `WALLET_NOT_CONNECTED`.

## 1. Open Seed Vault Wallet

The app drives a wallet you already have; it doesn't set one up. On the Solana Seeker that wallet is **Seed Vault Wallet**.

1. Open Seed Vault Wallet on its own, before the app, and finish whatever it asks for on first run. It is the wallet, not this app, that decides how you unlock it — a PIN, a fingerprint, a face. This app adds no lock of its own and asks for none.
2. Check that it shows an account and an address. If it doesn't, there is nothing for the app to connect to yet.
3. Note its **version**, from the wallet's own settings or from Android's **Settings → Apps → Seed Vault Wallet**, and write it into [the wallet under test](#the-wallet-under-test) below. Which networks a wallet serves, and what it shows while signing, are properties of that version.

Any other wallet that speaks Mobile Wallet Adapter works the same way. The acceptance check for this stage is Seed Vault Wallet on the Seeker, though: see [the rule about development wallets](#a-development-wallet-is-not-the-check).

## 2. Connect

1. Open the app. The first row on **Connections** is **Wallet**; tap it.
2. Pick the **network**: Mainnet, Devnet, or Testnet. You can't change it later without connecting again.
3. Tap **Connect wallet**. The wallet opens and asks you to choose an account and approve.
4. Back in the app you see the address, the network, the account's name in the wallet, and when you connected it. Underneath, the app says how many connections it told.

The **Connections** screen's Wallet row then shows the address, so you can see at a glance which wallet agents are working with.

### If something goes wrong

| What the app says | What it means | What to do |
| --- | --- | --- |
| No wallet app answered | Nothing on the phone speaks Mobile Wallet Adapter | Install or set up a wallet, such as Seed Vault Wallet, and try again |
| The wallet didn't give an account | You declined, or left the wallet without choosing | Nothing changed. Tap **Connect wallet** again. |
| The wallet no longer accepts this app's authorization | The wallet revoked what the phone had stored | The phone has forgotten it. Tap **Connect wallet** to approve afresh. |
| The wallet doesn't serve this network | The wallet has no such cluster | Pick a network it offers. Which ones your wallet offers is a property of the wallet, not of this app. |
| The wallet didn't list this network for the account | The wallet returned the account but not that chain | You can go on, but the wallet may refuse to sign on it. Connecting on the network the wallet does list is safer. |
| Couldn't tell N connection(s) | A sidecar was unreachable | Tap **Tell them again** once it's back. Opening the app again also retries. |

## 3. Check the address and the network

Two places have to agree: the wallet itself, and what agents read.

1. On the app's **Wallet** screen, compare the address with the account in Seed Vault Wallet, character for character.
2. With the sidecar running and `MCP_URL` and `MCP_TOKEN` set (see [`macbook-seeker-quickstart.md`](macbook-seeker-quickstart.md)):

   ```console
   $ pnpm agent address
   {"wallet":"G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW","network":"devnet","bound_at":"2026-09-12T09:30:00.000Z"}
   ```

   With no wallet connected it exits 9 instead:

   ```text
   WALLET_NOT_CONNECTED: the owner has no wallet connected on their phone; ask them to connect one in the app
   ```

Hermes and other agents read the same thing through the MCP tool `vault_get_address`.

**The network is the wallet's choice, not the app's.** The app offers Mainnet, Devnet, and Testnet and asks the wallet for the one you picked; if the wallet doesn't serve it, it says so. Record which one your wallet actually accepted in [the wallet under test](#the-wallet-under-test), because everything afterwards happens on that network.

## 4. Sign the example message

This is the whole round trip, and it costs nothing: a signature moves no money and sends nothing to the network. The complete walkthrough, with every outcome the screen can show, is in [`message-signing.md`](message-signing.md); the same trip from Hermes is in [`../integrations/hermes.md`](../integrations/hermes.md#5-sign-a-message-with-your-wallet).

1. Ask for the signature from the computer that runs the sidecar:

   ```console
   $ pnpm agent sign "Sign in to example.com
   Nonce: 4711"
   idempotency key: sign-1f0a…
   The owner reviews it on their Seeker; nothing is signed until they approve.
   {"request_id":"f7e6d5c4-…","action":"sign_message","status":"PENDING","wallet":"G4bAtd9o…",…}
   ```

   **The wallet does not open.** Nothing has been signed, and the request is simply stored.

2. On the phone, open the app and then **Pending requests**, or tap **Refresh**. Open the request. Check the complete message, the byte count, and the wallet named under **Signs with**. A line break shows as `␊`, and any other invisible character as its code point.
3. Tap **Approve and sign**. Seed Vault Wallet opens and asks you to sign. Look at what it shows you, and approve there too.
4. The app comes back and says your wallet signed it.
5. Read the result, and let the agent check the signature itself:

   ```console
   $ pnpm agent get f7e6d5c4-…
   {"request_id":"f7e6d5c4-…","action":"sign_message","status":"COMPLETED","terminal":true,
    "wallet":"G4bAtd9o…","signature":"52o3UtBit8…","signed_message_base64":"U2lnbiBpbiB0by…",
    "signature_verified":true}
   ```

   `pnpm agent get` verifies the signature with its own Ed25519 verifier, against `signed_message_base64` — the exact bytes that were signed — and prints `signature_verified`. `true` means that address really did sign those bytes.

**Try rejecting, too.** Ask for another signature and tap **Reject** on the phone: no wallet opens, and the agent reads `REJECTED`. Ask for a third, tap **Approve and sign**, and decline inside the wallet instead: the app says you declined, and the agent reads `REJECTED` as well — not `FAILED`, because nothing went wrong; you said no.

## 5. Disconnect

Tap **Disconnect wallet**. The app tells the wallet that it no longer needs its authorization, forgets the address and the authorization, and tells every sidecar that no wallet is connected. Agents are then told `WALLET_NOT_CONNECTED` again, and pending wallet requests are cancelled.

Disconnecting the wallet doesn't touch your connections, your paired sidecars, or your answers.

## Change the wallet or the network

Connect again from the Wallet screen. Choosing another account, or another network, replaces the binding:

- Every sidecar is told the new address and network.
- Any **pending request queued for the old wallet is cancelled** by the sidecar, and disappears from Pending requests. Requests that don't involve a wallet, such as the demo acknowledgements, are left alone.

## The wallet under test

The wallet is not part of this repository, so what it does is recorded rather than assumed. Fill this in from your own device when you run the checks in [`../testing/stage-3.md`](../testing/stage-3.md#the-owners-checks-on-the-seeker).

| | Recorded |
| --- | --- |
| Wallet app | Seed Vault Wallet |
| Version tested | **NOT RUN**: no Seeker was attached during verification |
| Device and Android version | **NOT RUN** |
| Network path the wallet accepted (Mainnet / Devnet / Testnet) | **NOT RUN**: step 9 of the owner's checks records it |
| What the wallet shows while signing a message | **NOT RUN**: step 19 records it |

Nothing in this stage needs funds on any of those networks. A message signature is not a transaction: no balance is read, nothing is broadcast, and an empty account signs exactly as well as a funded one.

### A development wallet is not the check

A fake or development wallet — including this repository's own `FakeWalletAdapter`, which every automated test uses — may stand in for **error handling**: no wallet installed, a declined authorization, an unsupported network, a wallet that couldn't sign. That is what the automated tests do, and it is useful.

It never stands in for acceptance. Stage 3 is accepted only when the owner's own Seeker, with Seed Vault Wallet, connects and signs by hand ([R8](https://docs.solanamobile.com/get-started/development-setup)). An emulator, a mock adapter, or a successful APK build is recorded as NOT RUN, never as a pass.

## What still doesn't happen yet

Connecting a wallet doesn't let anything spend, and neither does signing a message: no transaction is built, and nothing reaches the network. Transfers and swaps arrive in Stages 4 and 6. Every one of them will still need your approval on the phone, every time.
