# Whether a feed is online (SEE-150)

A phone showed a shared feed as **Connected** because the gateway answered. That was true, and it was
the wrong question.

The gateway keeps serving what a publisher last published after that publisher's own server has
stopped — a process ending does not withdraw anything, and it should not. So an owner watched a feed
that had been dead for hours, saw "Connected · 3 pending", and had no way to tell that nothing new
would ever arrive on it. Nothing failed anywhere. It took a person looking at a phone to notice.

Reaching a gateway is evidence about a gateway. This page is about the second answer: whether the
server behind a channel is running. The two are now asked for separately, shown separately, and
neither is ever derived from the other.

## The shape of it

```
publisher's server ──Heartbeat──▶ gateway ◀──GetFeedStatus── phone
   (or any publication)          last_seen_at
```

Three facts, and each one is load-bearing:

1. **The gateway never contacts a publisher.** Not here and nowhere else — a publisher's `host` is
   administrative metadata it supplied, and a gateway that connected to one would be a gateway a
   publisher could aim at somebody else. A boundary test
   ([`TestOnlyTheBrokerAndRelayCallOut`](../../services/gateway/internal/gateway/boundary_test.go)) holds
   it.
2. **So presence is pushed.** Every authenticated call on the publisher API is a check-in, because a
   publisher that is publishing is manifestly running. `PublisherService.Heartbeat` exists for the
   publisher that has nothing to publish, which is most publishers most of the time.
3. **The phone reads it from the gateway**, unauthenticated, like every other feed read. It has no
   address for a publisher and should not have one.

## What the gateway answers

`FeedService.GetFeedStatus(channels)` → one `FeedStatus { channel, availability }` per channel this
gateway hosts.

| Availability | What it means |
| --- | --- |
| `ONLINE` | The publisher checked in within the window |
| `OFFLINE` | The gateway hosts this feed, and its publisher has not checked in within the window — or never has. What it last published is still served |
| `UNSPECIFIED` | **Never sent.** It exists so a client reading a value it does not understand lands on "unknown" rather than on "online" |

The window is **three times** `BROADCAST_HEARTBEAT_SECONDS` (default 30s, so 90s). Three rather than
one because a check-in is one HTTP request over somebody else's network, and a single lost one must
not flip a running feed to offline on every phone reading it. It is derived rather than configured
beside the interval, so no deployment can be set up with a window shorter than the interval it asks
for — which would show every publisher offline for ever and look exactly like a feature nobody
configured.

Two things are deliberately *not* refusals, on the same terms a stream ticket and a topic list are
not:

- **A channel this gateway does not host is absent from the answer**, not fatal. One stale feed
  reference on a phone must not cost that phone the truth about its other feeds.
- **An answer naming no online feed is still an answer.** Unlike a topic list nobody could subscribe
  to, there is nothing to fail: the phone reads the channels it was told about and treats the rest as
  unknown.

What is refused is the request: no channels, more than 32, or a name that is not `server/<uuid>`.

## What it says about a reader: nothing

A presence read is anonymous, writes nothing, and answers the same for everyone who asks —
`TestAskingWhetherAFeedIsOnlineIsNotRecordedAnywhere` reads the database before and after three calls
and compares. The check-in is written on the *publisher* listener, where every caller has already
resolved to exactly one registered server, so there is nowhere for a reader's identity to enter.

The contract is pinned field by field
([`TestTheContractIsBoundedAndSaysNothingAboutAnyone`](../../services/gateway/internal/gateway/boundary_test.go)):
the request carries channels, the answer carries a channel and a verdict, and the verdict is an enum
with three named values rather than a free field. `HeartbeatRequest` has **no field at all** — the
credential says who is calling — so no check-in can tell a gateway where to reach anybody.

Presence is therefore public in the same sense a feed's documents are. A publisher that would rather
not say whether it is running simply does not call `Heartbeat`, and its feed reads as offline.

## Storage

