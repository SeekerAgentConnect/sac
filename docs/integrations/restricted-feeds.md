# Running a restricted feed (SEE-156)

How a third-party publisher runs a feed only devices it approved may read, on the shared
[`publisher-support/access`](../../publisher-support/access) library. Everything here is library
code: there is no service to sign up to, no account system, and nothing this page describes is
private to the demos.

Why the pieces are shaped the way they are — what each signature is bound to, what the gateway is
deliberately not told, and what the six-hour offline bound is buying — is
[`docs/wiki/restricted-feeds.md`](../wiki/restricted-feeds.md). This page assumes that reasoning and
says what you do with it. The publication side, which a restricted feed does not change, is
[`signal-api.md`](signal-api.md).

The worked example is [`demo-copytrading`](../../demo-copytrading): its
[`cmd/copytrading/main.go`](../../demo-copytrading/cmd/copytrading/main.go) wires every piece named
below in about forty lines, and its Devices page
([`internal/admin/devices.go`](../../demo-copytrading/internal/admin/devices.go)) is one way — not
the only way — to give an operator somewhere to decide. Point at it; do not copy it wholesale,
because its policy is *ask a human*, and yours probably is not.

## What you decide before you start

Three things, and only the first is yours alone.

**Your eligibility rule.** Who may read this feed: a paying subscriber, an entitlement in a system
you already run, a person an operator vouches for. This is the whole reason a restricted feed
exists, and it is the one decision the library does not make for you.

**Where phones reach you.** A public HTTPS origin serving `/access/v1`. It holds no credential, so
it can sit in front of the token-protected API rather than behind it, but it must be reachable by
every phone that will ever read the feed.

**That the gateway's operator registers you as restricted.** You cannot choose this. A feed's
policy lives in the gateway's own registration, beside your authentication origin, and the operator
sets it:

```sh
feed-gatewayctl access --server <uuid> --access restricted --auth-origin https://auth.example.com
```

([`feed-gateway/cmd/feed-gatewayctl/main.go`](../../feed-gateway/cmd/feed-gatewayctl/main.go).) Ask
for it before you write a line, because until it is done your publisher will refuse to publish, by
design. The origin must be an origin: HTTPS, no path, no query, no fragment, and plain HTTP only on
loopback (`config.Origin` in [`publisher-support/config/config.go`](../../publisher-support/config/config.go)).
Agree it character for character with the operator — `https://Auth.Example.com:443/` and
`https://auth.example.com` are not the same string to a phone, and only one of them is what your
manifest will claim.

## Configuration, and failing closed

A restricted feed reads its own settings beside the ordinary `PUBLISHER_*` ones, through
`access.Load` ([`publisher-support/access/config.go`](../../publisher-support/access/config.go)):

| Variable | Default | What it is |
| --- | --- | --- |
| `PUBLISHER_AUTH_ORIGIN` | — required | The origin phones reach `/access/v1` at, exactly as the gateway's operator registered it. Missing or malformed is a refused start |
| `PUBLISHER_AUTH_ADDRESS` | empty | A listener of its own for the endpoint, as `host:port`. Empty serves `/access/` beside the token-protected `/v1` on `PUBLISHER_API_ADDRESS`, which is what a platform that gives a service one public port needs |
| `PUBLISHER_ACCESS_GRANT_HOURS` | `6` (1 to 720) | How long a grant runs before you renew it — and so the bound on how long access outlives your reach to the gateway |
| `PUBLISHER_ACCESS_INVITATION_MINUTES` | `5` (1 to 60) | How long an approved device has to redeem its invitation |
| `PUBLISHER_ACCESS_CHALLENGES_PER_HOUR` | `60` | Challenges one address may ask for in an hour. Status questions and redemptions get twenty times it |

Every problem is noted on the reader rather than returned, so a first start names all of them at
once. The two remaining lifetimes — the challenge's and the clock skew a signed moment may have —
are fields on `access.Settings`, not variables, and default to five minutes each
(`DefaultChallengeLifetime`, `DefaultSkew`).

