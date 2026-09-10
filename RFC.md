# seeker-vault — MVP Implementation Plan

**Working name:** `seeker-vault`  
**Revision:** September 10, 2026  
**Purpose:** a master plan to be broken down into implementation tasks.

> Agents propose actions. The user reviews them on Seeker and approves them through the wallet.

## 1. Product and MVP Scope

An Android app for Seeker that serves as a control center for requests from external AI agents. The user connects multiple self-hosted MCP servers, configures rules for each one, and manually approves operations through Seed Vault Wallet and Mobile Wallet Adapter (MWA).

We provide the app and self-hosted server software. The user deploys the server alongside their agent or on a separate gateway. No cloud service or account with us is required; the user's own infrastructure, Solana RPC, and external APIs remain part of the system.

**Included in the first version:** connections, a request queue, manual approval, transfers, Jupiter swaps, a policy builder, activity history, Docker packaging, and a test agent.

**Outside the first version:** a separate agent key, automatic signing, a separate biometric authentication flow in our app, a mandatory foreground service, a persistent bidirectional stream, push notifications, and SKR staking.

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

**Communication:** the agent uses MCP; the phone uses a separate Connect API. The MVP uses unary RPCs and fetches pending requests when the app opens or the user refreshes the screen.

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

MWA is invoked from an Activity through `ActivityResultSender`; a dedicated Activity is not an architectural requirement. For transactions, we use `signAndSendTransactions`: the wallet handles both signing and sending. The first end-to-end test uses message signing. [MWA documentation][mwa]

The queue stores an **action request**, not a prebuilt transaction. The sidecar builds the transaction when the user reviews the request, because a recent blockhash has a limited validity period. If the transaction needs to be refreshed, the phone checks it again and displays the updated parameters before approval. [Transaction confirmation and expiration][confirmation]

For swaps, we use Jupiter `/build`: retrieve the instructions, build the transaction on the sidecar, and pass it to the phone for inspection and submission through MWA. The Jupiter API key and RPC settings are configured in the user's infrastructure, not embedded in the APK. [Jupiter Build][jupiter]

## 4. Protocol and Component Responsibilities

### Connections

Each connection stores the server address, credentials, name, its own policy, and activity history. Request IDs are scoped by connection ID.

Pairing uses a QR code containing the server address and a one-time token, and runs over a secure connection. The agent's MCP access is separate from the phone's access to the approval API. Connection revocation and secure credential storage are included.

### Phone–Sidecar Contract

| Entity / Operation | Purpose |
|---|---|
| `Pair` | Pair the phone with the server |
| `ListPending` / `GetRequest` | Retrieve the queue or an individual request |
| `PrepareRequest` | Prepare a transaction for the current review |
| `SubmitResult` | Submit the user's decision, a message signature, or the transaction submission result |
| `ActionRequest` | ID, action type, parameters, target wallet address, network, expiration, and state |
| `PreparedTransaction` | A specific version of an unsigned transaction and its validity parameters |
| `PolicyEvaluation` | A local assessment and warning reasons; not an execution state |

Names are provisional; detailed fields will be defined when the plan is broken down into tasks. Approval is bound to a specific transaction version, not just the request ID.

### MCP Tools

| Tool | Purpose |
|---|---|
| `vault_get_address` | Get the connected wallet address and network |
| `vault_get_capabilities` | Get supported actions and the manual approval mode |
| `vault_sign_message` | Request manual message signing; the first end-to-end test |
| `vault_transfer` | Request a transfer |
| `vault_swap` | Request a swap through Jupiter |
| `vault_get_request` | Get a request's state and result |

Creating a request returns a `request_id` without keeping the MCP call open until the user decides. Retrying the same request must not create a duplicate payment.

## 5. Policies and User Interface

Policies are edited and enforced **only on the phone**, separately for each connection. The agent cannot change the rules. A full copy of the policy is not sent to the sidecar.

The builder includes action types, assets, recipients, programs, a per-transaction threshold, and a daily threshold. In the first version, monetary thresholds are expressed in units of the selected asset, without requiring conversion to dollars. The daily counter covers operations through our app, not all wallet activity.

