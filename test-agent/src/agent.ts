/**
 * The test agent's MCP client. It uses the same Streamable HTTP interface Hermes uses, with no
 * LLM in between, and never reports success without the phone's acknowledgement.
 */
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  StreamableHTTPClientTransport,
  StreamableHTTPError,
} from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import {
  ErrorCode,
  McpError,
  type CallToolResult,
  type Tool,
} from "@modelcontextprotocol/sdk/types.js";

export const DISPLAY_COMMAND_TOOL = "vault_display_command";
export const GET_ADDRESS_TOOL = "vault_get_address";
export const GET_CAPABILITIES_TOOL = "vault_get_capabilities";
export const SIGN_MESSAGE_TOOL = "vault_sign_message";
export const TRANSFER_TOOL = "vault_transfer";
export const REQUEST_ACK_TOOL = "vault_request_ack";
export const GET_REQUEST_TOOL = "vault_get_request";
export const CANCEL_REQUEST_TOOL = "vault_cancel_request";
/**
 * Stage 6's swap tool. No sidecar serves it yet, and nothing here implements one: the name exists
 * so that `swap` can say what is missing rather than fail as an unknown command.
 */
export const SWAP_TOOL = "vault_swap";

/** Process exit codes (test-agent/README.md). */
export const ExitCode = {
  OK: 0,
  FAILURE: 1,
  USAGE: 2,
  CONNECTION: 3,
  OFFLINE: 4,
  BUSY: 5,
  TIMEOUT: 6,
  CANCELLED: 7,
  INVALID_TEXT: 8,
  REFUSED: 9,
  UNSETTLED: 10,
  BAD_OUTCOME: 11,
} as const;
export type ExitCode = (typeof ExitCode)[keyof typeof ExitCode];

// The sidecar's tool errors are "<CODE>: <message>" (docs/protocol.md).
const TOOL_ERROR_EXIT_CODES: Readonly<Record<string, ExitCode>> = {
  OFFLINE: ExitCode.OFFLINE,
  BUSY: ExitCode.BUSY,
  TIMEOUT: ExitCode.TIMEOUT,
  CANCELLED: ExitCode.CANCELLED,
  INVALID_TEXT: ExitCode.INVALID_TEXT,
};

/** Why the agent did not get an acknowledgement, with the exit code to report. */
export class AgentFailure extends Error {
  readonly exitCode: ExitCode;

  constructor(exitCode: ExitCode, message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "AgentFailure";
    this.exitCode = exitCode;
  }
}

/** The phone's acknowledgement, as returned by vault_display_command. */
export interface Acknowledgement {
  readonly id: string;
  readonly result: "OK";
}

/** Opens an MCP session on `url`, runs `use`, and ends the session again. */
export async function withMcpSession<T>(
  url: URL,
  token: string,
  use: (client: Client) => Promise<T>,
): Promise<T> {
  const transport = new StreamableHTTPClientTransport(url, {
    requestInit: { headers: { Authorization: `Bearer ${token}` } },
  });
  const client = new Client({
    name: "seeker-vault-test-agent",
    version: "0.1.0",
  });
  try {
    await client.connect(transport);
  } catch (error) {
    throw connectionFailure(url, error);
  }
  try {
    return await use(client);
  } catch (error) {
    throw error instanceof AgentFailure ? error : connectionFailure(url, error);
  } finally {
    // Ending the session frees the sidecar's state for it, including a waiting command.
    await transport.terminateSession().catch(() => undefined);
    await client.close();
  }
}

export async function listTools(client: Client): Promise<Tool[]> {
  return (await client.listTools()).tools;
}

/**
 * Fails with exit code 3 unless the MCP server offers the tool `name`. `why` tells the reader what
 * to do about it. Listing the tools also lets the SDK validate results against their schemas.
 */
export async function requireTool(
  client: Client,
  name: string,
  why?: string,
): Promise<void> {
  if (!(await listTools(client)).some((tool) => tool.name === name)) {
    throw new AgentFailure(
      ExitCode.CONNECTION,
      `the MCP server does not offer ${name}${why === undefined ? "" : `; ${why}`}`,
    );
  }
}

