/**
 * Confirmation end to end (SAW-022): a transfer the owner approved and the wallet sent, and what
 * the sidecar makes of the signature afterwards. The chain is a fake on loopback, so nothing
 * reaches a cluster, nothing is signed by the sidecar, and nothing is ever sent from here.
 *
 * The cases are the ones that go wrong in practice: a confirmation that arrives late, a
 * transaction that failed on chain, an endpoint that stopped answering, a signature the endpoint
 * hasn't seen yet, and a signature that names a transaction nobody approved.
 */
import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";

import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { PublicKey } from "@solana/web3.js";

import { Network, RequestState } from "@seekeragentconnect/server-sdk/protocol";
import { GENESIS_HASHES } from "../solana/network.ts";
import { startSidecar, type Sidecar } from "../server.ts";
import {
  FakeChain,
  startFakeRpc,
  walletAccount,
  type FakeRpc,
} from "../testing/chain.ts";
import {
  Code,
  ConnectError,
  callTool,
  connectAgent,
  pairPhone,
  requestClient,
  viewOf,
  type TestPhone,
} from "../testing/clients.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { testWallet } from "../../../../packages/server-sdk/src/testing/wallet.ts";
import { encodeBase58 } from "../../../../packages/server-sdk/src/requests/action.ts";
import {
  GET_REQUEST_TOOL,
  TRANSFER_TOOL,
  type RequestView,
} from "./mcp-tools.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const RECIPIENT = new PublicKey(Uint8Array.from({ length: 32 }, () => 22));
const logs: string[] = [];
let sidecar: Sidecar;
let agent: Client;
let paired: TestPhone;
let chain: FakeChain;
let endpoint: FakeRpc;
let databasePath: string;
let wallet: string;
let keys = 0;

before(async () => {
  chain = new FakeChain();
  endpoint = await startFakeRpc(chain);
  databasePath = temporaryDatabasePath();
  sidecar = await startSidecar(
    {
      host: "127.0.0.1",
      port: 0,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 1,
      databasePath,
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
      solanaRpcUrl: endpoint.url,
    },
    { log: (line) => logs.push(line) },
  );
  agent = await connectAgent(sidecar.url, MCP_TOKEN);
  paired = await pairPhone(sidecar.url, databasePath);
  wallet = testWallet().address;
  await phone().publishWallet({
    connectionId: paired.connectionId,
    binding: { wallet, network: Network.DEVNET },
  });
  chain.put(RECIPIENT, walletAccount());
});

beforeEach(() => {
  chain.unavailable = undefined;
  chain.height = 900n;
  chain.signatures.clear();
  chain.calls.length = 0;
});

after(async () => {
  await agent.close();
  await sidecar.close();
  await endpoint.close();
});

function phone(token: string = paired.phoneToken) {
  return requestClient(sidecar.url, token);
}

/** One transfer taken all the way to SUBMITTED: approved, and reported as sent. */
interface Sent {
  readonly ref: { connectionId: string; requestId: string };
  /** The signature the wallet reported, base58, as it would appear on chain. */
  readonly signature: string;
  /** Exactly the bytes the owner approved, with the wallet's signature filled in. */
  readonly onChain: Uint8Array;
}

async function send(): Promise<Sent> {
  const view = viewOf(
    await callTool(agent, TRANSFER_TOOL, {
      wallet,
      network: "devnet",
      recipient: RECIPIENT.toBase58(),
      amount: "1000000",
      idempotency_key: `confirm-${++keys}`,
    }),
  );
  const ref = { connectionId: paired.connectionId, requestId: view.request_id };
  const { prepared } = await phone().prepareRequest({ ref });
  assert.ok(prepared !== undefined);
  await phone().submitResult({
    ref,
    result: {
      case: "approval",
      value: {
        preparedVersion: prepared.version,
        contentHash: prepared.contentHash,
      },
    },
  });
  // A wallet signs the approved bytes and reports the first signature as the transaction's ID.
  const signature = Uint8Array.from({ length: 64 }, () => keys);
  const onChain = Uint8Array.from(prepared.transaction);
  onChain.set(signature, 1);
  const { request } = await phone().submitResult({
    ref,
    result: { case: "transactionSubmission", value: { signature } },
  });
  assert.equal(request?.state, RequestState.SUBMITTED);
  return { ref, signature: encodeBase58(signature), onChain };
}

