/** Sync's automatic work is read-only confirmation: it uses the existing byte-verifying tracker,
 * publishes the resulting durable mutation, and never asks a wallet or sends a transaction. */
import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";

import { createClient, type Interceptor } from "@connectrpc/connect";
import { createGrpcTransport } from "@connectrpc/connect-node";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { PublicKey } from "@solana/web3.js";

import { Network, RequestState } from "@seeker-vault/server-sdk/protocol";
import {
  SubscribeRequestSchema,
  SubscribeSchema,
  UpdateService,
  type SubscribeRequest,
  type SubscribeResponse,
} from "@seeker-vault/server-sdk/protocol";
import { create } from "@bufbuild/protobuf";
import { TRANSFER_TOOL } from "../requests/mcp-tools.ts";
import { encodeBase58 } from "../../../../packages/server-sdk/src/requests/action.ts";
import { startSidecar, type Sidecar } from "../server.ts";
import {
  FakeChain,
  startFakeRpc,
  walletAccount,
  type FakeRpc,
} from "../testing/chain.ts";
import {
  callTool,
  connectAgent,
  pairPhone,
  requestClient,
  viewOf,
  type TestPhone,
} from "../testing/clients.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { testWallet } from "../../../../packages/server-sdk/src/testing/wallet.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const RECIPIENT = new PublicKey(Uint8Array.from({ length: 32 }, () => 31));
let chain: FakeChain;
let endpoint: FakeRpc;
let sidecar: Sidecar;
let agent: Client;
let paired: TestPhone;
let wallet: string;

before(async () => {
  chain = new FakeChain();
  endpoint = await startFakeRpc(chain);
  const databasePath = temporaryDatabasePath();
  sidecar = await startSidecar(
    {
      host: "127.0.0.1",
      port: 0,
      updatePort: 0,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 1,
      databasePath,
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
      demoTools: true,
      solanaRpcUrl: endpoint.url,
    },
    { log: () => undefined, updatePollMs: 10 },
  );
  agent = await connectAgent(sidecar.url, MCP_TOKEN);
  paired = await pairPhone(sidecar.url, databasePath);
  wallet = testWallet().address;
  await requestClient(sidecar.url, paired.phoneToken).publishWallet({
    connectionId: paired.connectionId,
    binding: { wallet, network: Network.DEVNET },
  });
  chain.put(RECIPIENT, walletAccount());
});

after(async () => {
  await agent.close();
  await sidecar.close();
  await endpoint.close();
});

describe("bounded confirmation during Sync", () => {
  it("observes chain advancement without CheckStatus and streams the byte-verified result", async () => {
    const sent = await submittedTransfer("sync-confirmation", 19);
    const input = new StreamInput<SubscribeRequest>();
    const abort = new AbortController();
    const client = updates();
    const stream: AsyncIterator<SubscribeResponse> = iteratorOf(
      client.subscribe(input, { signal: abort.signal }),
    );
    input.push(
      create(SubscribeRequestSchema, {
        connectionId: paired.connectionId,
        message: {
          case: "subscribe",
          value: create(SubscribeSchema, { protocolVersion: 1 }),
        },
      }),
    );
    const ready = await nextUpdate(stream);
    assert.equal((await nextUpdate(stream)).event.case, "syncRequired");

    chain.land(sent.signatureText, {
      slot: 5150n,
      commitment: "confirmed",
      transaction: sent.onChain,
    });
    const snapshot = await client.sync({
      connectionId: paired.connectionId,
      protocolVersion: 1,
      subscriptionCursor: ready.cursor,
      knownNonterminal: [
        { ref: sent.ref, revision: 3n, state: RequestState.SUBMITTED },
      ],
    });
    assert.equal(snapshot.requests[0]?.request?.state, RequestState.CONFIRMED);
    assert.equal(snapshot.requests[0]?.revision, 4n);
    assert.equal(
      snapshot.requests[0]?.request?.outcome?.confirmation?.matchesApproval,
      true,
    );
    assert.deepEqual(snapshot.confirmationDeferred, []);
    const event = await nextUpdate(stream);
    assert.equal(event.event.case, "requestChanged");
    if (event.event.case === "requestChanged") {
      assert.equal(event.event.value.request?.state, RequestState.CONFIRMED);
      assert.equal(event.event.value.revision, 4n);
    }
    assert.ok(chain.calls.includes("getTransaction"));
    assert.equal(
      chain.calls.some((call) => /send|simulate/i.test(call)),
      false,
    );
    abort.abort();
  });

  it("checks no more than four supplied transfers and rotates the deferred record", async () => {
    chain.calls.length = 0;
    const sent = await Promise.all(
      Array.from({ length: 5 }, (_, index) =>
        submittedTransfer(`sync-bound-${index}`, 30 + index),
      ),
    );
    const client = updates();
    const known = sent.map((item) => ({
      ref: item.ref,
      revision: 3n,
      state: RequestState.SUBMITTED,
    }));
    const first = await client.sync({
      connectionId: paired.connectionId,
      protocolVersion: 1,
      knownNonterminal: known,
    });
    assert.equal(first.confirmationDeferred.length, 1);
    assert.equal(
      chain.calls.filter((call) => call === "getSignatureStatuses(cache)")
        .length,
      4,
    );
    const firstDeferred = first.confirmationDeferred[0]?.requestId;
    chain.calls.length = 0;
    const second = await client.sync({
      connectionId: paired.connectionId,
      protocolVersion: 1,
      knownNonterminal: known,
    });
    assert.equal(second.confirmationDeferred.length, 1);
    assert.notEqual(second.confirmationDeferred[0]?.requestId, firstDeferred);
    assert.ok(
      chain.calls.some((call) => call === "getSignatureStatuses(cache)"),
      "the previously deferred request is checked on the next rotation",
    );
  });
});

