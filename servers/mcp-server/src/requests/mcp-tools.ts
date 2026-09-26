/**
 * The agent's durable request tools (docs/protocol.md). vault_sign_message asks the owner's wallet
 * for a signature, vault_request_ack queues an acknowledgement, vault_get_request reads a request,
 * and vault_cancel_request withdraws one; vault_get_address and vault_get_capabilities only read.
 * Every call answers at once; none of them waits for the owner. vault_request_ack is a
 * development and demo tool, served only when MCP_DEMO_TOOLS is set.
 */
import { create } from "@bufbuild/protobuf";
import { timestampDate, type Timestamp } from "@bufbuild/protobuf/wkt";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

import {
  MAX_BASE_UNITS,
  MAX_COMMAND_TEXT_BYTES,
  MAX_EXPIRES_IN_SECONDS,
  MAX_MESSAGE_BYTES,
  MAX_NOTE_BYTES,
  MIN_EXPIRES_IN_SECONDS,
  RequestFailure,
  actionBinding,
  encodeBase58,
  invalidActionReason,
  isTerminal,
  messageBytes,
  networkName,
  privateRequest,
  type AgentRequests,
  type AgentTransfers,
} from "@seeker_agent_connect/server-sdk";
import {
  ActionSchema,
  AssetSchema,
  ConfirmationLevel,
  Network,
  RequestError,
  RequestState,
  type Action,
  type ActionRequest,
  type Confirmation,
  type WalletBinding,
} from "@seeker_agent_connect/server-sdk/protocol";

export const GET_ADDRESS_TOOL = "vault_get_address";
export const GET_CAPABILITIES_TOOL = "vault_get_capabilities";
export const SIGN_MESSAGE_TOOL = "vault_sign_message";
export const TRANSFER_TOOL = "vault_transfer";
export const REQUEST_ACK_TOOL = "vault_request_ack";
export const GET_REQUEST_TOOL = "vault_get_request";
export const CANCEL_REQUEST_TOOL = "vault_cancel_request";

// Every action the protocol has a name for, which is what a view of a request may report. It is
// not the list this sidecar serves: that one is `operations` in vault_get_capabilities, and it is
// built from the tools actually wired up. A staking request is created by the standalone staking
// server (SEE-146) and never by this one, but a client reading this schema still has to be able to
// read one back if it ever sees it.
const ACTIONS = ["ack", "sign_message", "transfer", "swap", "staking"] as const;
const STATUSES = [
  "PENDING",
  "PROCESSING",
  "SUBMITTED",
  "CONFIRMED",
  "COMPLETED",
  "REJECTED",
  "CANCELLED",
  "EXPIRED",
  "FAILED",
  "UNKNOWN",
] as const;

const LEVELS = ["not_found", "processed", "confirmed", "finalized"] as const;

/** Each ConfirmationLevel's name for agents; UNSPECIFIED has none, and is left out. */
const LEVEL_NAMES: ReadonlyMap<ConfirmationLevel, (typeof LEVELS)[number]> =
  new Map([
    [ConfirmationLevel.NOT_FOUND, "not_found"],
    [ConfirmationLevel.PROCESSED, "processed"],
    [ConfirmationLevel.CONFIRMED, "confirmed"],
    [ConfirmationLevel.FINALIZED, "finalized"],
  ]);

const NETWORKS = ["mainnet", "devnet", "testnet"] as const;

/** The Network value each name stands for; `networkName` writes them the same way. */
const NETWORK_VALUES: Readonly<Record<(typeof NETWORKS)[number], Network>> = {
  mainnet: Network.MAINNET,
  devnet: Network.DEVNET,
  testnet: Network.TESTNET,
};

