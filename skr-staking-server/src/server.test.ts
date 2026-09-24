/**
 * What a started server is wired with.
 *
 * The cluster is a stub in this file: it answers `getGenesisHash` with mainnet-beta's and nothing
 * else, which is exactly as much chain as starting up requires. No test here reaches a network.
 */
import assert from "node:assert/strict";
import { after, describe, it } from "node:test";
import { MAINNET_GENESIS_HASH } from "./skr/chain.ts";
import {
  configFor,
  removeTemporaryDirectories,
  stubCluster,
} from "./testing/cluster.ts";
import { startStakingServer } from "./server.ts";

after(removeTemporaryDirectories);

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
