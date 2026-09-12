# Sign a message

An agent can ask your wallet to sign a message: a piece of text, signed with the wallet you connected, which anyone can then check against your address. This guide is the whole round trip, from the agent's request to the signature it reads back.

Before this, pair the phone ([`pairing.md`](pairing.md)) and connect your wallet ([`wallet-setup.md`](wallet-setup.md)).

## What a signature is, and what it isn't

- **It proves your wallet signed those exact bytes.** That's what logging in to a site, or approving a statement, usually means.
- **It moves no money and sends nothing to the network.** No transaction exists, and nothing appears on chain. Transfers are a different action, and a later task.
- **Nothing is signed without you.** The request waits on your phone until you tap **Approve and sign**, and only then does the app open your wallet. Until you do, no wallet is opened, no key is touched, and the agent is told the request is `PENDING`.
- **The sidecar can't sign.** It holds no key. It stores the request, and afterwards checks that the signature really is your wallet's, over the message it stored.
- **An agent can only ask for text.** `vault_sign_message` takes the message as text, and its UTF-8 bytes are what your wallet signs. There is no way to queue bytes that aren't text, so you can always read what you are approving.

## The agent asks

```console
$ pnpm agent capabilities
{"approval":"manual","signing":"wallet","operations":["sign_message"],"wallet_connected":true,…}

$ pnpm agent sign "Sign in to example.com
Nonce: 4711"
idempotency key: sign-1f0a…
The owner reviews it on their Seeker; nothing is signed until they approve.
{"request_id":"f7e6d5c4-…","action":"sign_message","status":"PENDING","wallet":"G4bAtd9o…",…}
```

Hermes and other agents call the MCP tool `vault_sign_message` for the same thing. The tool answers at once with a request ID; it never waits for you.

## You review it

The request appears in **Pending requests** the next time you open the app, or when you tap **Refresh**. Open it, and the screen shows:

| | |
| --- | --- |
| **From** | The connection that asked, by name and host |
| **Message** | The complete message, exactly as the agent sent it |
| **What will be signed** | Text or bytes, and how many bytes the wallet signs |
| **Signs with** | The wallet and network you connected |
| **The agent's note** | Its own words, unverified, shown apart from the message |

**Invisible characters are marked.** A line break shows as `␊`, a tab as `␉`, a carriage return as `␍`; anything else that would take no space at all, or that looks like an ordinary space but isn't one — a zero-width space, a right-to-left override, a no-break space, a variation selector — shows as its code point, such as `<U+200B>`. The app asks Unicode what is invisible rather than checking a list of usual suspects, and a character it doesn't recognize is marked rather than shown. If the message has any, the screen says so under it. Nothing can hide in what you approve, and the app changes nothing about the message itself: your wallet signs the original bytes.

Then either:

- **Approve and sign** — the app opens your wallet, which asks you again. The wallet signs, and the signature goes to the sidecar.
- **Reject** — nothing reaches the wallet, and the agent is told `REJECTED`.

## What can happen

| What the app says | What it means |
| --- | --- |
| You approved this. Waiting for the wallet… | The approval is in, and your wallet is being asked |
| Your wallet signed this. The agent can read the signature. | Done |
| Your wallet signed this. The signature is saved on this phone, and is sent when the server can be reached. | The server was unreachable; it's sent again on the next refresh, and nothing is lost |
| You declined in the wallet. Nothing was signed. | You said no in the wallet itself |
| Nothing was signed. … | The wallet couldn't sign, and says why. Nothing happened. |
| This phone never learned what the wallet did. … | The app closed, or the wallet never answered, while the message was with it. No signature reached this phone, so none exists anywhere and nothing went to the network. The agent is told the request failed, and the wallet is not asked again. |
| Connect a wallet before approving this. | No wallet is connected. Connect one on the **Wallet** screen. |
| This request names another wallet than the one you connected. | The agent asked for a wallet you don't have here; you can only reject it |
| Your wallet changed while you were reviewing this. | The selection isn't the one on screen any more, so the app asked the wallet nothing. Look at the request again. |

**Approving binds to what you saw.** The app sends the SHA-256 of the exact message with your approval, and the sidecar refuses an approval that doesn't match the request it stored. Changing the message, the wallet, or the network needs a new review: a changed message is a different request, and changing your wallet cancels the requests it no longer fits.

**If the app closes between your approval and the wallet's answer,** nothing was signed, and the request is reported as failed the next time you open it. A signature that never reached this phone doesn't exist anywhere, and nothing was sent to the network, so there is nothing in doubt. The app never re-opens the wallet by itself: if you still want the signature, the agent asks again, and you review the new request.

**Rotating the phone, or leaving the app and coming back, changes nothing.** The request stays where it was, your approval isn't sent twice, and the wallet isn't asked twice. If your wallet signed but the server couldn't be reached, the signature is kept here and sent on the next refresh — see [`../testing/wallet-lifecycle.md`](../testing/wallet-lifecycle.md).

## The agent reads the result

```console
$ pnpm agent get f7e6d5c4-…
{"request_id":"f7e6d5c4-…","action":"sign_message","status":"COMPLETED","terminal":true,
 "wallet":"G4bAtd9o…","signature":"52o3UtBit8…","signed_message_base64":"U2lnbiBpbiB0by…",
 "signature_verified":true}
```

- **`signed_message_base64`** is exactly the bytes that were signed, so an agent verifies the signature against those rather than re-encoding the message itself. `pnpm agent get` checks it with its own Ed25519 verifier and prints `signature_verified`.
- **`signature`** is the 64-byte Ed25519 signature in base58, and **`wallet`** the address that made it.
- **`REJECTED`** means you or the wallet declined, **`EXPIRED`** that the deadline passed, and **`FAILED`** that the wallet couldn't sign. None of them signed anything.

Any Ed25519 verifier checks it. In Node:

```js
import { createPublicKey, verify } from "node:crypto";
// wallet is base58-decoded to 32 bytes, wrapped in the SPKI header for Ed25519.
const key = createPublicKey({
  key: Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), publicKeyBytes]),
  format: "der",
  type: "spki",
});
verify(null, Buffer.from(signedMessageBase64, "base64"), key, signatureBytes); // true
```

## What never leaves the phone

Your seed phrase and private keys stay in the wallet, which this app never asks for them. The wallet's authorization for this app stays on the phone, encrypted and never backed up. What goes to the sidecar is the signature, which is public, and the address that made it — the same address `vault_get_address` already gives out. See [`../security.md`](../security.md).
