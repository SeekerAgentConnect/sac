/**
 * Test-agent configuration from the environment. `pnpm agent` loads the ignored root `.env`
 * with Node's `--env-file-if-exists`; variables already set in the environment take precedence.
 * Problems name the variable but never echo a token.
 */

export interface AgentConfig {
  readonly mcpUrl: URL;
  readonly mcpToken: string;
  /** Default client timeout: the sidecar's deadline plus a margin, so the sidecar answers first. */
  readonly timeoutSeconds: number;
  /**
   * Development and demo mode (MCP_DEMO_TOOLS), the sidecar's own switch. It gates the two
   * diagnostics — `hello` and `ack` — which put text in front of the owner and get an
   * acknowledgement back. An acknowledgement is neither a signature nor a payment, and a client
   * that runs in a deployment should not be able to reach for one by accident. `--demo` turns it
   * on for a single command.
   */
  readonly demoTools: boolean;
}

export class AgentConfigError extends Error {
  readonly problems: readonly string[];

  constructor(problems: readonly string[]) {
    super(
      [
        "Invalid test-agent configuration:",
        ...problems.map((problem) => `  - ${problem}`),
      ].join("\n"),
    );
    this.name = "AgentConfigError";
    this.problems = problems;
  }
}

type Env = Readonly<Record<string, string | undefined>>;

/** The sidecar's default deadline, used when LIVE_COMMAND_TIMEOUT_SECONDS is not set. */
const DEFAULT_LIVE_COMMAND_TIMEOUT_SECONDS = 60;
/** How much longer than the sidecar's deadline the client waits for its answer. */
export const CLIENT_TIMEOUT_MARGIN_SECONDS = 15;

export function loadAgentConfig(env: Env): AgentConfig {
  const problems: string[] = [];

  const rawUrl = env.MCP_URL?.trim();
  let mcpUrl: URL | undefined;
  if (!rawUrl) {
    problems.push("MCP_URL is not set.");
  } else {
    mcpUrl = URL.parse(rawUrl) ?? undefined;
    if (
      mcpUrl === undefined ||
      (mcpUrl.protocol !== "http:" && mcpUrl.protocol !== "https:")
    ) {
      problems.push(
        "MCP_URL must be an http:// or https:// URL, for example http://127.0.0.1:8080/mcp.",
      );
      mcpUrl = undefined;
    }
  }

  const mcpToken = env.MCP_TOKEN?.trim();
  if (!mcpToken) {
    problems.push("MCP_TOKEN is not set.");
  } else if (mcpToken.startsWith("REPLACE_WITH_")) {
    problems.push(
      "MCP_TOKEN still has the .env.example placeholder; use the sidecar's MCP_TOKEN.",
    );
  }

  let deadlineSeconds = DEFAULT_LIVE_COMMAND_TIMEOUT_SECONDS;
  const rawDeadline = env.LIVE_COMMAND_TIMEOUT_SECONDS?.trim();
  if (rawDeadline) {
    const value = /^\d+$/.test(rawDeadline) ? Number(rawDeadline) : Number.NaN;
    if (!Number.isInteger(value) || value < 1 || value > 3600) {
      problems.push(
        "LIVE_COMMAND_TIMEOUT_SECONDS must be a whole number from 1 to 3600.",
      );
    } else {
      deadlineSeconds = value;
    }
  }

  const rawDemo = env.MCP_DEMO_TOOLS?.trim().toLowerCase() ?? "";
  if (rawDemo !== "" && rawDemo !== "true" && rawDemo !== "false") {
    problems.push("MCP_DEMO_TOOLS must be true or false.");
  }

  if (problems.length > 0 || mcpUrl === undefined || !mcpToken) {
    throw new AgentConfigError(problems);
  }
  return {
    mcpUrl,
    mcpToken,
    timeoutSeconds: deadlineSeconds + CLIENT_TIMEOUT_MARGIN_SECONDS,
    demoTools: rawDemo === "true",
  };
}
