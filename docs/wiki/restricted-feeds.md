# Restricted feeds (SEE-156)

Every feed before this one was a broadcast. A reference could be printed in a README, holding it was
the whole of the grant, and the gateway's own design note said so: "a feed is a broadcast", and the
most the gateway knew was which channel someone had asked about
([feed-gateway.md](feed-gateway.md#the-two-apis)).

A **restricted** feed is the other case, and it is a real one: a trader who publishes signals to the
people who subscribed to them, and to nobody else. SEE-156 adds it without giving up what the
broadcast was built for. The publisher still publishes once and the gateway still fans out to
everybody entitled to it; the owner's parameters, approvals, signatures and results still never
leave the phone. What is added is a decision about **who may read**, made by the publisher, enforced
by the gateway, and proved by the owner's wallet.

This page is why it is shaped this way. What a third-party publisher does with it is
[`docs/integrations/restricted-feeds.md`](../integrations/restricted-feeds.md); running the shipped
demo end to end is [`docs/guides/restricted-feed-demo.md`](../guides/restricted-feed-demo.md).

## Public and restricted, and who decides which

A feed's policy is **the gateway operator's registration**. It is not in the link the owner scans,
not something the publisher's manifest can choose, and not a default anything falls back to:

```sh
feed-gatewayctl access --server <uuid> --access restricted --auth-origin https://auth.example.com
feed-gatewayctl register --server <uuid> --label "copy trading" \
    --access restricted --auth-origin https://auth.example.com
```

The registration holds three things — the policy, the authentication origin, and an epoch this page
comes back to (`publisher.access_policy`, `auth_origin`, `access_epoch`, schema v7 in
[`internal/storage/sqlite/store.go`](../../services/gateway/internal/storage/sqlite/store.go)). Every
registration that existed before v7 migrates to exactly what it was: public, with no origin.

Three consequences follow from putting it there rather than anywhere else.

**The manifest is stamped, not relayed.** A phone reads the policy off the manifest the gateway
serves, and the gateway writes that field from its own registration whatever the stored document
says (`stamped` in [`internal/gateway/access.go`](../../services/gateway/internal/gateway/access.go)). A
feed switched to restricted after its manifest was published is never served as public, and a public
feed's manifest is byte for byte what every manifest was before SEE-156, because a public feed
carries no `FeedAccess` at all.

**A publisher that disagrees is refused, not believed.** A publication whose manifest claims a
policy or an origin other than the registered one is `GATEWAY_PROBLEM_ACCESS_MISMATCH`
(`declaredAccessFits`). A restricted feed must say it is restricted and name the registered origin,
so it cannot be published as public by forgetting; a public feed may say nothing or say public, and
may not name an origin nobody registered.

**The link cannot supply the origin.** A feed reference may carry `&access=restricted`, and that is
a floor and nothing more: it tells the owner what they are adding, and a feed added from it whose
manifest then says public is refused (`ManifestProblem.AccessDowngraded`). The one address the phone
will ever send a wallet proof to comes from the manifest — which came from the operator's
registration — because otherwise a link would be able to nominate who gets asked to prove a wallet.
That is the whole reason the origin is registered separately from the publisher's own configuration.

The publisher checks the other direction for itself before it says anything at all. A restricted
publisher's drainer asks `DescribeAccess` and refuses to publish until the gateway confirms it
enforces this feed as restricted at this origin (`Guard` in
[`packages/publisher-support/access/sync.go`](../../packages/publisher-support/access/sync.go)). A gateway too old to
know about restricted feeds, or one where the operator registered the feed as public, therefore
never receives a signal — the failure is a deferred publication rather than an audience nobody
approved.

## Three parties, and what each one holds

| | Holds | Never holds |
| --- | --- | --- |
| The publisher | The wallet address, the device key it bound, the label the phone claimed, the decision, the invitation, the grant ID and the digest of the session | Nothing about what an owner did with a signal: no amount, no decision, no transaction, no result |
| The gateway | A grant ID, two opaque publisher-scoped references, the SHA-256 of a session, an expiry, and a push target while one is registered | The wallet address, the signature, the device name, or the session itself |
| The phone | A per-feed device key in the Keystore, the session, and its own record of where the request stands | — |

The privacy split is the point of the shape. Deciding who may read is the publisher's job, so the
publisher is the party that sees a wallet address; enforcing that decision needs no identity at all,
so the gateway is handed labels it never interprets. `subscriber_ref` is one opaque value per wallet
— so two devices of one wallet look related in the operator's view without the gateway ever learning
what they have in common — and `device_ref` is one per device. Both are bounded printable ASCII the
gateway stores and never reads (`reference` in `internal/gateway/access.go`).

The session is the same idea again: the publisher mints 32 random bytes and hands them to the phone,
and the gateway is given only `sha256(session)`. A copy of the gateway's database is therefore not a
copy of anybody's access.

## Getting access

```text
reference → challenge → one wallet signature → device-key binding → pending
          → the publisher's decision → single-use invitation → redemption
          → the publisher grants at the gateway → session → snapshot and live delivery
```

1. **The reference.** The owner adds `seekervault://feed?v=1&gateway=…&server=…&access=restricted`
   through the ordinary Add connection flow ([feed-onboarding.md](feed-onboarding.md)). The gateway
   answers the manifest to anyone — it is the onboarding metadata a phone needs in order to know
   where to prove itself — and nothing else about the channel.
2. **A challenge.** The phone generates a P-256 device key for this feed if it has none, and asks
   the registered authentication origin for a challenge, naming the channel, the wallet address, the
   device key and a label. The publisher records it and answers with the fields and the exact text.
3. **One wallet signature.** The phone **rebuilds the text from the fields itself** and compares it
   with the publisher's copy before the wallet sees anything, so a publisher cannot get a wallet to
   sign words this app did not write (`FeedAccessProof`, and `proof.go` on the other side; both pin
   [`fixtures/restricted-feeds/challenge.json`](../../fixtures/restricted-feeds/challenge.json)).
   What the owner is shown is:

   ```text
   Seeker Agent Connect feed access v1

   https://auth.example.com asks you to prove that you control this wallet, so it can decide
   whether this device may read its restricted feed.

   This is not a transaction. Signing it moves no funds and approves nothing.

   Wallet: 9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj
   Feed: server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d
   Device key: 0f1e2d3c4b5a69788796
   Attempt: 7c9e6679-7425-40de-944b-e07fc1f90ae7
   Nonce: AAECAwQFBgcICQoLDA0ODw
   Issued: 2026-09-25T12:00:00Z
   Expires: 2026-09-25T12:05:00Z
   ```

   It is an Ed25519 message signature over ASCII text. It is not a transaction, it moves no funds,
   and it says so in its own second paragraph — not as reassurance, but because the owner is being
   asked to authorize something in the same app they authorize transfers in, and the two must not
   look alike. The phone verifies the wallet's answer against the address before sending it.
4. **The device key binds the installation.** The same bytes are signed again by the device key, and
   both signatures go up together. That is what makes this installation *this* installation: a
   device label or an identifier the phone reports is a claim anybody could repeat, and a signature
   from the key the wallet named in its own signed text is a proof. It is also why the owner is
   asked for exactly one wallet signature in the whole flow — every later step is signed by the
   device key instead.
5. **Pending.** The publisher verifies both signatures, consumes the challenge, and records a device
   request. What it does next is the [`Eligibility`](../integrations/restricted-feeds.md) hook: the
   shipped demo's `ManualApproval` leaves every request for an operator, and a publisher with a
   subscription database answers `Eligible` or `Ineligible` on the spot.
6. **The decision.** An operator approves or rejects on the Devices page. A rejected device receives
   no grant. Two devices of one wallet are two rows with two decisions: approving one does not
   approve the other.
7. **An invitation.** Approval issues a single-use token, five minutes by default, bound to that
   device. It can be redeemed automatically by the phone that is polling for it, or carried as
   `seekervault://feed?…&invitation=<token>` for a link or a QR code — which is safe to show,
   because on any other phone it does nothing.
8. **Redemption.** The phone signs `seekervault-feed-access-redeem:v1`, the channel, the token and
   the current moment with its device key. The publisher checks all of it again, consumes the
   invitation atomically, and records a grant.
9. **The grant, and the session.** The publisher tells the gateway `GrantAccess` over its own
   authenticated publisher API — the grant ID, the two opaque references, the session digest and a
   lifetime — and hands the session to the phone. Reads begin.

Ordinary reconnects use the session. They do not open the wallet again, and the invitation's
lifetime and the grant's lifetime are separate clocks with separate jobs.

## Why each rule is there

Each of these exists because of a specific way access could otherwise be taken or copied, and none
of them is defence in depth for its own sake.

**A challenge expires (five minutes by default) and is one attempt.** A recorded exchange replayed
later gets `challenge_expired`; a second answer to the same challenge gets `challenge_used`. A
*failed* verification spends it too (`SpendChallenge`): a challenge is one attempt, whatever the
attempt was, so a captured challenge cannot be used as an oracle to try signatures against.

**The challenge names the origin, the feed, the wallet, the device key, the attempt and a nonce.** A
signature is only ever worth what it is bound to. Without the origin, one publisher could relay a
proof to another; without the feed, a proof for a free feed would open a paid one; without the device
key, a proof captured in transit would be a proof for whoever captured it.

**An invitation is single use, short lived, and bound to the device key.** The binding is what makes
copying it pointless — the redemption is signed by a key that never leaves the Keystore — and the
single use is enforced inside the transaction that records the grant, so of two concurrent
redemptions exactly one succeeds. A superseded invitation is refused by name
(`invitation_superseded`), so reissuing one invalidates the last.

**Approval is checked again at redemption.** Approval is the one thing in the list that can change
between being granted and being used. So the eligibility rule is asked again, the stored state is
read again inside the transaction, and a device revoked in the meantime gets `not_approved` rather
than a session (`Redeem` in [`packages/publisher-support/store/access.go`](../../packages/publisher-support/store/access.go)).

**Every signed step carries a moment, checked against a five-minute skew.** A recorded status
question or redemption is worth nothing for long, and a phone whose clock is wrong is told so rather
than silently refused.

**One device holds one live session.** Redeeming revokes the device's previous grant in the same
transaction, so a device cannot accumulate sessions that outlive each other.

## What the gateway enforces

The gateway's part is enforcement and nothing else. It never saw a wallet, a signature or a device
name; it holds a publisher's statement that some device may read, and it applies it to every read of
the channel, whatever the phone does or fails to do.

| Call | On a restricted channel |
| --- | --- |
| `GetServerManifest` | Answered to anyone. It is the onboarding metadata, and it names only the gateway, the channel and where to prove a wallet |
| `ListRequests`, `ListProposals` | A live grant's session, **on every page** — a walk that began with access does not keep it through a revocation half way through |
| `GetRequest`, `GetProposal` | A live grant's session |
| `GetStreamTicket` | A restricted channel is named in the ticket only for a live grant, and the ticket lasts no longer than the shortest grant it carries |
| `GetFeedTopics` | A restricted channel is **always absent**: it has no public topic |
| `GetFeedStatus` | A restricted channel without a live session is absent from the answer |
| `SetFeedPushTarget` | Only under the session of the grant the target belongs to |

There is no anonymous fallback and no method that skips the check. A restricted channel a caller has
no session for behaves the same way an unknown channel does — left out of a ticket, a topic list or
a status answer — so one stale feed on a phone never costs it the others.

The eight problem codes are numbered 47 to 54, after the retired private range, and none of them
reuses a number or a meaning that range carried
([`problem.proto`](../../packages/protocol/proto/seekervault/gateway/v1/problem.proto)):

| Problem | What it means | Connect code |
| --- | --- | --- |
| `ACCESS_REQUIRED` | Restricted, and no session — or one this gateway does not hold for that channel. One code for both, so a caller learns nothing about sessions it does not hold | `permission_denied` |
| `ACCESS_REVOKED` | The publisher revoked it. Final: that session is never accepted again | `permission_denied` |
| `ACCESS_EXPIRED` | The grant ran out without renewal. Not final: the same grant renewed works again | `permission_denied` |
| `ACCESS_MISMATCH` | A manifest claiming a policy or origin the operator did not register | `failed_precondition` |
| `NOT_RESTRICTED` | A grant call from a publisher whose feed is public | `failed_precondition` |
| `NO_SUCH_GRANT` | A grant the caller does not hold: never granted, or another publisher's. One code for both, so a publisher cannot probe another's grants | `not_found` |
| `GRANT_REVOKED` | A renewal of a grant this gateway already revoked | `failed_precondition` |
| `BAD_GRANT` | A grant whose identity, references, digest or lifetime is malformed | `invalid_argument` |

The order of the first three is the order the phone acts on: ask the publisher for access, accept
that it ended, or wait for a renewal.

## Revocation, and the epoch

Revocation has to be true of an *already open* stream, not only of the next read. A short-lived token
alone cannot do that, so revocation is not built on one.

The publisher revokes a device — one device, or every device of a wallet — and the store turns its
unused invitations and active grants into revocations in a single transaction. Each grant is an
outbox row with a revision: what this publisher wants the gateway to hold, and what the gateway
confirmed. The syncer sends until it is confirmed, with backoff, and the operator's page shows the
ones that have not landed with their last error. **A revocation is never reported as done before the
gateway enforces it.**

At the gateway, `RevokeAccess` ends the grants and **moves the channel's access epoch** in the same
write. A restricted channel's broker stream name carries that epoch
(`RestrictedStreamChannel` in [`internal/stream/stream.go`](../../services/gateway/internal/stream/stream.go)),
so publications after a revocation go out under a new name and a listener still attached under the
old one — a revoked device replaying an old ticket, say — receives nothing more. Revocation does not
depend on the broker closing anybody's connection.

The retired stream name gets one last event, `AccessChanged`, which carries no fields at all: which
grant was revoked is the publisher's business, and every listener on the channel receives the same
event. A listener that sees it asks for a fresh ticket, and is granted the channel again only if its
own grant is still live. A client too old to know the event reads the snapshot instead, which checks
the same grant. On the phone, a ticket that saw one is spent whatever the broker said about the
close, and the other feeds on that stream are restored by the next pass
(`ForegroundFeedManager`).

Reads, reconnects and renewals are all denied by the same grant row, so none of this needs push to
work. What push adds is speed: after a revocation commits, the gateway sends a best-effort hint to
the push targets those grants held, so the phone reconciles sooner than its next glance. Those
devices' reads are then refused. **A queued push confers no authority** — it is a content-free hint
that something changed, every read it prompts is checked again, and a hint that was never delivered
changes nothing about whether access ended.

## The offline bound, stated plainly

A grant is finite, and that is the honest answer to "what if the publisher cannot reach the
gateway?"

`PUBLISHER_ACCESS_GRANT_HOURS` (default **6**, 1 to 720) is how long a grant runs before the
publisher renews it, and the publisher renews when a third of it is left. So when the publisher
cannot reach the gateway at all:

- an approved device keeps reading for at most one grant lifetime;
- a device revoked during that outage keeps reading for at most what is left of its grant, because
  nothing renews a grant the publisher has revoked.

**Six hours is therefore the documented bound on how long access outlives the publisher's reach.**
It is not zero, and nothing in this design makes it zero: immediate revocation requires the
publisher to reach the gateway, and an operator who wants a tighter bound sets a shorter grant
lifetime and pays for it in renewal traffic. The gateway caps any grant at
`BROADCAST_MAX_GRANT_HOURS` (default 24 hours) and says the bound it enforces in
`DescribeAccessResponse.most_grant_seconds`, so a publisher asking for longer is shortened rather
than surprised.

Renewal is also where an eligibility rule is re-asked. A publisher whose rule now refuses a device
revokes it at renewal time rather than letting the grant roll forward.

## Push for a restricted feed

A public feed's hint goes to a Firebase topic anyone may join. A restricted feed's must not: topic
membership is Firebase's, nobody here can revoke it, and a device whose access ended would keep
being told that the feed moved.

So `GetFeedTopics` always leaves a restricted channel out, and each approved device registers its
own target instead through `SetFeedPushTarget`, under the session its grant was issued with. The
target is kept against the grant, used only for that grant's hints, never returned by a read outside
the relay, and dropped with the grant. At send time the live grants are read fresh, so a grant
revoked a second ago is not among them
([`internal/relay/restricted.go`](../../services/gateway/internal/relay/restricted.go)).

What is sent is the same content-free hint a topic carries. It grants nothing, and the phone it
wakes reads the feed under its own session like any other read.

## On the phone

Restricted access lives in
[`apps/android/.../access/`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/access) and is
per feed connection.

**Six states**, which are what the connection's status line says
(`FeedAccessStore.State`, `ConnectionText.statusText`):

| State | What the owner is told |
| --- | --- |
| Pending | Waiting for the publisher to approve this device. |
| Approved | Approved. Finishing the connection… |
| Rejected | The publisher didn't approve this device. Ask again to make a new request. |
| Connected | Nothing about access — the ordinary feed status takes over |
| Revoked | The publisher revoked this device's access to the feed. Signals already on this phone stay. |
| Expired | This device's access to the feed has run out. Check again to renew it. |

Access is said before anything about the transport, because it is the more basic fact: a device
waiting for approval is not "unreachable", and telling the owner to wait for a connection that will
never open is telling them the wrong thing to wait for. Pending is not treated as a problem — it is
the flow working; the three that end access are, and each needs the owner to do something.

**The device key** is a P-256 key in the Android Keystore, generated there, usable only for signing,
and never readable by this app or anything else — alias `seekervault.feed-access.v1.<connection ID>`,
one per feed connection rather than one per phone, so two publishers cannot compare notes about the
same device. A restored backup is a different device and has to ask again.

**Where things are kept.** The record of what was asked and where it stands is one atomic JSON
document per connection in `noBackupFilesDir/feed-access/`: the wallet, the device-key fingerprint
that wallet bound, the request ID, the state and the grant expiry. It holds no secret. The session —
the one value that reads the feed — is sealed in its own vault, `noBackupFilesDir/feed-sessions`, in
the same format and under the same Keystore key as a phone credential. Both are out of backups, for
the reason the device key is.

**Access belongs to the wallet it was proven with.** Selecting a different wallet does not inherit
it. Asking again with that wallet starts a new request and drops the old session, so a wallet change
can never silently reuse another wallet's authorization.

**Removing the connection forgets all of it.** Removal drops the session, deletes the access record
and deletes the Keystore key, alongside the credential, answers, rules and proposals a removal
already cleared (`ConnectionRepository.remove` → `FeedAccessManager.forget`). What outlives it is
the owner's own Activity, which is theirs and not the connection's.

**What the phone will not do.** It sends a wallet proof only to the origin the gateway stamped on
that feed's manifest, on every call, and the HTTP client used for it follows no redirects: an answer
from anywhere else is not the publisher's. It refuses a challenge whose fields do not match what it
asked for, or that claims to last longer than thirty minutes, before the wallet is opened at all.

## The operator's page

The shipped demo's Devices / Feed access page is
[`examples/demo-signals/internal/admin/devices.go`](../../examples/demo-signals/internal/admin/devices.go),
inside the existing password-gated trader UI, and it is the operator's whole surface: list, approve,
reject, revoke, reissue, and revoke every device of a wallet. Its mutations go through the
publisher's existing token-protected API path and through the one process that owns the store, so
there is no second writer. Since SEE-163 a live invitation is drawn as a QR code beside its link,
so the device it belongs to scans it off the screen instead of its operator copying a long URL —
which changes nothing about what an invitation is, because it is single use and bound to that one
device key.

It shows the wallet address, the device-key fingerprint, the **label the phone claimed** — shown as
exactly that, a claim — the request and decision times, and one word for where access stands:

`pending_approval` · `rejected` · `invited` · `invitation_expired` · `grant_pending` · `connected` ·
`expired` · `revocation_pending` · `revoked`

Two of those words exist because honesty about the gateway matters more than a tidy list.
`grant_pending` means the publisher has recorded a grant the gateway has not confirmed, and
`revocation_pending` means a revocation the gateway has not confirmed — which is *not* revoked, and
is not shown as revoked. A grant's row carries its attempt count and its last error while it is in
that state.

Reissuing is refused for any device that is not approved right now, so it can never undo a
revocation, and it supersedes the invitation it replaces.

## What this is not

**It cannot erase what was already delivered.** A signal a phone has read is on that phone, and
revoking access stops future delivery, reads, reconnects and renewals — it does not reach back. This
is the same rule the public feed has always had: what a phone holds is the phone's until its owner
removes the feed. Nothing in this build implies otherwise, and the copy the owner sees says it out
loud ("Signals already on this phone stay").

**It is not an account system.** There is no password, no OAuth, no social login, no subscriber
record beyond the device rows the publisher keeps, and no dependency on Seeker ID. The only identity
in the flow is a wallet that signed one message and a key that lives in one Keystore.

**A wallet-authentication signature is not a transaction signature.** They are different messages
for different purposes and the challenge text says which one it is. Nothing in this flow prepares,
signs or sends a transaction, and the access path never touches the owner's approval of one.

**A topic, a hint or a ticket is not access.** A hint is content-free and grants nothing; a ticket
admits a listener to named channels for a bounded time and is refused for a restricted channel
without a live grant; and a restricted channel has no topic to hold in the first place.

**A grant is not a subscription the gateway understands.** It is an opaque statement with an expiry.
The gateway cannot tell you who a subscriber is, what they paid for, or why they were approved,
because it was never told.

## Where the code is

| | |
| --- | --- |
| The contracts | [`feed.proto`](../../packages/protocol/proto/seekervault/gateway/v1/feed.proto), [`publish.proto`](../../packages/protocol/proto/seekervault/gateway/v1/publish.proto), [`event.proto`](../../packages/protocol/proto/seekervault/gateway/v1/event.proto), [`problem.proto`](../../packages/protocol/proto/seekervault/gateway/v1/problem.proto), [`manifest.proto`](../../packages/protocol/proto/seekervault/server/v1/manifest.proto) |
| The publisher's side | [`packages/publisher-support/access/`](../../packages/publisher-support/access) and [`packages/publisher-support/store/access.go`](../../packages/publisher-support/store/access.go) |
| The gateway's side | [`internal/gateway/access.go`](../../services/gateway/internal/gateway/access.go), [`internal/relay/restricted.go`](../../services/gateway/internal/relay/restricted.go), schema v7 in `internal/storage/` |
| The phone's side | [`apps/android/.../access/`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/access), `servers/ManifestValidation.kt`, `feeds/ConnectFeedGateway.kt` |
| The shared fixture | [`fixtures/restricted-feeds/challenge.json`](../../fixtures/restricted-feeds/challenge.json), pinned by a test on each side |
