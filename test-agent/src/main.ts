import { randomUUID } from "node:crypto";
import { parseArgs } from "node:util";

import {
  AgentFailure,
  CANCEL_REQUEST_TOOL,
  ExitCode,
  GET_REQUEST_TOOL,
  REQUEST_ACK_TOOL,
  displayCommand,
  listTools,
  requestTool,
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
  ack <text>       Queue text for the owner to acknowledge later (vault_request_ack).
                   Prints the request, PENDING, as JSON; it doesn't wait for the owner.
  get <id>         Print a request as it is now (vault_get_request).
  cancel <id>      Withdraw a PENDING request (vault_cancel_request).
  tools            List the MCP server's tools as JSON.

Options:
  --key <key>      ack: the idempotency key. Default: a new one, printed on stderr.
  --note <text>    ack: a note for the owner, shown apart from the text.
  --expires <s>    ack: seconds until the request expires, 60 to 604800.
  --timeout <s>    hello: client timeout in seconds; default LIVE_COMMAND_TIMEOUT_SECONDS + ${CLIENT_TIMEOUT_MARGIN_SECONDS}.
  -h, --help       Show this help.

Environment (the root .env, or the shell, which takes precedence):
  MCP_URL, MCP_TOKEN, LIVE_COMMAND_TIMEOUT_SECONDS

Exit codes: 0 OK, 1 unexpected result, 2 usage or configuration, 3 connection,
4 OFFLINE, 5 BUSY, 6 TIMEOUT, 7 CANCELLED, 8 INVALID_TEXT, 9 refused by the sidecar.`;

// How many positional arguments each command takes.
const ARITY: Readonly<Record<string, readonly [number, number]>> = {
  hello: [0, 1],
  ack: [1, 1],
  get: [1, 1],
  cancel: [1, 1],
  tools: [0, 0],
};

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

function wholeSeconds(value: string | undefined): number | undefined {
  if (value === undefined) return undefined;
  return /^\d+$/.test(value) ? Number(value) : 0;
}

async function main(argv: string[]): Promise<number> {
  let parsed;
  try {
    parsed = parseArgs({
      args: argv,
      allowPositionals: true,
      options: {
        timeout: { type: "string" },
        key: { type: "string" },
        note: { type: "string" },
        expires: { type: "string" },
        help: { type: "boolean", short: "h" },
      },
    });
  } catch (error) {
    printError(
      `${error instanceof Error ? error.message : String(error)}\n\n${USAGE}`,
    );
    return ExitCode.USAGE;
  }
  const [command = "", ...rest] = parsed.positionals;
  if (parsed.values.help === true) {
    print(USAGE);
    return ExitCode.OK;
  }
  const arity = ARITY[command];
  if (arity === undefined || rest.length < arity[0] || rest.length > arity[1]) {
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
    timeoutSeconds = wholeSeconds(parsed.values.timeout) ?? 0;
    if (timeoutSeconds < 1) {
      printError("--timeout must be a whole number of seconds, at least 1.");
      return ExitCode.USAGE;
    }
  }
  const expires = wholeSeconds(parsed.values.expires);
  if (expires === 0) {
    printError("--expires must be a whole number of seconds.");
    return ExitCode.USAGE;
  }

  try {
    await withMcpSession(config.mcpUrl, config.mcpToken, async (client) => {
      switch (command) {
        case "tools": {
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
        case "ack": {
          const key = parsed.values.key ?? `ack-${randomUUID()}`;
          if (parsed.values.key === undefined) {
            // Printed so that a retry can reuse it and get the same request back.
            printError(`idempotency key: ${key}`);
          }
          const args: Record<string, unknown> = {
            text: rest[0],
            idempotency_key: key,
          };
          if (parsed.values.note !== undefined) args.note = parsed.values.note;
          if (expires !== undefined) args.expires_in_seconds = expires;
          print(
            JSON.stringify(await requestTool(client, REQUEST_ACK_TOOL, args)),
          );
          return;
        }
        case "get":
        case "cancel":
          print(
            JSON.stringify(
              await requestTool(
                client,
                command === "get" ? GET_REQUEST_TOOL : CANCEL_REQUEST_TOOL,
                { request_id: rest[0] },
              ),
            ),
          );
          return;
        default: {
          const text = rest[0] ?? "Hello Seeker";
          printError(
            `Showing the text on the phone; waiting up to ${timeoutSeconds} s for OK...`,
          );
          print(
            JSON.stringify(
              await displayCommand(client, text, timeoutSeconds * 1000),
            ),
          );
        }
      }
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
