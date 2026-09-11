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
 * Discovers vault_display_command, calls it with `text`, and returns the acknowledgement once the
 * user taps OK. Throws AgentFailure for tool errors (OFFLINE, BUSY, ...) and for the client timeout.
 */
export async function displayCommand(
  client: Client,
  text: string,
  timeoutMs: number,
): Promise<Acknowledgement> {
  if (
    !(await listTools(client)).some(
      (tool) => tool.name === DISPLAY_COMMAND_TOOL,
    )
  ) {
    throw new AgentFailure(
      ExitCode.CONNECTION,
      `the MCP server does not offer ${DISPLAY_COMMAND_TOOL}`,
    );
  }

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
