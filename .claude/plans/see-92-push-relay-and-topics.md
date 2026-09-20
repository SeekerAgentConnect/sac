# SEE-92 — a push relay and per-server topics

Stage 7.1, the sixth child of SEE-85 after SEE-91. A publisher notifies every subscriber of its
public feed through **our** Firebase relay, without ever receiving a Firebase credential or a device
token. SEE-73's whole pipeline — registration, invalidation handling, bounded Sync, notifications,
permission, tap routing — is extended rather than duplicated.

## The shape of it

```
publisher --PublishProposal--> gateway --commit--> outbox notice
                                                     |
                                       drainer -> Fan{ broker, relay }
                                                     |            |
                            stream to phones being looked at    FCM topic
                                                                  |
                                                     phone: validate hint -> WorkManager
                                                     -> authoritative unary read -> notification
```

Two deliveries of the same committed publication, neither trusted: the stream carries the document,
the relay carries a **content-free hint** and the phone reads the document from the gateway.

## Decisions taken before writing any code

1. **The gateway states the topic; the phone never derives one.** A topic name has to agree on both
   sides, and a derivation in two languages is the kind of mismatch SEE-91 already documented as
   silent. So `FeedService.GetFeedTopics` answers, per channel, the topic its hints are sent on —
   the same soft-omission rule `GetStreamTicket` uses. It is a **separate method** from the ticket
   on purpose: a deployment may run the relay with no broker, and then there is no ticket to
   piggyback on.
2. **The topic is derived from the notice's channel, so a publisher cannot name one.** The channel
   in a notice was written by the gateway from the authenticated publisher's own server ID, and the
   publisher API already refuses a document claiming another server's channel. There is no field
   anywhere in which a publisher could put a topic.
3. **`feed.<environment>.<server_id>`.** The environment is the relay's own setting
   (`BROADCAST_PUSH_ENVIRONMENT`, `production` or `sandbox`), so one Firebase project can serve a
   sandbox deployment and a production one without their hints crossing. It is not SEE-97's
   environment model and says so.
4. **The relay is best-effort and never defers a notice.** A failed hint is logged and dropped: the
   outbox's meaning stays "the document was fanned out", and a Firebase outage cannot stall the
   stream. This follows the private path's own dispatcher (SAW-056), and the phone's foreground
   stream and WorkManager recovery are the paths that do not depend on a hint arriving.
5. **The hint reuses the invalidation shape with its own kind.** `{kind: "feed_invalidation",
   version: "1"}` beside SAW-056's `request_invalidation`, both matched exactly, both carrying
   nothing else. Which feed changed is the **topic**, which is an FCM routing field — the same
   distinction SAW-056 made between a target and payload data.
6. **The work input stays empty.** A hint enqueues one unique `FeedSyncWorker` with no input, and
   the worker reads every feed that is not already live in the foreground. So a hint that was
   coalesced away or dropped costs nothing: the next one reads everything. Nothing about a feed is
   written into WorkManager's database.
7. **An unrecognised topic unsubscribes itself.** Membership is Firebase's, and the phone keeps no
   topic registry on disk; a feed removed while the process was dead can therefore leave a
   subscription behind. The hint's own topic is the cure: one that no feed wants is unsubscribed
   from, in memory, on the same serialized channel the registration manager uses.
8. **The image gains a CA bundle, on purpose.** The gateway's second outbound link is Google's, over
   TLS. That is a visible line in the Dockerfile and a changed sentence in AGENTS.md, not something
   that was always there.
9. **Quota per topic, not per publisher.** A token bucket keyed by topic bounds how often a feed's
   subscribers are woken (`BROADCAST_PUSH_RATE`, `BROADCAST_PUSH_BURST`), which is the thing worth
   bounding: publications are already limited per publisher, and a wake-up costs every subscriber.