/**
 * Discovers vault_display_command, calls it with `text`, and returns the acknowledgement once the
 * user taps OK. Throws AgentFailure for tool errors (OFFLINE, BUSY, ...) and for the client timeout.
 */
export async function displayCommand(
  client: Client,
  text: string,
  timeoutMs: number,
): Promise<Acknowledgement> {
  await requireTool(client, DISPLAY_COMMAND_TOOL);

  let result: CallToolResult;
  // Without resumable streams, the SDK reports a dropped response stream only through onerror and
  // leaves the call waiting for its timeout. Fail the call at once instead: the sidecar treats a
  // dropped connection as a cancellation, so no answer is coming.
  const dropped = new AbortController();
  const onerror = client.onerror;
  client.onerror = (error) => {
    onerror?.(error);
    if (error.message.startsWith("SSE stream disconnected"))
      dropped.abort(error);
  };
  try {
    // The listTools() call above also lets the SDK validate the result against the tool's schema.
    result = (await client.callTool(
      { name: DISPLAY_COMMAND_TOOL, arguments: { text } },
      undefined,
      {
        timeout: timeoutMs,
        signal: dropped.signal,
      },
    )) as CallToolResult;
  } catch (error) {
    if (dropped.signal.aborted) {
      throw new AgentFailure(
        ExitCode.CONNECTION,
        "lost the connection to the sidecar before the phone answered",
        { cause: error },
      );
    }
    if (
      error instanceof McpError &&
      error.code === Number(ErrorCode.RequestTimeout)
    ) {
      throw new AgentFailure(
        ExitCode.TIMEOUT,
        `TIMEOUT: no answer within ${timeoutMs / 1000} s (client timeout)`,
        {
          cause: error,
        },
      );
    }
    throw error;
  } finally {
    client.onerror = onerror;
  }

  if (result.isError === true) {
    const first = result.content[0];
    const message =
      first?.type === "text" ? first.text : "the tool reported an error";
    const code = /^([A-Z_]+): /.exec(message)?.[1];
    throw new AgentFailure(
      (code === undefined ? undefined : TOOL_ERROR_EXIT_CODES[code]) ??
        ExitCode.FAILURE,
      message,
    );
  }
  const content = result.structuredContent;
  if (typeof content?.id !== "string" || content.result !== "OK") {
    throw new AgentFailure(
      ExitCode.FAILURE,
      "the tool result carries no acknowledgement",
    );
  }
  return { id: content.id, result: "OK" };
}

/**
 * How long a tool that answers at once may take. A durable tool returns the stored request, but a
 * read of a SUBMITTED transfer also checks the chain, so it is not instant.
 */
const DEFAULT_CALL_TIMEOUT_MS = 30_000;

/** A durable request as the sidecar's tools return it (docs/protocol.md#agent-api-mcp). */
export interface RequestView {
  readonly request_id: string;
  readonly action: string;
  readonly status: string;
  readonly terminal: boolean;
  readonly created_at: string;
  readonly expires_at: string;
  readonly updated_at: string;
  readonly wallet?: string;
  readonly network?: string;
  readonly signature?: string;
  readonly signed_message_base64?: string;
  readonly detail?: string;
  readonly confirmation?: string;
  readonly slot?: number;
  readonly chain_error?: string;
  readonly checked_at?: string;
  readonly checked_with?: string;
}

/** The clusters a request can name, and how an explorer names each one. */
const CLUSTERS: Readonly<Record<string, string | undefined>> = {
  // Explorer's default, which takes no cluster parameter.
  mainnet: undefined,
  devnet: "devnet",
  testnet: "testnet",
};

/**
 * Where to read a transaction on the public explorer, or undefined when there is nothing to read
 * there. A signature over a message is not a transaction: it moves nothing, it reaches no cluster,
 * and no explorer has it. Linking one as if it were a payment is the mistake this guards against,
 * so the action decides, not the presence of a signature.
 */
