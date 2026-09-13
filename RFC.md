# seeker-vault — MVP Implementation Plan

**Working name:** `seeker-vault`  
**Revision:** September 14, 2026. Stage 5.2 adds live foreground observation and eventual background synchronization without automatic wallet action.

**Purpose:** a master plan to be broken down into implementation tasks.

> Agents propose actions. The user reviews them on Seeker and approves them through the wallet.

## 1. Product and MVP Scope

An Android app for Seeker that serves as a control center for requests from external AI agents. The user connects multiple self-hosted MCP servers, configures rules for each one, and manually approves operations through Seed Vault Wallet and Mobile Wallet Adapter (MWA).

We provide the app and self-hosted server software. The user deploys the server alongside their agent or on a separate gateway. No cloud service or account with us is required; the user's own infrastructure, Solana RPC, and external APIs remain part of the system.

**Included in the first version:** connections, a request queue, manual approval, transfers, Jupiter swaps, a policy builder, activity history, authenticated foreground updates, eventual WorkManager synchronization, Docker packaging, and a test agent.

**Outside the first version:** a separate agent key, automatic signing, a separate biometric authentication flow in our app, a mandatory foreground service, an always-on/background stream, push notifications, and SKR staking. Stage 5.2's bidirectional stream exists only while the app is foreground; background observation is a constrained periodic unary sync and has no exact-delivery promise.

**First milestone:** before any wallet work, Stage 1 proves the transport end to end with display-only text. A real agent sends a message over MCP, and the Seeker displays it while the app is open. The user taps OK, and the agent receives the acknowledgement. Stage 1 uses no wallet, keys, queue, persistence, policies, QR pairing, OAuth, Docker deployment, or background service.

## 2. Components and Repository

```text
proto/         Protobuf contract, Buf, Kotlin and TypeScript code generation
sidecar/       TypeScript/Node: MCP, Connect API, queue, transaction building
android/       Kotlin/Compose: connections, policies, requests, MWA, history
gateway/       Docker Compose, TLS, OAuth/MCP gateway configuration
test-agent/    Minimal MCP client for testing and demos
docs/          Architecture, protocol, policies, setup, and integrations
```

**Android:** Kotlin, Compose, Material 3, `connect-kotlin`, `mobile-wallet-adapter-clientlib-ktx`, and a QR scanner. We will select the SDK for parsing Solana transactions during integration validation; `sol4k` is a candidate, not a mandatory dependency.

**Sidecar:** TypeScript, Node.js, `@connectrpc/connect-node`, `@modelcontextprotocol/sdk`, a Solana SDK, Jupiter API, and a persistent local queue. The sidecar does not store wallet private keys or sign transactions.

**Communication:** the agent uses MCP; the phone uses a separate authenticated API. Stage 2 begins with unary Connect RPCs. Stage 5.2 adds a production bidirectional gRPC stream over HTTP/2 while the app process is foreground and a unary gRPC Sync shared by recovery, Refresh, and WorkManager. The phone retains one connected-network-constrained periodic job with Android's 15-minute minimum interval while it has usable connections; WorkManager may defer it and Force stop suppresses it until the owner reopens the app. Both transports observe the same durable request store and are scoped per phone connection. Stage 1's diagnostic server stream remains separate, screen-scoped, and unnecessary to the durable workflow.

## 3. Core Workflow

```text
Agent → MCP: propose an action
             ↓
Sidecar: persist the request, return request_id and pending
             ↓
Seeker: fetch the queue, open a request
             ↓
Sidecar: prepare a fresh unsigned transaction
             ↓
Seeker: inspect the contents, apply rules, display the request card
             ↓
User: Reject or Approve → MWA → Seed Vault Wallet
             ↓
Wallet: sign and send → transaction status → result returned to the agent
```

MWA is invoked from an Activity through `ActivityResultSender`; a dedicated Activity is not an architectural requirement. For transactions, we use `signAndSendTransactions`: the wallet handles both signing and sending. The first wallet end-to-end test uses message signing (Stage 3). Before that, Stage 1 exercises the same agent → phone → agent path with display-only text and no wallet. [MWA documentation][mwa]

The queue stores an **action request**, not a prebuilt transaction. The sidecar builds the transaction when the user reviews the request, because a recent blockhash has a limited validity period. If the transaction needs to be refreshed, the phone checks it again and displays the updated parameters before approval. [Transaction confirmation and expiration][confirmation]

For swaps, we use Jupiter `/build`: retrieve the instructions, build the transaction on the sidecar, and pass it to the phone for inspection and submission through MWA. The Jupiter API key and RPC settings are configured in the user's infrastructure, not embedded in the APK. [Jupiter Build][jupiter]

