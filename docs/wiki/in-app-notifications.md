# In-app notifications

SEE-147 gives the app a foreground banner for the two things a system notification is posted for:
something newly waiting for the owner, and a paired server that ended the pairing. While the app is
being looked at, the owner is told inside it; while it is not, the system notification does the
telling, exactly as before.

## What the owner sees

| Kind | When | Container | Icon | Title | Sub-line |
| --- | --- | --- | --- | --- | --- |
| `Request` | A paired server sent a request | primary | `notifications_active` | the request's own words | `New request · {server} · {detail}` |
| `Signal` | A feed published a proposal | primary | `sensors` | the signal's own words | `New signal · {source} · {detail}` |
| `Disconnected` | A paired server ended the pairing | tertiary | `link_off` | `{server} disconnected` | none |
| `Request` / `Signal` (several) | Several arrived together (SEE-175) | primary | as above | `{n} new requests`, `{n} new signals` or `{n} new items to review` | `From {server} · Open Inbox to review` or `From {k} connections · Open Inbox to review` |

The words are not new. They come out of `requestNotificationCopy` and `proposalNotificationCopy` —
the very functions `notifications/NotificationAppearance.kt` builds a system notification from — so
the two surfaces cannot say different things about the same event, and the banner inherits the
privacy rule they were written under: the validated action kind and the owner's own local name for
the server, never the request's body, note, bytes or identifiers.

## Service messages

Everything else the app has to say in passing — a connection added, paired, renamed or removed; why
a restricted feed's access request stopped short; the publisher approving, rejecting or revoking
this device; rules saved; an explorer link that would not open — is a *service message*. A screen
posts the text to `LocalInAppNotices` (`notifications/InAppNotices.kt`) and the host shows it as an
`Info` banner: the same shape at the top, on the theme's neutral `surface3` with `onSurface` ink, a
title of up to three lines and no subtitle. It waits its turn behind any request, signal or
disconnection banner, stays six seconds, and a tap or a swipe only dismisses it. None of these
screens has a snackbar any more.

## Where each half lives

`:designsystem`'s `InAppNotification` owns one banner on screen: its looks, the drag under the
finger and the motion that brings it in and takes it out. It is the Home wallet banner's shape with
a shadow, and `design/components/notification/spec.md` holds its measurements and its gesture
contract.

The design system is also the only place that *can* own it. `StageBoundaryTest`'s
`theV4PresentationUsesOnlySolidOpaqueLayers` rejects `shadow(`, `graphicsLayer`, `copy(alpha` and a
translucent colour anywhere under `apps/android/app/src/main`, and a banner that floats over the content
and fades under a finger needs all of them.

`:app`'s `notifications/` owns everything about *which* banner is visible:

- `InAppNotificationQueue` — one banner at a time, later events FIFO behind it. Requests and
  signals are coordinated rather than queued one per item (see
  [Bursts and backlogs](#bursts-and-backlogs-see-175)). A request or signal
  is armed for six seconds when it reaches the front, never when it arrives, so a banner queued
  behind a long-lived one still gets its whole six seconds once it is seen. A disconnected banner is
  never armed at all: it holds the queue until the owner taps or swipes it. A dismissed note stays
  in the queue, marked `leaving`, for the 200ms its exit takes.
- `InAppNotificationSource` — successive snapshots into arrivals: what is new to the session *and*
  marked live by `ArrivalLedger`.
- `ArrivalLedger` — app-scoped; the repositories mark which waiting items reached the phone as news.
- `InAppNotifications` — the composable that joins the two, resolves the words, and renders the
  head of the queue. `SeekerVaultApp` mounts it as the last child of the root `Box` at a `zIndex`
  above `SeekerSheet`'s `10f + index`, so the banner is over the content and over the whole sheet
  stack. It intercepts touches on itself and nowhere else: the banner consumes its own pointer down
  so a press never reaches a sheet underneath, and the rest of the screen is untouched.

## How an arrival is noticed

Nothing here decides that something arrived. The banner reads the same two lists the Home screen
and the inbox count are drawn from — `pendingItems(...)` over the inbox and the reviewable
proposals, and the connections — and announces what appeared in them since the last look. That is
the same set difference `sync/PushSynchronization` and `sync/FeedSynchronization` already use to
decide what deserves a system notification.

Reading the shown state rather than a second event stream is what makes *"the inbox count goes up at
the moment the banner appears"* true by construction: there is one list, and both are derived from
it. A disconnection is the same story. `ConnectionRepository.markRevoked` stamps `revokedAt`,
deletes the credential, settles the connection's waiting results as undeliverable and drops its
pending requests in one locked write; the banner is raised off that same `revokedAt`, so the server
row already says "pair again to reconnect" and the cancelled requests are already gone by the time
the owner reads the banner.

`markRevoked` is also the reason "disconnected" means what the specification says it means. It is
reached only when the server refuses the phone's credential (`GatewayException.Kind.Unauthenticated`)
or when a sync stream says the pairing is revoked. A sidecar that is merely unreachable takes the
`recordFailure` path and becomes `CheckOutcome.Unreachable` or a reconnecting stream, and raises no
banner: a dropped Wi-Fi connection is not a server ending a pairing.

## Foreground, background, and coming back

Collection runs inside `repeatOnLifecycle(STARTED)`, which is the app's own definition of foreground
— the same boundary `MainActivity.onStart`/`onStop` uses to start and stop the foreground streams.

- Leaving the foreground empties the queue and cancels its timers, so a banner never outlives the
  screen it was on.
- The first *ready* snapshot after coming back is a baseline and announces nothing. Everything that
  arrived while the app was away was the system notification's to tell, and is not replayed as a
  burst of banners.
- What arrives after the owner is back is a banner again.

Ready is the whole of what makes a cold start silent, and it is more than the stored connections
having been read. The stored connections carry no pending requests and no proposals with them: the
inbox is filled by the fetch the app makes when it opens, and the proposals by
`ProposalRepository.load()`. A baseline taken at `ConnectionsUiState.loaded` would therefore be
empty, and everything those two reads brought back — an inbox the owner has been carrying for days —
would look like it had just arrived. So the banner waits for all three:

| Signal | Means |
| --- | --- |
| `ConnectionsUiState.loaded` | the stored connections have been read |
| `ConnectionsUiState.fetched` | the fetch that follows that read has settled for every usable connection |
| `OperationsUiState.loaded` | `ProposalRepository.load()` has read the stored proposals |

`fetched` implies `loaded`, so `SeekerVaultApp` passes `state.fetched && (operations == null ||
operationsState.loaded)`. A build with no operations holds no proposals, so there is nothing there
to wait for. `OperationsUiState.loaded` is the repository's own flag rather than "the combine has
emitted", for exactly the same reason: its first emission happens before the store has been read.

## Bursts and backlogs (SEE-175)

Set difference alone announced everything that appeared, and a lot appears that is not news:
connecting a feed reads its whole backlog, coming back to the app reads what changed while it was
away, and a burst of publications is many items at once. Two things stop that.

### Only news is announced

`notifications/ArrivalLedger` is written by the repositories **before** they publish, so a mark is
always visible no later than the item it is about:

| Delivery | Producer | News? |
| --- | --- | --- |
| Feed stream event (`FeedStreamEvent.Published`, not replayed) | `ProposalRepository.apply(…, Live)` | always, when it creates a proposal |
| Feed history replayed when the stream opens (`replayed = true`) | `apply(…, Replayed)` | only if the feed was already read whole in this session |
| Feed snapshot (`ProposalRepository.refresh`: stream open, push hint, manual, add-feed) | refresh | only if the feed was already read whole in this session (decided before the walk) |
| Direct stream event (`SynchronizationRepository.applyEvent`) | `applyCache(…, SyncDelivery.Event)` | always, when it creates a pending request |
| Direct snapshot (`synchronize`) | `applyCache(…, SyncDelivery.Snapshot(live))` | requests created by events buffered during the read; everything else only if already read this session |
| Legacy fetch (`ConnectionRepository.fetch`) | fetch | only if already read this session |
| Sync cache loaded from disk | `applyCache(…, SyncDelivery.Cached)` | never |

"Created" means the phone did not hold it: a repeated delivery, a new revision or a status update is
never an arrival. `MainActivity.onStop` (a real one, not a rotation) calls
`ArrivalLedger.onBackground()`, which forgets which connections were read and every mark, so the
first read after coming back is catching up again. There is no timer anywhere in this: a live item
published during a feed's first snapshot arrives on the stream, or in the next read, and is marked
either way.

`InAppNotificationSource` keeps what it has seen for the whole foreground session, not just the last
look, so an item that leaves the list and returns is not announced twice; items that appear unmarked
become known silently. The inbox, its counts and unread state are untouched by all of this.

### One banner per burst, with limits

`InAppNotificationQueue.arrive` takes every request and signal that arrived in one look:

- There is at most **one** incoming banner, visible or waiting. Arrivals merge into it, counted by
  unique `ReviewIdentity` (connection, namespace and ID, so two servers' IDs cannot collide). One
  item keeps its own words and opens its review; several become the aggregate row above and open the
  Inbox (`InAppNotificationTarget.Inbox` → Inbox tab, Pending).
- Its lifetime is `IncomingNotificationPolicy.LIFETIME_MS` (6 s) from when it becomes visible and is
  **never extended** by merging.
- When it leaves — timed out, swiped or tapped — a cooldown of `COOLDOWN_MS` (30 s) starts. What
  arrives meanwhile is held and shown as **one** banner when it ends; what was answered or opened in
  the meantime is dropped from it (`retainWaiting`). So under sustained traffic at most one incoming
  banner appears per `6 s + 200 ms exit + 30 s` ≈ 36 s, and dismissing one never brings the same
  burst back immediately.
- At most `MOST_COUNTED` (99) items are counted or held; beyond that the banner says "99+".
- Service messages and disconnections are never throttled, never merged, and are queued ahead of an
  incoming banner nobody has seen yet.

`IncomingNotificationQueueTest` pins these values and drives them under virtual time, including 90
seconds of ten requests a second over three servers.

## Suppression and routing

A request or signal whose review is already on screen raises no banner — the owner is looking at the
thing it would announce. The check is asked afresh each time, against `AppNavigator`'s live sheet
stack, and a disconnection is never suppressed.

A tap dismisses the banner and then opens what it is about, by the same route a system
notification's tap takes: back to a base destination the flow graph allows the destination to be
pushed from, then push it. A request or signal opens its review; a disconnection opens Add
connection, because pairing again is the only way back; a banner for several opens the Inbox.

## Accessibility

The banner is one node with the button role, labelled `"{title}, open request"`,
`"{title}, open signal"` or `"{server} disconnected, open"`. Its arrival is announced politely, and
a custom action named "Dismiss" stands in for a swipe nobody using TalkBack is going to perform.
