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

The words are not new. They come out of `requestNotificationCopy` and `proposalNotificationCopy` —
the very functions `notifications/NotificationAppearance.kt` builds a system notification from — so
the two surfaces cannot say different things about the same event, and the banner inherits the
privacy rule they were written under: the validated action kind and the owner's own local name for
the server, never the request's body, note, bytes or identifiers.

## Where each half lives

`:designsystem`'s `InAppNotification` owns one banner on screen: its looks, the drag under the
finger and the motion that brings it in and takes it out. It is the Home wallet banner's shape with
a shadow, and `design/components/notification/spec.md` holds its measurements and its gesture
contract.

The design system is also the only place that *can* own it. `StageBoundaryTest`'s
`theV4PresentationUsesOnlySolidOpaqueLayers` rejects `shadow(`, `graphicsLayer`, `copy(alpha` and a
translucent colour anywhere under `android/app/src/main`, and a banner that floats over the content
and fades under a finger needs all of them.

`:app`'s `notifications/` owns everything about *which* banner is visible:

- `InAppNotificationQueue` — one banner at a time, later events FIFO behind it. A request or signal
  is armed for six seconds when it reaches the front, never when it arrives, so a banner queued
  behind a long-lived one still gets its whole six seconds once it is seen. A disconnected banner is
  never armed at all: it holds the queue until the owner taps or swipes it. A dismissed note stays
  in the queue, marked `leaving`, for the 200ms its exit takes.
- `InAppNotificationSource` — successive snapshots into arrivals, by set difference.
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

## Suppression and routing

A request or signal whose review is already on screen raises no banner — the owner is looking at the
thing it would announce. The check is asked afresh each time, against `AppNavigator`'s live sheet
stack, and a disconnection is never suppressed.

A tap dismisses the banner and then opens what it is about, by the same route a system
notification's tap takes: back to a base destination the flow graph allows the destination to be
pushed from, then push it. A request or signal opens its review; a disconnection opens Add
connection, because pairing again is the only way back.

## Accessibility

The banner is one node with the button role, labelled `"{title}, open request"`,
`"{title}, open signal"` or `"{server} disconnected, open"`. Its arrival is announced politely, and
a custom action named "Dismiss" stands in for a swipe nobody using TalkBack is going to perform.