export function explorerUrl(view: RequestView): string | undefined {
  if (view.action !== "transfer" && view.action !== "swap") return undefined;
  if (view.signature === undefined || view.network === undefined) {
    return undefined;
  }
  if (!(view.network in CLUSTERS)) return undefined;
  const cluster = CLUSTERS[view.network];
  const query = cluster === undefined ? "" : `?cluster=${cluster}`;
  return `https://explorer.solana.com/tx/${view.signature}${query}`;
}

/** The states that are settled and good, and those that are settled and not. */
const SUCCEEDED = new Set(["CONFIRMED", "COMPLETED"]);
const FAILED = new Set(["REJECTED", "CANCELLED", "EXPIRED", "FAILED"]);

/**
 * The exit code for a request's state: 0 once it ended the way it was asked for, BAD_OUTCOME once
 * it ended any other way, and UNSETTLED while no outcome is established. UNKNOWN counts as
 * unsettled on purpose — it is the one state that means nobody knows, and a script that treats it
 * as a failure is a script that re-sends money.
 */
export function outcomeExitCode(status: string): ExitCode {
  if (SUCCEEDED.has(status)) return ExitCode.OK;
  if (FAILED.has(status)) return ExitCode.BAD_OUTCOME;
  return ExitCode.UNSETTLED;
}

/** The same three answers in a word, for a script that would rather not know the state table. */
export type Outcome = "succeeded" | "failed" | "unsettled";

export function outcomeOf(status: string): Outcome {
  if (SUCCEEDED.has(status)) return "succeeded";
  if (FAILED.has(status)) return "failed";
  return "unsettled";
}

/** How a bounded wait ended, and the request as it was when it ended. */
export interface WaitResult {
  readonly view: RequestView;
  /** True when the deadline passed with the request still unsettled. */
  readonly timedOut: boolean;
  readonly waitedSeconds: number;
  readonly polls: number;
}

/**
 * Reads a request until it settles or the deadline passes, whichever comes first.
 *
 * Bounded on purpose, in both directions: it asks at most once every `intervalMs`, and it gives up
 * at `timeoutMs` rather than waiting on an owner who has gone to bed. Giving up is not a failure
 * and never becomes one — the request is still there, `timedOut` says what happened, and the
 * caller reports it as unsettled. Nothing here retries a request or creates a second one.
 */
export async function waitForRequest(
  client: Client,
  requestId: string,
  options: {
    readonly timeoutMs: number;
    readonly intervalMs: number;
    readonly onPoll?: (view: RequestView, waitedMs: number) => void;
    readonly now?: () => number;
  },
): Promise<WaitResult> {
  const now = options.now ?? Date.now;
  const started = now();
  let polls = 0;
  let last: RequestView | undefined;
  for (;;) {
    // Each read is given what is left of the deadline, and no more. A read of a SUBMITTED transfer
    // checks the chain and can take seconds; without this, `--for 1` could block for the call's own
    // timeout instead, and the bound this function advertises would not be one.
    const budget = options.timeoutMs - (now() - started);
    let view: RequestView;
    try {
      view = await requestTool(
        client,
        GET_REQUEST_TOOL,
        { request_id: requestId },
        Math.max(1, Math.min(budget, DEFAULT_CALL_TIMEOUT_MS)),
      );
    } catch (error) {
      // The deadline passed while a read was in flight. That is the wait giving up, which is not a
      // failure: report the request as it was last seen. With nothing seen yet, there is nothing to
      // report and the caller hears about the read instead.
      if (last === undefined || !timedOut(error)) throw error;
      return {
        view: last,
        timedOut: true,
        waitedSeconds: Math.round((now() - started) / 1000),
        polls,
      };
    }
    last = view;
    polls += 1;
    const waited = now() - started;
    options.onPoll?.(view, waited);
    if (view.terminal) {
      return {
        view,
        timedOut: false,
        waitedSeconds: Math.round(waited / 1000),
        polls,
      };
    }
    const remaining = options.timeoutMs - waited;
    if (remaining <= 0) {
      return {
        view,
        timedOut: true,
        waitedSeconds: Math.round(waited / 1000),
        polls,
      };
    }
    await delay(Math.min(options.intervalMs, remaining));
  }
}

