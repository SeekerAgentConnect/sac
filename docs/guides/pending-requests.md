# Pending requests

What an agent asks of you arrives on the phone as a **pending request**. You review each one and answer it yourself. The app answers nothing for you. While the app is closed, Android may let its bounded periodic worker fetch server state, but that worker cannot approve or open a wallet.

> **Stage 5.3 status:** while the app is open, a configured current sidecar delivers live request and outcome changes through one persistent stream. In the background, one network-constrained WorkManager job periodically uses unary Sync. SAW-056 can additionally send a content-free, best-effort Firebase invalidation; SAW-057 hands it to unique work and omits connections whose foreground stream is already Live. SAW-058 may show a generic request notification after that Sync, subject to Android permission. The hint contains no request details and can be delayed, collapsed, expired, throttled, or dropped. No update or notification-tap path performs an automatic wallet action.

## Where requests come from

- **An agent asks a sidecar,** for example Hermes, or `pnpm agent ack` from the test agent. The sidecar stores the request as PENDING and tells the agent at once that it's waiting. The agent reads your answer later ([`docs/protocol.md`](../protocol.md#agent-api-mcp)).
- **Requests wait on the sidecar while the app is closed.** Each one expires after a day, unless the agent chose another lifetime or the sidecar's `REQUEST_TTL_SECONDS` says otherwise.
- **For now, the only kind is a queued acknowledgement:** a message that you acknowledge or reject. Wallet actions arrive from Stage 3 on.

The phone must be paired with the sidecar first ([`pairing.md`](pairing.md)).

## When the phone fetches

The phone reconciles every connection when the app opens or comes back to the foreground, then keeps one live stream to each usable configured sidecar while the app remains open. A new request or changed result appears on Home, Inbox, Request details, and Activity without opening the screen again or tapping **Refresh**. Rotating the phone and moving between screens keep the same stream. Leaving for the wallet closes foreground streams without canceling the wallet action; returning reconciles stored answers and missed server changes before live delivery resumes.

Connection status says whether live delivery is connecting, live, reconnecting, unreachable, revoked, unsupported, or intentionally paused in the background. **Last synced** is shown separately: an older successful sync does not mean a stream is live. **Refresh** remains available and its existing failure text remains actionable.

With at least one usable connection, Android retains one periodic background job. Its configured interval is 15 minutes, which is Android's minimum—not a promise that every request appears within 15 minutes. Doze, battery restrictions, standby, lack of a network, and device policy can defer a run. Android Settings **Force stop** stops scheduled and push-triggered work until you reopen the app. A configured invalidation may prompt a sooner Sync, but delivery is never guaranteed. After a successful push Sync discovers a new pending request, the configured app may show a generic notification if Android permission and the request channel are enabled. Duplicate hints coalesce, and if a foreground stream is already Live it remains the active path instead of a push worker fetching the same connection again. Open the app or use **Refresh** when you need to force the newest state. Setup and inspection steps are in the [live and background updates runbook](live-background-updates.md); optional Firebase and notification behavior is in the [Firebase guide](firebase.md#notifications-and-tap-to-open-saw-058).

A background run reloads stored connections and encrypted credentials, fetches a bounded unary snapshot, retries an answer you already recorded if needed, and saves the resulting requests, Activity outcomes, and last-sync time. It never prepares a transaction, answers a request, approves, opens the wallet, signs, sends, or repeats a transfer.

Requests remain authoritative on the sidecar. The phone keeps the last complete revisioned view so it survives process death. A complete multi-page refresh replaces that cache only after its final page arrives; if paging is interrupted, the preceding complete view remains. Duplicate or stale updates change nothing, while a cursor gap, conflicting revision, damaged cache, or sidecar restart requests a full snapshot. Removing or revoking a connection removes its cached server state, but Activity remains the owner's record.

## Reviewing a request

Home previews waiting requests in a horizontal carousel. Its first card begins at the left content
edge. As you swipe, cards between the endpoints settle in the centre; the final card settles at the
right content edge. The spacing and ordinary horizontal swipe gesture stay the same. The carousel
only browses requests—tap a card to review it and answer on Request details. If new cards arrive
before the card you are reading, that card stays in place instead of jumping or flashing. A
left-pointing **N new** marker remains inside the carousel until those newly arrived cards have been
scrolled into view.