The origin goes in two places, and they must agree. It is `access.Settings.AuthOrigin`, which is the
first line of every challenge a wallet signs, and it is `manifest.Settings.AuthOrigin`, which is
what makes your published manifest say the feed is restricted
([`publisher-support/manifest/manifest.go`](../../publisher-support/manifest/manifest.go)). A
manifest that claims a policy or an origin the operator did not register is refused with
`GATEWAY_PROBLEM_ACCESS_MISMATCH`.

**A restricted publisher publishes nothing until the gateway confirms the policy.** `access.Guard`
asks `DescribeAccess` and answers a refusal — which defers a signal exactly as a gateway outage
would — unless the gateway says *restricted, at this origin*. A gateway too old to know about
restricted feeds answers unimplemented, which is the same answer. Wire it as the drainer's guard and
there is no path around it:

```go
guard := access.NewGuard(gateway, restricted.AuthOrigin, time.Now)
drainer := publish.NewDrainer(publish.Plan{
	// … Documents, Gateway, ServerID, Manifest, Log, Now …
	Guard: guard.Check,
})
```

A confirmation is trusted for one minute, so an operator who switches the policy back to public
stops receiving your signals within a minute rather than at your next restart. The refusal's problem
is `access_unconfirmed` and it is not permanent: the signals wait in your database and go out when
the registration is right.

## The eligibility hook

One method, in [`publisher-support/access/access.go`](../../publisher-support/access/access.go):

```go
type Eligibility interface {
	Decide(ctx context.Context, subject Subject) (Decision, error)
}

type Subject struct {
	Wallet       string // the base58 address that signed the challenge
	Installation string // the device key's fingerprint: hex of the first 10 bytes of its SHA-256
	Label        string // the phone's own name for itself — a claim, never a reason
	Channel      string // "server/<server_id>"
}
```

`Decision` has three values. `Undecided` leaves the request pending for a human. `Eligible` approves
it on the spot and issues its invitation in the same transaction. `Ineligible` rejects it, and
revokes an already-approved device the next time it is asked about.

It is asked three times, and what it can do differs each time:

| When | What happens to each answer |
| --- | --- |
| A wallet answers a challenge (`Service.Request`) | `Eligible` approves and invites, `Ineligible` rejects, `Undecided` leaves it pending |
| The device redeems its invitation (`Service.Redeem`) | Only `Ineligible` acts: the device is revoked and the redemption answers `not_approved`. It cannot approve a device an operator has not |
| A grant comes up for renewal (`Syncer.renew`) | `Ineligible` revokes the device rather than rolling its grant forward. Only a device whose rule answered is renewed: an error leaves its grant's expiry exactly as it is, and the rule is asked again after a backoff |

An error is not a decision. The request stays exactly as it is and the question is asked again
later, so a rule that depends on a database you cannot reach fails towards *nothing changes* rather
than towards *everybody in* or *everybody out*. Return an error; do not return `Ineligible` because
a lookup failed.

The shipped demo plugs in `access.ManualApproval{}`, which answers `Undecided` to everything: every
wallet-verified request waits for the operator, and the operator's decision stands until they change
it. A publisher with a subscription database answers on the spot instead:

```go
// Subscribers admits a device when the wallet that proved itself holds a live subscription.
type Subscribers struct{ Billing *billing.Client }

func (s Subscribers) Decide(ctx context.Context, subject access.Subject) (access.Decision, error) {
	live, err := s.Billing.SubscriptionLive(ctx, subject.Wallet)
	if err != nil {
		// Not a decision: the request keeps its current state and is asked about again.
		return access.Undecided, fmt.Errorf("ask billing about %s: %w", subject.Wallet, err)
	}
	if !live {
		return access.Ineligible, nil
	}
	return access.Eligible, nil
}
```

Decide on `Wallet`, or on `Wallet` and `Installation` together if you cap devices per subscriber.
Never decide on `Label`: it is whatever the phone typed, bounded to 64 printable bytes
(`MostLabelBytes`) and checked for nothing else.