/** A request as agents see it: every tool returns this in structuredContent. */
export interface RequestView {
  readonly request_id: string;
  readonly action: (typeof ACTIONS)[number];
  readonly status: (typeof STATUSES)[number];
  readonly terminal: boolean;
  readonly created_at: string;
  readonly expires_at: string;
  readonly updated_at: string;
  readonly wallet?: string;
  /** The network a wallet action names; absent for an action that names none. */
  readonly network?: (typeof NETWORKS)[number];
  readonly signature?: string;
  readonly signed_message_base64?: string;
  readonly detail?: string;
  readonly confirmation?: (typeof LEVELS)[number];
  readonly slot?: number;
  readonly chain_error?: string;
  readonly checked_at?: string;
  readonly checked_with?: string;
}

const VIEW_SCHEMA = {
  request_id: z
    .string()
    .describe("The request's ID. Keep it to read the result later."),
  action: z.enum(ACTIONS),
  status: z.enum(STATUSES),
  terminal: z.boolean().describe("True once the status can't change any more."),
  created_at: z.string(),
  expires_at: z
    .string()
    .describe(
      "The deadline for the owner's decision, while the request is PENDING.",
    ),
  updated_at: z.string(),
  wallet: z
    .string()
    .optional()
    .describe("The wallet the request is bound to, for a wallet action."),
  network: z
    .enum(NETWORKS)
    .optional()
    .describe(
      "The cluster the request is bound to, for an action that names one. A signature belongs to one cluster and to no other; read it before writing an explorer link.",
    ),
  signature: z
    .string()
    .optional()
    .describe("The wallet's signature in base58, once there is one."),
  signed_message_base64: z
    .string()
    .optional()
    .describe(
      "For a signed message: exactly the bytes the wallet signed, in base64. Verify the signature against these bytes and wallet, rather than re-encoding the message yourself.",
    ),
  detail: z
    .string()
    .optional()
    .describe("Display text that explains how the request ended."),
  confirmation: z
    .enum(LEVELS)
    .optional()
    .describe(
      "How far the transaction had got the last time the chain was asked. It is not the status: a finalized transaction that failed on chain leaves the request FAILED.",
    ),
  slot: z
    .number()
    .optional()
    .describe("The slot the transaction landed in, once it has been seen."),
  chain_error: z
    .string()
    .optional()
    .describe("The chain's own error, when the transaction ran and failed."),
  checked_at: z
    .string()
    .optional()
    .describe("When the chain was last asked about the signature."),
  checked_with: z
    .string()
    .optional()
    .describe(
      "The host of the single Solana RPC endpoint this result rests on. CONFIRMED and FAILED are that one endpoint's word, checked against the exact transaction the owner approved.",
    ),
};

/** The owner's wallet as agents see it: a public address, never a key. */
export interface AddressView {
  readonly wallet: string;
  readonly network: (typeof NETWORKS)[number];
  readonly bound_at: string;
}

const ADDRESS_SCHEMA = {
  wallet: z
    .string()
    .describe("The owner's wallet, as a base58 Solana address (a public key)."),
  network: z
    .enum(NETWORKS)
    .describe("The network the owner selected: the chain is solana:<network>."),
  bound_at: z
    .string()
    .describe("When the phone last published this wallet to the sidecar."),
};

/** What this sidecar can actually do, as agents see it: never a promise about a later stage. */
export interface CapabilitiesView {
  readonly approval: "manual";
  readonly signing: "wallet";
  /** The action kinds this sidecar serves a creation tool for, right now. */
  readonly operations: string[];
  readonly wallet_connected: boolean;
  readonly max_message_bytes: number;
  readonly max_note_bytes: number;
  readonly max_pending_requests: number;
  readonly min_expires_in_seconds: number;
  readonly max_expires_in_seconds: number;
  /**
   * Where a confirmed or failed transfer's word comes from: the host of the one Solana RPC
   * endpoint this sidecar is configured with, or absent when it has none and can check nothing.
   */
  readonly confirmed_with?: string;
}

