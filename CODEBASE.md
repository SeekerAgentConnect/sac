# Codebase Map

> Keep updated — see CLAUDE.md for rules.

## Project Overview

`seeker-vault` (repo: SeekerAgentWallet) is an Android app for the Solana Seeker phone that acts as a control center for requests from external AI agents, plus the self-hosted server software it talks to. Agents propose actions (message signing, transfers, Jupiter swaps) over MCP to a self-hosted TypeScript sidecar; the user reviews each request on the phone against per-connection policies and approves it through Mobile Wallet Adapter and Seed Vault Wallet. The sidecar never holds keys or signs. Planned stack: Kotlin/Compose (Android), TypeScript/Node (sidecar, test agent), Protobuf + Buf + Connect (phone–sidecar contract), Docker Compose (gateway/deployment).

**Status:** pre-implementation. Only the plan (`RFC.md`) exists; the component directories below are planned in RFC §2 and not yet created.

## Directory Structure

| Path | Description |
| ---- | ----------- |
| `.claude/` | Claude Code project settings, plans, and task notes |
| `.claude/plans/` | Task plans with checkable items |
| `.claude/tasks/` | `lessons.md` (learned patterns) and `decisions.md` (architectural decisions) |
| `docs/` | Project documentation (changelog, wiki, guide, dev); RFC §2 also plans architecture/protocol/policy/setup docs here |
| `docs/changelog/` | Release notes and change logs |
| `docs/wiki/` | Feature documentation |
| `docs/guide/` | Integration guides (MWA, Jupiter, MCP clients, gateways) |
| `docs/dev/` | Developer processes, CI, tooling |
| `proto/` | *(planned)* Protobuf contract with Buf; generates Kotlin and TypeScript code |
| `sidecar/` | *(planned)* TypeScript/Node: MCP server, Connect API, persistent request queue, transaction building |
| `android/` | *(planned)* Kotlin/Compose app: connections, policies, requests, MWA, activity history |
| `gateway/` | *(planned)* Docker Compose, TLS, OAuth/MCP gateway configuration |
| `test-agent/` | *(planned)* Minimal MCP client for testing and demos |

## Key Files

| File | Description |
| ---- | ----------- |
| `RFC.md` | MVP implementation plan: scope, components, core workflow, protocol, policies, stages, readiness criteria |
| `CLAUDE.md` | Claude Code working conventions for this repo |
| `CODEBASE.md` | This structural map |
| `LICENSE` | Project license |
| `.claude/settings.json` | Shared Claude Code permissions (tracked) |
| `.claude/settings.local.json` | Local Claude Code settings (enabled plugins) |

## Architecture

Planned (see `RFC.md` §3–4):

- **Agent → sidecar (MCP):** agents call `vault_*` MCP tools (`get_address`, `get_capabilities`, `sign_message`, `transfer`, `swap`, `get_request`). The sidecar persists an *action request* (not a prebuilt transaction) and immediately returns `request_id` + pending; retries must not create duplicate payments.
- **Phone → sidecar (Connect API, unary):** the Android app pairs via QR code (server address + one-time token), then calls `Pair`, `ListPending`/`GetRequest`, `PrepareRequest`, `SubmitResult`. Pending requests are fetched on app open/refresh; there's no push or streaming in the MVP.
- **Transaction prep:** the sidecar builds a fresh unsigned transaction at review time (blockhash expiry); swaps use Jupiter `/build`. Approval is bound to a specific `PreparedTransaction` version.
- **Policy:** policies live and are enforced only on the phone, per connection. The phone parses the actual transaction and produces a `PolicyEvaluation` of `ALLOWED` or `UNDER_RESTRICTIONS`; unrecognized contents are marked unverified. Manual approval is always required.
- **Signing:** the phone invokes MWA `signAndSendTransactions` from an Activity; Seed Vault Wallet signs and sends. The result returns to the sidecar and then to the agent. On-chain confirmation is checked separately from MWA's submission response.
- **Contract:** `proto/` is the single source of truth, and both `sidecar/` and `android/` consume generated code.
- **Deployment:** `docker compose up` runs the sidecar (persistent storage), gateway (TLS, optional OAuth), and test agent.
