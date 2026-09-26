/**
 * The chain client (SAW-019): what it reads, and how it fails. Every case runs against a local
 * stub, never a real endpoint, and the last one is the one that matters most for secrets — an
 * endpoint URL can carry an API key, so it must never reach an error message.
 */
import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, describe, it } from "node:test";

import { Network } from "@seeker_agent_connect/server-sdk/protocol";
import { FakeChain, startFakeRpc, type FakeRpc } from "../testing/chain.ts";
import { GENESIS_HASHES } from "./network.ts";
import { ChainUnavailable, SolanaRpc, withChainBudget } from "./rpc.ts";

const TIMEOUT_MS = 2000;
const API_KEY = "super-secret-api-key";

describe("SolanaRpc", () => {
  let chain: FakeChain;
  let endpoint: FakeRpc;
  let rpc: SolanaRpc;

  before(async () => {
    chain = new FakeChain();
    endpoint = await startFakeRpc(chain);
    rpc = new SolanaRpc(endpoint.url, { timeoutMs: TIMEOUT_MS });
  });

  after(async () => {
    await endpoint.close();
  });

  it("reads the genesis hash once and remembers it", async () => {
    assert.equal(await rpc.genesisHash(), GENESIS_HASHES.get(Network.DEVNET));
    const before = chain.calls.length;
    assert.equal(await rpc.genesisHash(), GENESIS_HASHES.get(Network.DEVNET));
    assert.equal(chain.calls.length, before, "the second call read nothing");
  });

  it("returns undefined for an address the chain has no account at", async () => {
    assert.equal(
      await rpc.account("So11111111111111111111111111111111111111112"),
      undefined,
    );
  });

  it("reads an account's owner, data, and whether it is executable", async () => {
    chain.put("So11111111111111111111111111111111111111112", {
      owner: "11111111111111111111111111111111",
      executable: true,
      data: Uint8Array.from([1, 2, 3]),
    });
    const account = await rpc.account(
      "So11111111111111111111111111111111111111112",
    );
    assert.equal(account?.owner, "11111111111111111111111111111111");
    assert.equal(account?.executable, true);
    assert.deepEqual(Array.from(account?.data ?? []), [1, 2, 3]);
  });

  it("reads the latest blockhash, the block height, the fee, and the rent", async () => {
    const latest = await rpc.latestBlockhash();
    assert.equal(latest.blockhash, chain.blockhash);
    assert.equal(latest.lastValidBlockHeight, 1000n);
    assert.equal(await rpc.blockHeight(), 900n);
    assert.equal(await rpc.feeForMessage("AAA="), 5000n);
    assert.equal(await rpc.rentExemption(165), 2_039_280n);
  });

  it("reports a fee the endpoint won't give as undefined, not as free", async () => {
    chain.feeLamports = undefined;
    assert.equal(await rpc.feeForMessage("AAA="), undefined);
    chain.feeLamports = 5000n;
  });
});

describe("SolanaRpc failures", () => {
  let server: Server;
  let url: string;
  let respond: (body: string, status?: number) => void;
  let answer = { body: "", status: 200, delayMs: 0 };

  before(async () => {
    server = createServer((_req, res) => {
      setTimeout(() => {
        res.writeHead(answer.status, { "Content-Type": "application/json" });
        res.end(answer.body);
      }, answer.delayMs).unref();
    });
    server.listen(0, "127.0.0.1");
    await once(server, "listening");
    const { port } = server.address() as AddressInfo;
    url = `http://127.0.0.1:${port}/?api-key=${API_KEY}`;
    respond = (body, status = 200) => {
      answer = { body, status, delayMs: 0 };
    };
  });

  after(async () => {
    const closed = new Promise<void>((resolve) =>
      server.close(() => resolve()),
    );
    server.closeAllConnections();
    await closed;
  });

  function client(): SolanaRpc {
    return new SolanaRpc(url, { timeoutMs: 200 });
  }

  it("reports a JSON-RPC error as unavailable, with what the endpoint said", async () => {
    respond(
      JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        error: { code: -32005, message: "Node is behind by 500 slots" },
      }),
    );
    await assert.rejects(client().blockHeight(), (error: Error) => {
      assert.ok(error instanceof ChainUnavailable);
      assert.match(error.message, /Node is behind by 500 slots/);
      return true;
    });
  });

  it("reports an HTTP error as unavailable", async () => {
    respond("rate limited", 429);
    await assert.rejects(client().blockHeight(), (error: Error) => {
      assert.ok(error instanceof ChainUnavailable);
      assert.match(error.message, /HTTP 429/);
      return true;
    });
  });

  it("reports an answer that isn't JSON as unavailable", async () => {
    respond("<html>gateway</html>");
    await assert.rejects(client().blockHeight(), ChainUnavailable);
  });

  it("reports an answer of the wrong shape as unavailable, rather than guessing", async () => {
    respond(JSON.stringify({ jsonrpc: "2.0", id: 1, result: "not a number" }));
    await assert.rejects(client().blockHeight(), (error: Error) => {
      assert.ok(error instanceof ChainUnavailable);
      assert.match(error.message, /a whole number/);
      return true;
    });
  });

  it("gives up on an endpoint that doesn't answer in time", async () => {
    answer = { body: "{}", status: 200, delayMs: 1000 };
    await assert.rejects(client().blockHeight(), (error: Error) => {
      assert.ok(error instanceof ChainUnavailable);
      assert.match(error.message, /within 200 ms/);
      return true;
    });
  });

  it("never puts the endpoint URL, which can hold an API key, in an error", async () => {
    const failures: string[] = [];
    for (const body of [
      JSON.stringify({ jsonrpc: "2.0", id: 1, error: { message: "no" } }),
      "not json",
      JSON.stringify({ jsonrpc: "2.0", id: 1, result: {} }),
    ]) {
      respond(body);
      await assert.rejects(client().latestBlockhash(), (error: Error) => {
        failures.push(error.message);
        return error instanceof ChainUnavailable;
      });
    }
    answer = { body: "{}", status: 200, delayMs: 1000 };
    await assert.rejects(client().blockHeight(), (error: Error) => {
      failures.push(error.message);
      return true;
    });
    assert.equal(failures.length, 4);
    for (const message of failures) {
      assert.ok(
        !message.includes(API_KEY) && !message.includes("127.0.0.1"),
        `the message leaked the endpoint: ${message}`,
      );
    }
  });
});

describe("withChainBudget", () => {
  it("returns what the operation returned, when it finishes in time", async () => {
    assert.equal(
      await withChainBudget(() => Promise.resolve("built"), 1000),
      "built",
    );
  });

  it("abandons an operation that outlasts the budget, so nothing it built is used", async () => {
    let finished = false;
    const slow = async () => {
      await new Promise((resolve) => setTimeout(resolve, 200));
      finished = true;
      return "built";
    };
    await assert.rejects(
      withChainBudget(slow, 20),
      (error: Error) =>
        error instanceof ChainUnavailable &&
        /didn't finish answering within 20 ms/.test(error.message),
    );
    assert.equal(finished, false, "the caller is not waiting on it either way");
  });
});