/** What the owner's own check makes of it. */
async function check(sent: Sent) {
  const { request } = await phone().checkStatus({ ref: sent.ref });
  assert.ok(request !== undefined);
  return request;
}

/** What the agent sees, which is also what triggers a check when it polls. */
async function read(sent: Sent): Promise<RequestView> {
  return viewOf(
    await callTool(agent, GET_REQUEST_TOOL, {
      request_id: sent.ref.requestId,
    }),
  );
}

describe("confirming a sent transfer", () => {
  it("confirms only what it found on chain and checked against the approved bytes", async () => {
    const sent = await send();
    chain.land(sent.signature, {
      slot: 4242n,
      commitment: "finalized",
      transaction: sent.onChain,
    });
    const request = await check(sent);
    assert.equal(request.state, RequestState.CONFIRMED);
    const confirmation = request.outcome?.confirmation;
    assert.equal(confirmation?.matchesApproval, true);
    assert.equal(confirmation?.slot, 4242n);
    // Which endpoint's word this rests on is recorded, and it is the host and nothing else.
    assert.equal(confirmation?.endpoint, new URL(endpoint.url).host);
    const view = await read(sent);
    assert.equal(view.status, "CONFIRMED");
    assert.equal(view.terminal, true);
    assert.equal(view.confirmation, "finalized");
    assert.equal(view.checked_with, new URL(endpoint.url).host);
  });

  it("waits through a delayed confirmation rather than calling processed a result", async () => {
    const sent = await send();
    chain.land(sent.signature, {
      slot: 10n,
      commitment: "processed",
      transaction: sent.onChain,
    });
    assert.equal((await check(sent)).state, RequestState.SUBMITTED);
    assert.equal(
      (await read(sent)).confirmation,
      "processed",
      "the level is reported, and it isn't a result",
    );

    chain.land(sent.signature, {
      slot: 11n,
      commitment: "confirmed",
      transaction: sent.onChain,
    });
    assert.equal((await check(sent)).state, RequestState.CONFIRMED);
  });

  it("fails a transaction that ran on chain and failed, keeping the chain's own error", async () => {
    const sent = await send();
    chain.land(sent.signature, {
      slot: 12n,
      commitment: "confirmed",
      chainError: '{"InstructionError":[0,{"Custom":1}]}',
      transaction: sent.onChain,
    });
    const request = await check(sent);
    assert.equal(request.state, RequestState.FAILED);
    assert.match(request.outcome?.detail ?? "", /InstructionError/);
    assert.equal(
      request.outcome?.confirmation?.chainError,
      '{"InstructionError":[0,{"Custom":1}]}',
    );
    assert.equal((await read(sent)).chain_error !== undefined, true);
  });

  it("keeps a signature the endpoint hasn't seen open while it could still land", async () => {
    const sent = await send();
    const request = await check(sent);
    assert.equal(request.state, RequestState.SUBMITTED);
    assert.equal(request.outcome?.confirmation?.level, 1); // NOT_FOUND
    assert.match(request.outcome?.confirmation?.detail ?? "", /can still land/);
  });

  it("fails a transaction that never landed, but only past its blockhash window", async () => {
    const sent = await send();
    // Still inside the window: not found is not a result, and the ledger isn't searched for it.
    assert.equal((await check(sent)).state, RequestState.SUBMITTED);
    assert.equal(
      chain.calls.includes("getSignatureStatuses(history)"),
      false,
      "a signature that could still land is not chased through the ledger",
    );

    chain.height = chain.lastValidBlockHeight + 1n;
    const request = await check(sent);
    assert.equal(request.state, RequestState.FAILED);
    assert.match(request.outcome?.detail ?? "", /never can/);
    assert.equal(
      chain.calls.includes("getSignatureStatuses(history)"),
      true,
      "the ledger itself is searched before anything is called a failure",
    );
  });

  it("takes a signature found only in the ledger as the result it is", async () => {
    const sent = await send();
    chain.height = chain.lastValidBlockHeight + 1n;
    // Long enough gone from the status cache that only a ledger search finds it — and it
    // succeeded. A missing status was never proof that it didn't.
    chain.land(sent.signature, {
      slot: 77n,
      commitment: "finalized",
      transaction: sent.onChain,
      onlyInHistory: true,
    });
    assert.equal((await check(sent)).state, RequestState.CONFIRMED);
  });

  it("settles nothing when the transaction under that signature isn't the approved one", async () => {
    const sent = await send();
    const other = Uint8Array.from(sent.onChain);
    other[other.length - 1] = (other.at(-1) ?? 0) ^ 0xff;
    chain.land(sent.signature, {
      slot: 5n,
      commitment: "finalized",
      transaction: other,
    });
    const request = await check(sent);
    assert.equal(
      request.state,
      RequestState.SUBMITTED,
      "a signature that names something else is not a confirmation of ours",
    );
    assert.equal(request.outcome?.confirmation?.matchesApproval, false);
    assert.match(
      request.outcome?.confirmation?.detail ?? "",
      /isn't the one the owner approved/,
    );
    const view = await read(sent);
    assert.equal(view.status, "SUBMITTED");
    assert.equal(view.terminal, false);
  });

  it("holds a status it can't check against the approved bytes back from a result", async () => {
    const sent = await send();
    // The endpoint has a status but won't serve the transaction yet, which is a real race.
    chain.land(sent.signature, { slot: 6n, commitment: "confirmed" });
    const request = await check(sent);
    assert.equal(request.state, RequestState.SUBMITTED);
    assert.match(
      request.outcome?.confirmation?.detail ?? "",
      /hasn't served the transaction itself yet/,
    );
  });

  it("treats an endpoint that stopped answering as no news, and never as a failure", async () => {
    const sent = await send();
    chain.unavailable = "the endpoint timed out";
    const request = await check(sent);
    assert.equal(
      request.state,
      RequestState.SUBMITTED,
      "a timeout says nothing about the transaction",
    );
    assert.equal(request.outcome?.confirmation?.checks, 1);
    assert.match(
      request.outcome?.confirmation?.detail ?? "",
      /says nothing about the transaction itself/,
    );

    chain.unavailable = undefined;
    chain.land(sent.signature, {
      slot: 8n,
      commitment: "finalized",
      transaction: sent.onChain,
    });
    assert.equal(
      (await check(sent)).state,
      RequestState.CONFIRMED,
      "and the next check still settles it",
    );
  });

  it("never names the configured endpoint's URL, which can carry a key", () => {
    const secret = new URL(endpoint.url);
    for (const line of logs) {
      assert.ok(!line.includes(`${secret.host}/`), line);
    }
  });
});