const CAPABILITIES_SCHEMA = {
  approval: z
    .literal("manual")
    .describe(
      "Every request waits for the owner's tap on their phone. There is no automatic approval, and no way to ask for one.",
    ),
  signing: z
    .literal("wallet")
    .describe(
      "The owner's own wallet app signs. The sidecar holds no key and signs nothing.",
    ),
  operations: z
    .array(z.string())
    .describe(
      "The actions this sidecar serves now, such as sign_message. Anything not listed here is not implemented; don't attempt it.",
    ),
  wallet_connected: z
    .boolean()
    .describe(
      "Whether the owner has a wallet connected. Read it with vault_get_address.",
    ),
  max_message_bytes: z.number(),
  max_note_bytes: z.number(),
  max_pending_requests: z
    .number()
    .describe("The most requests the owner may have waiting at once."),
  min_expires_in_seconds: z.number(),
  max_expires_in_seconds: z.number(),
  confirmed_with: z
    .string()
    .optional()
    .describe(
      "The host of the single Solana RPC endpoint that decides whether a transfer CONFIRMED or FAILED. There is no second opinion: a result rests on this one endpoint's word. Absent when none is configured, and then a sent transfer stays SUBMITTED.",
    ),
};

const GET_CAPABILITIES_DESCRIPTION =
  "Returns what this sidecar can actually do, so you don't have to guess. Approval is always " +
  "manual: the owner reviews every request on their Seeker and taps Approve, and nothing can be " +
  "carried out without them. Signing is done by the owner's own wallet app; the sidecar holds no " +
  "key and never signs. `operations` lists only what is implemented here, so treat anything " +
  "missing from it as unavailable rather than trying it. Read it once per session, before the " +
  "first request. It takes no input and never fails.";

const SIGN_MESSAGE_DESCRIPTION =
  "Asks the owner to have their wallet sign a message, and returns at once with the stored " +
  "request: its request_id and the status PENDING. Being stored is not a signature and not the " +
  "owner's approval: they see the request the next time they open the app, review the exact " +
  "bytes, and approve or reject it, and only then does the wallet sign. Read the outcome later " +
  "with vault_get_request until terminal is true. COMPLETED carries `signature` (base58), " +
  "`wallet`, and `signed_message_base64`, exactly the bytes that were signed; verify the " +
  "signature against those bytes yourself. REJECTED means the owner or the wallet declined, " +
  "EXPIRED that the deadline passed, and FAILED that the wallet could not sign. A signature " +
  "proves the owner's wallet signed those bytes; it moves no funds and sends nothing on chain. " +
  "The message is text, and its UTF-8 encoding is signed exactly as given, never trimmed, " +
  "normalized, or re-encoded; there is no way to ask for bytes that aren't text, because the " +
  "owner reviews what they sign. `wallet` " +
  "must be the one vault_get_address returns. A retry with the same idempotency_key and the same " +
  "message returns the same request instead of queueing another. Errors start with a code: " +
  "INVALID_PARAMETERS, IDEMPOTENCY_CONFLICT, NOT_PAIRED, WALLET_NOT_CONNECTED, WALLET_MISMATCH, " +
  "or PENDING_LIMIT.";

const TRANSFER_DESCRIPTION =
  "Asks the owner to send SOL or an SPL token from their wallet, and returns at once with the " +
  "stored request: its request_id and the status PENDING. Nothing is built, signed, or sent " +
  "here. The owner sees the request the next time they open the app; the sidecar then builds a " +
  "fresh unsigned transaction for them to review, and only their own wallet can sign and send " +
  "it. Read the outcome later with vault_get_request until terminal is true: CONFIRMED means it " +
  "succeeded on chain and carries the transaction's `signature`, REJECTED that the owner or the " +
  "wallet declined, EXPIRED that the deadline passed, FAILED that it did not go through, and " +
  "UNKNOWN that the result isn't settled yet — never retry an UNKNOWN request. `amount` is in " +
  "the asset's base units, never a decimal: lamports for SOL (1 SOL is 1000000000), and the " +
  "mint's own base units for a token, whose decimals the sidecar reads from the chain rather " +
  "than from any ticker. `recipient` is the receiving wallet's own address, not a token " +
  "account; when it has no account for the token yet, the transaction creates one and the owner " +
  "is shown what that costs. Only classic SPL tokens are supported: a Token-2022 mint or an NFT " +
  "is refused. A retry with the same idempotency_key and the same parameters returns the same " +
  "request instead of sending twice; a different amount or recipient under a used key is " +
  "refused. Errors start with a code: INVALID_PARAMETERS, IDEMPOTENCY_CONFLICT, NOT_PAIRED, " +
  "WALLET_NOT_CONNECTED, WALLET_MISMATCH, PENDING_LIMIT, or CHAIN_UNAVAILABLE.";

