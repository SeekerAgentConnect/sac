/**
 * The two reads a confirmation rests on, against a stub endpoint.
 *
 * `confirmedTransaction` is the one call in this package that does not go through `Connection`,
 * because it needs the transaction in the bytes the chain holds rather than a re-encoding of a
 * parse. That makes it worth testing directly: the bytes it returns are what a submitted staking
 * request is compared against, and a byte lost in transit would read as "not the approved
 * transaction" forever.
 */
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { describe, it } from "node:test";
import type { AddressInfo } from "node:net";
import { ChainUnavailable, SolanaRpc } from "./chain.ts";

const SIGNATURE = "4".repeat(88);

type Answer = (method: string) => {
  status?: number;
  body?: unknown;
};

/** An endpoint whose URL carries a secret, so a leaked message would be visible in a test. */
async function stub(
  answer: Answer,
): Promise<{ rpc: SolanaRpc; url: string; close: () => Promise<void> }> {
  const server = createServer((request, response) => {
    let body = "";
    request.on("data", (chunk: Buffer) => (body += chunk.toString()));
    request.on("end", () => {
      const call = JSON.parse(body) as { id: number; method: string };
      const { status = 200, body: result } = answer(call.method);
      response.writeHead(status, { "content-type": "application/json" });
      response.end(
        JSON.stringify({ jsonrpc: "2.0", id: call.id, result: result ?? null }),
      );
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  const url = `http://127.0.0.1:${port}/?api-key=TOPSECRET`;
  return {
    rpc: new SolanaRpc(url, { timeoutMs: 2_000 }),
    url,
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}

function statusResponse(value: unknown): { body: unknown } {
  return { body: { context: { slot: 1 }, value: [value] } };
}

describe("signatureStatus", () => {
  it("reads a confirmed status", async () => {
    const endpoint = await stub(() =>
      statusResponse({
        slot: 321,
        confirmations: 4,
        err: null,
        confirmationStatus: "confirmed",
      }),
    );
    try {
      const status = await endpoint.rpc.signatureStatus(SIGNATURE, false);
      assert.deepEqual(status, {
        slot: 321n,
        commitment: "confirmed",
        chainError: undefined,
      });
    } finally {
      await endpoint.close();
    }
  });

  it("keeps the chain's own error as display text", async () => {
    const endpoint = await stub(() =>
      statusResponse({
        slot: 9,
        confirmations: null,
        err: { InstructionError: [0, { Custom: 6001 }] },
        confirmationStatus: "finalized",
      }),
    );
    try {
      const status = await endpoint.rpc.signatureStatus(SIGNATURE, true);
      assert.equal(status?.commitment, "finalized");
      assert.match(status?.chainError ?? "", /InstructionError/);
    } finally {
      await endpoint.close();
    }
  });

  it("reads an unrecognised commitment as the weakest one, which settles nothing", async () => {
    const endpoint = await stub(() =>
      statusResponse({ slot: 9, confirmations: 0, err: null }),
    );
    try {
      const status = await endpoint.rpc.signatureStatus(SIGNATURE, false);
      assert.equal(status?.commitment, "processed");
    } finally {
      await endpoint.close();
    }
  });

  it("says nothing at all when the endpoint has no record", async () => {
    const endpoint = await stub(() => statusResponse(null));
    try {
      assert.equal(
        await endpoint.rpc.signatureStatus(SIGNATURE, false),
        undefined,
      );
    } finally {
      await endpoint.close();
    }
  });
});

describe("confirmedTransaction", () => {
  const BYTES = Buffer.from([1, 0, 255, 128, 64, 7]);

  it("returns the transaction byte for byte", async () => {
    const endpoint = await stub(() => ({
      body: {
        slot: 4242,
        transaction: [BYTES.toString("base64"), "base64"],
        meta: { err: null },
      },
    }));
    try {
      const found = await endpoint.rpc.confirmedTransaction(SIGNATURE);
      assert.equal(found?.slot, 4242n);
      assert.equal(found?.chainError, undefined);
      assert.ok(Buffer.from(found?.transaction ?? []).equals(BYTES));
    } finally {
      await endpoint.close();
    }
  });

  it("reports a transaction that ran and failed", async () => {
    const endpoint = await stub(() => ({
      body: {
        slot: 1,
        transaction: [BYTES.toString("base64"), "base64"],
        meta: { err: "InsufficientFundsForRent" },
      },
    }));
    try {
      const found = await endpoint.rpc.confirmedTransaction(SIGNATURE);
      assert.equal(found?.chainError, "InsufficientFundsForRent");
    } finally {
      await endpoint.close();
    }
  });

  it("is undefined when the endpoint has not served it yet", async () => {
    const endpoint = await stub(() => ({ body: null }));
    try {
      assert.equal(
        await endpoint.rpc.confirmedTransaction(SIGNATURE),
        undefined,
      );
    } finally {
      await endpoint.close();
    }
  });

  it("fails without repeating the endpoint, which can carry an API key", async () => {
    const endpoint = await stub(() => ({ status: 500, body: null }));
    try {
      await assert.rejects(
        endpoint.rpc.confirmedTransaction(SIGNATURE),
        (error: unknown) => {
          assert.ok(error instanceof ChainUnavailable);
          assert.ok(!error.message.includes("TOPSECRET"));
          assert.match(error.message, /getTransaction/);
          return true;
        },
      );
    } finally {
      await endpoint.close();
    }
  });

  it("fails when the answer is not a transaction at all", async () => {
    const endpoint = await stub(() => ({ body: { slot: 1, meta: null } }));
    try {
      await assert.rejects(
        endpoint.rpc.confirmedTransaction(SIGNATURE),
        ChainUnavailable,
      );
    } finally {
      await endpoint.close();
    }
  });
});
