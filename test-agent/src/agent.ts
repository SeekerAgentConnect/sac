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
  readonly signature?: string;
  readonly signed_message_base64?: string;
  readonly detail?: string;
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
): Promise<RequestView> {
  const view = await callView(client, name, args);
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
): Promise<Record<string, unknown>> {
  const result = (await client.callTool({ name, arguments: args }, undefined, {
    timeout: 30_000,
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
