# Connect your wallets

This guide connects the wallets you already have to the app, so agents and feeds can read the
address a connection uses and ask you to sign with it. It goes the whole way round: open Seed Vault
Wallet, save a wallet profile, choose it for a connection, check the address and network, sign the
example message, and remove the profile again. Nothing here creates a wallet, and the app never sees
your seed phrase or your keys.

Before this, pair the phone with at least one sidecar ([`pairing.md`](pairing.md)) or add a feed.

## You will never be asked for your seed phrase

**No step in this repository asks you to type, paste, photograph, or export a seed phrase, a recovery phrase, or a private key.** Not this guide, not the app, not the sidecar, not an agent. Your keys stay inside the wallet app on the phone, which never hands them out, and nothing here has anywhere to put them.

If a screen, a page, or an agent ever asks you for one, it isn't part of this project. Stop, and don't type it.

| | Where it goes |
| --- | --- |
| Each wallet profile's **address** (a public key) and **network** | Kept on the phone. A direct sidecar is told only the address and network of the profile **its own connection** uses, so its agents can read it. A feed is told nothing. |
| Each wallet's **authorization** for this app | Kept on the phone only, encrypted, never backed up, never sent to a sidecar, a feed or a log. Every profile and the authorization it signs with are kept in one sealed record, so this phone can never hold one wallet's authorization beside another wallet's address. |
| Your **seed phrase** or **private keys** | Never asked for, never seen, never stored |

The sidecar holds no keys and makes no wallet of its own. Until its connection has a wallet, an agent
that asks for the address is told `WALLET_NOT_CONNECTED`.

## Wallet profiles

The app keeps any number of saved **wallet profiles**. A profile is one account, in one wallet app,
on one Solana network — Mainnet, Devnet or Testnet:

- **Different accounts** are different profiles, whether they are in one wallet app or several.
- **The same address on another network** is another profile. "Main account on Mainnet" and "Main
  account on Devnet" sit side by side, and each connection uses the one it was given.
- **The same account, in the same wallet app, on the same network** is one profile. Connecting it
  again refreshes that profile rather than adding a duplicate, and every connection that uses it
  keeps using it.