One nullable column, `publisher.last_seen_at_ms`, on the registration it is a fact about — SQLite
version 6, Postgres version 2. Nullable rather than `NOT NULL DEFAULT 0` because "has never checked
in" and "checked in at the epoch" are different facts: both read as offline today, but only one of
them is true, and a registration made before this version has told the gateway nothing.

The write only ever moves forward (`WHERE last_seen_at_ms IS NULL OR last_seen_at_ms < ?`), so two
racing calls or a clock that went backwards across a restart cannot make a server that is known to be
running look older than that. A check-in for a `server_id` nothing is registered under changes no row
and is not an error: the only way to reach it is with a credential that resolved a moment ago, so the
case is a registration deleted in between, and there is nothing to record about one that is gone.

## The publisher's side

Nothing, if it publishes regularly. For the quiet publisher,
[`publish.Presence`](../../packages/publisher-support/publish/presence.go) is the loop, and both demos run it
beside their drainer:

```go
go publish.NewPresence(publish.PresencePlan{Gateway: gateway, Log: log}).Run(ctx)
```

The first check-in is immediate — a publisher that has just started is running, and an owner opening
the app should not see its feed as offline for the length of one interval. After that it calls on the
interval **the gateway named**, not one the template chose: a publisher checking in on its own
schedule is a publisher shown offline while it is running.

Three kinds of answer, and only one of them ends the loop:

- **`unimplemented`** — a gateway older than SEE-150. Its phones show the feed as unknown, which is
  what they do for anything they cannot read, so nothing is wrong. It is **retried anyway**, on
  `publish.UnsupportedBackoff` — doubling from a minute to an hour — because the gateway is the half
  of the pair an operator upgrades first (SEE-155). A publisher that gave up on the old gateway was
  shown offline for the rest of its process's life once the new one arrived, and publications only
  hid that between them. The loop logs once when an episode starts and once when check-ins resume,
  and then follows the interval the upgraded gateway names.
- **any other permanent refusal** — the credential is not a credential for this server, which an
  operator has to fix. The drainer already says so about publishing; a second voice on a timer adds
  nothing, and retrying a rejected credential is how an operator gets locked out. This one still
  stops the loop for good.
- **everything else** — the gateway, a proxy or the network, retried on `gateway.Backoff`.

## The phone's side

[`FeedStatusManager`](../../apps/android/app/src/main/java/io/github/brrenat/seekervault/feeds/FeedStatusManager.kt)
polls while the app is being looked at — one pass over every gateway per 30 seconds — and publishes
`FeedStatusState`, keyed by connection id.

A gateway answers at most 32 channels per request, so a pass reads each gateway's channels in
batches of at most 32 (SEE-155). Before that, one request carried every channel, and an owner's
thirty-third feed on a gateway made that gateway refuse every read — leaving all of its feeds stale,
on every poll. The batches are split, never truncated. A batch that fails keeps only its own feeds as
they were; the other batches, and the other gateways, still land.

A **manual refresh** of a feed — opening its connection screen, or its refresh button — also asks for
a presence read at once, through `ConnectionsViewModel.refresh`, so an owner whose publisher has just
come back is not told "Feed offline" until the next poll (SEE-155). It does not move the periodic
timer. Because that allows two reads of one gateway in flight at once, each read is stamped when it
starts and an answer for a channel is published only if no newer read has answered for that channel
first. The stamp is per channel, not per gateway, because a read does not succeed or fail as a whole:
a newer read answered for thirty-two channels and refused for the thirty-third has heard nothing about
that one, so the older read that did hear about it is still the best thing known and publishes it.
Claiming a channel and writing it happen under one lock, because the periodic read and the refresh are
two threads and not two turns of one loop: checking the stamp and then writing outside the lock let
the older read write after the newer one had published. A feed the owner removed while a read was out
is not written back in by its answer. Opening the app fetches
every connection without also asking presence once per feed: the manager already reads on
foregrounding.

It sits **beside** `ForegroundFeedManager` rather than inside it, because that one owns whether this
phone reaches a gateway and this one owns whether a publisher is up. Keeping them apart keeps the
failure modes apart too:

