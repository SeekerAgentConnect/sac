# Wallet profiles and per-connection binding (SEE-174)

Until SEE-174 the phone held one wallet. Every direct server was told the same address, every feed
prepared for it, and changing it changed everything at once. Now the phone keeps any number of
saved **wallet profiles** — an account in one wallet app, on one Solana network — and **every
connection names the one it uses**. There is no global active wallet any more: a review, a
preparation, a restricted-feed proof and a signing all resolve the wallet from the connection they
belong to, and from nothing else.

| Connection | Profile | Network |
| --- | --- | --- |
| SKR staking server | Main account | Mainnet |
| A development MCP server | The same main account | Devnet |
| A prediction feed | Trading account | Mainnet |

Several connections may share a profile, and one profile never becomes another connection's by
accident. The owner-facing steps are in [`docs/guides/wallet-setup.md`](../guides/wallet-setup.md);
the servers' side — which networks a server declares — is
[server-manifests.md#supported-networks](server-manifests.md#supported-networks). What the automated
tests cover, and the device checks still to run, are in
[`docs/testing/see-174.md`](../testing/see-174.md).

"Network" means Solana Mainnet, Devnet or Testnet. No other blockchain is modelled.

## The profile

[`wallet/Wallet.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/wallet/Wallet.kt),
`WalletProfile`:

| Field | What it is |
| --- | --- |
| `id` | A stable local ID. Never shown, and never sent to a server |
| `address`, `network` | The account and the Solana network. Neither ever changes for a given ID |
| `label` | The owner's own name for it, or none to show the wallet's |
| `accountLabel` | The wallet app's name for the account, as it reported it |
| `route` | The wallet app it lives in, and how to reach it ([wallet-targeting.md](wallet-targeting.md)) |
| `authorizationId` | Which stored authorization signs for it |
| `connectedAt` | When it was connected, or last reconnected |
| `networkConfirmed` | False when the wallet didn't list the network's chain for the account |
| `authorized` | False once the wallet refused the authorization it uses |

It holds no secret. The screens show the wallet app's name and the account label separately, so an
account called "phantom" in Seed Vault Wallet can't read as the Phantom app.

`SelectedWallet`, the value a review captures and a signing checks, gained `profileId` and
`walletApp`. Two profiles with the same address on the same network in two different wallet apps
are two different things to approve with, and the check before signing compares the ID as well as
the address and network.

### Deduplication

The same account in the same wallet app on the same network is **one** profile: the key is
(wallet package, address, network). Reconnecting it refreshes that profile in place — same ID, new
authorization, new `connectedAt` — which is what keeps every connection that names it pointing at the
same thing. A profile saved before the phone learned which app answered (no package) is matched by
address and network, and the new authorization then names the app.

Anything else is another profile. The same address on Devnet is a second profile beside the Mainnet
one; the same address in another wallet app is a third. Because an ID's address and network never
change, nothing can move a connection to another account or network by editing the profile it names.

## Storage

[`wallet/storage/WalletStore.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/wallet/storage/WalletStore.kt)
keeps every profile, and the authorizations they use, in **one** sealed record: the file
`wallet-profiles` in `noBackupFilesDir`, format 4, encrypted with AES-256-GCM under the Keystore key,
with `seekervault/wallet-profiles/v4` as the associated data. It is one record for the reason SEE-84
made the single session one: a profile and the authorization it signs with are one fact, and an
interrupted write must leave either the old record or the new one, never a profile naming an
authorization that isn't there.

| Part | Holds |
| --- | --- |
| `profiles` | Every `WalletProfile` above |
| `authorizations` | `StoredAuthorization`: `id`, the Mobile Wallet Adapter `token`, the `network` it was asked for, and the `route` of the wallet app that issued it |
| `legacyProfileId` | The profile the single-session format became, when it did ([Migration](#migration)) |

Several profiles can share one authorization, which is what a wallet that authorizes several
accounts at once produces. An authorization no profile uses is dropped on the next write. A token is
never offered to another wallet app and never used for another network: Mobile Wallet Adapter scopes
it to the wallet that issued it and the chain it was authorized for.

Nothing in the record leaves the phone. Tokens never reach a manifest, a binding, a log or analytics;
`toString()` of every type that holds one redacts it.

## Adding, reconnecting, renaming, removing

[`wallet/WalletRepository.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/wallet/WalletRepository.kt)
owns all of it, behind the one wallet lock that has always serialized wallet interactions: however
many profiles there are, one wallet handoff runs at a time.

- **Add** (`connectProfiles(network, app)`) always asks for a **fresh** authorization on the chosen
  network, aimed at the chosen wallet app — or the one installed, or failing that whichever one
  Android resolves. No stored token is offered, so the wallet asks which account to authorize
  instead of silently handing back the one it authorized before; that is what adding a second
  account from the same wallet needs. Every account the wallet returns is saved, deduplicated as
  above. Adding binds the profile to nothing and publishes nothing.
- **Reconnect** (`reconnect(profileId)`) asks the profile's own wallet app again, offering its
  authorization while the wallet still honours it. A wallet that answers with a different account
  leaves it as it was. When the authorization is shared, reconnecting **reauthorizes that grant in
  place** rather than splitting the profile off it: the stored authorization keeps its ID and takes
  the token the wallet hands back — the same one or a rotated one — and every profile sharing it
  stays on it, authorized when the wallet still names its account and needing reconnect when it
  doesn't. The grant is reauthorized in place whenever its token was offered, or when no other
  profile still signs with it; only a profile that alone lost its account while the others still
  hold a working token gets a fresh authorization of its own.
- **Rename** changes the owner's label only. A blank name goes back to the wallet's own.
- **Remove** (`remove(profileId)`) is below, under [Removing a profile](#removing-a-profile).

Cancelling in the wallet app, or a wallet that doesn't answer, changes no profile and no connection.

The Wallet tab presents profiles as cards. One card may be expanded at a time to reveal its full
address, copy action, connection usage, wallet app, date, and Rename/Reconnect/Remove actions. **Add
wallet** opens a bottom sheet; network and wallet app are chosen there, never in a connection's
picker.

### Authorization scope

A wallet that refuses an authorization (`AuthorizationExpired`) marks **every profile using that
authorization** as needing reconnect, and nothing else: no other profile, and no connection's
binding. A wallet that no longer authorizes one account marks only that profile. A token the wallet
rotates while signing replaces the shared authorization's token, so every profile sharing it keeps
working, and so does one rotated by reconnecting any of them. Removing a profile asks the wallet to
deauthorize only when no other profile still uses the authorization, and never a token another
stored authorization still holds.

## Binding a connection

[`connections/Connection.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/connections/Connection.kt)
has `walletProfileId`: the profile this connection signs with, or null when the owner hasn't chosen
one. It is the owner's choice and nobody else's; a server's manifest can't set it. The connection
store (format 7) persists it with the manifest's supported networks.

`bind(connectionId, profileId)` sets it:

- A profile on a network the server **declared** is bound. One on any other network is refused
  (`BindOutcome.Incompatible`) and nothing changes.
- A server that declared **no** networks may still be bound — a restricted feed proves its reader
  with the profile — but nothing is signed for it until the server declares one.
- A **direct** server is told at once, and only that server ([Publication](#publication)).
- A **feed** is told nothing. Its binding is a local execution setting: no saved wallet is ever
  published to a publisher or the gateway.

The modal picker
([`connections/ConnectionWallet.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/connections/ConnectionWallet.kt))
shows every saved profile. When the server declares networks, incompatible profiles remain visible
but disabled and explain that their network must be changed on the Wallet tab. **Use this wallet**
stays disabled until the owner selects a compatible profile. **Add a &lt;network&gt; wallet** opens the
shared add-wallet sheet with a single declared network preselected. When the server declares no
network, an orange notice explains the limitation, every profile is selectable for an access
proof, and the action reads **Add a wallet**; signing remains gated by readiness.

The picker contains no wallet-app choice. It opens by itself right after a connection is added —
the pairing code is already spent and the manifest already read — and from the connection detail's
**Wallet** row, which shows the bound profile's full address, wallet app, and network as separate
facts.

### Readiness

Whether a connection may sign is derived, never stored: `readiness(connectionId)` returns a
`WalletReadiness`, from the profile the connection names, the networks its server declared and, for a
direct server, whether the server confirmed the binding.

| State | Meaning | What the owner does |
| --- | --- | --- |
| `Ready` | Its own profile, on a declared network, authorized, and (direct) confirmed by the server | — |
| `NoProfile` | No wallet chosen | Choose one |
| `ProfileMissing` | The profile it named was removed; nothing was chosen in its place | Choose another |
| `NeedsReconnect` | The wallet refused the profile's authorization | Reconnect it on the Wallets screen |
| `NetworksUnknown` | The server declares no Solana network (older than SEE-174, never read, or ack-only) | Update the server |
| `NetworkUnsupported` | The server no longer lists the profile's network | Choose a profile on a supported network |
| `PublicationPending` | A direct server hasn't confirmed this binding yet; it is retried | Wait, or retry once the server is back |
| `NoConnection` | No such connection, or it is retired | — |

Only `Ready` reaches a wallet. `walletFor(connectionId)` returns the profile's `SelectedWallet` when
the connection is ready and null otherwise, and every review reads null as "no wallet" and refuses
to approve. There is **no fallback**: a connection whose own profile isn't ready has no wallet,
whatever else is saved.

### Signing

`sign` and `signAndSend` take the reviewed `SelectedWallet` — with its `profileId` — and the
connection ID. Inside the wallet lock, after any wait for another wallet interaction, they check
that the profile still exists with the same address and network, that the connection still names
it, and that the connection is still `Ready`. A profile that was removed, rebound or reconnected in
the meantime is reported as changed without asking the wallet anything. Two open reviews on two
connections each carry their own profile; whichever gets the lock first is checked then, and the
other is checked again when its turn comes.

The per-operation checks are unchanged and still apply: the required signer, the transaction's
actual contents, the provider's network and the owner's rules. A server's declared networks are
never proof that a transaction is safe.

## Publication

A direct server holds one `WalletBinding` for its connection, as it always has
([protocol.md#the-wallet-binding](../protocol.md#the-wallet-binding)). What changed is who is told.
`Publication` is tracked **per connection and per binding content** (`address|network`):

- Binding, rebinding or removing a profile publishes to the affected direct servers only.
- `publish()` at app start sends each direct server the binding it should already hold. The same
  binding republished cancels nothing, and a server whose binding was confirmed isn't sent it again
  just because another connection changed.
- A server that can't be reached keeps the connection `PublicationPending`, and the binding is
  retried; nothing is signed against a binding the server may not hold.
- Adding, renaming or reconnecting a profile publishes nothing.

## What rebinding does

**A direct connection.** The server alone is told. It replaces its binding and cancels **its own**
PENDING requests that don't fit the new one — the same rule `PublishWallet` has always had — and the
phone reports how many it cancelled. The picker says so before the owner chooses. Requests already
approved or submitted keep the context they were approved in.

**A feed.** Nothing is sent anywhere. `OperationViewModel` resolves the feed's own profile; a
preparation carries the profile it was built for, and a rebinding drops it together with the
approval acknowledgement and moves the review's generation on (`OperationProblem.WalletChanged`), so
a read still in flight for the old wallet can't land on the new review. The terms are kept and
prepared again for the new profile. Provider resolution and rule evaluation use the feed's own
profile's network. The shared signal and other subscribers are untouched.

**A restricted feed.** Access belongs to the address that proved it
([restricted-feeds.md#one-wallet-per-feed](restricted-feeds.md#one-wallet-per-feed)). Rebinding to
the same address on another network keeps the grant; rebinding to another address drops the old
session and asks for access again with the new wallet.

## Removing a profile

The Wallets screen shows which connections use a profile before it is removed. On confirmation,
`remove(profileId)`:

1. removes the profile, and its authorization only when no other profile uses it (and only then
   asks the wallet to deauthorize);
2. clears the binding of every connection that named it — **nothing is chosen in its place**, and
   each of them waits in `NoProfile` for the owner;
3. tells the affected direct servers there is no wallet, which cancels their own PENDING wallet
   requests, and no other server.

Connections, their credentials, history and ongoing confirmation tracking are kept. A restricted
feed whose profile is gone stops using the access it proved. Removing a connection never removes a
profile.

## History and follow-ups

History records and confirmation tracking keep the address and network the operation was actually
signed with; they never read the connection's current binding. Renaming or removing a profile, or
rebinding a connection, rewrites no historical fact. Confirmation polling and explorer links use
each request's own network.

A prediction position's sale (SEE-172) uses the position's **owner on its original network**:
`ownerProfile(address, network)` returns the authorized profile that is that account, and nothing
that merely stands in for it. When there is none, the sale is blocked until the owner reconnects
that wallet; the feed's current wallet is never substituted.

## Migration

The single `wallet-session` record (formats 2 and 3, and the two-file format before them) becomes one
profile and one authorization on the first read, with IDs **derived from the session** — a digest of
its address, network and wallet package — so a migration interrupted and repeated produces the same
IDs. The session is deleted only after the new record is committed; until then it is what the next
read migrates again. A session that can't be decrypted is no profile, exactly as it was no wallet.

Connections stored before connection-store format 7 carry the marker `legacy:single-wallet`
(`Connection.LEGACY_WALLET_PROFILE`). On every start, `ConnectionRepository.adoptLegacyWallet` binds
each of them to the migrated profile, or to none when there was no usable wallet; a connection that
already names a profile is left alone, so running it again changes nothing. The binding it produces
is the one each direct server already held, so the first publication after the upgrade cancels no
request. Restricted-feed grants stay with the connection and the address that proved them.

Cached manifests from before format 7 have no supported networks, which reads as none declared: the
connection is readable and shows the server-update state until the server publishes a new revision
with its networks.

## Where the code is

| File | What it holds |
| --- | --- |
| `wallet/Wallet.kt` | `WalletProfile`, `SelectedWallet`, `WalletNetwork` |
| `wallet/storage/WalletStore.kt` | The sealed `wallet-profiles` record and the migration |
| `wallet/WalletRepository.kt` | Profiles, binding, readiness, publication, signing |
| `wallet/WalletScreen.kt`, `wallet/WalletViewModel.kt` | The collapsible Wallet tab and add-wallet sheet mapping |
| `connections/ConnectionWallets.kt` | What the wallet repository may ask of the connections |
| `connections/ConnectionWallet.kt` | The detail sheet's Wallet row and modal picker |
| `connections/ConnectionRepository.kt` | `setWalletProfile`, `adoptLegacyWallet`, `clearWalletProfile`, `publishWalletCancelling` |
| `access/FeedAccessManager.kt` | Restricted-feed access by each feed's own wallet |
| `operations/OperationViewModel.kt`, `inbox/InboxViewModel.kt` | Feed and direct reviews resolving the connection's wallet |
| `positions/PositionsViewModel.kt` | Sales by the position owner's profile |

## What this is not

- Not a global wallet switch, and not a picker on every approval. The wallet is chosen per
  connection, when it is set up or deliberately changed.
- Not several bindings in one direct connection. A direct server still holds one.
- Not key import or custody. The wallet app still holds every key.
- Not automatic. Nothing ever picks a profile for a connection, moves one to another network, or
  signs with a profile the connection doesn't name.
