# SEE-95 — the Go CopyTrading publisher template

A trader's own server: it publishes one signal, every subscribed phone reads the same document, and
each owner picks their own amount on their own device. The template publishes and stops. Nothing
about a subscriber exists here to collect.

## What this is, next to the two servers that already exist

| | `sidecar/` (Node) | `broadcast/` (Go) | **`publisher/` (Go, this ticket)** |
| --- | --- | --- | --- |
| Whose | the owner's own | whoever hosts the broadcast | **a developer's / a trader's** |
| Talks to | one paired phone | publishers and every phone | **the gateway, once per statement** |
| Holds | requests, results | publications, publisher grants | **its own signals, and its outbox** |
| Knows a subscriber | yes, the one | no | **no — there is nobody to know** |

It is a new Go module rather than a command inside `broadcast/`: the gateway's operator and the
publisher are different people, and a template that imported the gateway's store would be a
template nobody could copy out. It shares the protocol and nothing else.

**One module, two templates.** SEE-96 is the prediction template, so the parts that are not about
swaps — configuration, the store, the outbox, the API, the CLI — are the module's core, and the
swap signal is one `signals.Kind` registered by `cmd/copytrading`. The seam exists because the
second one is already specified, not on speculation.

## The decisions this turns on

- **The CLI is a client of the API**, not a second path into the store. One place validates, assigns
  a revision and publishes; the CLI is the worked example that a strategy engine can do the same
  thing. It carries the API token like any other caller.
- **The document is its own outbox.** A signal row carries the revision it is at and the revision the
  gateway has confirmed; anything where those differ is pending, and the drainer publishes it. There
  is no second table to keep in step, and a restart resumes from the same two numbers.
- **A retry sends identical bytes.** The revision and the content are settled in the store before the
  first call, so a retried publication is the same document at the same revision and the gateway
  answers `UNCHANGED` — no duplicate proposal, and no second notification.
- **A revision moves only when the content does.** An update that changes nothing is answered
  "unchanged" locally and publishes nothing, so a bot that re-posts its state every minute does not
  wake anybody's phone every minute.
- **`Idempotency-Key` is required on create.** A bot that retries a create has no other way to be
  safe; the same key with the same body returns the same signal, and the same key with a different
  body is a conflict rather than a silent second signal.
- **Sandbox and production are separate deployments, and the database says which.** The environment
  is stamped into the file on first use and a mismatch refuses to open it: the isolation survives a
  copied compose file, which is the way it actually gets broken.
- **The gateway being down is not a failed signal.** The API stores it, answers `202` with
  `"publication":"pending"`, and the drainer keeps trying with backoff. A refusal that cannot become
  success — a malformed document, another server's channel, the channel's bound — stops the retries
  and is reported on the signal as `refused` with the gateway's own problem code.

## Plan

### The module
- [x] `buf.gen.publisher.yaml` — Go for this module: `publish.proto`, `problem.proto`, `proposal.proto`, `manifest.proto` and nothing else. **No feed client is generated**, because the template never reads a feed: the same argument that keeps `publish.proto` out of the phone's generation.
- [x] `scripts/generate.mjs` — the new template and its output directory, so `pnpm check:generated` covers it.
- [x] `publisher/go.mod` — the same Go, Connect, protobuf and SQLite versions the gateway pins.

### The core
- [x] `internal/config` — every setting one `PUBLISHER_*` variable, every problem at once, nothing with a default that opens something. The gateway credential is the exception that has to be usable: `BROADCAST_CREDENTIAL`, the name `broadcastctl` already prints, or `BROADCAST_CREDENTIAL_FILE` for a mounted secret.
- [x] `internal/ids` — a lowercase v4 UUID from `crypto/rand`, which is the form every ID in this protocol has.
- [x] `internal/signals` — the kind seam, the swap kind, and the terms: mints, decimals, the slippage ceiling, the optional bounds and labels, by `docs/protocol.md`'s table and `SwapTerms.kt`'s rules.
- [x] `internal/store` — SQLite, one writer: the environment stamp, the manifest and its revision, the signals with their two revisions, and the idempotency keys. No column for a subscriber, and a test that reads the schema.
- [x] `internal/publish` — the gateway client (manifest, proposal, withdrawal), the problem codes classified into retry and refuse, and the drainer that publishes what the store says is pending.
- [x] `internal/api` — the HTTP API: create, update, cancel, read, the manifest and the feed reference. Strict JSON, a bearer token compared in constant time, and `Idempotency-Key`.

