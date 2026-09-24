/**
 * What a test needs to start this server: a cluster that only knows which cluster it is, and a
 * configuration pointed at a temporary directory. No test reaches a network.
 */
import { createServer } from "node:http";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AddressInfo } from "node:net";

import type { Config } from "../config.ts";

export interface StubCluster {
  readonly url: string;
  methods(): readonly string[];
  close(): Promise<void>;
}

/** A JSON-RPC endpoint that knows which cluster it is and refuses to pretend anything else. */
export async function stubCluster(genesis: string): Promise<StubCluster> {
  const methods: string[] = [];
  const server = createServer((request, response) => {
    let body = "";
    request.on("data", (chunk: Buffer) => (body += chunk.toString()));
    request.on("end", () => {
      const call = JSON.parse(body) as { id: number; method: string };
      methods.push(call.method);
      const result = call.method === "getGenesisHash" ? genesis : null;
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({ jsonrpc: "2.0", id: call.id, result }));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${port}`,
    methods: () => methods,
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}

const directories: string[] = [];

/** A valid configuration in a temporary directory, with port 0 so tests cannot collide. */
export function configFor(
  rpcUrl: string,
  overrides: Partial<Config> = {},
): Config {
  const directory = mkdtempSync(join(tmpdir(), "skr-staking-server-test-"));
  directories.push(directory);
  return {
    host: "127.0.0.1",
    // Port 0: the listener picks a free one, so concurrent tests cannot collide.
    port: 0,
    mcpToken: "t".repeat(64),
    publicUrl: undefined,
    dataDirectory: directory,
    databasePath: join(directory, "skr-staking-server.db"),
    rpcUrl,
    rpcTimeoutMs: 2_000,
    requestTtlSeconds: 3_600,
    pendingLimit: 10,
    pairingTokenTtlSeconds: 600,
    allowedHosts: [],
    guardian: undefined,
    h2c: false,
    ...overrides,
  };
}

/** Removes every directory `configFor` made; call it from a test file's `after`. */
export function removeTemporaryDirectories(): void {
  for (const directory of directories.splice(0)) {
    rmSync(directory, { recursive: true, force: true });
  }
}
