# Monorepo layout

Seeker Agent Connect is one product monorepo. The Android app, the protocol, the Direct Server SDK,
the MCP servers, the gateway and the demos change together in one pull request, and each of them
still installs, builds and ships on its own. SEE-167 moved every component under a group directory
that says what kind of thing it is; nothing about what a component does changed.

A component's **identifier** is the same everywhere it appears: its folder, the root `pnpm` command
that checks it, its CI job and, in the packaging follow-up (SEE-168), its published artifact. The
identities that other people already depend on are deliberately **unchanged** by this layout — see
[What did not change](#what-did-not-change).

## Components

| Identifier | Path | Was | Toolchain | What it owns |
| --- | --- | --- | --- | --- |
| `android` | `apps/android/` | `android/` | Gradle, Kotlin | The Android application (`app`), the visual-only `designsystem` module and shared preview test support (`preview-testing`) |
| `gateway` | `services/gateway/` | `feed-gateway/` | Go module | The shared public-feed gateway: the read and publisher APIs, its service-owned admin UI (`internal/admin`), the push relay (`internal/relay`, `internal/pushrelay`), `feed-gatewayctl`, and the pinned Centrifugo configuration and image (`centrifugo.yaml`, `Dockerfile.centrifugo`) |
| `protocol` | `packages/protocol/` | `proto/`, `buf.yaml`, `buf.gen*.yaml`, `third_party/centrifugo/` | Buf | The one canonical protocol source: the Buf module in `proto/`, cross-runtime fixtures in `proto/fixtures/`, `buf.yaml`, every generation template, and the vendored Centrifugo client schema in `third_party/centrifugo/` |
| `server-sdk` | `packages/server-sdk/` | `server-sdk/` | pnpm, TypeScript | The embeddable Direct Server SDK (`@seeker_agent_connect/server-sdk`) |
| `publisher-support` | `packages/publisher-support/` | `publisher-support/` | Go module | The source library both public-feed demos share. It is not a service and has no image |
| `mcp-server` | `servers/mcp-server/` | `mcp-server/` | pnpm, TypeScript | The general self-hosted MCP server (`@seeker_agent_connect/mcp-server`) |
| `mcp-skr-staking` | `servers/mcp-skr-staking/` | `skr-staking-server/` | pnpm, TypeScript | The SKR staking MCP server (`@seeker_agent_connect/mcp-skr-staking`) |
| `demo-signals` | `examples/demo-signals/` | `demo-copytrading/` | Go module | The CopyTrading signals demo: publisher, trader UI (`copytrading-admin`) and `publishctl` |
| `demo-prediction` | `examples/demo-prediction/` | `demo-prediction/` | Go module | The Prediction demo: market discovery publisher, admin UI and `publishctl` |
| `test-agent` | `tools/test-agent/` | `test-agent/` | pnpm, TypeScript | The MCP test client behind `pnpm agent` and the acceptance/integration suites (`@seeker_agent_connect/test-agent`, private) |
| `loadtest` | `tools/loadtest/` | `loadtest/` | Go module | The gateway's load, isolation and failover harness |

`servers/` holds independently runnable MCP servers; a new one goes in `servers/<component>/`.
`examples/` holds functional demo servers and sites as `examples/demo-<name>/`. `packages/` holds
only the shared code that existing components actually consume.

Directories that are not components keep their paths:

| Path | What it is |
| --- | --- |
| `deploy/` | The Compose projects, ingress and operator examples. Project names, volume names and file paths were unchanged by this layout. They have since moved, with the DigitalOcean App Platform specs, to the `do-deploy` repository, where the Compose projects live under [`compose/`](https://github.com/SeekerAgentConnect/do-deploy/tree/main/compose) and pull published images |
| `design/` | The Android design guide, tokens and captured references |
| `docs/` | Documentation |
| `fixtures/` | Test data shared by the app and the servers (transfer transactions, captured Jupiter answers, the restricted-feed challenge) |
| `scripts/` | Root commands: generation, the Go and deployment checks, acceptance/integration/load runners, emulator helpers |
| `examples/hermes.config*.yaml` | Hermes configuration to merge into another tool; not a component |
| `spa/` | A static documentation page bundle |

## Old → new path mapping

Every existing component is accounted for; nothing was removed or merged.

| Old path | New path |
| --- | --- |
| `android/` | `apps/android/` |
| `feed-gateway/` | `services/gateway/` |
| `proto/` | `packages/protocol/proto/` |
| `proto/fixtures/` | `packages/protocol/proto/fixtures/` |
| `buf.yaml` | `packages/protocol/buf.yaml` |
| `buf.gen.yaml`, `buf.gen.*.yaml` | `packages/protocol/buf.gen.yaml`, `packages/protocol/buf.gen.*.yaml` |
| `third_party/centrifugo/` | `packages/protocol/third_party/centrifugo/` |
| `server-sdk/` | `packages/server-sdk/` |
| `publisher-support/` | `packages/publisher-support/` |
| `mcp-server/` | `servers/mcp-server/` |
| `skr-staking-server/` | `servers/mcp-skr-staking/` |
| `demo-copytrading/` | `examples/demo-signals/` |
| `demo-prediction/` | `examples/demo-prediction/` |
| `test-agent/` | `tools/test-agent/` |
| `loadtest/` | `tools/loadtest/` |

Files were moved with `git mv`, so `git log --follow <new path>` shows each file's history from
before the move.

## Dependencies between components

```text
protocol ──generates──▶ android, gateway, server-sdk, mcp-server, publisher-support, loadtest
server-sdk ◀──workspace:*── mcp-server, mcp-skr-staking, test-agent
publisher-support ◀──go.mod replace── demo-signals, demo-prediction
gateway ◀──runs the binary in tests── publisher-support, demo-prediction, test-agent, loadtest
```

- **Node workspace.** `pnpm-workspace.yaml` lists `packages/server-sdk`, `servers/mcp-server`,
  `servers/mcp-skr-staking` and `tools/test-agent`. The servers import the SDK only through its
  public package exports (`@seeker_agent_connect/server-sdk` and `/protocol`); the MCP server's build
  vendors the built SDK into its own package (`servers/mcp-server/scripts/package.mjs`).
- **Go modules.** Each Go component is its own module. The demos resolve `publisher-support`
  through `replace … => ../../packages/publisher-support`; no other Go module depends on another.
- **Android.** Generated Kotlin is committed under `apps/android/app/src/main/generated/`. The
  unit tests read the protocol fixtures and `fixtures/` as test resources and, for cross-runtime
  contract tests, start `servers/mcp-server` and read a few gateway and SDK source files.
- **Test-only source imports.** Tests in `servers/mcp-server` and the acceptance suites in
  `tools/test-agent` import SDK internals (`packages/server-sdk/src/storage/…`, `src/testing/…`) and
  the MCP server's test helpers directly. These are deliberate test harness imports, not accidental
  production coupling: production code crosses component boundaries only through package exports,
  generated code or a `replace`, and `servers/mcp-server/src/stage-boundary.test.ts` and
  `packages/server-sdk/src/package-boundary.test.ts` keep it that way. None was introduced by the
  move.

## The protocol and its generated code

`packages/protocol` is the single source. Nothing else holds a `.proto` file, and every generated
tree is written by `pnpm generate` from one template and owned by the component that consumes it:

| Template (`packages/protocol/`) | Output | Owner |
| --- | --- | --- |
| `buf.gen.yaml` | `apps/android/app/src/main/generated/{java,kotlin}` | `android` |
| `buf.gen.centrifugo.yaml` | `apps/android/app/src/main/generated/centrifugo/` | `android` |
| `buf.gen.server-sdk.yaml` | `packages/server-sdk/src/gen` | `server-sdk` |
| `buf.gen.mcp-server.yaml` | `servers/mcp-server/src/gen` | `mcp-server` |
| `buf.gen.feed-gateway.yaml` | `services/gateway/internal/gen` | `gateway` |
| `buf.gen.publisher-support.yaml` | `packages/publisher-support/gen` | `publisher-support` |
| `buf.gen.loadtest.yaml` | `tools/loadtest/internal/gen` | `loadtest` |

`scripts/generate.mjs` runs `buf` from the repository root, so every input and output path in a
template is repository-relative. It checks the vendored schema's digest first, then runs each
template and converts `packages/protocol/proto/fixtures/**/*.json` into `.binpb`.

```sh
pnpm generate                          # write generated code and binary fixtures
pnpm check:generated                   # fail if a fresh generation differs from what is committed
buf lint packages/protocol             # also part of pnpm check:lint
buf format packages/protocol --diff    # also part of pnpm check:format
```

The remote Buf plugins are fetched from the Buf Schema Registry, so generation needs network access
and is subject to the registry's rate limit for unauthenticated clients.

## Setting up and building

From a clean checkout, with the toolchain in [`toolchain.md`](toolchain.md):

```sh
pnpm install --frozen-lockfile          # the Node workspace
pnpm build                              # server-sdk, mcp-server, mcp-skr-staking, test-agent
pnpm check                              # format, lint, SDK build, type checks, Node tests
pnpm check:generated                    # protocol generation is reproducible
pnpm check:android                      # android (needs a JDK and the Android SDK)
pnpm check:gateway                      # gateway (needs Go)
pnpm check:demos                        # publisher-support, demo-signals, demo-prediction (needs Go)
pnpm check:loadtest                     # loadtest (needs Go)
pnpm check:deployments                  # Dockerfile health commands, package .env.example, retired layout
```

| Component | Build | Run locally | Container |
| --- | --- | --- | --- |
| `android` | `(cd apps/android && ./gradlew :app:assembleDebug)` | `scripts/emulator.sh` ([emulator runbook](emulator-e2e.md)) | — |
| `gateway` | `(cd services/gateway && go build ./...)` | [`services/gateway/README.md`](../../services/gateway/README.md) | `docker build -f services/gateway/Dockerfile .`; broker: `docker build -f services/gateway/Dockerfile.centrifugo services/gateway` |
| `protocol` | `pnpm generate` | — | — |
| `server-sdk` | `pnpm build:server-sdk` | — (a library; `pnpm test:server-sdk-package` installs it outside the workspace) | — |
| `publisher-support` | `pnpm check:publisher-support` | — (a library) | — |
| `mcp-server` | `pnpm --filter @seeker_agent_connect/mcp-server run build` | `pnpm dev:mcp-server`, `pnpm pair` | `docker build -f servers/mcp-server/Dockerfile .` |
| `mcp-skr-staking` | `pnpm --filter @seeker_agent_connect/mcp-skr-staking run build` | `pnpm dev:mcp-skr-staking` | `docker build -f servers/mcp-skr-staking/Dockerfile .` |
| `demo-signals` | `(cd examples/demo-signals && go build ./...)` | [`examples/demo-signals/README.md`](../../examples/demo-signals/README.md) | `docker build -f examples/demo-signals/Dockerfile .` |
| `demo-prediction` | `(cd examples/demo-prediction && go build ./...)` | [`examples/demo-prediction/README.md`](../../examples/demo-prediction/README.md) | `docker build -f examples/demo-prediction/Dockerfile .` |
| `test-agent` | `pnpm --filter @seeker_agent_connect/test-agent run build` | `pnpm agent <command>` | `docker build -f tools/test-agent/Dockerfile .` |
| `loadtest` | `(cd tools/loadtest && go build ./...)` | `pnpm test:load` | — |

Every Dockerfile still builds from the repository root as its context, except the Centrifugo image,
whose context is `services/gateway/`. The Compose presets, now under `compose/` in `do-deploy`,
pull the published images instead of building. They are checked there by
`node scripts/check-compose.mjs`; this repository's `pnpm check:deployments` keeps only the
repository-side checks (Dockerfile health commands, package `.env.example` names and ports, and the
retired layout).

## What did not change

- **Package identities.** SEE-167 changed only each manifest's `repository.directory`. The scope
  itself changed in the packaging task that followed: the packages are `@seeker_agent_connect/…`
  now, `skr-staking-server` was renamed to `mcp-skr-staking` so the package, the folder and the
  image share one identifier, and all three are published. `@seeker_agent_connect/test-agent` stays
  private. See [releases.md](releases.md).
- **Go module paths** stay `github.com/BrRenat/SeekerAgentWallet/<old folder name>` (for example
  `…/feed-gateway`, `…/demo-copytrading`), so no import line changed. See the note under
  [Migrating](#go-module-paths) about fetching them by path.
- **Images and deployments.** Docker image names and tags, Compose project names, service names,
  volume names, ports, environment variables, the `deploy/` file paths and the DigitalOcean specs.
  (`deploy/` has since moved to the `do-deploy` repository as `compose/`.)
- **The app.** The application ID `io.github.brrenat.seekervault`, the `seekervault://` pairing and
  feed links, the generated protocol code and the wire contract.
- **Commands.** Every existing root `pnpm` command still works. The ones that named an old folder
  gained a component-named equivalent and remain as aliases: `check:feed-gateway` and
  `check:broadcast` → `check:gateway`, `check:copytrading` → `check:demo-signals`,
  `check:prediction` → `check:demo-prediction`. `dev:mcp-skr-staking` is new.

## Migrating

### A development checkout

Pull, then move the ignored local files git does not move for you, and delete the leftover old
folders (they hold only ignored build output such as `node_modules/`, `dist/`, `build/` or Go
binaries):

```sh
mv android/local.properties apps/android/local.properties                     # if you have one
mv android/app/google-services.json apps/android/app/google-services.json     # if you have one
rm -rf android feed-gateway proto third_party server-sdk publisher-support mcp-server \
  skr-staking-server demo-copytrading demo-prediction test-agent loadtest
pnpm install --frozen-lockfile
```

The root `.env` stays where it is; `pnpm dev:mcp-server`, `pnpm dev:mcp-skr-staking`, `pnpm pair`
and `pnpm agent` still read it. Replace old paths in your own shell history, scripts and editor
settings using the mapping above, for example `node mcp-server/src/cli.ts` →
`node servers/mcp-server/src/cli.ts` and `cd android` → `cd apps/android`.

### Compose deployments (`deploy/`)

The Compose projects have since moved to the separate `do-deploy` repository: `deploy/<project>/`
is now `compose/<project>/` there, run from a `do-deploy` checkout with `up -d` against published
images. What follows describes the SEE-167 move itself.

No operator action beyond pulling. The commands (`docker compose --env-file deploy/<project>/.env
-f deploy/<project>/compose.yaml …`), project names and volume names are unchanged; only the build
context's Dockerfile paths inside the Compose files moved, and the feed stack now mounts
`services/gateway/centrifugo.yaml`. Rebuild with `--build` as usual.

### Building images by hand

Use the new `-f` paths; the context is still the repository root:

| Before | After |
| --- | --- |
| `docker build -f feed-gateway/Dockerfile .` | `docker build -f services/gateway/Dockerfile .` |
| `docker build -f Dockerfile.centrifugo .` (in `feed-gateway/`) | `docker build -f Dockerfile.centrifugo .` (in `services/gateway/`) |
| `docker build -f mcp-server/Dockerfile .` | `docker build -f servers/mcp-server/Dockerfile .` |
| `docker build -f skr-staking-server/Dockerfile .` | `docker build -f servers/mcp-skr-staking/Dockerfile .` |
| `docker build -f demo-copytrading/Dockerfile .` | `docker build -f examples/demo-signals/Dockerfile .` |
| `docker build -f demo-prediction/Dockerfile .` | `docker build -f examples/demo-prediction/Dockerfile .` |
| `docker build -f test-agent/Dockerfile .` | `docker build -f tools/test-agent/Dockerfile .` |

The image names and tags you push are yours to keep; nothing else about the images changed.

### Commands inside the Node images

The Node images mirror the workspace, so the application moved inside them too. The images' own
`CMD`, `ENTRYPOINT` and `HEALTHCHECK` are updated; only a command you run yourself inside a
container, or an override in your own orchestration, needs the new path:

| Image | Before | After |
| --- | --- | --- |
| MCP server | `node mcp-server/dist/cli.js pair` | `node servers/mcp-server/dist/cli.js pair` |
| MCP server | `node mcp-server/dist/healthcheck.js` | `node servers/mcp-server/dist/healthcheck.js` |
| SKR staking | `node skr-staking-server/dist/cli.js pair` | `node servers/mcp-skr-staking/dist/cli.js pair` |
| SKR staking | `node skr-staking-server/dist/healthcheck.js` | `node servers/mcp-skr-staking/dist/healthcheck.js` |
| Test agent | entry point `test-agent/dist/main.js` | entry point `tools/test-agent/dist/main.js` |

The Go images (gateway, Centrifugo, both demos) contain only their binaries at `/`, unchanged.

### DigitalOcean App Platform

The App Platform specs, now in the separate
[`do-deploy`](https://github.com/SeekerAgentConnect/do-deploy) repository, deploy prebuilt images by
tag and name no source path, so the running apps are unaffected by this change. Build the next image
with the new Dockerfile path (above), push it, and update the tag as that repository's README
describes.

### Source-based installs

A host that runs a server from a checkout (for example a service unit running
`node mcp-server/src/cli.ts start`) must point at `servers/mcp-server/src/cli.ts`, or
`servers/mcp-skr-staking/src/cli.ts` for the staking server. Their data directories default to
`~/.seeker-agent-connect/…` and are unaffected. An installed npm tarball is unaffected.

### Go module paths

Go resolves a module fetched by path from the repository subdirectory that matches the path. Inside
this repository nothing fetches them — the demos use a `replace`, and each module builds on its own —
so builds are unaffected. A project outside the repository that required, say,
`github.com/BrRenat/SeekerAgentWallet/publisher-support` at a commit after this move would not
resolve it by path; pin a revision from before the move, or copy the module and use a `replace` as
the demo READMEs describe. No such consumer is known (`publisher-support` is explicitly not a
published client), and aligning module paths with the new folders is left to the packaging work,
because it changes every import line.

### CI

The workflow's jobs were renamed to their component identifiers (`gateway`, `demo-signals`) and
read the Go version and caches from the new `go.mod`/`go.sum` paths. Branch protection that required
a job by its old name (`feed-gateway`, `demo-copytrading`) needs the new name.

## Historical records

Changelog entries, test evidence under `docs/testing/`, review evidence and the SEE-128 migration
map describe the repository as it was when they were written, so their prose keeps the old paths;
only their links were updated so they still resolve. Use the mapping above to read them.