/**
 * The database outlives the process, and SOLANA_RPC_URL does not. A restart that points the same
 * stored requests at another cluster must not let that cluster's block height read as "the
 * transaction expired, and nothing was spent" — which is a settled, terminal, and false answer
 * about money that may well have moved.
 */
describe("a restart with the RPC on another cluster", () => {
  let wrongChain: FakeChain;
  let wrongEndpoint: FakeRpc;
  let restarted: Sidecar | undefined;

  before(async () => {
    wrongChain = new FakeChain();
    // Mainnet, where this devnet transfer's signature does not exist, and whose block height is
    // long past the window the approved transaction named.
    wrongChain.genesisHashValue = GENESIS_HASHES.get(Network.MAINNET) ?? "";
    wrongChain.height = 10_000_000n;
    wrongEndpoint = await startFakeRpc(wrongChain);
  });

  after(async () => {
    await restarted?.close();
    await wrongEndpoint.close();
  });

  it("settles nothing, and says which cluster it was pointed at", async () => {
    const sent = await send();
    await agent.close();
    await sidecar.close();
    try {
      // The same database, after the old owner has stopped, with another cluster configured.
      restarted = await startSidecar({
        host: "127.0.0.1",
        port: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath,
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
        solanaRpcUrl: wrongEndpoint.url,
      });

      const { request } = await requestClient(
        restarted.url,
        paired.phoneToken,
      ).checkStatus({ ref: sent.ref });

      assert.equal(
        request?.state,
        RequestState.SUBMITTED,
        "another cluster's silence is not this transfer's expiry",
      );
      assert.match(request?.outcome?.confirmation?.detail ?? "", /mainnet/);
      assert.match(
        request?.outcome?.confirmation?.detail ?? "",
        /nothing here has been settled/,
      );
      assert.equal(request?.outcome?.confirmation?.matchesApproval, false);
      // Nothing about the transaction was read at all: not its status, not the transaction under
      // it, and above all not the block height.
      assert.deepEqual(wrongChain.calls, ["getGenesisHash"]);
    } finally {
      await restarted?.close();
      restarted = undefined;
      sidecar = await startSidecar(
        {
          host: "127.0.0.1",
          port: 0,
          mcpToken: MCP_TOKEN,
          phoneToken: PHONE_TOKEN,
          liveCommandTimeoutSeconds: 1,
          databasePath,
          requestTtlSeconds: 86_400,
          pendingLimit: 100,
          solanaRpcUrl: endpoint.url,
        },
        { log: (line) => logs.push(line) },
      );
      agent = await connectAgent(sidecar.url, MCP_TOKEN);
    }
  });

  it("settles the transfer again once the RPC is back on its own cluster", async () => {
    const sent = await send();
    chain.land(sent.signature, {
      slot: 99n,
      commitment: "finalized",
      transaction: sent.onChain,
    });
    assert.equal((await check(sent)).state, RequestState.CONFIRMED);
  });
});

