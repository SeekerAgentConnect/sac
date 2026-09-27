<div align="center">
  <img width="128" src="docs/assets/readme/logo.png" alt="Seeker Agent Connect" />
  <h1>Seeker Agent Connect</h1>
  <p><strong>Let AI agents propose. You approve on your Seeker.</strong></p>
  <p>
    An open-source Android app and self-hosted server stack that lets AI agents send wallet
    requests to your Solana Seeker, where you review and sign every one yourself.
  </p>
</div>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/SeekerAgentConnect/sac?style=flat-square" alt="License" /></a>
  <a href="https://github.com/SeekerAgentConnect/sac/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/SeekerAgentConnect/sac/ci.yml?style=flat-square&label=CI" alt="CI" /></a>
  <a href="https://www.npmjs.com/package/@seeker_agent_connect/mcp-server"><img src="https://img.shields.io/npm/v/@seeker_agent_connect/mcp-server?style=flat-square&label=mcp-server" alt="npm mcp-server" /></a>
  <a href="https://www.npmjs.com/package/@seeker_agent_connect/server-sdk"><img src="https://img.shields.io/npm/v/@seeker_agent_connect/server-sdk?style=flat-square&label=server-sdk" alt="npm server-sdk" /></a>
  <a href="https://hub.docker.com/r/brenat/seeker-agent-connect"><img src="https://img.shields.io/docker/pulls/brenat/seeker-agent-connect?style=flat-square&logo=docker&logoColor=white" alt="Docker pulls" /></a>
  <a href="https://github.com/SeekerAgentConnect/sac/stargazers"><img src="https://img.shields.io/github/stars/SeekerAgentConnect/sac?style=flat-square" alt="GitHub stars" /></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-Kotlin%20%2F%20Compose-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Solana-Seeker-9945FF?style=flat-square&logo=solana&logoColor=white" alt="Solana Seeker" />
  <img src="https://img.shields.io/badge/MCP-server-000000?style=flat-square" alt="MCP" />
  <img src="https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white" alt="TypeScript" />
  <img src="https://img.shields.io/badge/Go-00ADD8?style=flat-square&logo=go&logoColor=white" alt="Go" />
</p>

<p align="center">
  <img src="docs/assets/readme/hero.png" alt="Seeker Agent Connect: an agent request reviewed and approved on the Seeker" />
</p>

## Table of contents