Prediction cards use the provider's actual question as their title and name the venue in the
footer. They do not add an app-authored “Prediction market” prefix. Under **Paired servers**, a
public feed uses the same **N pending** wording as a direct server, counted from the open signals
currently held for that feed. Live disconnect and reconnect changes appear there without treating
the feed's intentional lack of a private credential as a disconnection.

1. On **Connections**, tap **Pending requests**, the first row. Or open a connection and tap **Pending requests** there for only that connection's.
2. The list has up to three parts:
   - **Waiting for you:** requests you haven't answered. Each shows the connection it came from, the action, how long ago the agent made it, and when it expires.
   - **Waiting to be sent:** your answers that haven't reached the server yet.
   - **Answered:** your answers that the server confirmed.

   A connection the phone couldn't reach is named at the top, with the reason. The list then shows what the phone already had.
3. Tap a request. **Request details** shows:
   - the connection and server it came from
   - the action
   - the message, exactly as the agent sent it
   - the agent's note, separately, because nobody checked it
   - when it was made and when it expires
   - its request ID
4. Tap **Acknowledge** or **Reject**. Both buttons stay disabled while your answer is sent, so a second tap does nothing.

## Opening a notification

Tap a request notification to open the connection and request named by its local route. The app
first fetches current state from that paired sidecar. Until the fetch succeeds, the screen shows no
answer, approval, or wallet controls. If the request expired, was canceled, or was answered
elsewhere, the screen says it is no longer waiting. A removed or revoked connection is named as
such; an unreachable sidecar offers a retry. An answer already stored on this phone opens its
existing result.

The notification is only a reminder. Tapping it never chooses an answer, approves, signs, or opens
a wallet. Its current-state Sync may retry only an answer you already recorded, just like Refresh
or background recovery. Continue with the same manual review described below only after the
current request appears.

## What happens to your answer

- **The phone saves your answer before sending it.** A crash, a dropped connection, or a lost response doesn't lose it.
- **If the server can't be reached,** the answer waits under **Waiting to be sent**. The app sends it again when you refresh, reopen the app, or tap **Send again** on the request. Your answer doesn't change. Sending it twice is safe, because the sidecar recognizes the repeat.
- **Once the sidecar confirms your answer,** the request moves to **Answered**. Opening it again shows your answer instead of the buttons.
- **If the agent cancelled the request, or it expired, before your answer arrived,** the request says so, and your answer has no effect.
- **If the server stopped accepting this phone,** an answer that's still waiting can't be sent. Pair again ([`pairing.md`](pairing.md)).

| Status | Meaning |
| --- | --- |
| Waiting for your answer. | Nobody has answered yet. |
| Sending your answer… | The phone is sending it now. |
| You acknowledged this. The agent can read your answer. | The sidecar has your answer. |
| You rejected this. The agent can read your answer. | The sidecar has your answer. |
| You acknowledged (or rejected) this. Your answer is saved on this phone, and is sent when the server can be reached. | The last attempt failed; the reason is shown under it. |
| The agent cancelled this request before your answer arrived. | Nothing more to do. |
| This request expired before your answer arrived. | Nothing more to do. |
| This request has expired, so it can no longer be answered. | Its deadline passed before you answered. |
| The server no longer accepts this phone, so your answer can't be sent. | Pair again. |

## Several servers

Requests from every connection appear together, each labeled with its connection. Two servers can use the same request ID. The phone keeps them apart, and an answer goes only to the server the request came from.

## Checking from the agent's side

The test agent can make and read requests, without Hermes ([`tools/test-agent/README.md`](../../tools/test-agent/README.md)). `pnpm agent ack` needs the demo tool `vault_request_ack`, which the sidecar serves only with `MCP_DEMO_TOOLS=true` in its `.env`, as `.env.example` sets it:

```console
$ pnpm --silent agent ack "Deploy finished"
idempotency key: ack-3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c
{"request_id":"f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19","action":"ack","status":"PENDING","terminal":false,...}
$ pnpm --silent agent get f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19
{"request_id":"f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19","action":"ack","status":"COMPLETED","terminal":true,...}
```

`pnpm agent cancel <id>` withdraws a request that's still pending. The full check is in [`docs/testing/stage-2.md`](../testing/stage-2.md). Hermes can do the same; see [queued requests](../integrations/hermes.md#4-queued-requests-create-now-read-the-result-later).