**Pass your rule to both the service and the syncer.** They hold separate references, and a `Syncer`
built without one defaults to `ManualApproval{}` — which means renewals silently stop re-asking your
rule, and a subscriber who cancelled keeps reading until an operator intervenes. The demo omits it
because its rule is manual either way; yours must not:

```go
rule := Subscribers{Billing: billing}
syncer := access.NewSyncer(access.SyncPlan{
	Store: documents, Grants: gateway, Eligibility: rule,
	Lifetime: restricted.GrantLifetime, Channel: signals.ChannelFor(settings.ServerID),
	Log: log, Now: time.Now,
})
devices := access.New(access.Plan{
	Store: documents, Eligibility: rule, Syncer: syncer, Log: log, Now: time.Now,
	Settings: access.Settings{ /* ServerID, GatewayURL, AuthOrigin, lifetimes */ },
})
```

## The endpoint phones call

`Service.Handler(access.Limits{PerHour: …})` is the whole public surface
([`publisher-support/access/http.go`](../../publisher-support/access/http.go)). It holds no
credential and tells nobody anything about anybody but the caller. Every body is one strict JSON
object: unknown fields are refused rather than dropped, nothing may follow it, and 16 KiB is the
most it may be. Base64 is accepted in either alphabet, padded or not, because Android's default and
Go's differ and neither is worth a refusal.

| Method and path | Body | Answer |
| --- | --- | --- |
| `GET /healthz` | — | `{"status": "ok"}` |
| `POST /access/v1/challenges` | `feed`, `wallet`, `device_key`, `label` | 201 with `attempt`, `nonce`, `issued_at`, `expires_at`, `auth_origin`, `feed`, `installation`, `message` |
| `POST /access/v1/requests` | `attempt`, `wallet_signature`, `device_signature` | 201 with `request_id`, `state` |
| `POST /access/v1/requests/{id}/status` | `at`, `device_signature` | 200 with `request_id`, `state`, `connected`, and `invitation` (`token`, `expires_at`, `link`) while one is live |
| `POST /access/v1/redeem` | `feed`, `invitation`, `at`, `device_signature` | 200 with `request_id`, `session`, `grant_id`, `until`, `gateway` |

A refusal is `{"error": "<code>", "detail": "…"}` with a stable code the phone branches on:
`challenge_expired`, `challenge_used`, `bad_signature`, `stale_proof`, `unknown_request`,
`unknown_invitation`, `invitation_used`, `invitation_superseded`, `invitation_expired`,
`not_approved`, `too_many_requests`, and the rest in `http.go`.

`device_key` is an X.509 SubjectPublicKeyInfo holding a P-256 public key — what an Android keystore
key's `getEncoded()` answers. `at` is a moment in milliseconds, and it must be within the skew of
your clock or the call is `stale_proof`.

### What is signed, and by whom

Two keys, kept apart on purpose
([`publisher-support/access/proof.go`](../../publisher-support/access/proof.go)):

- The **wallet** — an Ed25519 Solana key — signs exactly one thing, ever: the challenge text
  returned as `message`. It is plain ASCII built from the challenge's own fields, it names the
  origin, the feed, the wallet, the device key, the attempt, a nonce and two timestamps, and its
  second paragraph says it is not a transaction. You verify it with `VerifyWallet`; the phone
  rebuilds the same text from the same fields and compares before the wallet is opened at all, so
  you cannot get a wallet to sign words the app did not write. Both sides pin
  [`fixtures/restricted-feeds/challenge.json`](../../fixtures/restricted-feeds/challenge.json).
- The **device key** — P-256, in the phone's keystore — signs the same bytes alongside the wallet,
  which is what binds the attempt to that installation. It then signs every later step:
  `seekervault-feed-access-status:v1` with the request ID and the moment, and
  `seekervault-feed-access-redeem:v1` with the channel, the invitation and the moment
  (`StatusStatement`, `RedeemStatement`; ECDSA over SHA-256, verified with `VerifyDevice`).

That is why the owner sees one wallet prompt in the whole flow, and why you must not add a second.

A failed verification spends the challenge (`SpendChallenge`), because a challenge is one attempt
whatever the attempt was. A status question whose signature does not verify is answered exactly like
an unknown request, so a caller without the key learns nothing.

