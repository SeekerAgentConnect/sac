# SEE-96 — the Go Prediction server template, with live-feed filtering

Stage 7.1, child of SEE-85. The second publisher template: a Go server that **discovers** Jupiter
Prediction markets, applies its operator's filters and publishes each match once through the shared
broadcast gateway. Every subscribed phone reads the same proposal and picks its own side and its own
stake, on its own device, through the bundled `jupiter.prediction` plugin (SEE-94).

It is the same core as SEE-95's CopyTrading template — the configuration, the store, the outbox, the
drainer, the manifest and the API — with two things added and one thing taken away:

- added: a **provider client** (`internal/jupiter`) and a **reconciler** (`internal/discovery`);
- taken away: **nobody may write a signal through the API**. This template's proposals are written
  by its own discovery, so its API is read-only and says so (403) rather than quietly not routing.

## The shape of it

- [x] `internal/signals/prediction.go` — the second `signals.Kind`. `Operation() = "prediction"`,
      `Requirement() = jupiter.prediction/1..1`, and `Terms` is `PredictionTerms.kt`'s rules on this
      side: `market_id`/`event_id` as bounded identifiers, `provider`, a `deposit_mint` from the
      closed set the provider takes, its decimals and symbol, and optional whole-number bounds with
      the provider's own $5 floor raised into them.
- [x] `internal/signals/contract_test.go` — a second contract test, reading `PredictionTerms.kt`
      for the deposit mints, the order floor and the identifier pattern. It skips when the phone's
      source is not there, which is what a copied-out template looks like.
- [x] `internal/jupiter/` — the provider, as one file plus captured fixtures: `Events` (paged),
      `Market` (one, by ID), the provider's own error shape, a minimum gap between calls for the
      keyless allowance, and a bounded retry that tells a rate limit apart from an outage. **The
      provider's host appears in this file and nowhere else**, which is the phone's own rule for the
      same constant (`JUPITER_ENDPOINT`, `StageBoundaryTest`).
- [x] `internal/discovery/` — the reconciler, the filters and the two row types the store keeps for
      it (`Market`, `Cycle`). Matching semantics are stated in the package comment and tested case
      by case.
- [x] `internal/store` — schema **version 2**: a `market` table (one row per tracked market, with a
      `UNIQUE` proposal, a generation and the source link) and a `discovery` row (the last cycle).
      A version-1 file — a CopyTrading deployment's — migrates rather than being refused.
- [x] `internal/api` — `Authorship`: `ByCallers` (SEE-95) or `ByDiscovery` (this one). Under
      discovery the three writing routes answer **403 `written_by_discovery`**, and two reading ones
      are added: `GET /v1/discovery` (the filters in force, the last cycle, every tracked market
      with its source link) and `POST /v1/discovery/poll` (run a cycle now).
- [x] `cmd/prediction/main.go` — the same startup as `cmd/copytrading`, with the reconciler's
      goroutine beside the drainer's.
- [x] `cmd/publishctl` — one command added, `discovery`, and `poll`.
- [x] Deployment: a third binary in the image, `compose.prediction.yaml` as its own stack,
      `Caddyfile.prediction` (read-only methods at the door), `.env.prediction.example`.

## The decisions worth writing down

- [x] **Absence is not closure.** A market that stops appearing in the listing has not necessarily
      closed — it may have left the operator's filter, or the provider's `trending` set. So a
      tracked market that is missing from a cycle is **asked about directly**
      (`GET /markets/{id}`), and only the provider's own answer — gone, closed, cancelled or
      resolved — withdraws a proposal. A provider that is unreachable withdraws nothing.
- [x] **A filter is discovery, not withdrawal.** A market that no longer matches but is still open
      keeps its proposal. Withdrawing because the operator's filter moved would take a signal back
      for a reason no subscriber can see, and a `trending` market that oscillates would publish and
      withdraw itself for ever.
- [x] **An expiry is the provider's close time**, never `now + something`: an expiry derived from
      the clock would change the document on every cycle and wake every phone. A market with no
      close time is expired from when it was first seen, which is stored.
- [x] **Nothing volatile goes in the note.** The event and market titles, the category and the close
      time do; prices and volume do not, because a document that moved with the price would be a
      revision a minute.