- [Table of contents](#table-of-contents)
- [⚡️ Highlights](#️-highlights)
- [✨ Features](#-features)
  - [📥 Requests inbox](#-requests-inbox)
  - [🔗 Connections and pairing](#-connections-and-pairing)
  - [📈 Feeds, swaps and predictions](#-feeds-swaps-and-predictions)
  - [🤖 Agent side](#-agent-side)
- [🧩 How it works](#-how-it-works)
- [🚀 Getting started](#-getting-started)
- [📦 Components](#-components)
- [🛠️ Development](#️-development)
- [🔐 Security](#-security)
- [📚 Documentation](#-documentation)
- [🙌 Contributing](#-contributing)
- [📄 License](#-license)

## ⚡️ Highlights

- 🙋 **You stay in control.** Agents only propose. Every transfer, swap, prediction or signature is
  reviewed on the phone and approved through the wallet (Seed Vault, Mobile Wallet Adapter).
  Nothing is signed automatically.
- 🔍 **The phone checks the transaction itself.** Before a wallet opens, the app decodes the
  prepared bytes on-device, so what you see is what gets signed, not what the server says.
- 🤖 **Works with any MCP agent.** Hermes, Claude over OAuth, or any Model Context Protocol client.
- 📡 **Private requests and public feeds.** Pair your own agent directly, or subscribe to signal
  feeds such as copy-trading and prediction markets.
- 🧱 **Self-hosted, no account.** You run the servers; there is no cloud service of ours in the path.
- 📏 **Rules you set.** Per-connection and global policies for actions, assets and addresses.

## ✨ Features

### 📥 Requests inbox

Everything your agents and feeds ask for lands in one queue. Open a request to see what it does,
who sent it and whether it matches your rules.

<p align="center">
  <img src="docs/assets/readme/inbox.png" width="30%" alt="Requests inbox" />
  <img src="docs/assets/readme/review-transfer.png" width="30%" alt="Transfer review" />
  <img src="docs/assets/readme/review-swap.png" width="30%" alt="Swap review" />
</p>

### 🔗 Connections and pairing

Pair a self-hosted MCP server with a one-time QR code, or add a public feed. Each connection has
its own status, history and rules.

<p align="center">
  <img src="docs/assets/readme/pairing.png" width="30%" alt="Pairing with a QR code" />
  <img src="docs/assets/readme/connection.png" width="30%" alt="Connection details" />
  <img src="docs/assets/readme/rules.png" width="30%" alt="Connection rules" />
</p>

### 📈 Feeds, swaps and predictions

Built-in Jupiter plugins turn a feed signal into a swap or a prediction-market order with your own
amount and side. The app reads the route and the transaction before handing it to the wallet.

<p align="center">
  <img src="docs/assets/readme/feed-signal.png" width="30%" alt="Feed signal" />
  <img src="docs/assets/readme/review-prediction.png" width="30%" alt="Prediction review" />
  <img src="docs/assets/readme/activity.png" width="30%" alt="Activity history" />
</p>

### 🤖 Agent side

Your agent calls ordinary MCP tools such as `vault_transfer`, `vault_sign_message` or
`vault_get_address`, and waits for the result from the phone.

<p align="center">
  <img src="docs/assets/readme/agent-terminal.png" width="80%" alt="An agent sending a request over MCP" />
</p>

## 🧩 How it works

<p align="center">
  <img src="docs/assets/readme/architecture.png" width="90%" alt="Architecture: agent → MCP server → phone → wallet; publishers → feed gateway → phones" />
</p>

There are two connection modes:

| Mode | Path | Use it for |
| --- | --- | --- |
| **Private direct** | Agent → your MCP server → your phone | Your own agent asking your wallet to transfer, sign, swap or acknowledge |
| **Public feed** | Publisher → feed gateway → every subscriber | Signals that many people follow, such as copy-trading or prediction markets |

More detail is in [`docs/architecture.md`](docs/architecture.md) and the product design in
[`RFC.md`](RFC.md).

## 🚀 Getting started

**From zero to a message on the phone:** follow the
[MacBook + Seeker quickstart](docs/guides/macbook-seeker-quickstart.md). No Android experience
needed.

**Run the MCP server without cloning:**

```bash
npx --package=@seeker_agent_connect/mcp-server -- seeker-agent-connect-mcp
```

**Embed the server SDK in your own Node app:**

```bash
npm install @seeker_agent_connect/server-sdk
```

**Run with Docker:** images are published at `docker.io/brenat/seeker-agent-connect`, one tag
prefix per component (`mcp-*`, `gateway-*`, …). See the
[installation guide](docs/guides/installation.md), and the
[`do-deploy`](https://github.com/SeekerAgentConnect/do-deploy) repository for Compose presets.

**Connect an agent:** [Hermes](docs/integrations/hermes.md) ·
[Claude over OAuth](docs/integrations/claude.md)

## 📦 Components

| Component | Path | Description |
| --- | --- | --- |
| 📱 `android` | [`apps/android`](apps/android) | Kotlin/Compose app: connections, review and wallet flows, policies, history |
| 🌐 `gateway` | [`services/gateway`](services/gateway) | Go feed gateway: publish once, every subscriber reads through isolated listeners |
| 📜 `protocol` | [`packages/protocol`](packages/protocol) | The single Protobuf/Buf contract; generated Kotlin and TypeScript |
| 🧰 `server-sdk` | [`packages/server-sdk`](packages/server-sdk) | TypeScript SDK for direct servers: pairing, durable requests, phone APIs |
| 🧩 `publisher-support` | [`packages/publisher-support`](packages/publisher-support) | Go library shared by the example feeds |
| 🤖 `mcp-server` | [`servers/mcp-server`](servers/mcp-server) | Self-hosted MCP server that connects an agent to the phone |
| 🥩 `mcp-skr-staking` | [`servers/mcp-skr-staking`](servers/mcp-skr-staking) | MCP server for SKR staking; builds unsigned transactions only |
| 📊 `demo-signals` | [`examples/demo-signals`](examples/demo-signals) | Example feed: copy-trading signals |
| 🔮 `demo-prediction` | [`examples/demo-prediction`](examples/demo-prediction) | Example feed: prediction markets |
| 🧪 `test-agent` | [`tools/test-agent`](tools/test-agent) | Minimal MCP client for trying things without an LLM |
| 🏋️ `loadtest` | [`tools/loadtest`](tools/loadtest) | Load, isolation and failover harness for the gateway |

## 🛠️ Development

Requires Node.js 24.21.0 (`.nvmrc`), pnpm 9.7+, Go, the Android SDK (Platform 37, Build-Tools
36.0.0) and a JDK 17+. Exact versions are in [`docs/development/toolchain.md`](docs/development/toolchain.md).

```bash
nvm install && corepack enable pnpm
pnpm install --frozen-lockfile

pnpm check              # formatting, lint, type checks, TypeScript tests
pnpm check:android      # Kotlin formatting, unit tests, lint, debug APKs
pnpm check:gateway      # feed gateway (Go)
pnpm check:demos        # example feeds (Go)
pnpm build              # SDK, MCP server and test agent
```

Run the stack locally:

```bash
cp .env.example .env
openssl rand -hex 32    # twice: MCP_TOKEN and PHONE_TOKEN
pnpm dev:mcp-server     # /mcp, the phone API and /healthz
pnpm pair               # one-time pairing QR code for the phone
pnpm agent hello "Hi"   # send a message and wait for the acknowledgement
```

More commands (`pnpm generate`, `pnpm test:integration`, `pnpm test:load`, `pnpm agent …`) are in
`package.json` and [`docs/development/`](docs/development).

## 🔐 Security

- The app never holds keys; signing happens in the wallet, after you approve.
- Transactions are decoded and checked on the phone before the wallet opens.
- Servers are yours and credentials stay on your infrastructure. Firebase push is optional and off
  by default.

Read the full model in [`docs/security.md`](docs/security.md). Please report vulnerabilities
privately through [GitHub security advisories](https://github.com/SeekerAgentConnect/sac/security/advisories/new)
rather than public issues.

## 📚 Documentation

| | |
| --- | --- |
| 📖 [Guides](docs/guides) | Installation, pairing, wallet setup, transfers, policies, self-hosting, troubleshooting |
| 🔌 [Integrations](docs/integrations) | Hermes, Claude, Jupiter and other external services |
| 🧠 [Wiki](docs/wiki) | How each feature works |
| 📡 [Protocol](docs/protocol.md) | The wire contract between servers and the app |
| 🧑‍💻 [Development](docs/development) | Toolchain, testing, releases |
| 📝 [Changelog](docs/changelog) | What changed, day by day |

## 🙌 Contributing

Issues and pull requests are welcome. Before opening a PR, run `pnpm check` (and
`pnpm check:android` for app changes). CI runs the Node, Go, Android and emulator checks on every
pull request.

## 📄 License

[MIT](LICENSE) © Renat Berezovsky
