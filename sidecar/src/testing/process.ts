/**
 * Runs the sidecar (`node src/main.ts`) as a real process, for the acceptance checks
 * (`pnpm test:hello`) and the restart tests. The process gets only the settings passed here, never
 * the developer's .env, and a throwaway database unless the caller names one.
 */
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdtempSync } from "node:fs";
import { createServer, type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const MAIN = fileURLToPath(new URL("../main.ts", import.meta.url));
const CLOCK = fileURLToPath(new URL("./clock.ts", import.meta.url));

export interface SidecarProcessOptions {
  readonly port: number;
  readonly mcpToken: string;
  readonly phoneToken: string;
  readonly liveCommandTimeoutSeconds: number;
  /** The SQLite file. Pass the same one to restart onto the same requests; a new one if omitted. */
  readonly databasePath?: string;
  /** Serves the demo tool vault_request_ack (MCP_DEMO_TOOLS). */
  readonly demoTools?: boolean;
  /**
   * Runs the sidecar's clock this many milliseconds ahead of the real one (clock.ts). A restart
   * with a larger value stands for time that passed while the sidecar was down.
   */
  readonly clockAheadMs?: number;
  /**
   * The Solana JSON-RPC endpoint (SOLANA_RPC_URL). Point it at `startFakeRpc` to run the transfer
   * tools against a chain the test controls; without one the sidecar serves no transfer at all.
   */
  readonly solanaRpcUrl?: string;
}

export interface SidecarProcess {
  readonly url: string;
  /** Everything the process has printed so far. */
  output(): string;
  /** Sends `signal` (default SIGTERM) and waits until the process has exited. */
  stop(signal?: NodeJS.Signals): Promise<void>;
}

/** A loopback port that was free a moment ago. */
export async function freePort(): Promise<number> {
  const server = createServer().listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address() as AddressInfo;
  server.close();
  await once(server, "close");
  return port;
}

/** A path for a new database, in a new temporary directory. */
export function temporaryDatabasePath(): string {
  return join(
    mkdtempSync(join(tmpdir(), "seeker-vault-sidecar-")),
    "sidecar.db",
  );
}

/** Starts the sidecar and resolves once it listens. */
export async function startSidecarProcess(
  options: SidecarProcessOptions,
): Promise<SidecarProcess> {
  const ahead = options.clockAheadMs ?? 0;
  const child = spawn(
    process.execPath,
    [...(ahead === 0 ? [] : ["--import", CLOCK]), MAIN],
    {
      env: {
        PATH: process.env.PATH,
        SIDECAR_HOST: "127.0.0.1",
        SIDECAR_PORT: String(options.port),
        MCP_TOKEN: options.mcpToken,
        PHONE_TOKEN: options.phoneToken,
        LIVE_COMMAND_TIMEOUT_SECONDS: String(options.liveCommandTimeoutSeconds),
        DATABASE_PATH: options.databasePath ?? temporaryDatabasePath(),
        MCP_DEMO_TOOLS: String(options.demoTools === true),
        SIDECAR_TEST_CLOCK_AHEAD_MS: String(ahead),
        ...(options.solanaRpcUrl === undefined
          ? {}
          : { SOLANA_RPC_URL: options.solanaRpcUrl }),
      },
    },
  );
  let output = "";
  const collect = (chunk: string): void => {
    output += chunk;
  };
  child.stdout.setEncoding("utf8").on("data", collect);
  child.stderr.setEncoding("utf8").on("data", collect);
  await new Promise<void>((resolve, reject) => {
    const onExit = (code: number | null): void => {
      reject(new Error(`the sidecar exited with ${code}: ${output}`));
    };
    const onData = (): void => {
      if (!output.includes("listening on")) return;
      child.stdout.off("data", onData);
      child.off("exit", onExit);
      resolve();
    };
    child.stdout.on("data", onData);
    child.once("exit", onExit);
  });
  return {
    url: `http://127.0.0.1:${options.port}`,
    output: () => output,
    async stop(signal = "SIGTERM") {
      if (child.exitCode !== null || child.signalCode !== null) return;
      const exited = once(child, "exit");
      child.kill(signal);
      await exited;
    },
  };
}
