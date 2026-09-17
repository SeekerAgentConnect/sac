import { randomUUID } from "node:crypto";
import { parseArgs } from "node:util";

import {
  AgentFailure,
  CANCEL_REQUEST_TOOL,
  ExitCode,
  GET_REQUEST_TOOL,
  REQUEST_ACK_TOOL,
  SIGN_MESSAGE_TOOL,
  SWAP_TOOL,
  TRANSFER_TOOL,
  displayCommand,
  getAddress,
  getCapabilities,
  explorerUrl,
  listTools,
  outcomeExitCode,
  outcomeOf,
  requestTool,
  requireTool,
  waitForRequest,
  withMcpSession,
  type RequestView,
  type WaitResult,
} from "./agent.ts";
import {
  AgentConfigError,
  CLIENT_TIMEOUT_MARGIN_SECONDS,
  loadAgentConfig,
} from "./config.ts";
import { verifyMessageSignature } from "./verify.ts";

// Waiting is bounded at both ends: an owner may be asleep, and a poll every second for an hour is
// a poll the sidecar does not need. Neither bound changes a request or creates one.
const DEFAULT_WAIT_SECONDS = 300;
const MIN_WAIT_SECONDS = 1;
const MAX_WAIT_SECONDS = 86_400;
const DEFAULT_POLL_SECONDS = 3;
const MIN_POLL_SECONDS = 1;
const MAX_POLL_SECONDS = 300;

const USAGE = `Usage: pnpm agent <command> [options]

Commands:
  hello [text]     Show text on the phone (default "Hello Seeker") and wait for OK.
                   Prints the acknowledgement {"id":"...","result":"OK"} on stdout.
  address          Print the wallet the owner selected on their phone and its network
                   (vault_get_address), as JSON. It fails if they've connected none.
  capabilities     Print what the sidecar can actually do (vault_get_capabilities), as JSON.
  sign <text>      Ask the owner's wallet to sign the text (vault_sign_message). Prints the
                   request, PENDING, as JSON; it doesn't wait for the owner unless --wait
                   says so. Read it back with get, which checks the signature itself.
  transfer <to> <amount>
                   Ask the owner to send <amount> base units to <to> (vault_transfer):
                   lamports for SOL, or the mint's base units with --mint. --wallet and
                   --network are both required, and neither is guessed. Prints the
                   request, PENDING, as JSON; nothing is built, signed, or sent here.
                   Read it back with get or status.
  swap <from> <amount>
                   Ask the owner to swap. No sidecar serves swaps yet, so this reports what
                   is missing and exits 3; it never queues anything.
  ack <text>       Queue text for the owner to acknowledge later (vault_request_ack, a demo
                   tool that the sidecar serves only with MCP_DEMO_TOOLS=true). Prints the
                   request, PENDING, as JSON; it doesn't wait for the owner unless --wait
                   says so. A development diagnostic: see --demo.
  get <id>         Print a request as it is now (vault_get_request).
  status <id>      Print what became of a request: its state, 'outcome' (succeeded, failed,
                   or unsettled), what the server last read from the chain, and, for a
                   transfer that was sent, the explorer link for its cluster. Exit 0 once it
                   ended as asked, 10 while no outcome is established, 11 once it ended any
                   other way.
  wait <id>        Read a request until it settles or the deadline passes, whichever comes
                   first, then print what status prints. Same exit codes: a deadline that
                   passes with the request still open is 10, not a failure — the request is
                   untouched, and nothing is ever sent twice.
  cancel <id>      Withdraw a PENDING request (vault_cancel_request).
  tools            List the MCP server's tools as JSON.

Options:
  --key <key>      ack, sign, transfer: the idempotency key. Default: a new one, printed on
                   stderr. Reuse it to retry without sending twice.
  --note <text>    ack, sign, transfer: a note for the owner, shown apart from the text.
  --expires <s>    ack, sign, transfer: seconds until the request expires, 60 to 604800.
  --wallet <addr>  sign: the wallet to sign with; default the one vault_get_address
                   returns. transfer: required, and never defaulted.
  --network <name> transfer: required. mainnet, devnet, or testnet.
  --mint <addr>    transfer: the SPL token's mint. Default: native SOL.
  --timeout <s>    hello: client timeout in seconds; default LIVE_COMMAND_TIMEOUT_SECONDS + ${CLIENT_TIMEOUT_MARGIN_SECONDS}.
  --wait           ack, sign, transfer: after the sidecar accepts the request, keep reading it
                   until it settles or --for passes. The request_id goes to stderr at once,
                   and stdout carries one JSON document: the outcome.
  --for <s>        wait, --wait: how long to keep reading, ${MIN_WAIT_SECONDS} to ${MAX_WAIT_SECONDS}. Default ${DEFAULT_WAIT_SECONDS}.
  --every <s>      wait, --wait: how long to leave between reads, ${MIN_POLL_SECONDS} to ${MAX_POLL_SECONDS}. Default ${DEFAULT_POLL_SECONDS}.
  --demo           hello, ack: run the diagnostic without MCP_DEMO_TOOLS=true in the
                   environment. They show text and get an acknowledgement back, which is
                   neither a signature nor a payment.
  -h, --help       Show this help.

Environment (the root .env, or the shell, which takes precedence):
  MCP_URL, MCP_TOKEN, LIVE_COMMAND_TIMEOUT_SECONDS, MCP_DEMO_TOOLS

Exit codes: 0 OK, 1 unexpected result, 2 usage or configuration, 3 connection,
4 OFFLINE, 5 BUSY, 6 TIMEOUT, 7 CANCELLED, 8 INVALID_TEXT, 9 refused by the sidecar,
10 status and wait: no outcome established yet, 11 status and wait: it ended badly.`;

