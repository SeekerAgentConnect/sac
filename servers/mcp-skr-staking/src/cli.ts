#!/usr/bin/env node
/**
 * The standalone SKR staking MCP server (SEE-146).
 *
 * It exposes sessionful MCP Streamable HTTP at `/mcp` and the Direct Server SDK's phone API on the
 * same listener. It does not speak MCP over stdio. Configuration comes from the environment, an
 * explicit `--config` env file, or the optional `config.env` in `SKR_STAKING_DATA_DIR`.
 *
 * It is a separate executable from `seeker-agent-connect-mcp` on purpose: a different database, a
 * different pairing, a different connection on the phone, and its own credentials. Running one says
 * nothing about running the other.
 */
import { loadConfigFile } from "./storage/config-file.ts";
import { ConfigError, loadConfig } from "./config.ts";
import { runPairingCommand } from "./pairing/cli.ts";
import { VERSION, startStakingServer } from "./server.ts";

const USAGE = [
  "Usage: seeker-skr-staking-mcp [--config <path>] <command>",
  "",
  "Commands:",
  "  start                 Run the server: MCP at /mcp and the phone API on one listener.",
  "  pair                  Show a one-use pairing code for the owner's phone.",
  "  pair status           Show the paired phone, if there is one.",
  "  pair revoke           Revoke the paired phone and cancel its pending requests.",
  "",
  "Options:",
  "  --config <path>       Read configuration from this env file before the environment.",
  "  --help                Show this message.",
  "  --version             Show the version.",
].join("\n");

interface Parsed {
  readonly command: string;
  readonly arguments: readonly string[];
  readonly configPath: string | undefined;
  readonly help: boolean;
  readonly version: boolean;
}

function parse(argv: readonly string[]): Parsed | undefined {
  let configPath: string | undefined;
  const rest: string[] = [];
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--help" || argument === "-h") {
      return {
        command: "",
        arguments: [],
        configPath,
        help: true,
        version: false,
      };
    }
    if (argument === "--version" || argument === "-v") {
      return {
        command: "",
        arguments: [],
        configPath,
        help: false,
        version: true,
      };
    }
    if (argument === "--config") {
      const value = argv[index + 1];
      if (value === undefined) return undefined;
      configPath = value;
      index += 1;
      continue;
    }
    if (argument?.startsWith("--")) return undefined;
    if (argument !== undefined) rest.push(argument);
  }
  const [command, ...remaining] = rest;
  return {
    command: command ?? "",
    arguments: remaining,
    configPath,
    help: false,
    version: false,
  };
}

async function runCli(argv: readonly string[]): Promise<number> {
  const parsed = parse(argv);
  if (parsed === undefined) {
    console.error(USAGE);
    return 2;
  }
  if (parsed.help) {
    console.log(USAGE);
    return 0;
  }
  if (parsed.version) {
    console.log(VERSION);
    return 0;
  }

  try {
    loadConfigFile(parsed.configPath);
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    return 2;
  }

  if (parsed.command === "pair") {
    return runPairingCommand(parsed.arguments);
  }
  if (parsed.command !== "start") {
    console.error(USAGE);
    return 2;
  }
  return runStart();
}

async function runStart(): Promise<number> {
  let started;
  try {
    started = await startStakingServer(loadConfig());
  } catch (error) {
    if (error instanceof ConfigError) {
      console.error(error.message);
      return 2;
    }
    console.error(
      `[skr-staking] failed to start: ${error instanceof Error ? error.message : String(error)}`,
    );
    return 1;
  }

  return new Promise<number>((resolve) => {
    let stopping = false;
    const stop = (signal: NodeJS.Signals): void => {
      if (stopping) return;
      stopping = true;
      console.log(
        `[skr-staking] ${signal}: shutting down; nothing in flight is approved by stopping`,
      );
      void started.close().then(
        () => resolve(0),
        (error: unknown) => {
          console.error(
            `[skr-staking] shutdown failed: ${error instanceof Error ? error.message : String(error)}`,
          );
          resolve(1);
        },
      );
    };
    for (const signal of ["SIGINT", "SIGTERM"] as const) {
      process.once(signal, () => stop(signal));
    }
  });
}

process.exitCode = await runCli(process.argv.slice(2));
