# A publisher template's signal API (SEE-95, SEE-96)

How an external system publishes signals through a publisher template: a trader's script, a cron
job, or a strategy engine that decides what to propose. It is plain JSON over HTTP, so it can be
called with `curl` in one line and from any language without generating anything.

This is **not** the phone–server protocol. What a phone reads is the proposal document, through the
shared gateway, in protobuf over Connect ([`docs/protocol.md`](../protocol.md)). What you send here
is the template's own affair, and it is deliberately simpler: this API is one server's control
surface, not a contract between runtimes.

The tool in [`publisher/cmd/publishctl`](../../publisher/cmd/publishctl) is a client of exactly
these endpoints and has no privileged path of its own, so anything it does, your program can do.

## What this API is not for

**There is no field here for anything about a subscriber**, and there never will be: not a wallet
address, not an amount, not a slippage somebody settled on, not a decision, not a signature, not an
execution result. A publisher has nobody to describe — it never learns which phones read its
channel — and each owner's choices are made on their own device and stay there (SEE-89,
[`docs/security.md`](../security.md)).

The decoder refuses a field the contract does not have rather than dropping it, so a caller that
believes otherwise is told:

```json
{
  "error": "bad_request",
  "detail": "there is no field \"amount\" in a signal. A signal says what is proposed and until when; the amount, the wallet and the decision are each subscriber's own and are never sent here"
}
```

Nor can it be smuggled in as a term: a kind refuses a term it does not know
(`"error": "unknown_term"`).

## Authorization

One token, on every endpoint but `/healthz`:

```
Authorization: Bearer <PUBLISHER_API_TOKEN>
```

It is the whole of the grant. A caller holding it can publish, update and withdraw anything this
publisher can — to everybody subscribed — so treat it as you would a signing key: at least 32
characters, never in a URL, never in a repository. Every way of failing answers the same 401, so a
caller learns that it may not publish and never whether what it presented used to work.

## Endpoints

| Method and path | What it does |
| --- | --- |
| `GET /healthz` | Says the process is up, and nothing else. No credential |
| `GET /v1/status` | Which publisher this is, which gateway it publishes through, what it promises, and how many signals are waiting to be published |
| `GET /v1/manifest` | The manifest this template publishes about itself, and the `seekervault://feed` reference a phone adds it from |
| `POST /v1/signals` | Publish a new signal. **Requires `Idempotency-Key`** |
| `GET /v1/signals` | Every signal this template holds, newest first |
| `GET /v1/signals/{id}` | One signal, and what the gateway has confirmed about it |
| `PUT /v1/signals/{id}` | Replace a signal's whole statement |
| `POST /v1/signals/{id}/cancel` | Withdraw one |
| `POST /v1/signals/{id}/retry` | Try a refused publication again |

Every answer is JSON, including the router's own refusals, and every answer carries
`Cache-Control: no-store`: what a publisher currently proposes is the answer, and a proxy deciding
how long that stays true would be a second opinion about it.

## Two templates, and one of them does not take signals

The endpoints above are the **CopyTrading** template's, whose signals are written by its callers —
which is what this whole page is about. The **Prediction** template (SEE-96,
[`wiki/prediction-template.md`](../wiki/prediction-template.md)) writes its own from a provider's
listing, so on that one the three writing endpoints answer **403** and two of its own are added:

| Method and path | What it does |
| --- | --- |
| `POST /v1/signals`, `PUT /v1/signals/{id}`, `POST /v1/signals/{id}/cancel` | `403 written_by_discovery` — nothing is stored |
| `GET /v1/discovery` | The filters in force, the last cycle, and every market it is tracking |
| `POST /v1/discovery/poll` | Run a discovery cycle now, rather than at the next interval. `409 busy` while one is running |

`GET /v1/status` says which of the two you are talking to before you try, so a client can branch on
an answer rather than on a refusal:

```json
{
  "operation": "prediction",
  "plugin_id": "jupiter.prediction",
  "writable": false,
  "discovery": { "markets": 7, "working": true, "last_cycle": { "outcome": "ok", "created": 7 } }
}
```

The refusal itself says where to look instead, because the way to change what a discovering template
publishes is to change what it looks for:

```json
{
  "error": "written_by_discovery",
  "detail": "this template's signals are written by its own discovery of prediction markets, not by callers. What it publishes is decided by the filters its deployment configured (GET /v1/discovery), and a cycle would undo anything posted here"
}
```

Reading is identical on both, and so is `POST /v1/signals/{id}/retry`: a retry is about the gateway
rather than about the statement, so it belongs to whoever operates the template rather than to
whoever wrote the signal.

