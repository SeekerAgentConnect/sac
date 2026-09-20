#!/usr/bin/env node
import {
  ConfigError,
  loadSidecarConfig,
  type SidecarConfig,
} from "./config.ts";
import { runPairingCommand } from "./pairing/cli.ts";
import { startSidecar } from "./server.ts";
import { loadConfigFile } from "./storage/config-file.ts";

const VERSION = "0.1.0";
const USAGE = `Seeker Agent Connect MCP server ${VERSION}

Usage:
  seeker-agent-connect-mcp [--config <path>] start
  seeker-agent-connect-mcp [--config <path>] pair [status | revoke]
  seeker-agent-connect-mcp --help
  seeker-agent-connect-mcp --version

The server exposes sessionful MCP Streamable HTTP at /mcp and the direct phone API on the same
listener. It does not speak MCP over stdio. Configuration comes from the environment, an explicit
--config env file, or the optional config.env in MCP_SERVER_DATA_DIR.`;

export async function runCli(args: readonly string[]): Promise<number> {
  if (args.length === 1 && args[0] === "--help") {
    console.log(USAGE);
    return 0;
  }
  if (args.length === 1 && args[0] === "--version") {
    console.log(VERSION);
    return 0;
  }

  const parsed = parseArgs(args);
  if (parsed === undefined) {
    console.error(USAGE);
    return 2;
  }
  try {
    loadConfigFile(parsed.configPath);
  } catch (error) {
    console.error(
      `Could not load MCP server configuration: ${error instanceof Error ? error.message : String(error)}`,
    );
    return 2;
  }

  if (parsed.command === "pair") {
    return runPairingCommand(parsed.arguments);
  }
  if (parsed.arguments.length !== 0) {
    console.error(USAGE);
    return 2;
  }
  return runServer();
}

interface ParsedArguments {
  readonly configPath?: string;
  readonly command: "start" | "pair";
  readonly arguments: readonly string[];
}

function parseArgs(args: readonly string[]): ParsedArguments | undefined {
  const values = [...args];
  let configPath: string | undefined;
  if (values[0] === "--config") {
    configPath = values[1];
    if (configPath === undefined || configPath === "") return undefined;
    values.splice(0, 2);
  }
  const command = values.shift();
  if (command !== "start" && command !== "pair") return undefined;
  return {
    ...(configPath === undefined ? {} : { configPath }),
    command,
    arguments: values,
  };
}

async function runServer(): Promise<number> {
  let config: SidecarConfig;
  try {
    config = loadSidecarConfig(process.env);
  } catch (error) {
    if (!(error instanceof ConfigError)) throw error;
    console.error(error.message);
    return 2;
  }

  const server = await startSidecar(config).catch((error: unknown) => {
    console.error(
      `[mcp-server] could not start: ${error instanceof Error ? error.message : String(error)}`,
    );
    return undefined;
  });
  if (server === undefined) return 1;

  return new Promise((resolve) => {
    let stopping = false;
    const stop = (signal: NodeJS.Signals): void => {
      if (stopping) return;
      stopping = true;
      console.log(
        `[mcp-server] ${signal}: shutting down; the in-flight command, if any, is cancelled`,
      );
      void server.close().then(
        () => resolve(0),
        (error: unknown) => {
          console.error(
            `[mcp-server] shutdown failed: ${error instanceof Error ? error.message : String(error)}`,
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