describe("checking status again", () => {
  it("asks for nothing and settles nothing once the request has ended", async () => {
    const sent = await send();
    chain.land(sent.signature, {
      slot: 9n,
      commitment: "finalized",
      transaction: sent.onChain,
    });
    const confirmed = await check(sent);
    assert.equal(confirmed.state, RequestState.CONFIRMED);

    const error = await phone()
      .checkStatus({ ref: sent.ref })
      .then(
        () => undefined,
        (caught: unknown) => caught,
      );
    assert.ok(error instanceof ConnectError);
    assert.equal(error.code, Code.FailedPrecondition);
    // And a finished request is left alone even if the chain changes its mind.
    chain.land(sent.signature, {
      slot: 9n,
      commitment: "finalized",
      chainError: "it failed after all",
      transaction: sent.onChain,
    });
    assert.equal((await read(sent)).status, "CONFIRMED");
  });

  it("reports the same signature, and records no second spending", async () => {
    const sent = await send();
    chain.land(sent.signature, {
      slot: 13n,
      commitment: "confirmed",
      transaction: sent.onChain,
    });
    const before = await read(sent);
    for (let attempt = 0; attempt < 3; attempt += 1) await read(sent);
    const after = await read(sent);
    assert.equal(after.signature, before.signature);
    assert.equal(after.status, "CONFIRMED");
    // Nothing here can send: the chain client has no method that would, and the only bytes in
    // play are the ones already on chain.
    assert.equal(
      chain.calls.some((call) => call.startsWith("send")),
      false,
    );
  });

  it("never returns an unsettled request to PENDING, however often it is checked", async () => {
    const sent = await send();
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const request = await check(sent);
      assert.notEqual(request.state, RequestState.PENDING);
      assert.equal(request.state, RequestState.SUBMITTED);
    }
    // And the owner is never shown it as something still to answer.
    const { requests } = await phone().listPending({
      connectionId: paired.connectionId,
    });
    assert.equal(
      requests.some((request) => request.ref?.requestId === sent.ref.requestId),
      false,
    );
  });

  it("doesn't ask the chain again for every poll of the same request", async () => {
    const sent = await send();
    await read(sent);
    const asked = chain.calls.length;
    await read(sent);
    assert.equal(
      chain.calls.length,
      asked,
      "an agent polling gets the stored answer between checks",
    );
    // The owner asking in person is a different thing: they get a fresh look every time.
    await check(sent);
    assert.ok(chain.calls.length > asked);
  });

  it("keeps the signature and the unresolved attempt across a restart", async () => {
    const sent = await send();
    await check(sent);
    await agent.close();
    await sidecar.close();

    sidecar = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath,
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
        solanaRpcUrl: endpoint.url,
      },
      { log: (line) => logs.push(line) },
    );
    agent = await connectAgent(sidecar.url, MCP_TOKEN);
    const restarted = await read(sent);
    assert.equal(restarted.status, "SUBMITTED");
    assert.equal(restarted.signature, sent.signature);
    assert.equal(restarted.confirmation, "not_found");

    // Nothing ran on its own over the restart: the transaction is settled when somebody asks.
    chain.land(sent.signature, {
      slot: 14n,
      commitment: "finalized",
      transaction: sent.onChain,
    });
    assert.equal((await check(sent)).state, RequestState.CONFIRMED);
  });
});