## Publishing a signal

```sh
curl -sS https://signals.example.com/v1/signals \
  -H "Authorization: Bearer $PUBLISHER_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: desk-1-sol-usdc-2026-09-17T19:00Z' \
  -d '{
    "expires_at": "2026-09-17T21:00:00Z",
    "note": "trimming SOL into USDC on the bounce",
    "terms": {
      "input_mint": "So11111111111111111111111111111111111111112",
      "input_decimals": "9",
      "output_mint": "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
      "output_decimals": "6",
      "max_slippage_bps": "50",
      "least_input": "100000000",
      "input_symbol": "SOL",
      "output_symbol": "USDC"
    }
  }'
```

The whole of the request body:

| Field | Rules |
| --- | --- |
| `expires_at` | Required. An **absolute** RFC 3339 instant, in the future. Absolute rather than a duration because a phone that was switched off for a day has to reach the same conclusion as one that was not |
| `note` | Optional. At most 1024 bytes of printable text, line breaks allowed. It is shown to each owner as the publisher's own words, beside — never instead of — what their phone established for itself |
| `terms` | Required. An object of **text** values: the operation's own terms. Text and not numbers because a quantity in base units exceeds what a JSON number holds exactly, and because that is what the document carries |

Everything else about a signal is the template's to mint, and there is no field for any of it: the
proposal ID, the revision, the channel, the operation, the plugin, the times it keeps and the
environment all come from the deployment and the kind it registered.