// How many positional arguments each command takes.
const ARITY: Readonly<Record<string, readonly [number, number]>> = {
  hello: [0, 1],
  address: [0, 0],
  capabilities: [0, 0],
  sign: [1, 1],
  transfer: [2, 2],
  swap: [2, 2],
  ack: [1, 1],
  get: [1, 1],
  status: [1, 1],
  wait: [1, 1],
  cancel: [1, 1],
  tools: [0, 0],
};

/** The durable commands that can be told to wait for the owner's answer. */
const CAN_WAIT = ["ack", "sign", "transfer"];
/** The two diagnostics: they need development mode, because an OK is not an approval. */
const DIAGNOSTICS = ["hello", "ack"];

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

/**
 * A request with `signature_verified` added, for a signed message. The agent checks the signature
 * itself, against the wallet and the exact bytes the sidecar returned; it never assumes one it was
 * handed is good.
 */
function checked(view: RequestView): Record<string, unknown> {
  const { wallet, signature, signed_message_base64: signed } = view;
  if (wallet === undefined || signature === undefined || signed === undefined) {
    return { ...view };
  }
  return {
    ...view,
    signature_verified: verifyMessageSignature(wallet, signed, signature),
  };
}

/** The clusters a transfer may name. The sidecar refuses any that isn't the owner's. */
const NETWORKS = ["mainnet", "devnet", "testnet"];

/**
 * What became of a request, as a script wants to read it: its state, what the server last read
 * from the chain, and where to see the transaction. `signature_is_transaction` is the field that
 * keeps the two kinds of signature apart. A signed message carries a signature too, and it is not
 * a payment: nothing was sent, no cluster has it, and there is no explorer link for it.
 */
function statusOf(view: RequestView): Record<string, unknown> {
  const sent = view.action === "transfer" || view.action === "swap";
  const url = explorerUrl(view);
  return {
    request_id: view.request_id,
    action: view.action,
    status: view.status,
    // The state in a word, so a script branches without knowing the state table. "unsettled"
    // covers UNKNOWN as well: nobody knows is not the same as it failed.
    outcome: outcomeOf(view.status),
    terminal: view.terminal,
    updated_at: view.updated_at,
    ...(view.network === undefined ? {} : { network: view.network }),
    ...(view.wallet === undefined ? {} : { wallet: view.wallet }),
    ...(view.signature === undefined
      ? {}
      : {
          signature: view.signature,
          signature_is_transaction: sent,
        }),
    ...(view.confirmation === undefined
      ? {}
      : { confirmation: view.confirmation }),
    ...(view.slot === undefined ? {} : { slot: view.slot }),
    ...(view.chain_error === undefined
      ? {}
      : { chain_error: view.chain_error }),
    ...(view.checked_at === undefined ? {} : { checked_at: view.checked_at }),
    ...(view.checked_with === undefined
      ? {}
      : { checked_with: view.checked_with }),
    ...(view.detail === undefined ? {} : { detail: view.detail }),
    ...(url === undefined ? {} : { explorer_url: url }),
  };
}

/**
 * What `wait` and `--wait` print: the same document `status` prints, plus how the wait itself
 * ended. `timed_out` is the honest answer to "is it done?" — no, and the request is exactly as it
 * was: still there, still the owner's to answer, and readable again with `status`.
 */
function waitedStatus(result: WaitResult): Record<string, unknown> {
  return {
    ...statusOf(result.view),
    waited_seconds: result.waitedSeconds,
    polls: result.polls,
    timed_out: result.timedOut,
  };
}

