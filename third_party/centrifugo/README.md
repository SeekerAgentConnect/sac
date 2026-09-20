# Vendored broker schema

`centrifugal/centrifugo/unistream/unistream.proto` is a byte-for-byte copy of

    https://raw.githubusercontent.com/centrifugal/centrifugo/v6.9.6/internal/unigrpc/unistream/unistream.proto

from **Centrifugo v6.9.6** (MIT licensed, © Centrifugal Labs LTD). `SHA256SUMS` records its digest
and `pnpm generate` refuses to run if the file no longer matches — an upgrade is an edit to that
file and that digest, which is what makes it a decision rather than a drift.

## Why it is here

The phone consumes Centrifugo's **unidirectional gRPC** transport, and a client needs the schema to
speak it. Fetching it at build time would make an offline build depend on a network and a release
tag; hand-writing an equivalent would put a wire contract we do not own into a file we maintain.
So it is copied, pinned and checksummed, and `buf.gen.centrifugo.yaml` generates Kotlin from it into
`android/app/src/main/generated/centrifugo/`.

It is deliberately **outside** `buf.yaml`'s workspace: `buf format` and `buf lint` apply our
conventions to our protocol, and neither should rewrite or judge somebody else's schema. Nothing in
`proto/` imports it, and nothing generated from it reaches the sidecar or the gateway.

## What the app is allowed to do with it

One file — `android/.../feeds/CentrifugoFeedStream.kt` — may import these types. Everything else in
the app sees `feeds/FeedStream.kt`'s own types, so the broker stays an implementation detail of one
adapter (SEE-91). `FeedBoundaryTest` fails if a second file imports them.

## Upgrading

1. Fetch the file for the new tag and overwrite the copy.
2. `shasum -a 256 centrifugal/centrifugo/unistream/unistream.proto > SHA256SUMS`.
3. `pnpm generate`, then read the diff of the generated Kotlin: a field number that moved or a
   message that disappeared is a wire break, and `FeedStreamContractTest` pins the fields the
   adapter depends on so the build says so.
4. Update the pinned server version in `feed-gateway/compose.yaml`, `docs/development/toolchain.md`
   and `docs/wiki/feed-gateway.md` in the same commit — a client schema from one release and a
   server from another is exactly the mismatch this directory exists to prevent.
