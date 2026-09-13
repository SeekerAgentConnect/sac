# Pending requests

What an agent asks of you arrives on the phone as a **pending request**. You review each one and answer it yourself. The app answers nothing for you, and nothing runs while it's closed.

> **Stage 5.2 status:** a configured current sidecar now uses the persistent bounded Sync path whenever this build fetches. Old or unconfigured sidecars keep manual Refresh through the earlier API. Foreground live updates and periodic background scheduling arrive in the next tickets; this ticket adds no push notification or automatic wallet action.

## Where requests come from

- **An agent asks a sidecar,** for example Hermes, or `pnpm agent ack` from the test agent. The sidecar stores the request as PENDING and tells the agent at once that it's waiting. The agent reads your answer later ([`docs/protocol.md`](../protocol.md#agent-api-mcp)).
- **Requests wait on the sidecar while the app is closed.** Each one expires after a day, unless the agent chose another lifetime or the sidecar's `REQUEST_TTL_SECONDS` says otherwise.
- **For now, the only kind is a queued acknowledgement:** a message that you acknowledge or reject. Wallet actions arrive from Stage 3 on.

The phone must be paired with the sidecar first ([`pairing.md`](pairing.md)).

## When the phone fetches

The phone fetches a connection's requests at three moments: when the app opens or comes back to the foreground, when you open that connection, and when you tap **Refresh**. Rotating the phone doesn't fetch. There's no push and no background service. A request made while the app is closed or in the background shows up the next time you open it.

Requests remain authoritative on the sidecar. The phone keeps the last complete revisioned view so it survives process death. A complete multi-page refresh replaces that cache only after its final page arrives; if paging is interrupted, the preceding complete view remains. Duplicate or stale updates change nothing, while a cursor gap, conflicting revision, damaged cache, or sidecar restart requests a full snapshot. Removing or revoking a connection removes its cached server state, but Activity remains the owner's record.

## Reviewing a request

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

The test agent can make and read requests, without Hermes ([`test-agent/README.md`](../../test-agent/README.md)). `pnpm agent ack` needs the demo tool `vault_request_ack`, which the sidecar serves only with `MCP_DEMO_TOOLS=true` in its `.env`, as `.env.example` sets it:

```console
$ pnpm --silent agent ack "Deploy finished"
idempotency key: ack-3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c
{"request_id":"f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19","action":"ack","status":"PENDING","terminal":false,...}
$ pnpm --silent agent get f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19
{"request_id":"f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19","action":"ack","status":"COMPLETED","terminal":true,...}
```

`pnpm agent cancel <id>` withdraws a request that's still pending. The full check is in [`docs/testing/stage-2.md`](../testing/stage-2.md). Hermes can do the same; see [queued requests](../integrations/hermes.md#4-queued-requests-create-now-read-the-result-later).