const GET_ADDRESS_DESCRIPTION =
  "Returns the wallet the owner selected on their Seeker, and the network they selected it for. " +
  "Use it before any wallet action, and name exactly this wallet and network: a request for " +
  "another one is refused with WALLET_MISMATCH. The sidecar holds no keys and makes no wallet of " +
  "its own, so there is no address to fall back on: if the owner has connected none, this fails " +
  "with WALLET_NOT_CONNECTED. The owner can change or disconnect the wallet at any time, so read " +
  "it again rather than caching it. Errors start with a code: NOT_PAIRED or WALLET_NOT_CONNECTED.";

const REQUEST_ACK_DESCRIPTION =
  "A development and demo tool, with no wallet involved. Queues display-only text for the owner " +
  "to acknowledge on their Seeker, and returns at once with the stored request: its request_id " +
  "and the status PENDING. Being stored is not the owner's approval, and doesn't mean they've " +
  "seen it: they see it the next time they open the app. Read the outcome later with " +
  "vault_get_request until terminal is true. COMPLETED means they tapped OK, REJECTED that they " +
  "declined, EXPIRED that the deadline passed, and CANCELLED that the request was withdrawn. A " +
  "retry with the same idempotency_key and text returns the same request instead of queueing " +
  "another. The text is never executed. Errors start with a code: INVALID_PARAMETERS, " +
  "IDEMPOTENCY_CONFLICT, NOT_PAIRED, or PENDING_LIMIT.";

const GET_REQUEST_DESCRIPTION =
  "Returns a request as it is now, by request_id: its status, whether that's final (terminal), " +
  "and its result. It never waits for the owner. For a SUBMITTED transfer it also asks the " +
  "chain what became of the signature, so polling this is how a transfer reaches CONFIRMED or " +
  "FAILED. A transfer stays SUBMITTED while its outcome is still open, and becomes UNKNOWN if " +
  "the transaction on chain under that signature isn't the one the owner approved; neither is " +
  "a failure, and neither is a reason to send a replacement. Errors start with a code: " +
  "NOT_FOUND or INVALID_PARAMETERS.";

const CANCEL_REQUEST_DESCRIPTION =
  "Withdraws a PENDING request so the owner can no longer act on it, and returns it as " +
  "CANCELLED. Cancelling a cancelled request returns it unchanged. A request the owner has " +
  "already acted on, or that expired, can't be cancelled: that fails with INVALID_STATE and " +
  "leaves it as it is. Errors start with a code: NOT_FOUND, INVALID_STATE, or INVALID_PARAMETERS.";

export interface RequestToolOptions {
  /** Serves vault_request_ack, a development and demo tool (MCP_DEMO_TOOLS). */
  readonly demoTools?: boolean;
}

/*
 * What the core serves is no longer an option handed in beside it: `core.transfers` is present
 * exactly when a Solana RPC endpoint is configured, and `core.confirmations` with it (SEE-87,
 * requests/agent-api.ts). Without an endpoint the sidecar could prepare no transfer, so it offers
 * none, and nothing can be checked on chain: a submitted request then stays SUBMITTED and says so.
 */