/** Whether a failed call is the client's own deadline rather than the sidecar refusing. */
function timedOut(error: unknown): boolean {
  return (
    error instanceof McpError && error.code === Number(ErrorCode.RequestTimeout)
  );
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** The owner's wallet as vault_get_address returns it (docs/protocol.md#agent-api-mcp). */
export interface AddressView {
  readonly wallet: string;
  readonly network: string;
  readonly bound_at: string;
}

/**
 * Reads the wallet the owner selected on their phone. NOT_PAIRED and WALLET_NOT_CONNECTED become
 * AgentFailure with exit code 9: there is no address to fall back on.
 */
export async function getAddress(client: Client): Promise<AddressView> {
  const view = await callView(client, GET_ADDRESS_TOOL, {});
  if (typeof view.wallet !== "string" || typeof view.network !== "string") {
    throw new AgentFailure(
      ExitCode.FAILURE,
      `${GET_ADDRESS_TOOL} returned no address`,
    );
  }
  return view as unknown as AddressView;
}

/** What the sidecar says it can do, as vault_get_capabilities returns it. */
export interface CapabilitiesView {
  readonly approval: string;
  readonly signing: string;
  readonly operations: readonly string[];
  readonly wallet_connected: boolean;
  readonly max_message_bytes: number;
  readonly max_note_bytes: number;
  readonly max_pending_requests: number;
  readonly min_expires_in_seconds: number;
  readonly max_expires_in_seconds: number;
}

/** Reads what this sidecar serves. It takes no input, and the sidecar always answers it. */
export async function getCapabilities(
  client: Client,
): Promise<CapabilitiesView> {
  const view = await callView(client, GET_CAPABILITIES_TOOL, {});
  if (view.approval !== "manual" || !Array.isArray(view.operations)) {
    throw new AgentFailure(
      ExitCode.FAILURE,
      `${GET_CAPABILITIES_TOOL} returned no capabilities`,
    );
  }
  return view as unknown as CapabilitiesView;
}

/**
 * Calls one of the durable request tools, which answer at once, and returns the request. A tool
 * error, such as NOT_PAIRED or NOT_FOUND, becomes AgentFailure with exit code 9 and the sidecar's
 * "<CODE>: <message>".
 */
export async function requestTool(
  client: Client,
  name: string,
  args: Record<string, unknown>,
  timeoutMs = DEFAULT_CALL_TIMEOUT_MS,
): Promise<RequestView> {
  const view = await callView(client, name, args, timeoutMs);
  if (typeof view.request_id !== "string" || typeof view.status !== "string") {
    throw new AgentFailure(ExitCode.FAILURE, `${name} returned no request`);
  }
  return view as unknown as RequestView;
}

/** Calls a tool that answers at once, and returns its structuredContent. */
async function callView(
  client: Client,
  name: string,
  args: Record<string, unknown>,
  timeoutMs = DEFAULT_CALL_TIMEOUT_MS,
): Promise<Record<string, unknown>> {
  const result = (await client.callTool({ name, arguments: args }, undefined, {
    timeout: timeoutMs,
  })) as CallToolResult;
  if (result.isError === true) {
    const first = result.content[0];
    throw new AgentFailure(
      ExitCode.REFUSED,
      first?.type === "text" ? first.text : `${name} reported an error`,
    );
  }
  return result.structuredContent ?? {};
}

function connectionFailure(url: URL, error: unknown): AgentFailure {
  if (error instanceof AgentFailure) return error;
  if (error instanceof StreamableHTTPError) {
    const reason =
      error.code === 401
        ? "the sidecar rejected MCP_TOKEN (HTTP 401)"
        : error.code === 403
          ? "the sidecar refused the request (HTTP 403: Host or Origin not allowed)"
          : `HTTP ${error.code ?? "error"}`;
    return new AgentFailure(
      ExitCode.CONNECTION,
      `could not use ${url.href}: ${reason}`,
      { cause: error },
    );
  }
  const detail =
    error instanceof Error
      ? error.cause instanceof Error
        ? error.cause.message
        : error.message
      : "";
  return new AgentFailure(
    ExitCode.CONNECTION,
    `could not reach ${url.href}: ${detail || "unknown error"}`,
    {
      cause: error,
    },
  );
}
