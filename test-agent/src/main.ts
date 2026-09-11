import { parseArgs } from "node:util";

import {
  AgentFailure,
  ExitCode,
  displayCommand,
  listTools,
  withMcpSession,
} from "./agent.ts";
import {
  AgentConfigError,
  CLIENT_TIMEOUT_MARGIN_SECONDS,
  loadAgentConfig,
} from "./config.ts";

const USAGE = `Usage: pnpm agent <command> [options]

Commands:
  hello [text]     Show text on the phone (default "Hello Seeker") and wait for OK.
                   Prints the acknowledgement {"id":"...","result":"OK"} on stdout.
  tools            List the MCP server's tools as JSON.

Options:
  --timeout <s>    Client timeout in seconds; default LIVE_COMMAND_TIMEOUT_SECONDS + ${CLIENT_TIMEOUT_MARGIN_SECONDS}.
  -h, --help       Show this help.

Environment (the root .env, or the shell, which takes precedence):
  MCP_URL, MCP_TOKEN, LIVE_COMMAND_TIMEOUT_SECONDS

Exit codes: 0 OK, 1 unexpected result, 2 usage or configuration, 3 connection,
4 OFFLINE, 5 BUSY, 6 TIMEOUT, 7 CANCELLED, 8 INVALID_TEXT.`;

// Tokens never reach the output, even inside an error message from a library.
const SECRETS = [process.env.MCP_TOKEN, process.env.PHONE_TOKEN]
  .map((value) => value?.trim() ?? "")
  .filter((value) => value.length >= 8);

function redact(text: string): string {
  return SECRETS.reduce(
    (result, secret) => result.replaceAll(secret, "[redacted]"),
    text,
  );
}

function print(text: string): void {
  process.stdout.write(`${redact(text)}\n`);
}

function printError(text: string): void {
  process.stderr.write(`${redact(text)}\n`);
}

async function main(argv: string[]): Promise<number> {
  let parsed;
  try {
    parsed = parseArgs({
      args: argv,
      allowPositionals: true,
      options: {
        timeout: { type: "string" },
        help: { type: "boolean", short: "h" },
      },
    });
  } catch (error) {
    printError(
      `${error instanceof Error ? error.message : String(error)}\n\n${USAGE}`,
    );
    return ExitCode.USAGE;
  }
  const [command, ...rest] = parsed.positionals;
  if (parsed.values.help === true) {
    print(USAGE);
    return ExitCode.OK;
  }
  if (
    (command !== "hello" && command !== "tools") ||
    (command === "tools" && rest.length > 0) ||
    rest.length > 1
  ) {
    printError(USAGE);
    return ExitCode.USAGE;
  }

  let config;
  try {
    config = loadAgentConfig(process.env);
  } catch (error) {
    if (!(error instanceof AgentConfigError)) throw error;
    printError(
      `${error.message}\nSet them in the root .env (see .env.example) or in the environment.`,
    );
    return ExitCode.USAGE;
  }
  let timeoutSeconds = config.timeoutSeconds;
  if (parsed.values.timeout !== undefined) {
    timeoutSeconds = /^\d+$/.test(parsed.values.timeout)
      ? Number(parsed.values.timeout)
      : 0;
    if (timeoutSeconds < 1) {
      printError("--timeout must be a whole number of seconds, at least 1.");
      return ExitCode.USAGE;
    }
  }

  try {
    await withMcpSession(config.mcpUrl, config.mcpToken, async (client) => {
      if (command === "tools") {
        const tools = await listTools(client);
        print(
          JSON.stringify(
            tools.map(({ name, title, description }) => ({
              name,
              title,
              description,
            })),
            null,
            2,
          ),
        );
        return;
      }
      const text = rest[0] ?? "Hello Seeker";
      printError(
        `Showing the text on the phone; waiting up to ${timeoutSeconds} s for OK...`,
      );
      print(
        JSON.stringify(
          await displayCommand(client, text, timeoutSeconds * 1000),
        ),
      );
    });
    return ExitCode.OK;
  } catch (error) {
    if (!(error instanceof AgentFailure)) throw error;
    printError(error.message);
    return error.exitCode;
  }
}

try {
  process.exitCode = await main(process.argv.slice(2));
} catch (error) {
  // A bug, not an expected failure: print the message only, never a stack trace.
  printError(
    `unexpected error: ${error instanceof Error ? error.message : String(error)}`,
  );
  process.exitCode = ExitCode.FAILURE;
}