/** Registers the durable request tools on an MCP server session; vault_request_ack only in demo mode. */
export function registerRequestTools(
  server: McpServer,
  core: AgentRequests,
  log: (message: string) => void,
  options: RequestToolOptions = {},
): void {
  if (options.demoTools === true) registerAckTool(server, core, log);
  registerSignMessageTool(server, core, log);
  if (core.transfers !== undefined) {
    registerTransferTool(server, core, core.transfers, log);
  }

  server.registerTool(
    GET_CAPABILITIES_TOOL,
    {
      title: "Read what this sidecar can do",
      description: GET_CAPABILITIES_DESCRIPTION,
      inputSchema: {},
      outputSchema: CAPABILITIES_SCHEMA,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    () =>
      answer(() => ({
        approval: "manual",
        signing: "wallet",
        operations: [
          ...(options.demoTools === true ? ["ack"] : []),
          "sign_message",
          ...(core.transfers === undefined ? [] : ["transfer"]),
        ],
        wallet_connected: core.connectedWallet() !== undefined,
        max_message_bytes: MAX_MESSAGE_BYTES,
        max_note_bytes: MAX_NOTE_BYTES,
        max_pending_requests: core.pendingLimit,
        min_expires_in_seconds: MIN_EXPIRES_IN_SECONDS,
        max_expires_in_seconds: MAX_EXPIRES_IN_SECONDS,
        ...(core.confirmations === undefined
          ? {}
          : { confirmed_with: core.confirmations.endpoint }),
      })),
  );

  server.registerTool(
    GET_ADDRESS_TOOL,
    {
      title: "Read the owner's wallet",
      description: GET_ADDRESS_DESCRIPTION,
      inputSchema: {},
      outputSchema: ADDRESS_SCHEMA,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    () => answer(() => addressView(core.activeWallet())),
  );

  server.registerTool(
    GET_REQUEST_TOOL,
    {
      title: "Read a request",
      description: GET_REQUEST_DESCRIPTION,
      inputSchema: {
        request_id: z
          .string()
          .describe("The request_id a creation tool returned."),
      },
      outputSchema: VIEW_SCHEMA,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    ({ request_id }) =>
      answerAsync(async () => {
        const request = core.get(request_id);
        // Reading is how the sidecar's knowledge advances: it has no background worker, so a
        // submitted transaction is checked against the chain when somebody asks about it.
        const confirmations = core.confirmations;
        return requestView(
          confirmations === undefined
            ? request
            : await confirmations.settle(request),
        );
      }),
  );

  server.registerTool(
    CANCEL_REQUEST_TOOL,
    {
      title: "Cancel a pending request",
      description: CANCEL_REQUEST_DESCRIPTION,
      inputSchema: {
        request_id: z
          .string()
          .describe("The request_id a creation tool returned."),
      },
      outputSchema: VIEW_SCHEMA,
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    ({ request_id }) =>
      answer(() => {
        const request = core.cancel(request_id);
        log(`request ${idOf(request)} cancelled by the agent`);
        return requestView(request);
      }),
  );
}

/**
 * vault_sign_message: queues a message for the owner's wallet to sign. It creates a request and
 * nothing else: no wallet is contacted until the owner approves it on their phone.
 */
function registerSignMessageTool(
  server: McpServer,
  core: AgentRequests,
  log: (message: string) => void,
): void {
  server.registerTool(
    SIGN_MESSAGE_TOOL,
    {
      title: "Ask the owner's wallet to sign a message",
      description: SIGN_MESSAGE_DESCRIPTION,
      inputSchema: {
        wallet: z
          .string()
          .describe(
            "The owner's wallet, exactly as vault_get_address returns it. Another one is refused with WALLET_MISMATCH.",
          ),
        message: z
          .string()
          .describe(
            `The message as text. Its UTF-8 encoding is what gets signed, exactly as given: 1 to ${MAX_MESSAGE_BYTES} bytes, and never empty.`,
          ),
        idempotency_key: z
          .string()
          .describe(
            "1 to 128 characters from A-Z, a-z, 0-9, '.', '_', ':', and '-'. Reuse it when you retry the same request; use a new one for a new request.",
          ),
        note: z
          .string()
          .optional()
          .describe(
            `Optional: why you're asking, up to ${MAX_NOTE_BYTES} UTF-8 bytes. The phone shows it apart from the message itself.`,
          ),
        expires_in_seconds: z
          .number()
          .optional()
          .describe(
            `Optional: how long the owner has to decide, ${MIN_EXPIRES_IN_SECONDS} to ${MAX_EXPIRES_IN_SECONDS} seconds. The sidecar's default applies otherwise.`,
          ),
      },
      outputSchema: VIEW_SCHEMA,
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    ({ wallet, message, idempotency_key, note, expires_in_seconds }) =>
      answer(() => {
        const { request, created } = core.createRequest(
          privateRequest(
            signMessageAction(wallet, message),
            note ?? "",
            idempotency_key,
            expires_in_seconds,
          ),
        );
        log(
          created
            ? `request ${idOf(request)} stored (sign_message for ${wallet})`
            : `request ${idOf(request)} returned again for its idempotency key`,
        );
        return requestView(request);
      }),
  );
}

/**
 * vault_transfer: queues a transfer of SOL or an SPL token. It creates a request and nothing
 * else. The asset is checked against the chain first, so an agent hears about a token this
 * sidecar can't send before the owner ever sees the request; the transaction itself is built
 * later, when the owner opens it.
 */
function registerTransferTool(
  server: McpServer,
  core: AgentRequests,
  transfers: AgentTransfers,
  log: (message: string) => void,
): void {
  server.registerTool(
    TRANSFER_TOOL,
    {
      title: "Ask the owner to send SOL or an SPL token",
      description: TRANSFER_DESCRIPTION,
      inputSchema: {
        wallet: z
          .string()
          .describe(
            "The owner's wallet, exactly as vault_get_address returns it. Another one is refused with WALLET_MISMATCH.",
          ),
        network: z
          .enum(NETWORKS)
          .describe(
            "The network, exactly as vault_get_address returns it. Another one is refused with WALLET_MISMATCH.",
          ),
        recipient: z
          .string()
          .describe(
            "The receiving wallet's own base58 address. For a token, this is the owner of the tokens, never a token account.",
          ),
        amount: z
          .string()
          .describe(
            `The amount in the asset's base units, as decimal digits: lamports for SOL (1 SOL is 1000000000), or the mint's base units for a token. 1 to ${MAX_BASE_UNITS.toString()}, with no sign, decimal point, exponent, or leading zeros. Never a human-readable decimal.`,
          ),
        token_mint: z
          .string()
          .optional()
          .describe(
            "The SPL token's base58 mint address. Leave it out to send native SOL. Only classic SPL mints are supported: Token-2022 mints and NFTs are refused.",
          ),
        idempotency_key: z
          .string()
          .describe(
            "1 to 128 characters from A-Z, a-z, 0-9, '.', '_', ':', and '-'. Reuse it when you retry the same transfer, so it is never sent twice; use a new one for a new transfer.",
          ),
        note: z
          .string()
          .optional()
          .describe(
            `Optional: why you're asking, up to ${MAX_NOTE_BYTES} UTF-8 bytes. The phone shows it apart from the verified parameters.`,
          ),
        expires_in_seconds: z
          .number()
          .optional()
          .describe(
            `Optional: how long the owner has to decide, ${MIN_EXPIRES_IN_SECONDS} to ${MAX_EXPIRES_IN_SECONDS} seconds. The sidecar's default applies otherwise.`,
          ),
      },
      outputSchema: VIEW_SCHEMA,
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    ({
      wallet,
      network,
      recipient,
      amount,
      token_mint,
      idempotency_key,
      note,
      expires_in_seconds,
    }) =>
      answerAsync(async () => {
        const asset = create(AssetSchema, {
          kind:
            token_mint === undefined
              ? { case: "nativeSol", value: {} }
              : { case: "tokenMint", value: token_mint },
        });
        const action = create(ActionSchema, {
          kind: {
            case: "transfer",
            value: {
              wallet,
              network: NETWORK_VALUES[network],
              recipient,
              amount,
              asset,
            },
          },
        });
        // The request's own rules first, so a malformed address or amount never reaches the chain.
        const reason = invalidActionReason(action);
        if (reason !== undefined) {
          throw new RequestFailure(RequestError.INVALID_PARAMETERS, reason);
        }
        // A retry is answered before anything is read from a chain: the tool's contract is that
        // the same idempotency key gives back the same request, and that must not depend on an
        // endpoint being reachable, or on the mint looking the same as it did then.
        const replay = core.replayOf(idempotency_key, action);
        if (replay !== undefined) {
          log(`request ${idOf(replay)} returned again for its idempotency key`);
          return requestView(replay);
        }
        await transfers.checkAsset(asset);
        const { request, created } = core.createRequest(
          privateRequest(
            action,
            note ?? "",
            idempotency_key,
            expires_in_seconds,
          ),
        );
        log(
          created
            ? `request ${idOf(request)} stored (transfer on ${network})`
            : `request ${idOf(request)} returned again for its idempotency key`,
        );
        return requestView(request);
      }),
  );
}

/**
 * The action for a message the owner can read. Stage 3 signs text and nothing else: the request
 * carries the message as text, so the phone can show the owner exactly what they are approving
 * (docs/guides/message-signing.md). SignMessageAction still has a `data` form for a later stage,
 * and no tool served here can create one.
 */
function signMessageAction(wallet: string, text: string): Action {
  return create(ActionSchema, {
    kind: {
      case: "signMessage",
      value: { wallet, content: { case: "text", value: text } },
    },
  });
}

/** vault_request_ack: queues a wallet-free acknowledgement. Not a financial action. */
function registerAckTool(
  server: McpServer,
  core: AgentRequests,
  log: (message: string) => void,
): void {
  server.registerTool(
    REQUEST_ACK_TOOL,
    {
      title: "Queue an acknowledgement on the Seeker",
      description: REQUEST_ACK_DESCRIPTION,
      inputSchema: {
        text: z
          .string()
          .describe(
            `Display-only text for the owner, 1 to ${MAX_COMMAND_TEXT_BYTES} UTF-8 bytes. It's never executed.`,
          ),
        idempotency_key: z
          .string()
          .describe(
            "1 to 128 characters from A-Z, a-z, 0-9, '.', '_', ':', and '-'. Reuse it when you retry the same request; use a new one for a new request.",
          ),
        note: z
          .string()
          .optional()
          .describe(
            `Optional: your own description of the request, up to ${MAX_NOTE_BYTES} UTF-8 bytes. The phone shows it apart from the text.`,
          ),
        expires_in_seconds: z
          .number()
          .optional()
          .describe(
            `Optional: how long the owner has to decide, ${MIN_EXPIRES_IN_SECONDS} to ${MAX_EXPIRES_IN_SECONDS} seconds. The sidecar's default applies otherwise.`,
          ),
      },
      outputSchema: VIEW_SCHEMA,
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    ({ text, idempotency_key, note, expires_in_seconds }) =>
      answer(() => {
        const action = create(ActionSchema, {
          kind: { case: "ack", value: { text } },
        });
        const { request, created } = core.createRequest(
          privateRequest(
            action,
            note ?? "",
            idempotency_key,
            expires_in_seconds,
          ),
        );
        log(
          created
            ? `request ${idOf(request)} stored (ack)`
            : `request ${idOf(request)} returned again for its idempotency key`,
        );
        return requestView(request);
      }),
  );
}

/** A request as agents see it, with timestamps in RFC 3339 and the signature in base58. */
export function requestView(request: ActionRequest): RequestView {
  const action = request.action;
  const kind = action?.kind.case ?? "ack";
  const { outcome } = request;
  const signed = outcome !== undefined && outcome.signature.length > 0;
  const binding = action === undefined ? undefined : actionBinding(action);
  const wallet = binding?.wallet;
  // A transfer's network, which says which cluster its signature belongs to. A message names
  // none: nothing about it reaches a cluster.
  const network =
    binding?.network === undefined ? undefined : networkName(binding.network);
  return {
    request_id: idOf(request),
    action: kind === "signMessage" ? "sign_message" : kind,
    status: RequestState[request.state] as RequestView["status"],
    terminal: isTerminal(request.state),
    created_at: iso(request.createdAt),
    expires_at: iso(request.expiresAt),
    updated_at: iso(request.updatedAt),
    ...(wallet === undefined ? {} : { wallet }),
    ...(network === undefined
      ? {}
      : { network: network as RequestView["network"] }),
    ...(signed ? { signature: encodeBase58(outcome.signature) } : {}),
    // Exactly the bytes the wallet signed: the sidecar accepted the signature only after
    // verifying it against them (docs/protocol.md#message-results).
    ...(signed && action?.kind.case === "signMessage"
      ? {
          signed_message_base64: Buffer.from(
            messageBytes(action.kind.value),
          ).toString("base64"),
        }
      : {}),
    ...(outcome !== undefined && outcome.detail !== ""
      ? { detail: outcome.detail }
      : {}),
    ...confirmationView(outcome?.confirmation),
  };
}

/**
 * What the sidecar has checked on chain, as agents see it. `checked_with` is there on purpose:
 * a CONFIRMED or FAILED transfer rests on one endpoint's word, and the agent is told whose.
 */
function confirmationView(
  confirmation: Confirmation | undefined,
): Partial<RequestView> {
  if (confirmation === undefined) return {};
  const level = LEVEL_NAMES.get(confirmation.level);
  return {
    ...(level === undefined ? {} : { confirmation: level }),
    ...(confirmation.slot === 0n ? {} : { slot: Number(confirmation.slot) }),
    ...(confirmation.chainError === ""
      ? {}
      : { chain_error: confirmation.chainError }),
    ...(confirmation.checkedAt === undefined
      ? {}
      : { checked_at: iso(confirmation.checkedAt) }),
    ...(confirmation.endpoint === ""
      ? {}
      : { checked_with: confirmation.endpoint }),
  };
}

/** The owner's wallet as agents see it, with the network lowercased and the time in RFC 3339. */
export function addressView(binding: WalletBinding): AddressView {
  return {
    wallet: binding.wallet,
    network: networkName(binding.network) as AddressView["network"],
    bound_at: iso(binding.boundAt),
  };
}

type ToolView = RequestView | AddressView | CapabilitiesView;

/** Runs a store operation and turns its view, or its RequestFailure, into a tool result. */
function answer(operation: () => ToolView): CallToolResult {
  try {
    return success(operation());
  } catch (error) {
    return refusal(error);
  }
}

/** `answer` for a tool that awaits, such as one that reads the chain before it stores a request. */
async function answerAsync(
  operation: () => Promise<ToolView>,
): Promise<CallToolResult> {
  try {
    return success(await operation());
  } catch (error) {
    return refusal(error);
  }
}

function success(view: ToolView): CallToolResult {
  return {
    content: [{ type: "text", text: JSON.stringify(view) }],
    structuredContent: { ...view },
  };
}

function refusal(error: unknown): CallToolResult {
  if (!(error instanceof RequestFailure)) throw error;
  // No structuredContent: clients validate it against the success schema.
  return {
    isError: true,
    content: [{ type: "text", text: `${error.code}: ${error.message}` }],
  };
}

function idOf(request: ActionRequest): string {
  return request.ref?.requestId ?? "";
}

function iso(timestamp: Timestamp | undefined): string {
  return timestamp === undefined ? "" : timestampDate(timestamp).toISOString();
}
