# The load harness

SEE-99's measurement of the broadcast transport: the real gateway, the pinned broker, real Redis and
synthetic publishers, with simulated phones on the same stream a phone listens on.

```sh
pnpm test:load -- --list          # the scenarios and the profiles
pnpm test:load                    # every scenario this machine can run
pnpm check:loadtest               # this module's own tests; needs no broker
```

- **[`docs/development/load.md`](../docs/development/load.md)** is the command, the profiles, the
  scenarios, what each number includes, and how to run it against a deployment of your own.
- **[`docs/testing/see-99.md`](../docs/testing/see-99.md)** is the report: the revision, the
  topology, every scenario's numbers, the stage the climb reached, and the limits.

It is a Go module of its own, and the reason is the point of it: this is the only consumer in the
repository that holds both ends of a feed — the publisher API, the client API and the broker's own
client schema — and nothing that ships is allowed to hold them together. Keeping it out of
[`feed-gateway/`](../feed-gateway) is what keeps the gateway's dependency list at three.

Its client is not an approximation of the app's. `internal/listen/policy.go` is a port of
`android/.../feeds/FeedRecovery.kt` — the same disconnect-code ranges, the same test for whether
continuity was proven, the same jittered backoff — and `policy_test.go` is a port of that file's own
test, so a change on either side that is not made on both fails here.
