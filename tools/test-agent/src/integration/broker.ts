/**
 * The pinned broker, when the machine running the integration suite has it (SEE-98).
 *
 * The transport the gateway fans out through is Centrifugo v6 with Redis as its engine
 * (`docs/wiki/feed-gateway.md#the-transport-and-what-it-cannot-do`), and the shipped
 * `feed-gateway/centrifugo.yaml` is the configuration this starts it with — unchanged, because a run
 * against a configuration nobody deploys proves nothing about the one they do. Only the engine is
 * overridden, to memory: Redis is what makes two nodes one broker, and one node accepting a
 * publication is what this leg is about. Two nodes, a shared history and a failover are
 * `feeds/CentrifugoStreamIntegrationTest` on the phone's side, and SEE-99's at size.
 *
 * It is opt-in on purpose. The binary is 65 MB, is verified against the release checksums by hand
 * (`third_party/centrifugo/SHA256SUMS`), and is not vendored; a run without it says the stream leg
 * did not run rather than quietly passing. That is the same arrangement the Go tests use, and the
 * same variable: `SEEKERVAULT_CENTRIFUGO`.
 */
import { spawn } from "node:child_process";
import { once } from "node:events";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";

/** The two keys this run's broker and gateway share. Throwaway, and never a deployment's. */
export const BROKER_API_KEY = "integration-api-key";
export const BROKER_TOKEN_KEY = "integration-token-key";

const CONFIG = fileURLToPath(
  new URL("../../../feed-gateway/centrifugo.yaml", import.meta.url),
);

export interface Broker {
  /** The broker's HTTP API origin, which is what the gateway publishes to. */
  readonly url: string;
  output(): string;
  /** A channel's epoch and offset: the supported way to ask whether a channel moved. */
  position(channel: string): Promise<{ epoch: string; offset: number }>;
  stop(): Promise<void>;
}

/** Starts the broker on the shipped configuration and resolves once it is healthy. */
export async function startBroker(
  binary: string,
  ports: { readonly api: number; readonly stream: number },
): Promise<Broker> {
  const child = spawn(binary, ["-c", CONFIG], {
    env: {
      ...process.env,
      CENTRIFUGO_ENGINE_TYPE: "memory",
      CENTRIFUGO_HTTP_SERVER_PORT: String(ports.api),
      CENTRIFUGO_UNI_GRPC_PORT: String(ports.stream),
      CENTRIFUGO_HTTP_API_KEY: BROKER_API_KEY,
      CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY: BROKER_TOKEN_KEY,
      CENTRIFUGO_LOG_LEVEL: "error",
    },
  });
  let output = "";
  child.stdout.setEncoding("utf8").on("data", (chunk: string) => {
    output += chunk;
  });
  child.stderr.setEncoding("utf8").on("data", (chunk: string) => {
    output += chunk;
  });
  const url = `http://127.0.0.1:${ports.api}`;
  // Starting it is also how its configuration is checked: `checkconfig` accepts settings the
  // server then refuses to run with, so a broker that never becomes healthy fails the run.
  const deadline = Date.now() + 15_000;
  for (;;) {
    if (child.exitCode !== null || child.signalCode !== null) {
      throw new Error(`the broker exited:\n${output}`);
    }
    try {
      const answer = await fetch(`${url}/health`);
      if (answer.ok) break;
    } catch {
      // Not listening yet.
    }
    if (Date.now() > deadline) {
      throw new Error(`the broker never became healthy:\n${output}`);
    }
    await delay(50);
  }
  return {
    url,
    output: () => output,
    async position(channel) {
      const answer = await fetch(`${url}/api/history`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-api-key": BROKER_API_KEY,
        },
        body: JSON.stringify({ channel, limit: 0 }),
      });
      const held = (await answer.json()) as {
        result?: { epoch?: string; offset?: number };
        error?: { code?: number };
      };
      if (held.error !== undefined) {
        throw new Error(
          `asking the broker for a position failed with code ${String(held.error.code)}`,
        );
      }
      return {
        epoch: held.result?.epoch ?? "",
        offset: held.result?.offset ?? 0,
      };
    },
    async stop() {
      if (child.exitCode !== null || child.signalCode !== null) return;
      const exited = once(child, "exit");
      child.kill("SIGTERM");
      await exited;
    },
  };
}