| What happened | What the owner is shown |
| --- | --- |
| Gateway reachable, publisher checked in | "Connected · N pending" |
| Gateway reachable, publisher stopped | "Feed offline · N pending", and the connection screen says the published signals are still there |
| Gateway unreachable | The gateway's own line. Nothing is claimed about the publisher — the phone has been told nothing about it |
| Gateway too old to answer presence | "Connected · N pending". Unknown is not bad news |

A poll rather than a push because there is nothing to push: presence is a fact the gateway holds and
revises *by not being told anything*, so it has no event, and inventing one would mean a second stream
for a line of text. Foreground-only because a phone nobody is holding has no use for it — what it
needs on return is the current answer, which is the first thing the manager asks for. Answers are kept
across the gap rather than cleared, so a stopped feed does not read as unknown every time the app is
reopened.

Two mappings carry the whole point of the feature:

- **An availability this build cannot read is `Unknown`, never `Online`** — the unspecified zero, or a
  value the contract gains later.
- **A gateway that fails leaves its feeds as they were** rather than marking them offline. Claiming
  otherwise would be the same conflation from the other direction.

The row stays `Connected` when a publisher is offline, and that is deliberate: this phone's own
connection is in order, the signals already published are readable and reviewable, and there is
nothing for the owner to retry. What changes is the sentence, which now says which of the two things is
down.

## Tests

| Where | What it holds |
| --- | --- |
| `services/gateway/internal/gateway/presence_test.go` | The reported bug (gateway up, publisher stopped, phone told); coming back; one offline feed leaving the others alone; every publication counting as a check-in; never-checked-in being offline rather than unknown; an unhosted channel absent; the refusals; a deployment with no broker and no relay still answering; the read writing nothing; surviving two missed check-ins; a backwards check-in; an anonymous caller unable to say a feed is online |
| `services/gateway/internal/storage/sqlite/store_test.go` | The version 5 → 6 migration keeping every row and answering "never"; forward-only writes; a stranger's check-in tolerated |
| `services/gateway/internal/gateway/boundary_test.go` | The pinned field lists and both new procedures being 404 on the other listener |
| `packages/publisher-support/publish/presence_test.go` | The gateway naming the interval; the default when it names none; `unimplemented` retried on the slow backoff with one log line; an upgraded gateway resuming check-ins in the same process on its own interval; a shutdown during the long wait; the unsupported backoff's bounds; a permanent refusal stopping it; an unreachable gateway retried on the backoff; a cancelled context ending it |
| `apps/android/…/feeds/ConnectFeedStatusTest.kt` | The request carrying the channels and nothing else; each verdict mapped; an unreadable availability being unknown; an unasked channel refused whole; `unimplemented` and unreachable told apart |
| `apps/android/…/feeds/FeedStatusManagerTest.kt` | The first read immediate; each feed its own answer; a feed returning on the next pass; a failing gateway not making its feeds offline; one gateway's failure not stopping the others; feeds added and removed; nothing asked in the background; a refresh asking at once; 32, 33 and 65 feeds in batches; a failed batch keeping only its own feeds; an older answer not overwriting a newer one, and a failed newer read not dropping it; a feed removed mid-read not coming back; two refreshes being one read |
| `apps/android/…/connections/FeedPresenceRefreshTest.kt` | The refresh action through the real `ConnectionsViewModel` and manager: a feed's refresh reading presence at once without moving the timer; a direct connection's refresh asking nothing; opening the app not asking once per feed |
| `apps/android/…/connections/ConnectionFeedAvailabilityTest.kt` | The row and the connection screen, including that unknown keeps the ordinary line and an unreachable gateway is said first |

## What this is not

- **Not a health check.** The gateway reports the last thing a publisher said about itself. It does
  not probe, measure, or have an opinion.
- **Not per-subscriber liveness.** There is no record of who asked, and the relay's `seen_at_ms`
  deliberately still never says "online": a binding is a standing permission, not a connection.
- **Not a reason to hide anything.** An offline feed is fully readable; only new publications wait on
  the publisher.
