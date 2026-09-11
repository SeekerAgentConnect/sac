/**
 * Runs the sidecar (`node src/main.ts`) as a real process, for the Stage 1 acceptance checks
 * (`pnpm test:hello`). The process gets only the settings passed here, never the developer's .env.
 */
import { spawn } from "node:child_process";
import { once } from "node:events";
import { createServer, type AddressInfo } from "node:net";
import { fileURLToPath } from "node:url";

const MAIN = fileURLToPath(new URL("../main.ts", import.meta.url));

export interface SidecarProcessOptions {
  readonly port: number;
  readonly mcpToken: string;
  readonly phoneToken: string;
  readonly liveCommandTimeoutSeconds: number;
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

/** Starts the sidecar and resolves once it listens. */
export async function startSidecarProcess(
  options: SidecarProcessOptions,
): Promise<SidecarProcess> {
  const child = spawn(process.execPath, [MAIN], {
    env: {
      PATH: process.env.PATH,
      SIDECAR_HOST: "127.0.0.1",
      SIDECAR_PORT: String(options.port),
      MCP_TOKEN: options.mcpToken,
      PHONE_TOKEN: options.phoneToken,
      LIVE_COMMAND_TIMEOUT_SECONDS: String(options.liveCommandTimeoutSeconds),
    },
  });
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