10. **The tap opens the feed.** There is no screen that lists a proposal yet (SEE-93/94), so the
    notification opens that feed's connection details — the deepest current review state this build
    has — and carries the proposal ID so the screen that lists one can open it without changing the
    notification. Nothing is prepared, signed or sent from a notification, ever.

## The contract

- [x] `proto/seekervault/gateway/v1/feed.proto`: `rpc GetFeedTopics`, `GetFeedTopicsRequest{channels}`,
      `GetFeedTopicsResponse{topics}`, `FeedTopic{channel, topic}`.
- [x] `proto/seekervault/gateway/v1/problem.proto`: `GATEWAY_PROBLEM_NO_PUSH = 33`.
- [x] `pnpm generate`, and the Go/Kotlin output committed.

## The gateway

- [x] `broadcast/internal/relay/relay.go` — the only new thing in the service that calls out: topic
      derivation, the content-free message, the per-topic quota, one retry after a refused token,
      and errors that name no credential.
- [x] `broadcast/internal/relay/token.go` — a service account's JWT grant, signed RS256 by hand and
      cached until shortly before it expires. No new dependency.
- [x] `broadcast/internal/dispatch/dispatch.go` — `Fan`, so one notice reaches the broker and the
      relay, and the notice's fate is the broker's.
- [x] `broadcast/internal/gateway/topics.go` — the `Topics` seam (declared by the user, as `Grants`
      is) and `GetFeedTopics`.
- [x] `broadcast/internal/gateway/feed.go`, `server.go`, `errors.go` — the seam, the wiring, and
      `NO_PUSH → Unimplemented`.
- [x] `broadcast/internal/config/config.go` — the `Relay` block, all of it or none of it.
- [x] `broadcast/cmd/broadcast/main.go` — build the relay, fan out to both, and say what is
      configured.

## The deployment

- [x] `broadcast/Dockerfile` — the CA bundle, with the reason.
- [x] `broadcast/compose.yaml`, `compose.public.yaml`, `.env.example`, `README.md` — the relay's
      settings and the credential mounted read-only into the gateway alone.

## The phone

- [x] `feeds/FeedTopics.kt`, `feeds/ConnectFeedGateway.kt` — ask the gateway which topic a feed's
      hints arrive on.
- [x] `push/FeedTopicClient.kt` — Firebase's topic membership, behind one interface, absent when
      Firebase is not configured.
- [x] `push/FeedTopicManager.kt` — one serialized reconciler: subscribe what the owner has,
      unsubscribe what they removed, re-subscribe after a registration change, and unsubscribe a
      topic nobody wants.
- [x] `push/SeekerVaultMessagingService.kt` — a second exact-match invalidation, and nothing else.
- [x] `sync/FeedSynchronization.kt` — the bounded authoritative read, in the one package that is
      allowed a WorkManager.
- [x] `notifications/FeedNotifications.kt` + strings — one generic alert per newly reviewable
      proposal, and a validated read-only route.
- [x] `MainActivity.kt`, `SeekerVaultApp.kt`, `SeekerVaultApplication.kt` — the route and the wiring.

## Tests

- [x] Go: topic derivation and refusal, the exact message, the quota, token minting/caching/retry,
      nothing sensitive in an error, the fan, the handler's grants and omissions, the config.
- [x] Go boundary: two packages may dial out, neither may carry an address; the contract pin.
- [x] Android: the topic manager (subscribe, unsubscribe, removal, registration change, stale
      topic, failure retry), the service's two kinds, the worker's bounded read and overlap with a
      live stream, the notifications' dedupe/expiry/cancellation/dismissal, and the tap route.
- [x] Android boundary: the Firebase import set, the new notification file, storage and background
      packages unchanged.
- [x] A cross-runtime pin: the phone's test reads the relay's Go source and fails if the hint's
      `kind`/`version` drift apart.
- [x] An opt-in Go test that sends one real hint to a real project when an operator supplies
      credentials, skipped otherwise.

## Docs