Why each of those bindings is there, and what goes wrong without it, is
[the wiki's "Why each rule is there"](../wiki/restricted-feeds.md#why-each-rule-is-there).

### The operator's routes

`Service.AdminRoutes()` returns the mutations, for your own token-protected API to mount as
`api.Plan.Access` ([`publisher-support/api/api.go`](../../publisher-support/api/api.go)):
`GET /v1/access/devices`, `POST /v1/access/devices/{id}/approve`, `…/reject`, `…/revoke`,
`…/reissue`, and `POST /v1/access/wallets/{wallet}/revoke`. They take an optional `{"by": "…"}`,
recorded as who decided. Mounting them there rather than beside `/access/v1` is deliberate: they
change the decision, so they belong behind the same token as publishing, and they go through the one
process that writes the database.

`Service.View` renders a device the way an operator needs to see it, including the single word for
where its access stands — `pending_approval`, `rejected`, `invited`, `invitation_expired`,
`grant_pending`, `connected`, `expired`, `revocation_pending`, `revoked`.

## Granting and revoking at the gateway

You never tell the gateway who anybody is. `Syncer` calls three methods on the publisher API
([`publisher-support/gateway/gateway.go`](../../publisher-support/gateway/gateway.go)):

- `DescribeAccess` — which policy the gateway enforces for you, the origin it registered, and
  `MostGrant`, the longest grant it will honour (`BROADCAST_MAX_GRANT_HOURS` at its end). Ask for
  longer and you are shortened, not surprised.
- `GrantAccess` with a `gateway.Grant`: the grant ID, a `SubscriberRef` (one opaque value per
  wallet), a `DeviceRef` (`"device-" + installation`), the SHA-256 of the session, and a lifetime.
  Renewal is the same call with the same grant ID.
- `RevokeAccess` with grant IDs. A grant the gateway never held answers `Absent` rather than an
  error: there is nothing to take back.

Every grant is an outbox row in `access_grant`
([`publisher-support/store/access.go`](../../publisher-support/store/access.go)): `revision` is what
you want the gateway to hold, `synced_revision` is what it confirmed. A grant, a renewal and a
revocation are each a new revision. `Syncer.Pass` sends what is behind — at most 32 rows a pass —
records the confirmation conditionally on the revision, so a revocation written while a grant was in
flight stays pending, and on a failure counts an attempt, stores the reason (truncated to 300
characters) and schedules the next try with `gateway.Backoff`: one second doubling to a minute. The
loop wakes every 15 seconds by default, and `Syncer.Wake` asks for a pass now. A redemption calls
`SyncNow` so the phone can usually read immediately; when the gateway did not answer, the redeem
response says `"gateway": "pending"` and the loop keeps trying.

One refusal is final: `grant_revoked` means the gateway already revoked that grant, so the syncer
records `GrantRevokedByGateway` and stops asking. A renewal can never bring it back.

Grants are renewed when a third of the lifetime is left, and only grants the gateway has already
confirmed, belonging to devices that are still approved and whose eligibility rule answered, are
renewed at all. With the default six hours that is a call every four hours per device. The lifetime
is the one the gateway granted, not the one you asked for: `GrantAccess` answers the lifetime left
after `BROADCAST_MAX_GRANT_HOURS`, the syncer records that expiry, and when the gateway caps grants
shorter than `PUBLISHER_ACCESS_GRANT_HOURS` it renews at a third of the cap instead — a one-hour cap
is a renewal about every forty minutes, not a grant that lapses at hour one.

**"Not yet confirmed" is the state you must be honest about.** A grant whose gateway state is behind
is `grant_pending`, and a revocation the gateway has not confirmed is `revocation_pending` — which
is *not* revoked, and must not be shown as revoked, because until the gateway has it that device can
still read. The bound is exact and it is in your own configuration: while you cannot reach the
gateway, an approved device keeps reading for at most one grant lifetime, and a revoked device for
at most what is left of its grant, because nothing renews a grant you have revoked. Six hours by
default. Shorten `PUBLISHER_ACCESS_GRANT_HOURS` if you want a tighter bound and pay for it in
renewal traffic.

## Onboarding links

The reference an owner adds the feed from is the ordinary one with a hint on the end, which
`manifest.ReferenceOf` produces and the publisher prints on stdout at startup:

```text
seekervault://feed?v=1&gateway=https%3A%2F%2Ffeeds.example.com&server=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d&access=restricted
```

`Service.Link` builds the same thing with `&invitation=<token>` appended, for an approved device
that would rather scan its invitation than poll for it. It is safe to show, print or photograph: the
token is single-use and bound to the one device key it was issued for, so on any other phone it does
nothing.

`access=restricted` is a floor and nothing else. It tells the owner what they are adding, and a feed
whose manifest then says public is refused. **A link can never supply the authentication origin.**
The phone reads the policy and the origin off the manifest the gateway serves — which the gateway
stamps from its own registration, whatever your published document says — and that is the only
address it will ever send a wallet proof to. Do not build a link that names your origin, and do not
ask anyone to trust one that does.

## What you must not do

**Do not open a second writer to the store.** One process owns the SQLite file, and every decision —
a redemption, a revocation, the invitation each consumes — is one transaction there, which is what
makes a revocation and the redemption racing it resolve to exactly one winner. An operator page,
an admin CLI or a billing webhook reaches the decisions through the token-protected
`/v1/access/...` routes, as the demo's page does; it does not open the database.

**Do not report a revocation as done before the gateway confirms it.** Say `revocation_pending` and
show the attempt count and last error while it is in that state. Anything else tells an operator
that a device has stopped reading when it has not.

**Do not reuse a retired problem number.** The gateway's access problems are 47 to 54, after the
`gateway_private` range 35 to 46 that is `reserved` in
[`problem.proto`](../../proto/seekervault/gateway/v1/problem.proto) — old logs, stored status and
clients still carry those meanings.

**Do not ask for a second wallet signature at redemption.** The device key signs it, and the whole
point of binding it in the challenge was that it can. An owner asked to open their wallet again for
something that is not a transaction learns the wrong habit.

**Do not treat a label, a push hint or an expired invitation as authority.** The label is a claim,
a hint is content-free and grants nothing, and an invitation that has been used, superseded or
expired is refused by name.

## Housekeeping

Every sync pass forgets the challenges that have expired (`Store.SweepChallenges`, called from
`Syncer.Pass`). A challenge that expired can no longer be answered, so the row is spent history,
and a publisher that has run for a year is not still holding one for every attempt anyone ever made
at it. A sweep that fails is logged and does not stop the pass: telling the gateway about a grant
matters more than tidying up after an attempt.

Nothing else expires on its own. Devices, invitations and grants are the record of decisions, and
they are kept.

## Where the code is

| | |
| --- | --- |
| The service, the hook, the decisions | [`publisher-support/access/access.go`](../../publisher-support/access/access.go) |
| The signatures and the challenge text | [`publisher-support/access/proof.go`](../../publisher-support/access/proof.go) |
| The HTTP surface and the operator's routes | [`publisher-support/access/http.go`](../../publisher-support/access/http.go) |
| The settings | [`publisher-support/access/config.go`](../../publisher-support/access/config.go) |
| The gateway outbox, renewal and the publication guard | [`publisher-support/access/sync.go`](../../publisher-support/access/sync.go) |
| The four tables (schema version 4) | [`publisher-support/store/access.go`](../../publisher-support/store/access.go) |
| The publisher API client | [`publisher-support/gateway/gateway.go`](../../publisher-support/gateway/gateway.go) |
| A worked wiring | [`demo-copytrading/cmd/copytrading/main.go`](../../demo-copytrading/cmd/copytrading/main.go), [`demo-copytrading/.env.example`](../../demo-copytrading/.env.example) |
| A deployment | [`deploy/copytrading/compose.yaml`](../../deploy/copytrading/compose.yaml), [`deploy/signals-demo.yaml`](../../deploy/signals-demo.yaml) |