function wholeSeconds(value: string | undefined): number | undefined {
  if (value === undefined) return undefined;
  return /^\d+$/.test(value) ? Number(value) : 0;
}

/** A --for or --every value, or undefined with the problem already reported. */
function boundedSeconds(
  raw: string | undefined,
  name: string,
  fallback: number,
  min: number,
  max: number,
  report: (message: string) => void,
): number | undefined {
  if (raw === undefined) return fallback;
  const value = wholeSeconds(raw);
  if (value === undefined || value < min || value > max) {
    report(`${name} must be a whole number of seconds from ${min} to ${max}.`);
    return undefined;
  }
  return value;
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
        wallet: { type: "string" },
        network: { type: "string" },
        mint: { type: "string" },
        note: { type: "string" },
        expires: { type: "string" },
        wait: { type: "boolean" },
        for: { type: "string" },
        every: { type: "string" },
        demo: { type: "boolean" },
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

  // `hello` and `ack` are diagnostics: they put text in front of the owner and get an
  // acknowledgement back. That acknowledgement is not a signature and not a payment, so they need
  // to be asked for on purpose — with the sidecar's own MCP_DEMO_TOOLS, or --demo for one run.
  if (
    DIAGNOSTICS.includes(command) &&
    !config.demoTools &&
    parsed.values.demo !== true
  ) {
    printError(
      `${command} is a development diagnostic: it shows text and returns the owner's OK, which ` +
        "is neither a wallet signature nor a payment confirmation. Run it with --demo, or set " +
        "MCP_DEMO_TOOLS=true in the environment. The wallet commands are sign and transfer.",
    );
    return ExitCode.USAGE;
  }

  const waiting = parsed.values.wait === true;
  if (waiting && !CAN_WAIT.includes(command)) {
    printError(
      `--wait applies to ${CAN_WAIT.join(", ")}; use \`wait <id>\` instead.`,
    );
    return ExitCode.USAGE;
  }
  let usageProblem: string | undefined;
  const report = (message: string): void => {
    usageProblem ??= message;
  };
  const waitSeconds = boundedSeconds(
    parsed.values.for,
    "--for",
    DEFAULT_WAIT_SECONDS,
    MIN_WAIT_SECONDS,
    MAX_WAIT_SECONDS,
    report,
  );
  const pollSeconds = boundedSeconds(
    parsed.values.every,
    "--every",
    DEFAULT_POLL_SECONDS,
    MIN_POLL_SECONDS,
    MAX_POLL_SECONDS,
    report,
  );
  if (
    usageProblem !== undefined ||
    waitSeconds === undefined ||
    pollSeconds === undefined
  ) {
    printError(usageProblem ?? "invalid wait settings");
    return ExitCode.USAGE;
  }
  const waitFor: number = waitSeconds;
  const waitEvery: number = pollSeconds;

  // A spending request names every part of itself. The CLI refuses an incomplete one here, before
  // it opens a session, so nothing reaches the sidecar that the owner would have to review. A swap
  // is held to the same rule, so that the command that reports it is unserved never becomes a
  // looser one the day it is.
  if (command === "transfer" || command === "swap") {
    if (
      parsed.values.wallet === undefined ||
      parsed.values.network === undefined
    ) {
      printError(
        `${command} needs --wallet and --network; read them with \`pnpm agent address\` and pass ` +
          "them, rather than letting a payment pick its own wallet or cluster.",
      );
      return ExitCode.USAGE;
    }
    if (!NETWORKS.includes(parsed.values.network)) {
      printError(`--network must be one of ${NETWORKS.join(", ")}.`);
      return ExitCode.USAGE;
    }
    if (!/^[1-9]\d*$/.test(rest[1] ?? "")) {
      printError(
        "<amount> is in the asset's base units: a whole number above zero, lamports for SOL. " +
          "A decimal such as 1.5 is not one.",
      );
      return ExitCode.USAGE;
    }
  }

  let outcome: ExitCode = ExitCode.OK;
  try {
    await withMcpSession(config.mcpUrl, config.mcpToken, async (client) => {
      /**
       * What a durable command prints. Without --wait that is the accepted request, PENDING, as
       * it has always been. With it, the request_id reaches stderr the moment the sidecar accepts
       * the request — before any waiting, so an interrupted wait still leaves the caller with the
       * one thing they need — and stdout carries a single JSON document at the end.
       */
      async function finish(view: RequestView): Promise<void> {
        if (!waiting) {
          print(JSON.stringify(view));
          return;
        }
        printError(`request_id: ${view.request_id}`);
        printError(
          `Waiting up to ${waitFor} s for the owner, reading every ${waitEvery} s. ` +
            "Stopping here changes nothing: the request stays as it is.",
        );
        report(
          await waitForRequest(client, view.request_id, {
            timeoutMs: waitFor * 1000,
            intervalMs: waitEvery * 1000,
          }),
        );
      }

      function report(result: WaitResult): void {
        print(JSON.stringify(waitedStatus(result)));
        outcome = outcomeExitCode(result.view.status);
        if (result.timedOut) {
          printError(
            `still ${result.view.status} after ${result.waitedSeconds} s. Nothing was withdrawn ` +
              `and nothing was sent twice; read it again with \`status ${result.view.request_id}\`.`,
          );
        }
      }

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
        case "address": {
          print(JSON.stringify(await getAddress(client)));
          return;
        }
        case "capabilities": {
          print(JSON.stringify(await getCapabilities(client)));
          return;
        }
        case "sign": {
          // Without --wallet, the agent reads the owner's wallet first, the way any agent should:
          // there is no address to assume, and naming another one is refused.
          const wallet =
            parsed.values.wallet ?? (await getAddress(client)).wallet;
          const key = parsed.values.key ?? `sign-${randomUUID()}`;
          if (parsed.values.key === undefined) {
            printError(`idempotency key: ${key}`);
          }
          const args: Record<string, unknown> = {
            wallet,
            message: rest[0],
            idempotency_key: key,
          };
          if (parsed.values.note !== undefined) args.note = parsed.values.note;
          if (expires !== undefined) args.expires_in_seconds = expires;
          printError(
            "The owner reviews it on their Seeker; nothing is signed until they approve.",
          );
          await finish(await requestTool(client, SIGN_MESSAGE_TOOL, args));
          return;
        }
        case "transfer": {
          // Every part of a spending request is named here, and none of it is guessed. The wallet
          // and the network are the two that decide whose money moves and on which cluster, so a
          // transfer that doesn't name them is refused rather than filled in from
          // vault_get_address. The sidecar refuses a wallet or network that isn't the owner's, so
          // naming them wrong fails the request instead of sending anything.
          const key = parsed.values.key ?? `transfer-${randomUUID()}`;
          if (parsed.values.key === undefined) {
            printError(`idempotency key: ${key}`);
          }
          const args: Record<string, unknown> = {
            wallet: parsed.values.wallet,
            network: parsed.values.network,
            recipient: rest[0],
            amount: rest[1],
            idempotency_key: key,
          };
          if (parsed.values.mint !== undefined) {
            args.token_mint = parsed.values.mint;
          }
          if (parsed.values.note !== undefined) args.note = parsed.values.note;
          if (expires !== undefined) args.expires_in_seconds = expires;
          printError(
            "The owner reviews it on their Seeker; nothing is signed or sent until they approve.",
          );
          await finish(await requestTool(client, TRANSFER_TOOL, args));
          return;
        }
        case "swap": {
          // Swaps are Stage 6, and no sidecar serves vault_swap yet. The command exists so that
          // asking for one says what is missing instead of failing as an unknown word, and it
          // stops here: nothing is queued, and nothing pretends a swap was requested.
          await requireTool(
            client,
            SWAP_TOOL,
            "swaps are a later stage, and this sidecar serves none. `capabilities` lists what it " +
              "does serve",
          );
          throw new AgentFailure(
            ExitCode.FAILURE,
            `${SWAP_TOOL} is offered but this client cannot drive it yet`,
          );
        }
        case "ack": {
          await requireTool(
            client,
            REQUEST_ACK_TOOL,
            "it's a demo tool, which the sidecar serves only with MCP_DEMO_TOOLS=true",
          );
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
          printError(
            "A diagnostic: the owner's acknowledgement is not a signature and not a payment.",
          );
          await finish(await requestTool(client, REQUEST_ACK_TOOL, args));
          return;
        }
        case "get":
        case "cancel": {
          const view = await requestTool(
            client,
            command === "get" ? GET_REQUEST_TOOL : CANCEL_REQUEST_TOOL,
            { request_id: rest[0] },
          );
          print(JSON.stringify(checked(view)));
          return;
        }
        case "status": {
          const view = await requestTool(client, GET_REQUEST_TOOL, {
            request_id: rest[0],
          });
          print(JSON.stringify(statusOf(view)));
          outcome = outcomeExitCode(view.status);
          return;
        }
        case "wait": {
          printError(
            `Reading ${rest[0] ?? ""} every ${waitEvery} s, for up to ${waitFor} s.`,
          );
          report(
            await waitForRequest(client, rest[0] ?? "", {
              timeoutMs: waitFor * 1000,
              intervalMs: waitEvery * 1000,
            }),
          );
          return;
        }
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
    return outcome;
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