| Assessment | Behavior |
|---|---|
| `ALLOWED` | Verified parameters match the rules; manual approval is still required |
| `UNDER_RESTRICTIONS` | Some parameters fall outside the rules; show the reasons and leave the decision to the user |

The MVP does not introduce `BLOCKED` as a separate policy result. Technically invalid requests are not executed, regardless of the rules.

**`ALLOWED` does not mean that a transaction is guaranteed to be safe.** The assessment is based on the actual transaction data, not the agent's description. Unrecognized contents are explicitly marked as unverified and are not classified as `ALLOWED`. The agent's explanation is displayed separately from the verified parameters.

Main screens: **Connections → Connection details → Policy builder → Pending requests → Request details → Activity**. The request card shows the source, network, wallet, action, amounts, recipient, fees, assessment result, and warning reasons.

## 6. Implementation Stages

| Stage | Scope | Outcome |
|---|---|---|
| **1. Foundation and contract** | Monorepo, Buf, code generation, basic CI, connection and request models | Components build and use a shared contract |
| **2. End-to-end workflow** | Pairing, persistent queue, test MCP client, fetching pending requests, message signing through MWA, returning the result | The agent receives the result of an actual manual approval on Seeker |
| **3. Transfers and policies** | Fresh transaction building, on-phone parsing, Policy Builder, MWA sign-and-send, activity history, multiple connections | A transfer completes the full workflow with an `ALLOWED` or `UNDER_RESTRICTIONS` assessment |
| **4. Jupiter** | `/build`, swap parameter checks, output amount and slippage display, refreshing expired transactions | Swaps use the same review and approval workflow |
| **5. Reliability** | Retries, rejections, expiration, restarts, wallet changes, connection loss after submission | Retries do not cause duplicate execution; uncertain outcomes are clearly reported |
| **6. Packaging and submission** | Docker, gateway, test agent, real-agent integration, documentation, APK, demo, and presentation | The project can be deployed, installed, and tested by following the instructions |

A successful submission response from MWA is not a substitute for on-chain confirmation. After a timeout, first determine the outcome of the previous operation instead of automatically building and sending a new one. [Solana documentation][confirmation]

## 7. Self-Hosting and Readiness Criteria

The target deployment is `docker compose up`: an MCP/Connect sidecar with persistent storage, a gateway with TLS and optional OAuth configuration, and a test agent as a separate service or profile. The repository includes `.env.example` and instructions for obtaining the pairing QR code.

For hosted MCP clients, choose an existing gateway that supports MCP authorization and verify compatibility with a specific client. An arbitrary OAuth proxy is not considered sufficient: discovery and access token validation must follow MCP requirements. [MCP Authorization][mcp-auth]

The test agent uses the same MCP interface and can request a wallet address, message signing, a transfer, a swap, and a request status. Hermes is the first real agent to validate; Claude through an OAuth gateway is a separate integration scenario.

**The MVP is ready** when, after deployment and pairing, the user can receive requests on a physical Seeker, inspect verified parameters and the policy assessment, approve or reject an operation, and have the correct result returned to the agent. Activity history, pending requests, and connections survive restarts. External RPC/API dependencies and limitations are explicitly documented.

**Submission package:** a functional APK, a GitHub repository that can be cloned and run, a README, a three-minute demo, and a short presentation. Demo flow: connect → an `ALLOWED` operation with manual approval → an `UNDER_RESTRICTIONS` operation with an explanation and rejection by the user.

---

## Technical References

Official documentation checked on September 10, 2026:

- [Solana Mobile: Android MWA][mwa]
- [Solana: Transaction Confirmation & Expiration][confirmation]
- [Jupiter: Build][jupiter]
- [MCP: Authorization, specification 2025-11-25][mcp-auth]

[mwa]: https://docs.solanamobile.com/android-native/using_mobile_wallet_adapter
[confirmation]: https://solana.com/developers/cookbook/transactions/confirmation
[jupiter]: https://developers.jup.ag/docs/swap/build
[mcp-auth]: https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
