# Seeker Agent Connect

An Android app for the Solana Seeker that puts the owner in control of what AI agents do with
their wallet. Agents propose actions; the owner reviews each one on the phone and approves it
through the wallet. Nothing is signed automatically.

The app connects in two ways:

- **Private direct connections** — a self-hosted MCP server that your own agent (Hermes, Claude,
  or any MCP client) talks to. The agent queues requests — transfers, message signatures, swaps,
  acknowledgements — and the phone shows them for review.
- **Public feeds** — signals published once through a shared feed gateway and read by every
  subscriber, such as copy-trading or prediction-market feeds.

This repository holds the app together with the self-hosted server software: the protocol, an
embeddable server SDK, the MCP servers, the feed gateway, and two example feeds. There is no
hosted service or account; you run the servers on your own infrastructure.

The product design is described in [`RFC.md`](RFC.md), the architecture in
[`docs/architecture.md`](docs/architecture.md), and the security model in
[`docs/security.md`](docs/security.md).

## Repository layout

Each component lives in its own folder and builds, tests and ships on its own. A component's
folder name is also its identifier in root `pnpm` commands, CI jobs and release tags.

| Component | Path | What it is |
| --- | --- | --- |
| `android` | [`apps/android/`](apps/android) | Kotlin/Compose app (`app` and `designsystem` modules): connections, review and wallet flows, policies, history |
| `gateway` | [`services/gateway/`](services/gateway) | Go feed gateway: publishers publish once, subscribers read through isolated listeners; includes admin UI and push relay |
| `protocol` | [`packages/protocol/`](packages/protocol) | The single Protobuf/Buf source for every runtime; generated Kotlin and TypeScript code |
| `server-sdk` | [`packages/server-sdk/`](packages/server-sdk) | TypeScript Direct Server SDK: pairing, durable request lifecycle, phone APIs |
| `publisher-support` | [`packages/publisher-support/`](packages/publisher-support) | Go library shared by the example feeds: outbox, gateway client, feed documents |
| `mcp-server` | [`servers/mcp-server/`](servers/mcp-server) | Self-hosted MCP server that connects an agent to the phone |
| `mcp-skr-staking` | [`servers/mcp-skr-staking/`](servers/mcp-skr-staking) | Standalone MCP server for SKR staking; builds unsigned transactions only |
| `demo-signals` | [`examples/demo-signals/`](examples/demo-signals) | Example public feed: copy-trading signals |
| `demo-prediction` | [`examples/demo-prediction/`](examples/demo-prediction) | Example public feed: prediction markets |
| `test-agent` | [`tools/test-agent/`](tools/test-agent) | Minimal MCP client for trying the server without an LLM |
| `loadtest` | [`tools/loadtest/`](tools/loadtest) | Load, isolation and failover harness for the gateway |

Other top-level folders: `design/` (Android design guide and tokens), `docs/` (architecture,
protocol, guides, integrations, development notes, changelog), `fixtures/` (cross-runtime test
data) and `scripts/` (root commands and checks).

## Getting started

- **Try it end to end:** [`docs/guides/macbook-seeker-quickstart.md`](docs/guides/macbook-seeker-quickstart.md)
  goes from a fresh Mac to a message on the phone, with no Android experience assumed.
- **Install without building:** npm packages are published under `@seeker_agent_connect` and
  container images at `docker.io/brenat/seeker-agent-connect`. See
  [`docs/guides/installation.md`](docs/guides/installation.md).

  ```bash
  npm install @seeker_agent_connect/server-sdk
  npx --package=@seeker_agent_connect/mcp-server -- seeker-agent-connect-mcp
  ```

- **Self-host:** [`docs/guides/self-hosting.md`](docs/guides/self-hosting.md) covers what you need
  and the boundaries; the Compose presets live in the separate
  [`do-deploy`](https://github.com/SeekerAgentConnect/do-deploy) repository.
- **Connect an agent:** [Hermes](docs/integrations/hermes.md) or
  [Claude over OAuth](docs/integrations/claude.md).
- **Publish your own feed:** [`docs/guides/server-development.md`](docs/guides/server-development.md).
- **Something broke:** [`docs/guides/troubleshooting.md`](docs/guides/troubleshooting.md).

## Development

### Prerequisites

Exact versions are in [`docs/development/toolchain.md`](docs/development/toolchain.md).

- Node.js 24.21.0 (see `.nvmrc`) and pnpm 9.7+ (the pinned version is selected automatically)
- Go, for the gateway, example feeds and load harness
- Android SDK Platform 37 and Build-Tools 36.0.0; a JDK 17+ to launch Gradle

### Build and check

```bash
nvm install
corepack enable pnpm
pnpm install --frozen-lockfile

pnpm check              # formatting, lint, type checks, TypeScript tests
pnpm check:android      # Kotlin formatting, unit tests, lint, debug APKs
pnpm check:gateway      # feed gateway (Go)
pnpm check:demos        # publisher-support and both example feeds (Go)
pnpm build              # SDK, MCP server and test agent

(cd apps/android && ./gradlew :app:assembleDebug)
```

The debug APK is written to `apps/android/app/build/outputs/apk/debug/app-debug.apk`.

### Running locally

```bash
cp .env.example .env
openssl rand -hex 32    # run twice: once for MCP_TOKEN, once for PHONE_TOKEN
pnpm dev:mcp-server     # starts /mcp, the phone API and /healthz
pnpm pair               # shows a one-time pairing QR code for the phone
pnpm agent hello "Hi"   # sends a message to the phone and waits for the acknowledgement
```

`.env` is git-ignored. Configuration is described in
[`docs/development/mcp-server.md`](docs/development/mcp-server.md); pairing in
[`docs/guides/pairing.md`](docs/guides/pairing.md). Firebase push is optional and off by default —
see [`docs/guides/firebase.md`](docs/guides/firebase.md), and never commit a service-account
credential.

### Other commands

| Command | What it does |
| --- | --- |
| `pnpm generate` / `pnpm check:generated` | Regenerate protocol code from `packages/protocol/proto/`, or verify it is up to date |
| `pnpm test:hello`, `pnpm test:queue` | Acceptance suites for the MCP round trip and the durable request queue |
| `pnpm test:integration` | Cross-component run: gateway, example feeds, MCP server and agent together (needs Go) |
| `pnpm test:load` | Gateway load and failover scenarios; see [`docs/development/load.md`](docs/development/load.md) |
| `pnpm test:*-package` | Pack, install and exercise each published npm package outside the workspace |
| `pnpm agent <command>` | Test agent: `hello`, `ack`, `get`, `cancel`, `address`, `sign`, `transfer`, `capabilities`; see [`tools/test-agent/README.md`](tools/test-agent/README.md) |
| `pnpm format`, `pnpm format:android` | Apply Prettier/`buf format`, or ktfmt for Kotlin |

See `package.json` for the full list. More developer documentation is in
[`docs/development/`](docs/development), and feature notes are in [`docs/wiki/`](docs/wiki).

### CI and releases

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs the Node, Go, Android and emulator
checks on every pull request with read-only permissions. Components are released independently
from `<component>-v<version>` tags by [`.github/workflows/release.yml`](.github/workflows/release.yml);
see [`docs/development/releases.md`](docs/development/releases.md).

## License

[MIT](LICENSE)