- [x] `docs/wiki/feed-gateway.md` — `## The push relay`.
- [x] `docs/guides/firebase.md` — the relay, the topics, and what a topic name is not.
- [x] `docs/protocol.md`, `docs/security.md`, `docs/architecture.md`.
- [x] `broadcast/README.md`, `docs/development/feed-gateway.md`, `docs/development/android.md`.
- [x] `docs/testing/stage-7-1.md` — what ran, what did not, and the force-stop limitation.
- [x] `docs/changelog/2026-09-17.md`, `AGENTS.md`, `CODEBASE.md`.

## Verification

- [x] `pnpm check:broadcast`, `pnpm check:android`, `pnpm check`, `check:generated`, `check:format`,
      `check:lint`, `test:push`, `test:updates`.
- [x] A native end-to-end run: the real gateway binary, a real publication, and the hint arriving at
      a stand-in FCM endpoint with a verifiable bearer token.
- [x] Deliberate breaks, each failing the check that is supposed to catch it.

## Review

### What changed against the plan

1. **The relay's quota became a token bucket rather than a minimum gap.** A gap would have dropped
   the second of two changes published together, which is the ordinary case for a publisher opening
   three proposals at once. A bucket allows that burst and bounds only the sustained rate — and the
   measured behaviour is in the changelog: eight publications in a row produced two hints and six
   logged as coalesced, while the broker received all sixteen events and the document ended at the
   revision it should.
2. **The credential's `token_uri` accepts loopback HTTP.** The alternative was a test hook in
   production code to inject a TLS client. The allowance is the same one `config.Origin` already
   makes for a development origin, it is written down where it is made, and it is what let the whole
   grant run for real against a stand-in endpoint — including the signature being verified with the
   public half of the key.
3. **The push overlay mounts its credential in the long form**, for `create_host_path: false`. The
   short form would create a *directory* where a missing file was named, and the gateway would then
   fail on something that looks like a typo instead of on the missing file.
4. **One more break than planned, and a different one.** "A topic derived on the phone" turned out
   not to be a thing any check could catch — the phone does not derive one — so it became two breaks
   on the pin that does hold: the relay renaming the hint's kind, and the relay changing the topic's
   prefix. A ninth was added for the notification order (posted after the read, never before it).
5. **`docs/development/android.md` gained a section.** It was not in the plan, and the file is where
   somebody looks for how the phone's push path is put together; leaving SEE-92 out of it would have
   left the newest half undocumented in the one place the older half is.

### What went wrong on the way

- The `note` field on a proposal is `publisher_note`, which the end-to-end run found by being
  refused. The refusal named the field and quoted nothing, which is the behaviour SEE-90 built.
- A feed connection's ID is a UUID the phone mints, and a fixture that used `feed-<uuid>` made the
  tap route's validation refuse a real notification. The validation was right and the fixture was
  wrong, which is the good way round.
- `FeedSynchronization.kt` was broken for the order check before it had been backed up, and `git
  checkout` cannot restore an untracked file. The edit was reversed by hand and the file was checked
  against both tests and `spotlessCheck` afterwards. Back up *every* file, including the new ones.

### The honest caveats

- **Docker: NOT RUN.** No daemon. `docker compose config` accepts the base file, the public overlay,
  the push overlay and all three together; `caddy validate`/`fmt` and `centrifugo checkconfig`
  accept the configurations; and every binary was run natively instead.
- **Real Firebase: NOT RUN.** No project or credential was available. The whole path up to the
  moment Google is called ran for real, against a stand-in that verifies the assertion's signature;
  `internal/relay/firebase_test.go` is the opt-in test that closes that gap when an operator has a
  project.
- **Physical device: NOT RUN.** Whether a Seeker receives a topic message, wakes, reads and shows
  the alert is in `docs/testing/stage-7-1.md` for the owner, force-stop limitation included.
- **No screen lists a proposal yet.** The alert opens the feed; SEE-93 and SEE-94 bring the plugins
  that read one's terms.