async function submittedTransfer(
  idempotencyKey: string,
  signatureByte: number,
): Promise<{
  readonly ref: { readonly connectionId: string; readonly requestId: string };
  readonly signatureText: string;
  readonly onChain: Uint8Array;
}> {
  const view = viewOf(
    await callTool(agent, TRANSFER_TOOL, {
      wallet,
      network: "devnet",
      recipient: RECIPIENT.toBase58(),
      amount: "1000000",
      idempotency_key: idempotencyKey,
    }),
  );
  const ref = { connectionId: paired.connectionId, requestId: view.request_id };
  const phone = requestClient(sidecar.url, paired.phoneToken);
  const { prepared } = await phone.prepareRequest({ ref });
  assert.ok(prepared !== undefined);
  await phone.submitResult({
    ref,
    result: {
      case: "approval",
      value: {
        preparedVersion: prepared.version,
        contentHash: prepared.contentHash,
      },
    },
  });
  const signature = Uint8Array.from({ length: 64 }, () => signatureByte);
  const onChain = Uint8Array.from(prepared.transaction);
  onChain.set(signature, 1);
  const submitted = await phone.submitResult({
    ref,
    result: { case: "transactionSubmission", value: { signature } },
  });
  assert.equal(submitted.request?.state, RequestState.SUBMITTED);
  return { ref, signatureText: encodeBase58(signature), onChain };
}

function updates() {
  return createClient(
    UpdateService,
    createGrpcTransport({
      baseUrl: sidecar.updateUrl ?? "",
      interceptors: [authorization(paired.phoneToken)],
      readMaxBytes: 65_536,
      writeMaxBytes: 65_536,
    }),
  );
}

function authorization(token: string): Interceptor {
  return (next) => (request) => {
    request.header.set("Authorization", `Bearer ${token}`);
    return next(request);
  };
}

class StreamInput<T> implements AsyncIterable<T> {
  readonly #queued: T[] = [];
  readonly #waiting: ((value: IteratorResult<T>) => void)[] = [];

  push(value: T): void {
    const waiter = this.#waiting.shift();
    if (waiter === undefined) this.#queued.push(value);
    else waiter({ done: false, value });
  }

  [Symbol.asyncIterator](): AsyncIterator<T> {
    return {
      next: () => {
        const value = this.#queued.shift();
        return value === undefined
          ? new Promise((resolve) => this.#waiting.push(resolve))
          : Promise.resolve({ done: false, value });
      },
      return: () => {
        for (const waiter of this.#waiting.splice(0))
          waiter({ done: true, value: undefined });
        return Promise.resolve({ done: true, value: undefined });
      },
      throw: (error?: unknown) =>
        Promise.reject(
          error instanceof Error ? error : new Error(String(error)),
        ),
    };
  }
}

function iteratorOf<T>(stream: AsyncIterable<T>): AsyncIterator<T> {
  return stream[Symbol.asyncIterator]();
}

async function nextUpdate(
  stream: AsyncIterator<SubscribeResponse>,
): Promise<SubscribeResponse> {
  const result = await stream.next();
  if (result.done) throw new Error("the update stream closed unexpectedly");
  return result.value;
}