### The commands
- [x] `cmd/copytrading` — configuration, the store, the swap kind, the manifest published at startup, the API, the drainer, an orderly shutdown. It prints the feed reference a phone adds.
- [x] `cmd/publishctl` — `create`, `update`, `cancel`, `list`, `show`, `reference`, over the API.

### Deployment
- [x] `Dockerfile` (scratch, static, one CA bundle with its reason), `compose.yaml` (loopback), `compose.public.yaml` + `Caddyfile.public` (a domain, deliberately a second command), `.env.example`, `README.md`.
- [x] `scripts/check-publisher.mjs`, `pnpm check:publisher`, and the two built binaries in `.gitignore`.

### Evidence
- [x] Tests: the configuration's problems; every term rule; the store's schema, stamp and idempotency; the classification of every gateway problem; the drainer's retry, backoff and refusal; the API's authorization, its strict decoding, its idempotency and its 405s.
- [x] A boundary test: no subscriber column, no field in a request that could carry one, no Firebase, no broker, no MCP, no feed client, and no credential in a log line.
- [x] An opt-in test against the **real gateway binary** (`SEEKERVAULT_BROADCAST=…`), which is also the automated half of "two phones see the same proposal": two independent readers, byte-identical documents, and a restart that republishes at the same revision for `UNCHANGED`.
- [x] A real end-to-end run recorded in the changelog: register, start, publish through the CLI and through `curl`, read the feed back, restart, withdraw.
- [x] Docs: the wiki page (including where publication ends and execution begins), the development page, the API page for strategy systems, and the pointers in protocol, security, architecture, the stage test record, AGENTS.md, CODEBASE.md, README.md and the changelog.
- [x] Deliberate breaks, each failing the check it is meant to.

## Review

Done as planned, with three things worth naming.

**`PUBLISHER_PUBLISH_URL` was not in the plan, and running the thing put it there.** The plan had one
address: the gateway's origin, which the manifest must name because the phone compares it. The first
native run published to exactly that and got a 404 — the gateway's *read* listener has no handler
that could write anything, and its publisher API is a second socket. Where a publication **goes** is
therefore its own setting, with the weaker canonical rule an address inside a deployment deserves
(any host, plain HTTP allowed, still no path), and the template now says at startup that nobody can
subscribe until its manifest is published, and names the variable — because the gateway cannot: the
address that answered was not its publisher API. My own opt-in test had hidden this by constructing
the client with the publisher port directly.

**The router's own refusals were rewritten, which was not planned either.** `http.ServeMux` answers
404 and 405 in plain text, so an API whose every other answer is JSON had two that were not. A small
`ResponseWriter` wrapper turns exactly those two into this API's own shape, and it tells them apart
from a handler's own 404 by the content type already set — which is what distinguishes "there is no
such signal" from "there is no such route".

**The seventh deliberate break found two tests that could not see it.** Publishing a withdrawn
document instead of calling `CancelProposal` left the API's withdrawal test and the drainer's
transition test green, because both test gateways stored what the real one refuses. Both fakes now
apply the gateway's own rule — a cancelled status is not a publication — and the transition test
counts which procedure was called; the same break now fails three tests in two packages. This is the
second ticket in a row where a break exposed a vacuous check, which is the argument for doing them.

### What was proven, and what was not

The real gateway accepts what this template publishes, as a separate process with its own database
and a credential its own tool issued: the manifest, a signal, **two independent readers getting
byte-identical documents**, `UNCHANGED` for a republication of the identical document, and a
withdrawal that the feed then serves as cancelled. By hand, the whole path ran end to end — both
CLI and API creates, a retried create, a reused key, a no-op update, a restart that republished
nothing, a withdrawal, and a publication during a real outage that went out by itself when the
gateway came back, with the channel's sequence counting the publications so the "no duplicates"
claim is a number rather than an assertion.

What is left is the two-phone run: one signal, two wallets, two amounts, and neither phone or the
template knowing anything about the other owner. It is step 8 of the owner's device run.

Nine deliberate breaks, each restored and compared byte for byte; counts and commands are in
[`docs/changelog/2026-09-17.md`](../../docs/changelog/2026-09-17.md).
