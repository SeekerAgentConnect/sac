# Running the restricted CopyTrading demo

The shipped CopyTrading demo publishes a **restricted** feed (SEE-156): a phone proves it controls
a wallet, an operator approves that device on a page, and the gateway admits only the devices that
were approved. Why it is shaped that way is
[`../wiki/restricted-feeds.md`](../wiki/restricted-feeds.md). This page is the other half — how to
run the whole thing end to end and watch each decision land, locally and against the deployment.

> **This runbook is written from the source, not from a run.** Every command, flag, variable, route
> and message below was read out of the code it comes from and is linked to it. Nothing here was
> executed, so no transcript, log line or screenshot is reproduced except where the source contains
> the exact string, and then the file it came from is named. Steps whose result depends on a device,
> a broker or a deployment are marked **(not observed)**. Correct this page from the first real run.

## 1. What has to exist first

| | What | Why |
| --- | --- | --- |
| A feed gateway | [`services/gateway/`](../../services/gateway), reads on `127.0.0.1:8090` and publications on `127.0.0.1:8091` by default ([feed-gateway.md](../development/feed-gateway.md)) | It is what enforces the restriction. The publisher only decides |
| A broker | Centrifugo and Redis, as `compose/feed/compose.yaml` in `do-deploy` runs them, plus `BROADCAST_STREAM_URL`, `BROADCAST_STREAM_API_KEY` and `BROADCAST_STREAM_TOKEN_KEY` on the gateway — all three or none | Only §8 needs it. Without them the gateway answers every read and says once that there is no stream, so a revocation shows on the next read rather than on an open one |
| The demo | [`examples/demo-signals/`](../../examples/demo-signals): `copytrading`, `copytrading-admin`, `publishctl` | The publisher, the operator's page, and the signal client |
| A phone with a wallet | A real Seeker, or any device with a wallet the app can ask to sign | The flow is a wallet signature, once |

**No funds move.** The one wallet interaction in the whole flow is an Ed25519 signature over ASCII
text; nothing prepares, signs or sends a transaction, and the text says so in its own second
paragraph (the pinned copy is
[`fixtures/restricted-feeds/challenge.json`](../../fixtures/restricted-feeds/challenge.json)):

```text
Seeker Agent Connect feed access v1

https://auth.copytrading.example.com asks you to prove that you control this wallet, so it can
decide whether this device may read its restricted feed.

This is not a transaction. Signing it moves no funds and approves nothing.

Wallet: 9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj
Feed: server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d
Device key: 0f1e2d3c4b5a69788796
Attempt: 7c9e6679-7425-40de-944b-e07fc1f90ae7
Nonce: AAECAwQFBgcICQoLDA0ODw
Issued: 2026-09-25T12:00:00Z
Expires: 2026-09-25T12:05:00Z
```