The **Wallet** tab lists them as cards, each with its network, account name and connection usage.
Tap a card to expand it; opening one closes the previous card and reveals the full address, wallet
app, date, and profile actions. There is no "active" wallet: a profile does nothing until a
connection is given it
([One wallet per connection](#one-wallet-per-connection)). How the phone stores and checks all of
this is in [`../wiki/wallet-profiles.md`](../wiki/wallet-profiles.md).

## 1. Open Seed Vault Wallet

The app drives a wallet you already have; it doesn't set one up. On the Solana Seeker that wallet is **Seed Vault Wallet**.

1. Open Seed Vault Wallet on its own, before the app, and finish whatever it asks for on first run. It is the wallet, not this app, that decides how you unlock it — a PIN, a fingerprint, a face. This app adds no lock of its own and asks for none.
2. Check that it shows an account and an address. If it doesn't, there is nothing for the app to connect to yet.
3. Note its **version**, from the wallet's own settings or from Android's **Settings → Apps → Seed Vault Wallet**, and write it into [the wallet under test](#the-wallet-under-test) below. Which networks a wallet serves, and what it shows while signing, are properties of that version.

Any other wallet that speaks Mobile Wallet Adapter works the same way. The acceptance check for this stage is Seed Vault Wallet on the Seeker, though: see [the rule about development wallets](#a-development-wallet-is-not-the-check).

## 2. Connect

This saves a wallet profile. It doesn't give it to any connection yet.

1. Open the app. The wallet row at the top of **Connections** opens **Wallets**; tap it. With one
   profile saved the row shows its wallet app and address; with several it counts them.
2. Tap **Add wallet**. In the bottom sheet, pick the **network**: Mainnet, Devnet, or Testnet. A profile keeps its
   network for good; to use the same account on another network, add it again for that network.
3. If the phone has more than one wallet app, pick **which one** under **Wallet app**. The list is
   what the phone reports as installed. With only one wallet app there is nothing to pick, and the
   app uses it.
4. Tap **Continue in &lt;wallet app&gt;**. The wallet you picked opens and asks which account to authorize — every time,
   even if it authorized one before, so you can add a second account from the same wallet. Approve.
5. Back in the app, each account the wallet authorized is listed as a profile, with its network,
   wallet app and address.

From here on, approvals for a connection open **that connection's profile's** wallet app straight
away — no Android "Open with…" list, and no need to make any wallet your phone's default. It stays
that way after you restart the app.

On each profile:

- **Rename** gives it your own name. A blank name goes back to the wallet's name for the account.
- **Reconnect** asks the profile's wallet app again. Use it when the app says the wallet no longer
  accepts the profile's authorization; the connections that use it keep it.
- **Remove** is [step 5](#5-remove-a-wallet).

### If something goes wrong

| What the app says | What it means | What to do |
| --- | --- | --- |
| No wallet app answered | Nothing on the phone speaks Mobile Wallet Adapter — or, when you were approving something, the connection's wallet app has been uninstalled | Install or set up a wallet, such as Seed Vault Wallet. If you uninstalled the one a connection uses, add a profile in the wallet you want and choose it for that connection. The app will not quietly move your approval to another wallet. |
| The wallet didn't give an account | You declined, or left the wallet without choosing | Nothing changed. Tap **Add wallet** again. |
| The wallet no longer accepts this profile's authorization | The wallet revoked what the phone had stored for that profile | Tap **Reconnect** on the profile. Other profiles, and every connection's choice of wallet, are unchanged. |
| The wallet didn't authorize this profile's account | You picked another account while reconnecting | Nothing changed. Reconnect and pick the profile's own account, or add the other one as a new profile. |
| The wallet doesn't serve this network | The wallet has no such cluster | Pick a network it offers. Which ones your wallet offers is a property of the wallet, not of this app. |
| The wallet didn't list this network for the account | The wallet returned the account but not that chain | You can go on, but the wallet may refuse to sign on it. Adding it on the network the wallet does list is safer. |
| N servers: … | A direct sidecar couldn't be told its connection's wallet | Tap **Tell them again** once it's back. Opening the app again also retries. Nothing is signed for that connection until its sidecar confirms. |

## One wallet per connection

Every connection — a paired sidecar or a feed — signs with **one** profile, chosen for it, and with
nothing else. Two connections can use two different wallets at the same time, and several can share
one.

**Choosing it.** Right after you add a connection, the app opens its modal **Wallet** picker. Every
saved profile remains visible. Profiles on a network the server does not support are disabled and
explain why; choose a compatible one to enable **Use this wallet**. **Add a Mainnet wallet** (or the
server's declared network) opens the same add-wallet sheet with that network already selected. A
server that declared no network shows an orange explanation, allows any profile for its restricted
feed access proof, and still cannot sign until it declares a network. Cancelling leaves every
profile and connection as it was; you can choose later.

**Seeing it.** The connection's page has a **Wallet** row showing the profile it uses, with the full
address, the wallet app and the network. Reviews and the wallet handoff name the same wallet app.

**Changing it** is the deliberate **Wallet** row on the connection's page, and it uses the same
picker. Wallet-app selection belongs only to the add-wallet sheet. See
[Change the wallet or the network](#change-the-wallet-or-the-network).

What the connection's Wallet row can say:

| The app says | What it means | What to do |
| --- | --- | --- |
| No wallet chosen | This connection has no profile | Choose one. Nothing from it can be signed until you do. |
| Its wallet was removed | The profile it used is gone, and nothing was chosen in its place | Choose another |
| The wallet no longer accepts this profile's authorization | The wallet revoked the profile's authorization | **Reconnect** it on Wallets |
| This server hasn't declared which Solana networks it supports | The server is older than wallet profiles, or declares no network ([supported networks](../wiki/server-manifests.md#supported-networks)) | Update the server. The connection and its history stay readable, but nothing from it is signed until it declares its networks. |
| This server no longer supports *network* | The server dropped the profile's network | Choose a wallet on a network it lists. The connection is never moved for you. |
| Telling the server about this wallet | A direct sidecar hasn't confirmed the choice yet | Wait, or **Tell them again**. Nothing is signed until it confirms. |

A profile on a network the server doesn't list can't be chosen for it at all. Even a server that
lists several networks may not support every action on each of them: the app still checks the
specific action and provider when it prepares a review.

## 3. Check the address and the network

Two places have to agree: the wallet itself, and what the sidecar's agents read for **its**
connection.

1. On the connection's page, compare the **Wallet** row's address with the account in Seed Vault Wallet, character for character.
2. With the sidecar running and `MCP_URL` and `MCP_TOKEN` set (see [`macbook-seeker-quickstart.md`](macbook-seeker-quickstart.md)):

   ```console
   $ pnpm agent address
   {"wallet":"G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW","network":"devnet","bound_at":"2026-09-12T09:30:00.000Z"}
   ```

   With no wallet chosen for this connection it exits 9 instead:

   ```text
   WALLET_NOT_CONNECTED: the owner has no wallet connected on their phone; ask them to connect one in the app
   ```

Hermes and other agents read the same thing through the MCP tool `vault_get_address`. Each sidecar
answers with its own connection's profile: two sidecars bound to two profiles answer two different
addresses.

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

2. On the phone, open the app and then **Pending requests**, or tap **Refresh**. Open the request. Check the complete message, the byte count, and the wallet named under **Signs with** — the profile this sidecar's connection uses. A line break shows as `␊`, and any other invisible character as its code point.
3. Tap **Approve and sign**. The profile's wallet app opens and asks you to sign. Look at what it shows you, and approve there too.
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

## 5. Remove a wallet

On **Wallets**, tap **Remove** on a profile. Before anything happens, the app lists the connections
that use it. On confirmation:

- the profile is forgotten, and the wallet is told the app no longer needs its authorization — unless
  another saved profile still uses the same authorization, in which case it is kept for that one;
- every connection that used it is left **without** a wallet until you choose one; nothing is chosen
  in its place;
- each of those that is a direct sidecar is told there is no wallet, so its agents are told
  `WALLET_NOT_CONNECTED` and its pending wallet requests are cancelled. No other sidecar is told
  anything.

Removing a profile doesn't touch your connections, your paired sidecars, your answers or your
history, and removing a connection never removes a profile.

## Change the wallet or the network

A connection's wallet is changed on **its own** page, with **Wallet**, and only there. Choosing
another profile — another account, another wallet app, or the same address on another network —
changes that connection alone:

- **A direct sidecar** is told the new address and network, and no other sidecar is. It **cancels
  its own pending requests made for the previous wallet**, and the app says how many; requests that
  don't involve a wallet, such as the demo acknowledgements, are left alone. If the sidecar can't be
  reached, the choice is saved, it is retried, and nothing is signed for that connection until the
  sidecar confirms.
- **A feed** is told nothing. Anything already prepared for the previous wallet — a quote, a
  transaction, a review waiting for approval — is dropped, and signals are prepared again for the
  new one. Nothing is signed with the old wallet.
- **A restricted feed** keeps its access when the new profile has the same address, because access
  belongs to the address. A different address asks the publisher for access again
  ([restricted-feed-demo.md](restricted-feed-demo.md)).

Approvals go to the new profile's wallet app. Adding, renaming or reconnecting a profile never
changes a connection's wallet, tells no sidecar anything, and cancels nothing.

## The wallet under test

The wallet is not part of this repository, so what it does is recorded rather than assumed. This is the owner's own device, from the checks in [`../testing/stage-3.md`](../testing/stage-3.md#the-owners-checks-on-the-seeker) and [`../testing/stage-4.md`](../testing/stage-4.md#the-owners-checks-one-real-transfer), run on 2026-09-12. Fill in your own when you run them.

| | Recorded |
| --- | --- |
| Wallet app | Seed Vault Wallet |
| Version tested | **Not captured.** The checks were run on 2026-09-12; the wallet's version string wasn't written down. |
| Device and Android version | Solana Mobile Seeker, Android 16 (API 36), as read over adb in the [Stage 1 record](../testing/stage-1.md#acceptance-report-saw-008) |
| Network path the wallet accepted (Mainnet / Devnet / Testnet) | **Devnet**, 2026-09-12, at step 9 of the owner's checks. Mainnet and testnet weren't tried. |
| What the wallet shows while signing a message | **Not captured.** The owner signed by hand on 2026-09-12; step 19 is where the wording goes when someone writes it down. |
| Network the wallet accepted for a **transfer** | **Devnet**, 2026-09-12, at check 41. The transfer that followed was finalized on devnet. |
| What the wallet shows while signing a **transaction** | **Not captured.** The owner approved in the wallet on 2026-09-12 and the app came back with the transaction's ID; check 48 is where the wording goes. |

Nothing in this stage needs funds on any of those networks. A message signature is not a transaction: no balance is read, nothing is broadcast, and an empty account signs exactly as well as a funded one.

Stage 4 is where that changes. Sending a transfer needs funds on whichever network the wallet actually serves, and on this device that is **devnet**, where faucet funds cost nothing. The walkthrough is [`transfers.md`](transfers.md#your-first-transfer-step-by-step), and the rule it keeps is that a mainnet check is the owner's own deliberate choice, with a deliberately small amount: nothing in this repository points at a cluster by itself, and nothing here has pointed at mainnet.

### A development wallet is not the check

A fake or development wallet — including this repository's own `FakeWalletAdapter`, which every automated test uses — may stand in for **error handling**: no wallet installed, a declined authorization, an unsupported network, a wallet that couldn't sign. That is what the automated tests do, and it is useful.

It never stands in for acceptance. Stage 3 is accepted only when the owner's own Seeker, with Seed Vault Wallet, connects and signs by hand ([R8](https://docs.solanamobile.com/get-started/development-setup)). An emulator, a mock adapter, or a successful APK build is recorded as NOT RUN, never as a pass.

## What still doesn't happen yet

Connecting a wallet doesn't let anything spend, and neither does signing a message: no transaction is built, and nothing reaches the network. Transfers and swaps arrive in Stages 4 and 6. Every one of them will still need your approval on the phone, every time.
