/**
 * The agent's durable request tools (docs/protocol.md). vault_request_ack queues an
 * acknowledgement, vault_get_request reads a request, and vault_cancel_request withdraws one.
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
  RequestState,
  type ActionRequest,
  type WalletBinding,
} from "../gen/seekervault/request/v1/request_pb.js";
import { MAX_COMMAND_TEXT_BYTES } from "../live/command.ts";
import { MAX_NOTE_BYTES, encodeBase58 } from "./action.ts";
import { isTerminal } from "./lifecycle.ts";
import {
  MAX_EXPIRES_IN_SECONDS,
  MIN_EXPIRES_IN_SECONDS,
  networkName,
  type RequestStore,
} from "../storage/request-store.ts";
import { RequestFailure } from "./failure.ts";

export const GET_ADDRESS_TOOL = "vault_get_address";
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
  readonly signature?: string;
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
  signature: z
    .string()
    .optional()
    .describe("The wallet's signature in base58, once there is one."),
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
  const kind = request.action?.kind.case ?? "ack";
  const { outcome } = request;
  return {
    request_id: idOf(request),
    action: kind === "signMessage" ? "sign_message" : kind,
    status: RequestState[request.state] as RequestView["status"],
    terminal: isTerminal(request.state),
    created_at: iso(request.createdAt),
    expires_at: iso(request.expiresAt),
    updated_at: iso(request.updatedAt),
    ...(outcome !== undefined && outcome.signature.length > 0
      ? { signature: encodeBase58(outcome.signature) }
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
function answer(operation: () => RequestView | AddressView): CallToolResult {
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