## 4. Protocol and Component Responsibilities

### Connections

Each connection stores the server address, credentials, name, its own policy overrides, and activity history. The phone also stores one global policy whose defaults apply to every connection. Request IDs are scoped by connection ID.

Pairing uses a QR code containing the server address and a one-time token, and runs over a secure connection. The agent's MCP access is separate from the phone's access to the approval API. Connection revocation and secure credential storage are included.

### Phone–Sidecar Contract

| Entity / Operation | Purpose |
|---|---|
| `WatchCommands` / `AcknowledgeCommand` | Stage 1 diagnostic: stream display-only commands to the open live-test screen and return the user's OK (see `docs/protocol.md`) |
| `LiveCommand` / `CommandAcknowledgement` | Stage 1 display-only text with an ID and a deadline, and the user's OK |
| `Pair` / `RevokeConnection` | Pair the phone with the server; end that connection |
| `GetConnectionCapabilities` / `UpdateCapability` | Discover the versioned HTTP/2 gRPC origin for new and already-saved pairings; old sidecars are explicitly upgrade-required |
| `ListPending` / `GetRequest` | Retrieve the queue or an individual request |
| `PrepareRequest` | Prepare a transaction for the current review |
| `SubmitResult` | Submit the user's decision, a message signature, or the transaction submission result |
| `ActionRequest` | ID, action type, parameters, target wallet address, network, expiration, and state |
| `RequestState` | The execution state, from PENDING to CONFIRMED, COMPLETED, REJECTED, CANCELLED, EXPIRED, FAILED, or UNKNOWN |
| `PreparedTransaction` | A specific version of an unsigned transaction and its validity parameters |
| `PolicyEvaluation` | A local assessment and warning reasons; not an execution state |
| `UpdateService.Subscribe` | One authenticated foreground bidirectional stream per usable connection: subscribe/resume, bounded heartbeats, request changes/removals, revocation, and full-sync signals |
| `UpdateService.Sync` | A frozen paginated snapshot of pending and known nonterminal Activity requests, with bounded read-only confirmation |

SAW-009 defined the Stage 2 contract: the fields, the lifecycle, idempotency, and the errors are in `docs/protocol.md`. Approval is bound to a specific transaction version, not just the request ID.

### MCP Tools

| Tool | Purpose |
|---|---|
| `vault_display_command` | Stage 1 diagnostic: show display-only text on the phone and wait for the user's OK; no wallet involved |
| `vault_get_address` | Get the connected wallet address and network |
| `vault_get_capabilities` | Get supported actions and the manual approval mode |
| `vault_sign_message` | Request manual message signing; the first wallet end-to-end test |
| `vault_transfer` | Request a transfer |
| `vault_swap` | Request a swap through Jupiter |
| `vault_get_request` | Get a request's state and result |
| `vault_cancel_request` | Withdraw a request the user hasn't approved yet |
| `vault_request_ack` | Development and demo only: queue an acknowledgement, to test the durable workflow without a wallet |

Creating a request returns a `request_id` without keeping the MCP call open until the user decides. Retrying the same request must not create a duplicate payment, so every creation carries an idempotency key.

## 5. Policies and User Interface

Policies are edited and evaluated **only on the phone**. One global document supplies defaults, and each connection may replace a section in full, explicitly configure no check, or inherit it; global and connection daily thresholds remain separate additive checks. The agent cannot change the rules. A full copy of the policy is not sent to the sidecar.

The builder includes action types, assets, recipients, programs, a per-transaction threshold, and a daily threshold. In the first version, monetary thresholds are expressed in units of the selected asset, without requiring conversion to dollars. The daily counter covers operations through our app, not all wallet activity.

| Assessment | Behavior |
|---|---|
| `ALLOWED` | Verified parameters match the rules; manual approval is still required |
| `UNDER_RESTRICTIONS` | Some parameters fall outside the rules; show the reasons and leave the decision to the user |

The MVP does not introduce `BLOCKED` as a separate policy result. Technically invalid requests are not executed, regardless of the rules.

**`ALLOWED` does not mean that a transaction is guaranteed to be safe.** The assessment is based on the actual transaction data, not the agent's description. Unrecognized contents are explicitly marked as unverified and are not classified as `ALLOWED`. The agent's explanation is displayed separately from the verified parameters.

Main screens: **Connections → Connection details → Policy builder → Pending requests → Request details → Activity**. The request card shows the source, network, wallet, action, amounts, recipient, fees, assessment result, and warning reasons.

## 6. Implementation Stages

This table was revised on September 11, 2026:

