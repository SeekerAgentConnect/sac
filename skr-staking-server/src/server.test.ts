/**
 * What a started server is wired with.
 *
 * The cluster is a stub in this file: it answers `getGenesisHash` with mainnet-beta's and nothing
 * else, which is exactly as much chain as starting up requires. No test here reaches a network.
 */
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, describe, it } from "node:test";
import type { AddressInfo } from "node:net";
import { MAINNET_GENESIS_HASH } from "./skr/chain.ts";
import type { Config } from "./config.ts";
import { startStakingServer } from "./server.ts";

/** A JSON-RPC endpoint that knows which cluster it is and refuses to pretend anything else. */
async function stubCluster(genesis: string): Promise<{
  url: string;
  methods: () => readonly string[];
  close: () => Promise<void>;
}> {
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

function configFor(rpcUrl: string): Config {
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
  };
}

after(() => {
  for (const directory of directories) {
    rmSync(directory, { recursive: true, force: true });
  }
});

describe("starting the staking server", () => {
  it("refuses to come up against a cluster that is not mainnet-beta", async () => {
    const cluster = await stubCluster(
      "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY",
    );
    try {
      await assert.rejects(
        startStakingServer(configFor(cluster.url), { log: () => undefined }),
        /mainnet-beta/,
      );
    } finally {
      await cluster.close();
    }
  });

  // The regression: the SDK builds a ConfirmationTracker only when it is handed a confirmation
  // provider, and this server handed it none. Without one, `confirmations` is undefined, a
  // SUBMITTED staking request is never asked about again — the agent polls and reads the same
  // unchanged request, and the owner's own CheckStatus is answered CHAIN_UNAVAILABLE — so it
  // never reaches CONFIRMED or FAILED. For an unstake that is a cooldown nobody can confirm began.
  it("can find out what became of a submitted transaction", async () => {
    const cluster = await stubCluster(MAINNET_GENESIS_HASH);
    const server = await startStakingServer(configFor(cluster.url), {
      log: () => undefined,
    });
    try {
      const confirmations = server.direct.requests.confirmations;
      assert.ok(
        confirmations !== undefined,
        "no confirmation tracking: a submitted request could never settle",
      );
      // The host and nothing else: the configured URL can carry an API key, and what an agent and
      // an owner are told is whose word a settled result rests on.
      assert.equal(confirmations.endpoint, new URL(cluster.url).host);
      assert.ok(!confirmations.endpoint.includes("http"));
    } finally {
      await server.close();
      await cluster.close();
    }
  });

  it("establishes the cluster before it serves anything", async () => {
    const cluster = await stubCluster(MAINNET_GENESIS_HASH);
    const server = await startStakingServer(configFor(cluster.url), {
      log: () => undefined,
    });
    try {
      assert.deepEqual(cluster.methods(), ["getGenesisHash"]);
      assert.match(server.url, /^http:\/\/127\.0\.0\.1:\d+$/);
    } finally {
      await server.close();
      await cluster.close();
    }
  });
});