- [x] **Source links are kept and never published.** The document carries the provider's market and
      event IDs, which is what lets the phone resolve the market itself; the link is in the
      template's own row for its operator. A publisher-supplied URL on a phone's screen is the thing
      the manifest rules exist to prevent.
- [x] **`PREDICTION_STATE=any` is refused in production.** Publishing a closed market is a sandbox
      exercise — the provider refuses the order — and the way that reaches production is a copied
      `.env`.

## Verification

- [x] `gofmt`, `go vet`, and the module's tests: the new packages case by case.
- [x] Deterministic fixtures, captured from the live API by `scripts/capture-jupiter.mjs --events`
      and served from an `httptest` server, covering pagination, an empty page, an update, a
      closure, a 404, a rate limit and a malformed body.
- [x] The opt-in live test (`SEEKERVAULT_JUPITER=1`) against the real provider, run by hand.
- [x] The opt-in gateway test (`SEEKERVAULT_BROADCAST`) extended to the prediction template: two
      independent readers, byte-identical documents, `UNCHANGED` on republication.
- [x] By hand, end to end: the real provider and the real gateway, a restart, a provider outage, a
      closure, and the two-phone step recorded in `docs/testing/stage-7-1.md`.
- [x] Deliberate breaks, each restored byte for byte.

## Documentation

- [x] `docs/wiki/prediction-template.md` — what it is, the filters and their exact semantics, the
      reconciliation table, and where publication ends and the phone begins.
- [x] `docs/integrations/jupiter.md` — the discovery endpoints, their parameters as the published
      spec states them, what the live API accepts beyond it, the beta warning, and the absence of a
      stream.
- [x] `docs/integrations/signal-api.md`, `docs/development/publisher.md`, `docs/protocol.md`,
      `docs/security.md`, `docs/architecture.md`, `docs/testing/stage-7-1.md`,
      `docs/changelog/2026-09-17.md`, `CODEBASE.md`, `README.md`, `publisher/README.md`.

## Review

**What was built.** `publisher/cmd/prediction`, over the same core: `internal/jupiter` (the provider
— two endpoints, paced, its host in one file), `internal/discovery` (the filters, the cycle, and the
two row types the store keeps), `signals.Prediction`, the store at schema version 2 with a `market`
table and a `discovery` row, `api.Authorship` and the two discovery endpoints, `publishctl discovery`
and `poll`, and the template's own stack. **470 Go test cases in 181 test functions across ten
packages**, 0 failures, 1 skip (the opt-in live provider).

**The decisions that changed during the work.**

- *The expiry.* The first shape was `now + lifetime`, which would have moved the document on every
  cycle and woken every subscribed phone every five minutes. It is the market's own close time, and
  a market with none expires from a stored instant.
- *The idempotency key.* Minting one per cycle would publish a market twice if a cycle were
  interrupted after reading the listing. It is derived from the market and its generation, and the
  `UNIQUE` index on the row's proposal says the same thing as a constraint.
- *Absence.* Withdrawing on absence was the obvious reading of "update or cancel when source
  availability changes", and it is wrong: a filtered listing's silence says nothing, and an outage
  would have looked like every market closing at once. Only the provider's own answer ends a
  proposal.
- *Authorship.* Leaving the API writable would have let a caller post a signal that the next cycle
  silently undid. 403 with the filters named, and the front door does not forward it either.
- *Source links.* "Preserve provider IDs and source links" is satisfied by keeping the link in the
  template's own row and publishing the identifiers — because a URL a publisher chose, rendered on a
  phone, is what the manifest rules exist to prevent, and the phone reads the market from the
  provider itself anyway.

**What the run found.** Two things, both in the tests rather than in the code: the opt-in
gateway test built its statement by hand, so a break that put a URL in the note left it green — it
now runs the reconciler and publishes what a cycle produced; and the clock-derived-expiry break is
invisible to "a second cycle publishes nothing", because both cycles fall inside one second, which is
why the expiry has a test that owns the clock.

**Thirteen deliberate breaks**, each failing the check that names it, each file restored byte for
byte. The provider was read live (684 markets considered, 36 matched, 3 published), the real gateway
was run as a separate process, and the closure path was run against a local stand-in because a real
market does not close on cue.

**What is left.** Step 9 of `docs/testing/stage-7-1.md`: two phones, one discovered market, two
sides — and the closure seen on both of them. Every Docker-daemon check is NOT RUN; `docker compose
config` and `caddy validate` accept the new stack without one.