The phone rebuilds that text from the challenge's fields and compares it with the publisher's copy
*before* the wallet is opened at all
([`FeedAccessProof.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/access/FeedAccessProof.kt),
[`proof.go`](../../packages/publisher-support/access/proof.go)).

**The headless emulator cannot finish this flow.** Seed Vault Wallet and Mobile Wallet Adapter are
absent there, so nothing can be signed ([emulator-e2e.md](../development/emulator-e2e.md)) and the
app answers *"Connect a wallet first. A restricted feed grants access to a wallet, not to a phone."*
Adding the feed and reading the restricted confirmation copy work; the approval path needs a wallet.

## 2. Register the feed as restricted on the gateway

The policy lives in the **gateway operator's registration**, not in the publisher's configuration
and not in the link. Either register a new publisher as restricted:

```sh
cd services/gateway
go run ./cmd/feed-gatewayctl register --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --label "copy trading" \
  --access restricted --auth-origin http://127.0.0.1:8092
```

or change one that already exists:

```sh
go run ./cmd/feed-gatewayctl access --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
  --access restricted --auth-origin http://127.0.0.1:8092
```

`access` prints the new policy and then, verbatim from
[`main.go`](../../services/gateway/cmd/feed-gatewayctl/main.go):

```text
Every stream name issued under the old policy is retired. The publisher should publish its
manifest again so the policy it states matches this one.
```

Flags that exist, and their rules:

| Flag | Rule |
| --- | --- |
| `--access public\|restricted` | On `access` it is required. On `register`, omitting it means public, which is what every registration was before |
| `--auth-origin <origin>` | Required with `--access restricted`, refused with `--access public` (*"--auth-origin belongs to a restricted feed"*). Validated by the same `config.Origin` the gateway canonicalizes its own origin with: HTTPS, or plain HTTP on `127.0.0.1`, `localhost` or `::1`; lowercase; no default port; no path, query, fragment or user information |
| `--database <path\|url>` | The gateway's own database, or `BROADCAST_DATABASE_PATH` / `BROADCAST_DATABASE_URL`. A Postgres URL is recognized by its scheme; anything else is a file |

`--access` on `rotate` is refused — *"--access belongs to `register` and `access`: rotation adds a
credential and changes nothing else about a server"*. `go run ./cmd/feed-gatewayctl list` then shows
the policy and the live grant count for a restricted publisher.

**Or from the gateway's admin page** (SEE-162), when one is configured: **Add server** has a *Who may
read its feed* choice with the authentication origin, and an existing publisher's page has a **Who
may read** form. Choose *Restricted*, enter the origin, type the server ID to confirm, **Save
access**. The rules and the result are the CLI's
([`internal/admin/admin.go`](../../services/gateway/internal/admin/admin.go)).

### If you forget this step

Nothing is published. The publisher's drainer asks `DescribeAccess` before every pass and fails
closed with this exact refusal from
[`packages/publisher-support/access/sync.go`](../../packages/publisher-support/access/sync.go):

```text
the gateway does not enforce this feed as restricted at <origin>; register it with
feed-gatewayctl access --access restricted --auth-origin <origin>. Nothing is published until it does
```

A gateway that cannot be reached at all gives the same `access_unconfirmed` problem with its own
error in it. That is the intended failure: a deferred publication rather than an audience nobody
approved.

## 3. Start the publisher

Everything the public demo needed, plus `PUBLISHER_AUTH_ORIGIN`
([demos.md](../development/demos.md) has the public form):

```sh
cd examples/demo-signals
PUBLISHER_SERVER_ID=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
PUBLISHER_GATEWAY_URL=http://127.0.0.1:8090 \
PUBLISHER_PUBLISH_URL=http://127.0.0.1:8091 \
PUBLISHER_ENVIRONMENT=sandbox \
PUBLISHER_DATABASE_PATH=./copytrading.db \
PUBLISHER_AUTH_ORIGIN=http://127.0.0.1:8092 \
BROADCAST_CREDENTIAL=<the credential feed-gatewayctl printed> \
PUBLISHER_API_TOKEN=$(openssl rand -base64 32) \
go run ./cmd/copytrading
```

The restricted settings, from
[`packages/publisher-support/access/config.go`](../../packages/publisher-support/access/config.go) and
[`examples/demo-signals/.env.example`](../../examples/demo-signals/.env.example):

| Variable | Default | What it is |
| --- | --- | --- |
| `PUBLISHER_AUTH_ORIGIN` | none; **required** | The public origin phones reach `/access/v1` at. It must be character for character what the gateway's operator registered |
| `PUBLISHER_AUTH_ADDRESS` | empty | A listener of its own for `/access/v1`, as `host:port`. Empty serves it on `PUBLISHER_API_ADDRESS` beside the token-protected `/v1`, which is what a platform that gives a service one public port needs |
| `PUBLISHER_ACCESS_GRANT_HOURS` | 6, range 1–720 | How long a grant runs before the publisher renews it. It is also the bound on how long a device keeps reading after a revocation the publisher cannot deliver |
| `PUBLISHER_ACCESS_INVITATION_MINUTES` | 5, range 1–60 | How long an approved device has to redeem its invitation |
| `PUBLISHER_ACCESS_CHALLENGES_PER_HOUR` | 60, range 1–100000 | Wallet challenges one address may ask for per hour |

`PUBLISHER_API_ADDRESS` defaults to `127.0.0.1:8092`, so with `PUBLISHER_AUTH_ADDRESS` empty the
auth origin above matches the API's own listener. **Watch for a collision:** the gateway's optional
admin page defaults to the same `127.0.0.1:8092` (`BROADCAST_ADMIN_ADDRESS`). Running both on one
machine means moving one of them.

At startup [`cmd/copytrading/main.go`](../../examples/demo-signals/cmd/copytrading/main.go) logs
`access=restricted` with the auth origin and the grant hours, logs that the authentication endpoint
is served beside the API at `/access/v1`, and prints the feed reference on **stdout**. A restricted
feed's reference carries `&access=restricted`
([`manifest.ReferenceOf`](../../packages/publisher-support/manifest/manifest.go)):

```text
seekervault://feed?v=1&gateway=http%3A%2F%2F127.0.0.1%3A8090&server=3f1b2c4d-…&access=restricted
```

That suffix is a hint and never an authority: the phone reads the policy, and the one origin it will
ever send a wallet proof to, off the **manifest** the gateway stamps from its own registration.

The four endpoints the phone uses are `POST /access/v1/challenges`, `/access/v1/requests`,
`/access/v1/requests/{id}/status` and `/access/v1/redeem`
([http.go](../../packages/publisher-support/access/http.go)). They are public and signature-checked, never
token-protected; the operator's own `/v1/access/...` routes stay behind `PUBLISHER_API_TOKEN`.

## 4. Start the trader UI and find the Devices page

The UI is a client of the publisher's own `/v1`, holding `PUBLISHER_API_TOKEN` so the browser never
does. Passwords are named bcrypt lines in a file; `hash` prints one:

```sh
cd examples/demo-signals
printf '%s\n' 'the-password-you-chose' | go run ./cmd/copytrading-admin hash trader >> ./admin-passwords

ADMIN_API_URL=http://127.0.0.1:8092 \
ADMIN_PASSWORDS_FILE=./admin-passwords \
ADMIN_SESSION_SECRET=$(openssl rand -base64 32) \
PUBLISHER_API_TOKEN=<the same token the publisher was started with> \
go run ./cmd/copytrading-admin
```

Defaults from [`internal/admin/config.go`](../../examples/demo-signals/internal/admin/config.go):
`ADMIN_LISTEN_ADDRESS` is `127.0.0.1:8096` and `ADMIN_PUBLIC_PATH` is `/trader`. So the signals page
is <http://127.0.0.1:8096/trader> and **Devices / feed access** is
<http://127.0.0.1:8096/trader/devices>, linked from the signals page.

Log in with the name you hashed (`trader` above) and its password. `ADMIN_SESSION_SECRET` and
`PUBLISHER_API_TOKEN` must each be at least 32 characters, and both may instead be `…_FILE` paths.
Every mutation POST is checked same-origin
([`internal/admin/server.go`](../../examples/demo-signals/internal/admin/server.go)) and rate limited per
signed-in name, so drive the page from a browser rather than a bare `curl`.

## 5. The happy path

1. **Add the feed.** On the phone: **Add connection** → **Pairing code**, paste the reference the
   publisher printed, **Continue**. The confirmation for a restricted feed reads *"This feed is
   restricted. After it is added, the publisher asks you to prove you control your wallet — by
   signing a message, not a transaction, which moves no funds — and then decides whether this
   device may read it."* Tap **Add feed**.
2. **The signature.** Adding a restricted feed asks for access straight away
   ([`ConnectionsViewModel.confirmFeed`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/connections/ConnectionsViewModel.kt)):
   the phone generates a P-256 Keystore key for this connection, fetches a challenge, rebuilds the
   text, compares it, and only then opens the wallet — once, showing the text in §1. The answer is
   verified against the address before anything is sent.
3. **Pending.** The connection's status line says *"Waiting for the publisher to approve this
   device."* It is not shown as a problem: it is the flow working.
4. **The request appears.** Reload
   [`/trader/devices`](../../examples/demo-signals/internal/admin/devices.go). The row shows the wallet,
   the device-key fingerprint (`installation`), the label the phone claimed — marked *"label
   (user-supplied)"*, because it is a claim — the request time, and **Pending approval**.
5. **Approve.** The page answers *"approved; the device receives a single-use invitation"*. The row
   becomes *"Approved — waiting for the device to redeem its invitation"* and shows the invitation
   as a link, single use, for that device only, until its expiry. Showing it is safe: on any other
   phone it does nothing.
6. **The phone redeems.** There is **no background poll for a decision**: the phone checks when the
   owner opens that connection's detail sheet or taps **Refresh** on it
   ([`SeekerVaultApp.kt`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/SeekerVaultApp.kt)).
   A feed with no request yet *asks*, which opens the wallet; one that already has a request only
   *checks*, signed with the device key, and a check that finds an invitation redeems it.
7. **Connected.** The row moves to `grant_pending` — *"Approved — the gateway has not confirmed the
   grant yet"* — and then, once the syncer lands it, to *"Connected — the gateway admits this device
   until &lt;expiry&gt;, renewed while approved"*. The phone's status line stops talking about access
   and says whatever the ordinary feed status says.
8. **A signal.** Create one on the signals page, or with `go run ./cmd/publishctl create` — the
   full swap invocation, with every `--term` it needs, is in
   [demos.md](../development/demos.md#running-them), and `PUBLISHER_API_URL` and
   `PUBLISHER_API_TOKEN` address it. It should reach that phone and no other. **(Not observed.)**

## 6. The denied path

Reject a pending request on the Devices page. The page answers *"rejected; the device receives no
grant"* and the row reads **Rejected**. No invitation is issued and no grant is ever recorded.

On the phone the status line becomes *"The publisher didn't approve this device. Ask again to make a
new request."* — and **Refresh** on that connection is the "ask again": a rejected or revoked record
is the one case where asking again starts a fresh request instead of a check, which opens the wallet
once more
([`FeedAccessManager.requestAccess`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/access/FeedAccessManager.kt)).
Rejected is treated as a problem on the connection row, because it needs the owner to do something.

## 7. A second device on the same wallet

Add the same feed on a second phone with the same wallet. The device key is per feed connection and
lives in that phone's Keystore, so the second phone is a second `installation` and a second row, and
approving one does not approve the other — the Devices page says so above the table. The gateway is
told one opaque `subscriber_ref` per wallet and one `device_ref` per device, so the two rows look
related in the operator's view without the gateway ever learning what they have in common.

## 8. Revoking an approved device, with its stream open

Press **Revoke device** while the phone is connected and, ideally, streaming.

**On the publisher's page.** The row does *not* jump to revoked. It reads *"Revocation pending at
the gateway — this device can still read until the gateway confirms"*, with the attempt count and
the last error while it is there, because until the gateway holds it the device really can still
read. Once the syncer's `RevokeAccess` lands, the row becomes *"Revoked — the gateway confirmed"*.

**At the gateway.** `RevokeAccess` ends the grants and moves the channel's **access epoch** in the
same write. A restricted channel's broker stream name carries that epoch, so publications after the
revocation go out under a new name and anything still attached to the old one receives nothing more.
The retired name gets one last, field-less `AccessChanged` event.

**On the phone.** A listener that sees `AccessChanged` spends its ticket whatever the broker said
about the close, reads the feed once to find out, and is refused — and the refusal is what records
the revocation
([`ForegroundFeedManager`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/feeds/ForegroundFeedManager.kt)).
The status line becomes *"The publisher revoked this device's access to the feed. Signals already on
this phone stay."* The other feeds on that stream come back on the next pass. Without a broker the
same revocation lands on the next read instead — the same enforcement, arriving less promptly.
**(Not observed — the stream half needs the broker from §1.)**

**Wallet-wide.** **Revoke all devices of this wallet** posts to
`/v1/access/wallets/{wallet}/revoke` and the page answers *"revoked N device(s) of that wallet; the
gateway is being told"*. Each device's row then goes through its own `revocation_pending`. What
revocation does *not* do is reach back: it stops future delivery, reads, reconnects and renewals,
and a signal a phone already read stays on that phone — which is what the copy on screen says.

## 9. Missed or disabled push

A restricted channel is **always absent** from `GetFeedTopics` — it has no public topic. Each
approved device instead registers its own push target with `SetFeedPushTarget`, under the session
its grant was issued with, and the gateway sends that grant's hints to it
([`internal/relay/restricted.go`](../../services/gateway/internal/relay/restricted.go)). A Firebase
registration re-states this device's target under every live grant
([`SeekerVaultMessagingService`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/push/SeekerVaultMessagingService.kt)).

To simulate a missed hint, pick whichever is easiest: run a debug build with no
`apps/android/app/google-services.json`, so Firebase fails to initialize, there is no background push and
no target is ever registered ([firebase.md](firebase.md)); turn the app's notifications off, or use
airplane mode across the publication or the revocation; or leave `BROADCAST_PUSH_CREDENTIALS`,
`BROADCAST_PUSH_ENDPOINT` and `BROADCAST_PUSH_ENVIRONMENT` unset on the gateway, which is the local
default. Recovery should then look like this, and none of it depends on the hint:

| What was missed | How it comes back |
| --- | --- |
| A signal | The periodic background feed pass (`BackgroundSyncWorker`, every 15 minutes), the next foreground pass, or opening the app |
| A revocation | The next read of the feed is refused, and the refusal is what records it. The gateway already stopped admitting the device when the revocation committed |
| A grant expiring | The next read is refused as expired, and the status line says *"This device's access to the feed has run out. Check again to renew it."* |

**A queued hint confers no authority.** It is content-free, every read it prompts is checked again,
and one that was never delivered changes nothing about whether access ended.

## 10. Reissuing, superseding and expiry

An invitation is single use, bound to the device key, and lives for
`PUBLISHER_ACCESS_INVITATION_MINUTES` (five by default). When it runs out unused, the row reads
*"Approved — the invitation expired before the device used it; reissue it"*.

**Reissue invitation** appears only for a device that is approved right now and has neither a live
invitation nor a live grant
([`Device.Reissuable`](../../examples/demo-signals/internal/admin/devices.go)). It answers *"a fresh
invitation was issued; the previous one no longer works"* — the replaced token is refused by name as
`invitation_superseded`. Reissuing is refused for anything that is not approved, so it can never
undo a revocation; the refusal is `wrong_state`.

Two ways for the device to use one. **It redeems it itself:** open the connection on the phone, or
tap **Refresh** — a check redeems a waiting invitation. **Or the link:** the page shows
`seekervault://feed?v=1&gateway=…&server=…&access=restricted&invitation=<token>`
([`Service.Link`](../../packages/publisher-support/access/access.go)), which is for a phone **adding the
feed** — adding a restricted feed from a reference carrying an invitation asks for access and
redeems the invitation straight after. A phone that already holds the connection uses Refresh
instead. **(Not observed.)**

Grants are a separate clock. One runs for `PUBLISHER_ACCESS_GRANT_HOURS` and is renewed when a third
of it is left; the gateway caps any at `BROADCAST_MAX_GRANT_HOURS` and states the bound it enforces
rather than surprising the publisher. Six hours is therefore the documented bound on how long access
outlives the publisher's reach, and a tighter one costs renewal traffic.

## 11. When it does not work

Publisher refusals are a stable code and a status, from
[`packages/publisher-support/access/access.go`](../../packages/publisher-support/access/access.go):

| Code | Status | Means |
| --- | --- | --- |
| `unknown_feed` | 404 | *"this service decides access to another feed"* — the channel does not match this publisher |
| `bad_wallet`, `bad_device_key`, `bad_label` | 400 | The wallet is not a base58 Solana address, or the device key or claimed label is malformed |
| `unknown_challenge` | 404 | *"no challenge of that attempt was issued"* |
| `challenge_used` | 409 | *"that challenge was already answered; ask for another"*. A failed verification spends the challenge too |
| `challenge_expired` | 410 | *"that challenge expired; ask for another"* — five minutes by default |
| `bad_signature` | 401 | The wallet's or the device key's signature did not verify |
| `stale_proof` | 401 | *"the signed moment is too far from this server's clock"* — the five-minute skew. Check the phone's clock |
| `unknown_request`, `unknown_invitation` | 404 | No request of that ID, or no invitation of that token *for this device* — including a token copied to another phone |
| `invitation_used`, `invitation_superseded` | 409 | Already redeemed, or replaced by a reissue |
| `invitation_expired` | 410 | It ran out before the device used it; reissue |
| `not_approved` | 403 | *"this device is not approved for this feed"*. Approval is re-checked inside the redemption, so a device revoked in the meantime lands here |
| `wrong_state` | 409 | *"that request is not in a state that allows this"* — e.g. reissuing for a device that is not approved |
| `access_unconfirmed` | — | The publisher's own guard: the gateway has not confirmed it enforces this feed as restricted. Nothing publishes until it does (§2) |

Gateway refusals arrive as Connect problems. The eight of them are tabulated in
[`../wiki/restricted-feeds.md`](../wiki/restricted-feeds.md#what-the-gateway-enforces); the two an
operator causes are `ACCESS_MISMATCH` — the manifest claims a policy or origin the operator did not
register, usually `PUBLISHER_AUTH_ORIGIN` not matching `--auth-origin` character for character — and
`NOT_RESTRICTED`, a grant call from a publisher whose feed is registered public.

What the phone says when asking stopped before the publisher had anything to decide
(`strings_connections.xml`); each of these means nothing was sent and nothing changed:

| Copy | Means |
| --- | --- |
| *"Connect a wallet first. A restricted feed grants access to a wallet, not to a phone."* | No wallet selected — the emulator case |
| *"The wallet didn't sign, so nothing was sent."* | The owner declined, or the signature did not verify against the address |
| *"The publisher asked this phone to sign something it didn't recognise, so nothing was signed."* | The rebuilt text or a challenge field did not match, or the challenge claimed to last over thirty minutes |
| *"Couldn't reach the publisher. Nothing changed — try again."* | The authentication origin did not answer. The client follows no redirects |
| *"The publisher refused the request."* | One of the codes in the first table |
| *"This feed reference asks for an access policy this version of the app doesn't know…"* | An `access=` value this build does not know, refused rather than read as public |

Two more: a debug build accepts plain HTTP only to `127.0.0.1`, over `adb reverse`, so a local auth
origin must be reachable at exactly that address from the device; and a feed added from a reference
saying `access=restricted` whose manifest then says public is refused outright, because the
reference is a floor.

## 12. Against the deployment

The deployed signals demo is `https://signals-demo-fzs2q.ondigitalocean.app`, with the trader UI at
`/trader` and therefore the Devices page at `/trader/devices`; the admin login is `DEMO_ADMIN_USER`
and `DEMO_ADMIN_PWD` from `~/.env` ([emulator-e2e.md](../development/emulator-e2e.md)).
[`do-deploy/apps/signals-demo.yaml`](https://github.com/SeekerAgentConnect/do-deploy/blob/main/apps/signals-demo.yaml) sets
`PUBLISHER_AUTH_ORIGIN=https://signals-demo-fzs2q.ondigitalocean.app`, with `/access/v1` served
beside the API on the one public port and `/trader` routed to `copytrading-admin` by the ingress.

Two things to check before expecting any of §5 to work there, rather than assuming. **The gateway's
registration:** the feed must be registered `restricted` at exactly that origin — from the
gateway's admin page at `https://seeker-gateway-sg8g3.ondigitalocean.app/admin` (§2, SEE-162), or
with `feed-gatewayctl access` in the gateway component's App Platform console, where
`BROADCAST_DATABASE_URL` is already set. Whether it has
been done is recorded nowhere in this repository, and until it is, the publisher publishes nothing
and says `access_unconfirmed`. **The image:**
[`do-deploy/apps/signals-demo.yaml`](https://github.com/SeekerAgentConnect/do-deploy/blob/main/apps/signals-demo.yaml) pins `copytrading-0.1.5`, and the
deployed servers are known to lag the repository
([emulator-e2e.md](../development/emulator-e2e.md) § Known issues), so confirm the running image
contains SEE-156 before reading a failure as a bug.

For Compose, [`compose/copytrading/compose.yaml`](https://github.com/SeekerAgentConnect/do-deploy/blob/main/compose/copytrading/compose.yaml) in
`do-deploy` now requires `PUBLISHER_AUTH_ORIGIN` — the service refuses to start without it — and passes
`PUBLISHER_ACCESS_GRANT_HOURS` and `PUBLISHER_ACCESS_INVITATION_MINUTES` through from
`compose/copytrading/.env`. The admin UI stays an opt-in host-loopback profile and is never placed on
the public feed ingress.

## Where to look next

Why it is shaped this way: [`../wiki/restricted-feeds.md`](../wiki/restricted-feeds.md). The demo's
own settings, one paragraph per variable:
[`examples/demo-signals/.env.example`](../../examples/demo-signals/.env.example) and
[`examples/demo-signals/README.md`](../../examples/demo-signals/README.md). Running the demos generally:
[`../development/demos.md`](../development/demos.md). The gateway and `feed-gatewayctl`:
[`../development/feed-gateway.md`](../development/feed-gateway.md) and
[`../wiki/feed-gateway.md`](../wiki/feed-gateway.md). Driving a phone with nobody at it:
[`../development/emulator-e2e.md`](../development/emulator-e2e.md). The API a signal is published
through: [`../integrations/signal-api.md`](../integrations/signal-api.md).