The terms for a swap are in [`docs/protocol.md`](../protocol.md#a-swap-signals-terms-see-93):
`input_mint`, `input_decimals`, `output_mint`, `output_decimals` and `max_slippage_bps` are
required; `least_input`, `most_input`, `input_symbol` and `output_symbol` are optional. An asset is
an exact base58 mint and never a ticker; direction is the ordered pair and there is no side field.
Numbers are published canonically, so `"09"` and `"9"` are the same signal.

### The answer, and the status code

```json
{
  "signal": {
    "server_id": "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    "channel": "server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    "proposal_id": "c0179bbb-e11a-4dd0-8ca0-e3168a1b163b",
    "revision": "1",
    "status": "open",
    "operation": "swap",
    "plugin_id": "jupiter.swap",
    "created_at": "2026-09-17T19:00:00Z",
    "updated_at": "2026-09-17T19:00:00Z",
    "expires_at": "2026-09-17T21:00:00Z",
    "note": "trimming SOL into USDC on the bounce",
    "terms": { "input_mint": "So11111111111111111111111111111111111111112", "…": "…" },
    "environment": "production"
  },
  "publication": { "state": "published", "confirmed_revision": "1", "attempts": 0 },
  "idempotent": false
}
```

A revision is written as text, because it can exceed what a JSON number holds exactly — the same
reason protojson writes a 64-bit integer as a string. Compare revisions as integers, not as floats.

| Status | What it means |
| --- | --- |
| **201** | The signal exists and the gateway holds it |
| **200** | Nothing changed: a replay of the same key, or an update whose content was identical |
| **202** | The signal exists here and its publication is **pending** — the gateway could not be reached, or is rate-limiting. It will be retried; `publication.next_attempt_at` says when |
| **400** | The statement broke a rule. `error` is a stable code, `term` names the term when one is at fault, `detail` is a sentence |
| **401** | No token, or the wrong one |
| **404** | No signal of that ID, or no such endpoint |
| **405** | That endpoint does not take this method; the `Allow` header says which |
| **409** | `key_reused` — that key belongs to a different statement — or `cancelled`: a withdrawal is final |
| **415** | The body was not declared `application/json` |
| **502** | The gateway **refused** the publication in a way retrying cannot change. `publication.problem` is the gateway's own problem code |

A 202 is a success: the signal is durably stored, and the answer a caller was given outlives the
process that gave it. Treat it as "accepted, not yet public" — `GET /v1/signals/{id}` tells you when
that changes.

## Idempotency

`Idempotency-Key` is required on a create: 1 to 200 printable characters, chosen by you and
reproducible by you. A strategy engine's own order ID, or a timestamp and a pair, is exactly right.

- The same key with the same statement → **200**, `"idempotent": true`, the signal the first call
  created. Nothing is published twice.
- The same key with a different statement → **409** `key_reused`. Two different statements cannot
  be one signal.
- A different key with identical content → a second signal. The key is what says "this is the same
  statement".

The comparison is against the *validated* statement, so whitespace, key order and how a number was
spelled do not make a retry look like a new request.

**Nothing else needs a key.** An update carries the whole statement, and its revision moves only if
the content actually changed; a withdrawal and a retry are idempotent by what they are.

## Updating and withdrawing

An update is the **whole statement**, not the parts that changed:

```sh
curl -sS -X PUT https://signals.example.com/v1/signals/$ID \
  -H "Authorization: Bearer $PUBLISHER_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"expires_at":"2026-09-17T22:00:00Z","note":"widening the slippage",
       "terms":{ "…": "…", "max_slippage_bps":"90" }}'
```

There is no field-level merge, because the gateway stores a publisher's complete current statement
and never merges a partial one — a merge here would be this template inventing a document nobody
wrote. An omitted `note` clears the note.

The answer carries `"changed": true` or `"changed": false`. False means the content was identical:
the revision did not move, nothing was published, and no phone was woken. That makes re-posting your
current view safe and cheap.

```sh
curl -sS -X POST https://signals.example.com/v1/signals/$ID/cancel \
  -H "Authorization: Bearer $PUBLISHER_API_TOKEN"
```

A withdrawal is final. Withdrawing again changes nothing (`"changed": false`); updating a withdrawn
signal is 409. A phone that already acted on a proposal keeps its own record for ever, and must
never be shown the same identity as open again — so propose something else by publishing another
signal, which is another identity.

## When a publication is refused

```json
{
  "signal": { "…": "…" },
  "publication": {
    "state": "refused",
    "confirmed_revision": "0",
    "attempts": 1,
    "problem": "too_many_proposals",
    "detail": "too_many_proposals (proposal_id)"
  }
}
```

The problem is the gateway's own code. The ones worth knowing:

| Problem | What to do |
| --- | --- |
| `unauthenticated` | The credential the gateway issued is wrong, revoked, or not this publisher's |
| `other_server`, `foreign_channel` | `PUBLISHER_SERVER_ID` is not the server that credential was issued for |
| `other_gateway`, `not_a_feed` | `PUBLISHER_GATEWAY_URL` is not the gateway's own origin |
| `too_many_proposals` | The channel is at its bound. Withdraw something, then retry |
| `stale_revision`, `revision_conflict`, `cancelled` | This template's view and the gateway's disagree; look at both before retrying |
| `unimplemented` (404) | `PUBLISHER_PUBLISH_URL` is not reaching the gateway's publisher API |

Nothing retries a refusal on its own. When the cause is fixed:

```sh
curl -sS -X POST https://signals.example.com/v1/signals/$ID/retry \
  -H "Authorization: Bearer $PUBLISHER_API_TOKEN"
```

`"cleared": true` means there was a refusal to clear; the publication is then attempted immediately
and the answer says what happened.

## A minimal strategy loop

The shape that stays correct under every failure this API has:

```python
import requests, uuid

API, TOKEN = "https://signals.example.com", os.environ["PUBLISHER_API_TOKEN"]
headers = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}

def publish(decision) -> str:
    # A key your own system can reproduce: the same decision retried must carry the same key.
    key = f"{decision.desk}-{decision.pair}-{decision.at.isoformat()}"
    answer = requests.post(f"{API}/v1/signals", headers={**headers, "Idempotency-Key": key},
                           json={"expires_at": decision.expires.isoformat(),
                                 "note": decision.reasoning,
                                 "terms": decision.terms})
    if answer.status_code in (200, 201, 202):
        # 202 means stored here and not public yet. It is not a reason to publish again.
        return answer.json()["signal"]["proposal_id"]
    if answer.status_code == 409:
        raise Duplicate(answer.json())   # this key already belongs to a different statement
    raise Refused(answer.status_code, answer.json())
```

Three rules are the whole of it:

1. **Retry a create with the same key.** Never mint a new one for the same decision: a new key is a
   new signal on everybody's phone.
2. **Do not treat 202 as failure.** The template holds the signal and will publish it.
3. **Never send anything about a subscriber.** There is no field for it, so the attempt is a 400
   rather than a silent success — but the reason it is worth knowing is that there is nothing to
   collect: what each owner does is theirs.

## What the template does with what you send

It validates the statement by the phone's own rules, mints an identity and a revision, stores both,
and publishes the document once to the shared gateway. Then it stops. Fills, positions, settlement,
outcomes and P&L are not here and are not anywhere in this stage: each owner's phone executes and
keeps its own record ([`docs/wiki/copytrading-template.md`](../wiki/copytrading-template.md)).
