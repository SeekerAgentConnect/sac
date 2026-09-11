# Connect your wallet

This guide connects the wallet you already have to the app, so agents can read your address and, from the next tasks on, ask you to sign with it. Nothing here creates a wallet, and the app never sees your seed phrase or your keys.

Before this, pair the phone with at least one sidecar: [`pairing.md`](pairing.md).

## What the app learns, and what it doesn't

| | Where it goes |
| --- | --- |
| Your wallet's **address** (a public key) and the **network** you picked | Kept on the phone, and published to every sidecar you've paired with, so agents can read it |
| The wallet's **authorization** for this app | Kept on the phone only, encrypted, never backed up, never sent to a sidecar or written to a log |
| Your **seed phrase** or **private keys** | Never asked for, never seen, never stored |

The sidecar holds no keys and makes no wallet of its own. Until you connect one, an agent that asks for your address is told `WALLET_NOT_CONNECTED`.

## Connect

1. Make sure a wallet that speaks Mobile Wallet Adapter is set up on the phone. On the Solana Seeker that's **Seed Vault Wallet**.
2. Open the app. The first row on **Connections** is **Wallet**; tap it.
3. Pick the **network**: Mainnet, Devnet, or Testnet. You can't change it later without connecting again.
4. Tap **Connect wallet**. The wallet opens and asks you to choose an account and approve.
5. Back in the app you see the address, the network, the account's name in the wallet, and when you connected it. Underneath, the app says how many connections it told.

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

## Check it from the agent's side

With the sidecar running and `MCP_URL` and `MCP_TOKEN` set (see [`macbook-seeker-quickstart.md`](macbook-seeker-quickstart.md)):

```console
$ pnpm agent address
{"wallet":"G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW","network":"devnet","bound_at":"2026-09-12T09:30:00.000Z"}
```

With no wallet connected it exits 9 instead:

```text
WALLET_NOT_CONNECTED: the owner has no wallet connected on their phone; ask them to connect one in the app
```

Hermes and other agents read the same thing through the MCP tool `vault_get_address`.

## Change the wallet or the network

Connect again from the Wallet screen. Choosing another account, or another network, replaces the binding:

- Every sidecar is told the new address and network.
- Any **pending request queued for the old wallet is cancelled** by the sidecar, and disappears from Pending requests. Requests that don't involve a wallet, such as the demo acknowledgements, are left alone.

## Disconnect

Tap **Disconnect wallet**. The app tells the wallet that it no longer needs its authorization, forgets the address and the authorization, and tells every sidecar that no wallet is connected. Agents are then told `WALLET_NOT_CONNECTED` again, and pending wallet requests are cancelled.

Disconnecting the wallet doesn't touch your connections, your paired sidecars, or your answers.

## What still doesn't happen yet

Connecting a wallet doesn't let anything spend. The app can't sign or send yet: message signing, transfers, and swaps arrive in the later tasks of Stages 3, 4, and 6. Every one of them will still need your approval on the phone, every time.
