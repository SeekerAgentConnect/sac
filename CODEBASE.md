# Codebase Map

> Keep updated — see CLAUDE.md for rules.

## Project Overview

`seeker-vault` (repo: SeekerAgentWallet) is an Android app for the Solana Seeker phone that acts as a control center for requests from external AI agents, plus the self-hosted server software it talks to. Agents propose actions (message signing, transfers, Jupiter swaps) over MCP to a self-hosted TypeScript sidecar; the user reviews each request on the phone against per-connection policies and approves it through Mobile Wallet Adapter and Seed Vault Wallet. The sidecar never holds keys or signs. Stack: Kotlin/Compose (Android), TypeScript/Node (sidecar, test agent), Protobuf + Buf + Connect (phone–sidecar contract), Docker Compose (gateway/deployment).

**Status:** Stage 1 (wallet-free hello world) is in progress. The SAW-001 bootstrap is done: pnpm workspace, sidecar skeleton (configuration only), empty Android app, Buf configuration, and CI. Commands and milestone status are in `README.md`, and agent rules in `AGENTS.md`.

## Directory Structure

| Path | Description |
| ---- | ----------- |
| `.claude/` | Claude Code project settings, plans, and task notes |
| `.claude/plans/` | Task plans with checkable items |
| `.claude/tasks/` | `lessons.md` (learned patterns) and `decisions.md` (architectural decisions) |
| `.github/workflows/` | `ci.yml`: Node job (`pnpm check`, `pnpm build`) and Android job (`pnpm check:android`); read-only permissions, actions pinned by SHA |
| `android/` | Gradle build: wrapper 9.7.1, AGP 9.4 with built-in Kotlin, catalog `gradle/libs.versions.toml`, JDK pin `gradle/gradle-daemon-jvm.properties`, Spotless/ktfmt in `build.gradle.kts`; one `app` module |
| `android/app/src/main/` | `AndroidManifest.xml`; `java/io/github/brrenat/seekervault/MainActivity.kt` (stock Compose bootstrap screen); `res/` (strings, platform day/night themes, `xml/data_extraction_rules.xml`) |
| `sidecar/` | TypeScript/Node package `@seeker-vault/sidecar`; runs `.ts` via Node type stripping; `tsconfig.json` (typecheck) and `tsconfig.build.json` (emit to `dist/`) |
| `sidecar/src/` | `config.ts` (Stage 1 environment validation), `config.test.ts` (`node:test`), `main.ts` (`pnpm dev:sidecar` entry) |
| `proto/` | Buf module for the Protobuf contract; empty until SAW-002 |
| `test-agent/` | Placeholder README; the MCP test client lands in SAW-005 |
| `gateway/` | Placeholder README; Docker/TLS/OAuth gateway lands in Stage 7 |
| `docs/` | Project documentation |
| `docs/development/` | Developer docs: `toolchain.md` (pinned versions, MacBook setup, Studio/terminal compatibility, verification record) |
| `docs/changelog/` | Release notes and change logs |
| `docs/wiki/` | Feature documentation |
| `docs/guide/` | Integration guides (MWA, Jupiter, MCP clients, gateways) |

## Key Files

| File | Description |
| ---- | ----------- |
| `README.md` | Overview, milestone status, prerequisites, bootstrap/build commands, command status table |
| `AGENTS.md` | Rules for coding agents: stage boundaries, stock UI, tests, documentation |
| `RFC.md` | MVP implementation plan: scope, components, core workflow, protocol, policies, stages, readiness criteria |
| `CLAUDE.md` | Claude Code working conventions for this repo |
| `CODEBASE.md` | This structural map |
| `LICENSE` | Project license |
| `package.json` | Root command contract (`check`, `check:android`, `build`, `generate`, `dev:sidecar`, `agent`, `test:hello`, `format`), pinned pnpm (`packageManager`) and Node (`engines`), root dev tools |
| `pnpm-workspace.yaml` | Workspace packages, TypeScript `catalog` version, `allowBuilds` |
| `.nvmrc` | Pinned Node version |
| `.env.example` | Stage 1 development variables (placeholders only; `.env` is ignored) |
| `buf.yaml`, `buf.gen.yaml` | Buf module config and pinned generators (TypeScript → `sidecar/src/gen`, Kotlin → `android/app/src/main/generated`) |
| `eslint.config.js`, `.prettierignore` | TypeScript lint (typescript-eslint, type-checked) and Prettier scope |
| `.claude/settings.json` | Shared Claude Code permissions (tracked) |
| `.claude/settings.local.json` | Local Claude Code settings (enabled plugins) |

## Architecture

**Stage 1 (current; planned in SAW-002–SAW-004):**

- Hermes or the test agent calls the MCP tool `vault_display_command` on the sidecar.
- The sidecar delivers the text over a foreground-only `WatchCommands` stream to the open Android screen.
- The user taps OK, and `AcknowledgeCommand` returns the result to the MCP caller.
- Everything is in memory; the sidecar is loopback-only (remote agents use an SSH tunnel), and no wallet is involved.
- Today only `sidecar/src/config.ts` exists (environment validation for `pnpm dev:sidecar`).

Later stages (see `RFC.md` §3–6):

- **Agent → sidecar (MCP):** agents call `vault_*` MCP tools (`get_address`, `get_capabilities`, `sign_message`, `transfer`, `swap`, `get_request`). The sidecar persists an *action request* (not a prebuilt transaction) and immediately returns `request_id` + pending; retries must not create duplicate payments.
- **Phone → sidecar (Connect API, unary):** the Android app pairs via QR code (server address + one-time token), then calls `Pair`, `ListPending`/`GetRequest`, `PrepareRequest`, `SubmitResult`. Pending requests are fetched on app open/refresh; there's no push or persistent stream.
- **Transaction prep:** the sidecar builds a fresh unsigned transaction at review time (blockhash expiry); swaps use Jupiter `/build`. Approval is bound to a specific `PreparedTransaction` version.
- **Policy:** policies live and are enforced only on the phone, per connection. The phone parses the actual transaction and produces a `PolicyEvaluation` of `ALLOWED` or `UNDER_RESTRICTIONS`; unrecognized contents are marked unverified. Manual approval is always required.
- **Signing:** the phone invokes MWA `signAndSendTransactions` from an Activity; Seed Vault Wallet signs and sends. The result returns to the sidecar and then to the agent. On-chain confirmation is checked separately from MWA's submission response.
- **Contract:** `proto/` is the single source of truth, and both `sidecar/` and `android/` consume generated code.
- **Deployment:** `docker compose up` runs the sidecar (persistent storage), gateway (TLS, optional OAuth), and test agent.

## Commands

| Command | Purpose |
| ------- | ------- |
| `pnpm install --frozen-lockfile` | Reproducible install |
| `pnpm check` | Prettier, ESLint, `tsc`, sidecar tests (no file changes) |
| `pnpm check:android` | Spotless, Android unit tests, lint, debug APK |
| `pnpm build` | Sidecar → `sidecar/dist` |
| `pnpm dev:sidecar` | Validate Stage 1 config from `.env`/environment |
| `pnpm generate` | `buf generate` (fails until SAW-002 adds protos) |