- Stage 1 is now a wallet-free hello world.
- The earlier end-to-end stage is split into persistent requests (Stage 2) and wallet message signing (Stage 3).
- Policies are now a stage of their own (Stage 5).
- Stage 5.2 adds foreground updates and eventual WorkManager sync; FCM stays in a later stage.
- Reliability work is built into every stage, with a final regression pass in Stage 8.

| Stage | Scope | Outcome |
|---|---|---|
| **1. Hello world (no wallet)** | Monorepo, toolchains, and CI; a minimal live-command protocol with Buf; the MCP command bridge; a stock Android hello screen; the MCP test client; the MacBook → Seeker guide; a real Hermes connection | A real agent's text appears on a physical Seeker, the user taps OK, and the agent receives the acknowledgement |
| **2. Persistent requests and connections** | Durable request contract and lifecycle; a persistent sidecar queue with async MCP results; secure pairing with separate agent and phone roles; multiple connections; a pending inbox | Requests survive restarts and are fetched when the app opens; several self-hosted servers can be connected |
| **3. Wallet connection and message signing** | MWA integration bound to a wallet and network; async message signing; wallet lifecycle and reliable result delivery | The agent receives the result of an actual manual signature on Seeker |
| **4. Transfers** | Fresh transaction preparation; independent on-phone parsing; MWA sign-and-send; on-chain confirmation and recovery of uncertain outcomes; activity history | A transfer completes the full workflow, and its outcome is confirmed on chain |
| **5. Policies** | Policy model and evaluation semantics; global defaults and connection overrides; connection-wide and global daily counters; the Policy Builder; policy results in request review | A transfer shows an `ALLOWED` or `UNDER_RESTRICTIONS` assessment with its reasons and rule sources |
| **5.2 Live and background updates** | Versioned update protocol; full-duplex gRPC/HTTP/2; cursor replay and frozen reconciliation; shared Android state; foreground lifecycle; unique network-constrained WorkManager sync | Open screens update without Refresh, missed changes converge after disconnect/restart, and delayed background results survive process death without opening a wallet |
| **6. Jupiter** | `/build`; swap parameter checks; output amount and slippage display; refreshing expired transactions | Swaps use the same review and approval workflow |
| **7. Packaging and integrations** | Docker Compose; a TLS gateway; an OAuth gateway for hosted MCP clients; the test-agent CLI; real Hermes integration; a self-hosting guide | The project can be deployed and connected by following the instructions |
| **8. Release and submission** | Cross-component reliability and security regression checks; a signed release APK; verified guides; the demo and presentation | Retries don't cause duplicate execution; uncertain outcomes are clearly reported; the release can be installed and reproduced |

A successful submission response from MWA is not a substitute for on-chain confirmation. After a timeout, first determine the outcome of the previous operation instead of automatically building and sending a new one. [Solana documentation][confirmation]

## 7. Self-Hosting and Readiness Criteria

The target deployment is `docker compose up`: an MCP/Connect sidecar with persistent storage, a gateway with TLS and optional OAuth configuration, and a test agent as a separate service or profile. The repository includes `.env.example` and instructions for obtaining the pairing QR code.

For hosted MCP clients, choose an existing gateway that supports MCP authorization and verify compatibility with a specific client. An arbitrary OAuth proxy is not considered sufficient: discovery and access token validation must follow MCP requirements. [MCP Authorization][mcp-auth]

The test agent uses the same MCP interface and can request a wallet address, message signing, a transfer, a swap, and a request status. Hermes is the first real agent to validate; Claude through an OAuth gateway is a separate integration scenario.

**The MVP is ready** when, after deployment and pairing, the user can receive requests on a physical Seeker, inspect verified parameters and the policy assessment, approve or reject an operation, and have the correct result returned to the agent. Activity history, pending requests, and connections survive restarts. External RPC/API dependencies and limitations are explicitly documented.

**Submission package:** a functional APK, a GitHub repository that can be cloned and run, a README, a three-minute demo, and a short presentation. Demo flow: connect → an `ALLOWED` operation with manual approval → an `UNDER_RESTRICTIONS` operation with an explanation and rejection by the user.

---

## Technical References

Official documentation checked through September 13, 2026:

- [Solana Mobile: Android MWA][mwa]
- [Solana: Transaction Confirmation & Expiration][confirmation]
- [Jupiter: Build][jupiter]
- [MCP: Authorization, specification 2025-11-25][mcp-auth]
- [Connect Node server plugins and HTTP/2][connect-node]

[mwa]: https://docs.solanamobile.com/android-native/using_mobile_wallet_adapter
[confirmation]: https://solana.com/developers/cookbook/transactions/confirmation
[jupiter]: https://developers.jup.ag/docs/swap/build
[mcp-auth]: https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
[connect-node]: https://connectrpc.com/docs/node/server-plugins/