describe("checking a request with nothing on chain", () => {
  it("explains an unknown outcome instead of inventing one, and asks the chain nothing", async () => {
    const view = viewOf(
      await callTool(agent, TRANSFER_TOOL, {
        wallet,
        network: "devnet",
        recipient: RECIPIENT.toBase58(),
        amount: "1000000",
        idempotency_key: `confirm-unknown-${++keys}`,
      }),
    );
    const ref = {
      connectionId: paired.connectionId,
      requestId: view.request_id,
    };
    const { prepared } = await phone().prepareRequest({ ref });
    assert.ok(prepared !== undefined);
    await phone().submitResult({
      ref,
      result: {
        case: "approval",
        value: {
          preparedVersion: prepared.version,
          contentHash: prepared.contentHash,
        },
      },
    });
    // The phone opened the wallet and never heard back, so no signature was ever reported.
    await phone().submitResult({
      ref,
      result: {
        case: "unknownOutcome",
        value: { detail: "The wallet's answer never reached this phone." },
      },
    });

    const asked = chain.calls.length;
    const { request } = await phone().checkStatus({ ref });
    assert.equal(request?.state, RequestState.UNKNOWN);
    assert.equal(
      chain.calls.length,
      asked,
      "there is no signature to look up, so nothing is asked of the endpoint",
    );
    assert.match(
      request?.outcome?.confirmation?.detail ?? "",
      /never reported a signature/,
    );
    assert.match(
      request?.outcome?.confirmation?.detail ?? "",
      /will send it again/,
      "and it says plainly that nothing replaces it",
    );
    const agentView = viewOf(
      await callTool(agent, GET_REQUEST_TOOL, { request_id: view.request_id }),
    );
    assert.equal(agentView.status, "UNKNOWN");
    assert.equal(agentView.terminal, false);
  });

  it("refuses a request the wallet never sent", async () => {
    const view = viewOf(
      await callTool(agent, TRANSFER_TOOL, {
        wallet,
        network: "devnet",
        recipient: RECIPIENT.toBase58(),
        amount: "1000000",
        idempotency_key: `confirm-pending-${++keys}`,
      }),
    );
    const error = await phone()
      .checkStatus({
        ref: { connectionId: paired.connectionId, requestId: view.request_id },
      })
      .then(
        () => undefined,
        (caught: unknown) => caught,
      );
    assert.ok(error instanceof ConnectError);
    assert.equal(error.code, Code.FailedPrecondition);
    assert.match(error.message, /nothing left to check on chain/);
  });
});
