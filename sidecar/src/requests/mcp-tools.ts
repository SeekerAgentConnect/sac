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
  ActionSchema,
  RequestError,
  RequestState,
  type Action,
  type ActionRequest,
  type WalletBinding,
} from "../gen/seekervault/request/v1/request_pb.js";
import { MAX_COMMAND_TEXT_BYTES } from "../live/command.ts";
import {
  MAX_MESSAGE_BYTES,
  MAX_NOTE_BYTES,
  actionBinding,
  encodeBase58,
  messageBytes,
} from "./action.ts";
import { isTerminal } from "./lifecycle.ts";
import {
  MAX_EXPIRES_IN_SECONDS,
  MIN_EXPIRES_IN_SECONDS,
  networkName,
  type RequestStore,
} from "../storage/request-store.ts";
import { RequestFailure } from "./failure.ts";

export const GET_ADDRESS_TOOL = "vault_get_address";
export const GET_CAPABILITIES_TOOL = "vault_get_capabilities";
export const SIGN_MESSAGE_TOOL = "vault_sign_message";
export const REQUEST_ACK_TOOL = "vault_request_ack";
export const GET_REQUEST_TOOL = "vault_get_request";
export const CANCEL_REQUEST_TOOL = "vault_cancel_request";

const ACTIONS = ["ack", "sign_message", "transfer", "swap"] as const;
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
  readonly signature?: string;
  readonly signed_message_base64?: string;
  readonly detail?: string;
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
};

const NETWORKS = ["mainnet", "devnet", "testnet"] as const;

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
  "The message is signed exactly as given, never trimmed, normalized, or re-encoded. `wallet` " +
  "must be the one vault_get_address returns. A retry with the same idempotency_key and the same " +
  "message returns the same request instead of queueing another. Errors start with a code: " +
  "INVALID_PARAMETERS, IDEMPOTENCY_CONFLICT, NOT_PAIRED, WALLET_NOT_CONNECTED, WALLET_MISMATCH, " +
  "or PENDING_LIMIT.";

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
  "and its result. It never waits for the owner. Errors start with a code: NOT_FOUND or " +
  "INVALID_PARAMETERS.";

const CANCEL_REQUEST_DESCRIPTION =
  "Withdraws a PENDING request so the owner can no longer act on it, and returns it as " +
  "CANCELLED. Cancelling a cancelled request returns it unchanged. A request the owner has " +
  "already acted on, or that expired, can't be cancelled: that fails with INVALID_STATE and " +
  "leaves it as it is. Errors start with a code: NOT_FOUND, INVALID_STATE, or INVALID_PARAMETERS.";

export interface RequestToolOptions {
  /** Serves vault_request_ack, a development and demo tool (MCP_DEMO_TOOLS). */
  readonly demoTools?: boolean;
}

/** Registers the durable request tools on an MCP server session; vault_request_ack only in demo mode. */
export function registerRequestTools(
  server: McpServer,
  store: RequestStore,
  log: (message: string) => void,
  options: RequestToolOptions = {},
): void {
  if (options.demoTools === true) registerAckTool(server, store, log);
  registerSignMessageTool(server, store, log);

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
        ],
        wallet_connected: store.connectedWallet() !== undefined,
        max_message_bytes: MAX_MESSAGE_BYTES,
        max_note_bytes: MAX_NOTE_BYTES,
        max_pending_requests: store.pendingLimit,
        min_expires_in_seconds: MIN_EXPIRES_IN_SECONDS,
        max_expires_in_seconds: MAX_EXPIRES_IN_SECONDS,
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
    () => answer(() => addressView(store.activeWallet())),
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
    ({ request_id }) => answer(() => requestView(store.get(request_id))),
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
        const request = store.cancel(request_id);
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
  store: RequestStore,
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
          .optional()
          .describe(
            `The message as text. Its UTF-8 encoding is what gets signed, exactly as given: 1 to ${MAX_MESSAGE_BYTES} bytes. Give either this or message_base64, not both.`,
          ),
        message_base64: z
          .string()
          .optional()
          .describe(
            `The message as bytes, in standard base64, signed as they are: 1 to ${MAX_MESSAGE_BYTES} bytes. Use it only for a message that isn't text; the owner sees text far better.`,
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
    ({
      wallet,
      message,
      message_base64,
      idempotency_key,
      note,
      expires_in_seconds,
    }) =>
      answer(() => {
        const { request, created } = store.create({
          action: signMessageAction(wallet, message, message_base64),
          agentNote: note ?? "",
          idempotencyKey: idempotency_key,
          expiresInSeconds: expires_in_seconds,
        });
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
 * The action for one of the two message forms. Exactly one must be given: an agent that sends
 * both, or neither, is refused rather than having one silently chosen for it.
 */
function signMessageAction(
  wallet: string,
  text: string | undefined,
  base64: string | undefined,
): Action {
  if ((text === undefined) === (base64 === undefined)) {
    throw new RequestFailure(
      RequestError.INVALID_PARAMETERS,
      "give either message or message_base64, not both and not neither",
    );
  }
  if (text !== undefined) {
    return create(ActionSchema, {
      kind: {
        case: "signMessage",
        value: { wallet, content: { case: "text", value: text } },
      },
    });
  }
  const data = decodeBase64(base64 ?? "");
  if (data === undefined) {
    throw new RequestFailure(
      RequestError.INVALID_PARAMETERS,
      "message_base64 is not standard base64",
    );
  }
  return create(ActionSchema, {
    kind: {
      case: "signMessage",
      value: { wallet, content: { case: "data", value: data } },
    },
  });
}

/** Standard base64, strictly: anything Buffer would quietly ignore is refused instead. */
function decodeBase64(text: string): Uint8Array | undefined {
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(text)) return undefined;
  const bytes = Buffer.from(text, "base64");
  return bytes.toString("base64") === text ? bytes : undefined;
}

/** vault_request_ack: queues a wallet-free acknowledgement. Not a financial action. */
function registerAckTool(
  server: McpServer,
  store: RequestStore,
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
        const { request, created } = store.create({
          action: create(ActionSchema, {
            kind: { case: "ack", value: { text } },
          }),
          agentNote: note ?? "",
          idempotencyKey: idempotency_key,
          expiresInSeconds: expires_in_seconds,
        });
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
  const wallet =
    action === undefined ? undefined : actionBinding(action)?.wallet;
  return {
    request_id: idOf(request),
    action: kind === "signMessage" ? "sign_message" : kind,
    status: RequestState[request.state] as RequestView["status"],
    terminal: isTerminal(request.state),
    created_at: iso(request.createdAt),
    expires_at: iso(request.expiresAt),
    updated_at: iso(request.updatedAt),
    ...(wallet === undefined ? {} : { wallet }),
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

/** Runs a store operation and turns its view, or its RequestFailure, into a tool result. */
function answer(
  operation: () => RequestView | AddressView | CapabilitiesView,
): CallToolResult {
  try {
    const view = operation();
    return {
      content: [{ type: "text", text: JSON.stringify(view) }],
      structuredContent: { ...view },
    };
  } catch (error) {
    if (!(error instanceof RequestFailure)) throw error;
    // No structuredContent: clients validate it against the success schema.
    return {
      isError: true,
      content: [{ type: "text", text: `${error.code}: ${error.message}` }],
    };
  }
}

function idOf(request: ActionRequest): string {
  return request.ref?.requestId ?? "";
}

function iso(timestamp: Timestamp | undefined): string {
  return timestamp === undefined ? "" : timestampDate(timestamp).toISOString();
}
