# SEE-134 — Extract independent root-level CopyTrading and Prediction demo servers

Parent: SEE-128 (step 6/8). Branch `superset/feat/see-128`, PR #38.
Ticket: https://linear.app/seekeragentwallet/issue/SEE-134

## Objective

Split the single Go module `publisher/` into three root modules:

| Root | Kind | Contents |
| --- | --- | --- |
| `publisher-support/` | non-deployable library (no `cmd/`, no Dockerfile, no listener) | the one durable publication/outbox engine, the gateway HTTP/Connect client, feed document rules, the shared business-API frame, and the shared test harness |
| `demo-copytrading/` | independent deployable | caller-authored signals: `cmd/copytrading`, `cmd/copytrading-admin`, `cmd/publishctl`, `internal/admin`, `sdk`, own Dockerfile/compose/env/guide/database |
| `demo-prediction/` | independent deployable | self-discovered signals: `cmd/prediction`, `internal/{jupiter,discovery,config,api}`, own Dockerfile/compose/env/guide/database |

Neither demo imports the other. Neither builds or starts the other. Both publish only through the
feed gateway's documented authenticated HTTP/Connect JSON API. No Direct Server SDK dependency and
no `feed-publisher-client/` module.

## Deliberate deviations from the SEE-129 sketch (recorded, with reasons)

1. **The market/discovery store extension stays in `publisher-support/store`.** `markets.go`
   writes a market row and a signal in one transaction through `createIn`/`updateIn`/`cancelIn`,
   the durable engine's own unexported helpers. Moving it to `demo-prediction` would either
   duplicate the durable publication/outbox engine or break the atomicity the code exists to
   guarantee — both forbidden by SEE-134 step 2. One schema, one engine; each demo still has its
   own database file, and a CopyTrading database simply never holds market rows.
2. **The shared business-API frame stays in `publisher-support/api`.** SEE-134 step 4 makes each
   demo's control API demo-specific in *semantics* (its own listener, token, routes and
   authorship), which the frame already models through `Authorship`. Copying ~1000 lines into both
   demos would be the duplication step 2 forbids. `publisher-support/api` is decoupled from the
   provider: it no longer imports `jupiter` or the reconciler.
3. **A new `publisher-support/markets` package** holds only the market records the store persists
   (`Market`, `Tracked`, `Cycle`, `ErrBusy`, outcome constants). `Filters`, the reconciler and the
   Jupiter provider client move to `demo-prediction`, so no provider code reaches the support
   library or the CopyTrading image.
4. **`publish.go` splits into `publisher-support/gateway`** (the authenticated Connect client) and
   **`publisher-support/publish`** (the durable drainer), as the SEE-129 sketch lays out.
5. **A new `publisher-support/publishertest` package** holds the exported test harness (the fake
   gateway, the API template driver and the real-feed-gateway process harness) so both demos' tests
   reuse one copy instead of three.

## Ordered steps

- [x] 1. Create `publisher-support/` module; `git mv` the shared packages and rewrite import paths.
- [x] 2. Split `discovery.go` into `publisher-support/markets` (records) and the prediction half.
- [x] 3. Split `publish` into `gateway/` (client) and `publish/` (drainer).
- [x] 4. Export the env `Reader` from `publisher-support/config` so the prediction loader can reuse it.
- [x] 5. Decouple `publisher-support/api` from `jupiter`/reconciler (`Cycles.Filters() map[string]any`).
- [x] 6. Convert the api + gateway test harnesses into exported `publisher-support/publishertest`.
- [x] 7. Create `demo-copytrading/` module: commands, admin UI, publishctl, sdk, tests.
- [x] 8. Create `demo-prediction/` module: command, jupiter, discovery, config, api wiring, tests.
- [x] 9. Per-demo Dockerfile, compose, Caddyfile, `.env.example`, health/build/start commands.
- [x] 10. Per-demo README + full deployment guide against the parent's 11-point checklist.
- [x] 11. Update `scripts/`, `.github/workflows/ci.yml`, `buf.gen.publisher-support.yaml`, `package.json`,
      `deploy/`, `CODEBASE.md`, active docs and the changelog. Retire `publisher/`.
- [x] 12. Verify: independent build/test/vet per module, independent Docker builds, HTTP publication
      against a real feed gateway, per-source isolation and restart evidence.

## Review

**What changed.** `publisher/` is gone. `publisher-support/` holds the one durable
publication/outbox engine, the gateway HTTP/Connect client, the feed document rules, the shared
business-API frame, the operator CLI's implementation and the shared test support — and it has no
command, no image, no listener and no deployment. `demo-copytrading/` and `demo-prediction/` are
independent modules with their own Dockerfile, Compose stack, Caddy front door, `.env.example`,
database, publisher identity, gateway credential and complete step-by-step guide.

**Five deviations from the step-1 sketch, each recorded in the code and in the migration map:**
the market/discovery store extension stayed with the durable engine (one transaction, one engine);
the API frame stayed shared but was decoupled from the provider; a new `markets` package holds only
the persisted record types so no provider code reaches the CopyTrading image; `publish` split into
`gateway/` + `publish/`; and the operator CLI became a library with a three-line `main` in each
demo, because both demos answer the same API and neither may import the other.

**Two things worth knowing for the next ticket.** The generated protobuf descriptors embed their Go
import path, so `publisher-support/gen` had to be *regenerated*, not rewritten — a `sed` over the
`.pb.go` files corrupts the length-prefixed raw descriptor and every test panics at init. And the
two cross-root source tests (`publisher-support/signals/contract_test.go`,
`publisher-support/environment/environment_test.go`) skip silently when their target is missing, so
moving a module one directory shallower had turned them into no-ops; their `../../..` prefixes are
now `../..` and both assert again.

**Verified.** `pnpm check`, `pnpm check:demos`, `pnpm check:generated`, `pnpm check:loadtest`,
`pnpm test:integration --no-android`, both opt-in real-gateway tests, `docker compose config` for
all three stacks, and an isolated-tree build proving each demo compiles and tests with only
`publisher-support/` beside it. Image builds are **NOT RUN** (no reachable Docker daemon); the live
provider and physical device remain opt-in and unrun. Evidence:
[`docs/testing/see-134.md`](../../docs/testing/see-134.md).
