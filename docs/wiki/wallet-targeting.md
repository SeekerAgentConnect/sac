# Wallet targeting

How this phone reaches the wallet app the owner connected, so that an approval opens that wallet and
no other — the first time, every time, and after the app has been restarted (SEE-159).

The owner-facing steps are in
[docs/guides/wallet-setup.md](../guides/wallet-setup.md#2-connect). What the automated checks cover,
and what only a device can settle, is in
[docs/testing/wallet-lifecycle.md](../testing/wallet-lifecycle.md#wallet-targeting).

## The problem it solves

Approving a prediction opened Android's "Open with…" list instead of the wallet the owner had
connected. With Jupiter, Backpack and Seeker Wallet all installed, every approval asked them again
which app to open, and the only way to stop being asked was to make one of them an Android-wide
default — which is a decision about the whole phone, for a choice that belongs to this app.

The cause is where Mobile Wallet Adapter keeps its routing. A wallet reports its own association URI
while it authorizes, in `AuthorizationResult.walletUriBase`, and the library's `MobileWalletAdapter`
stores it in a **private** field with a getter and setter for neither. So it could learn a wallet
inside one process and never be told one:

- The first association in any process went out as a bare `solana-wallet:` intent, which Android
  resolves like any other — with the chooser when several apps claim it.
- That is every first approval after the app is reopened, and every approval at all whenever the
  session had been dropped.
- Because the *connection* went out the same way, the app the owner authorized was whichever one the
  chooser resolved. That is the whole of the "phantom" label on the home card: it was the account
  label of the app that really answered, which was not necessarily the one the owner thought they had
  connected. It was never stale metadata.

## What is stored

`WalletRouting`, in the same sealed record as the account and its authorization
(`wallet/storage/WalletStore.kt`, format 3). All three are one fact: an account without the app that
holds it is how an approval reaches the wrong wallet, so they are written together and read together.

| Field | Where it comes from |
| --- | --- |
| `uriBase` | `AuthorizationResult.walletUriBase`, kept only when Mobile Wallet Adapter would take it back as an association prefix — an absolute, hierarchical `https` URI |
| `packageName` | `PackageManager`, from the activities that answer the local association intent |
| `appLabel` | `PackageManager`'s own label for that app, for display only |

Nothing here is invented. The app never writes down a wallet's package name it was not told by the
system, and no sidecar can influence any of it.

A record written by the build before this one is still a whole session and still loads; it simply has
no route. The next association resolves the way it used to and learns one, so upgrading keeps the
owner's wallet rather than asking for it again.

## Where an association is aimed

`targetOf(route, installed)` in `wallet/WalletRouting.kt` decides it, and decides nothing else: no
wallet, no `PackageManager` and no intent are involved, which is why every branch of it is a unit
test.

| Route | This phone has | Aim |
| --- | --- | --- |
| none | anything | `Wide` — Android resolves it, and may ask |
| names a package this phone doesn't list | at least one wallet app | `Missing` — nothing is opened |
| `https` URI | the app, or nothing listed | `Endpoint(uri, package)` — the wallet's own routing, narrowed to that app when it is known |
| package only | that app | `App(package)` — the ordinary intent, aimed at one app |

An empty list of installed apps is "this phone didn't say", not "nothing is installed", so it does not
condemn a route on its own. Launching is the authority either way: an intent aimed at one package
opens that app or fails, and can never open another.

`Missing` is the case worth stating plainly. **A signing whose wallet app has gone opens nothing.**
Falling back to a wide association there would put an approval the owner gave for one wallet in front
of whichever other wallet Android found, which is exactly the silent switch that must never happen.
The answer is `NoWallet` — the owner is told no wallet app answered, the request is still theirs to
review, and they connect a wallet again on the Wallet screen. **Connecting** is the opposite case and
does fall back to asking Android: connecting *is* the owner choosing a wallet.

## Building the association

Because the library's facade cannot be given a route, the app builds the association itself, in
`MwaSession` inside `wallet/MwaWalletAdapter.kt`. Everything the handshake needs is the library's and
is used as it comes:

- `LocalAssociationScenario` for the local transport, with the library's own timeouts.
- `LocalAssociationIntentCreator.createAssociationIntent(uriPrefix, port, session)` for the intent —
  the same call the library makes, with the prefix this app restored rather than one it had to learn
  in the same process. `setPackage` is added when the aim names an app.
- `LocalAdapterOperations` for authorize, reauthorize, deauthorize, `signMessagesDetached` and
  `signAndSendTransactions`, and the protocol version decides which authorization request is made,
  exactly as the library decides it.
- `WalletIntentSender` (`wallet/WalletIntentSender.kt`) to launch it, because the library's own
  `ActivityResultSender` can only send the intent the library built: its launch method is `internal`.

Nothing is reached by reflection, and no private field is touched.

An owner who comes back out of the wallet without deciding leaves the handshake with nobody to answer
it; the library's handshake timeout ends it, they are told the wallet didn't answer, and the request is
still theirs.

## Choosing the wallet app

- **One wallet app installed.** It is connected outright. There is one answer and the system gave it,
  so nobody is asked and no chooser appears.
- **Several installed.** The Wallet screen lists them under **Wallet app** — labels and packages from
  `PackageManager`, nothing else — and the owner picks one before connecting. That is not Android's
  chooser and it sets no Android-wide default; it is a one-time choice inside this app, and it is
  what lets every association afterwards be aimed.
- **Picking another one** replaces the whole route, association URI included. Carrying that over
  would send the next approval back to the wallet being left.
- **Disconnecting** clears the record, so nothing aimed at that wallet is left behind, and the wallet
  app is told over its own route while it still exists.

## What the screens say

The wallet is the app the session belongs to; the account label is the account's, inside it. The
Wallet screen's card and the home card are both named after the app now, with the account label beside
the address, so a label like "phantom" can no longer read as the wallet that would open. When this
phone was never told which app answered — a record from an older build, or a connection Android
resolved — the account label stands in, as it did before.

## Boundaries this does not cross

- No key, seed phrase or signature of this app's own. The wallet app still owns everything.
- No new permission, and no manifest change: the `<queries>` entry for the `solana-wallet` scheme
  comes from the Mobile Wallet Adapter client's own manifest, so package visibility already allows
  the question this asks.
- No package name, wallet name or URI that did not come from the wallet's own authorization or from
  `PackageManager`.
- Nothing about routing reaches or comes from a sidecar. `WalletBinding` still carries the address and
  the network, and nothing else.
