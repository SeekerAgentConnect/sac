/**
 * What a test needs to start this server: a cluster that knows which cluster it is — and, when a
 * test hands it some, the accounts it holds — and a configuration pointed at a temporary
 * directory. No test reaches a network.
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

/** One account as `getMultipleAccounts` reports it: who owns it, and its bytes. */
export interface StubAccount {
  readonly owner: string;
  readonly data: Buffer;
  readonly lamports?: number;
}

/**
 * A JSON-RPC endpoint that knows which cluster it is and refuses to pretend anything else.
 *
 * It answers `getMultipleAccounts` from `accounts`, keyed by base58 address, and reports every
 * address it was not given as absent — which is what the chain says about an account nobody has
 * created. Everything else is answered `null`.
 */
export async function stubCluster(
  genesis: string,
  accounts: ReadonlyMap<string, StubAccount> = new Map(),
): Promise<StubCluster> {
  const methods: string[] = [];
  const server = createServer((request, response) => {
    let body = "";
    request.on("data", (chunk: Buffer) => (body += chunk.toString()));
    request.on("end", () => {
      const call = JSON.parse(body) as {
        id: number;
        method: string;
        params?: readonly unknown[];
      };
      methods.push(call.method);
      const result =
        call.method === "getGenesisHash"
          ? genesis
          : call.method === "getMultipleAccounts"
            ? multipleAccounts(accounts, call.params)
            : null;
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

/** The shape a real endpoint gives `getMultipleAccounts`, as the web3.js client validates it. */
function multipleAccounts(
  accounts: ReadonlyMap<string, StubAccount>,
  params: readonly unknown[] | undefined,
): unknown {
  const addresses = (params?.[0] ?? []) as readonly string[];
  return {
    context: { slot: 1 },
    value: addresses.map((address) => {
      const account = accounts.get(address);
      return account === undefined
        ? null
        : {
            data: [account.data.toString("base64"), "base64"],
            executable: false,
            lamports: account.lamports ?? 1_000_000,
            owner: account.owner,
            rentEpoch: 0,
            space: account.data.length,
          };
    }),
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
    updatePort: undefined,
    relay: undefined,
    ...overrides,
  };
}

/** Removes every directory `configFor` made; call it from a test file's `after`. */
export function removeTemporaryDirectories(): void {
  for (const directory of directories.splice(0)) {
    rmSync(directory, { recursive: true, force: true });
  }
}
