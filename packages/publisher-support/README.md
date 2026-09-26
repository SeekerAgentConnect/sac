# publisher-support

The source library the two public-feed demos share (SEE-134), and nothing else.

**It is not a service.** There is no command in it, no `main` package, no Dockerfile, no compose
file, no listener and no deployment. It is built only as part of a module that depends on it, and
the two that do are [`examples/demo-signals/`](../../examples/demo-signals) and
[`examples/demo-prediction/`](../../examples/demo-prediction).

**It is not a published client product either.** There is no `feed-publisher-client` package here
and none is planned. [`gateway/`](gateway) is an ordinary authenticated HTTP/Connect call to the
feed gateway's documented publication API — the same call a shell script with `curl`, a Python
strategy loop or a Rust service would make. It exists here because both demos happen to be written
in Go, not because publishing to the gateway requires a library.

**Why it exists at all.** Both demos hold signals durably before publishing them, mint the same
idempotency keys, move the same revisions, and drain the same outbox to the same API with the same
retry judgment. Two copies of that would be two subtly different answers to "was this published",
and the first sign of the difference would be a duplicate proposal or a signal that never left.
There is one implementation, and it is here.

## What is in it

| Package | What it is |
| --- | --- |
| [`signals`](signals) | What a signal is, as pure data, and the one seam a demo supplies: `Kind` |
| [`manifest`](manifest) | What a publisher says about itself, and the `seekervault://feed` reference a phone adds it from |
| [`environment`](environment) | The production/sandbox stamp, and the two words the phone uses for it |
| [`markets`](markets) | The market records the store persists for a publisher that discovers its own signals |
| [`store`](store) | The only place that speaks SQL: the signals, the market rows, the idempotency keys, the outbox |
| [`gateway`](gateway) | The one thing that reaches out of a publisher's process: the gateway client and the retry judgment |
| [`publish`](publish) | The drainer, which turns what the store holds into what the gateway has |
| [`api`](api) | The business-API frame: authorization, routing, strict decoding, and one refusal shape |
| [`config`](config) | The settings every publisher has, and the reader a demo adds its own settings through |
| [`ids`](ids), [`limit`](limit) | Identity minting; the rolling-hour cap on new signals |
| [`publisherctl`](publisherctl) | The operator CLI's implementation; each demo ships the binary |
| [`gen`](gen) | Generated contracts, written by `buf.gen.publisher-support.yaml` and never edited by hand |
| [`publishertest`](publishertest), [`demotest`](demotest) | Test support: the gateway doubles, the shared fixtures, the real-gateway process harness, and the whole-demo driver |

`publishertest` and `demotest` are ordinary packages rather than `_test.go` files because a test
file is not importable and three copies of one fake gateway would be three opinions about what the
real one does. Nothing that ships imports them, and there is a boundary test that fails if anything
does.

## Two deliberate design points

**The market and discovery rows are here, not in the Prediction demo.** A market this demo tracks
*is* a proposal it publishes: the row and the signal are written in one transaction, through the
same unexported helpers that mint an idempotency key and settle a revision. Splitting them across
modules would mean either duplicating the durable engine or giving up that atomicity, and a state
where the signal existed without the row would be a proposal nothing maintains — nothing would ever
withdraw it. So the schema and the transaction stay in one place, and each demo still has its own
database file. A CopyTrading database simply never holds market rows.

What is *not* here is anything that knows a provider: the filters, the reconciler and the Jupiter
client are the Prediction demo's, so no provider code reaches the CopyTrading image.

**The API frame is here; each demo's API is its own.** Both demos serve the same authorization, the
same routing, the same strict decoding and the same refusal shape, and copying a thousand lines of
that into each would be exactly the duplication this library exists to prevent. What differs is who
writes a demo's signals, and that is a seam: `api.Authorship`, plus the interfaces a discovering
demo supplies. Each demo owns its own listener, its own token, its own database, its own publisher
identity and credential, and its own decision about who may write to it. The frame names no
provider and imports no reconciler.

## Working on it

```sh
cd packages/publisher-support
gofmt -l . && go vet ./... && go test ./...
```

or `pnpm check:publisher-support` from the repository root, which also builds the feed gateway so
the opt-in test that runs the real one is not silently skipped.

Changing anything here changes both demos, so both are checked too:

```sh
pnpm check:demos      # the library and both demos, each on its own
```

The generated code is regenerated, never edited:

```sh
pnpm generate         # buf.gen.publisher-support.yaml writes packages/publisher-support/gen
pnpm check:generated  # and this fails if the committed output has drifted
```

Developer internals and the verification commands are
[`docs/development/demos.md`](../../docs/development/demos.md). The deployment guides belong to the
demos: [`examples/demo-signals/README.md`](../../examples/demo-signals/README.md) and
[`examples/demo-prediction/README.md`](../../examples/demo-prediction/README.md).
