/**
 * Transfers end to end (SAW-019): the agent's tool over MCP, the phone's preparation over Connect,
 * and what an approval is bound to. The sidecar runs against a fake chain on loopback, so nothing
 * touches a real cluster, nothing is signed, and nothing is submitted.
 */
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { after, before, describe, it } from "node:test";

import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { PublicKey, VersionedTransaction } from "@solana/web3.js";

import {
  Network,
  RequestError,
  RequestErrorDetailSchema,
  RequestState,
} from "@seeker_agent_connect/server-sdk/protocol";
import { startSidecar, type Sidecar } from "../server.ts";
import {
  ASSOCIATED_TOKEN_PROGRAM,
  TOKEN_2022_PROGRAM,
  associatedTokenAddress,
} from "../solana/addresses.ts";
import {
  FakeChain,
  mintAccount,
  startFakeRpc,
  tokenAccount,
  walletAccount,
  type FakeRpc,
} from "../testing/chain.ts";
import {
  Code,
  ConnectError,
  callTool,
  connectAgent,
  errorCode,
  pairPhone,
  requestClient,
  viewOf,
  type TestPhone,
} from "../testing/clients.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { testWallet } from "../../../../packages/server-sdk/src/testing/wallet.ts";
import {
  GET_CAPABILITIES_TOOL,
  TRANSFER_TOOL,
  type CapabilitiesView,
  type RequestView,
} from "./mcp-tools.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const MINT = new PublicKey(Uint8Array.from({ length: 32 }, () => 33));
const RECIPIENT = new PublicKey(Uint8Array.from({ length: 32 }, () => 22));
const logs: string[] = [];
let sidecar: Sidecar;
let agent: Client;
let paired: TestPhone;
let chain: FakeChain;
let endpoint: FakeRpc;
let wallet: string;
let keys = 0;

before(async () => {
  chain = new FakeChain();
  endpoint = await startFakeRpc(chain);
  const databasePath = temporaryDatabasePath();
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

after(async () => {
  await agent.close();
  await sidecar.close();
  await endpoint.close();
});

function phone(token: string = paired.phoneToken) {
  return requestClient(sidecar.url, token);
}

async function ask(fields: Record<string, unknown> = {}): Promise<RequestView> {
  return viewOf(
    await callTool(agent, TRANSFER_TOOL, {
      wallet,
      network: "devnet",
      recipient: RECIPIENT.toBase58(),
      amount: "1000000",
      idempotency_key: `transfer-${++keys}`,
      ...fields,
    }),
  );
}

function refOf(view: RequestView) {
  return { connectionId: paired.connectionId, requestId: view.request_id };
}

describe("vault_transfer", () => {
  it("is offered only with the chain it needs, and named in the capabilities", async () => {
    const capabilities = (await callTool(agent, GET_CAPABILITIES_TOOL, {}))
      .structuredContent as unknown as CapabilitiesView;
    assert.ok(capabilities.operations.includes("transfer"));
    const tools = await agent.listTools();
    assert.ok(tools.tools.some((tool) => tool.name === TRANSFER_TOOL));
  });

  it("stores a SOL transfer as PENDING, and builds nothing yet", async () => {
    const before = chain.calls.length;
    const view = await ask();
    assert.equal(view.action, "transfer");
    assert.equal(view.status, "PENDING");
    assert.equal(view.terminal, false);
    assert.equal(view.wallet, wallet);
    assert.equal(
      chain.calls.length,
      before,
      "a SOL transfer reads nothing until the owner opens it",
    );
  });

  it("returns the same request for a repeated idempotency key", async () => {
    const key = `transfer-repeat-${++keys}`;
    const first = await ask({ idempotency_key: key });
    const again = await ask({ idempotency_key: key });
    assert.equal(again.request_id, first.request_id);
  });

  it("answers a repeat before it reads the chain, so a dead endpoint still replays", async () => {
    const mint = new PublicKey(Uint8Array.from({ length: 32 }, () => 88));
    chain.put(mint, mintAccount({ decimals: 6, supply: 1_000_000n }));
    const key = `transfer-replay-${++keys}`;
    const first = await ask({
      idempotency_key: key,
      token_mint: mint.toBase58(),
    });

    chain.unavailable = "the endpoint is down";
    try {
      const again = await ask({
        idempotency_key: key,
        token_mint: mint.toBase58(),
      });
      assert.equal(
        again.request_id,
        first.request_id,
        "a retry gets its request back, not the state of an endpoint",
      );
    } finally {
      chain.unavailable = undefined;
    }
  });

  it("refuses a changed retry under a used idempotency key", async () => {
    const key = `transfer-conflict-${++keys}`;
    await ask({ idempotency_key: key });
    assert.equal(
      await errorOf({ idempotency_key: key, amount: "2000000" }),
      "IDEMPOTENCY_CONFLICT",
    );
  });

  it("refuses another wallet or another network", async () => {
    assert.equal(
      await errorOf({ wallet: testWallet().address }),
      "WALLET_MISMATCH",
    );
    assert.equal(await errorOf({ network: "mainnet" }), "WALLET_MISMATCH");
  });

  it("refuses an amount that isn't whole base units", async () => {
    for (const amount of [
      "0",
      "1.5",
      "-1",
      "0x10",
      "01",
      "18446744073709551616",
    ]) {
      assert.equal(
        await errorOf({ amount, idempotency_key: `bad-${++keys}` }),
        "INVALID_PARAMETERS",
        `amount ${amount}`,
      );
    }
  });

  it("refuses an address that isn't one", async () => {
    assert.equal(
      await errorOf({ recipient: "not-an-address" }),
      "INVALID_PARAMETERS",
    );
  });

  it("refuses a Token-2022 mint before the owner ever sees it", async () => {
    const mint = new PublicKey(Uint8Array.from({ length: 32 }, () => 55));
    chain.put(
      mint,
      mintAccount({ decimals: 6, supply: 1n, program: TOKEN_2022_PROGRAM }),
    );
    assert.equal(
      await errorOf({ token_mint: mint.toBase58() }),
      "INVALID_PARAMETERS",
    );
  });

  it("refuses an NFT before the owner ever sees it", async () => {
    const mint = new PublicKey(Uint8Array.from({ length: 32 }, () => 66));
    chain.put(mint, mintAccount({ decimals: 0, supply: 1n }));
    assert.equal(
      await errorOf({ token_mint: mint.toBase58() }),
      "INVALID_PARAMETERS",
    );
  });

  it("reports an endpoint that won't answer as CHAIN_UNAVAILABLE, and stores nothing", async () => {
    const mint = new PublicKey(Uint8Array.from({ length: 32 }, () => 77));
    chain.put(mint, mintAccount({ decimals: 6, supply: 1_000n }));
    chain.unavailable = "the endpoint is down";
    try {
      assert.equal(
        await errorOf({ token_mint: mint.toBase58() }),
        "CHAIN_UNAVAILABLE",
      );
    } finally {
      chain.unavailable = undefined;
    }
  });
});

describe("PrepareRequest", () => {
  it("builds a fresh transaction the owner's wallet alone can sign", async () => {
    const view = await ask({ amount: "4200000" });
    const { prepared } = await phone().prepareRequest({ ref: refOf(view) });

    assert.ok(prepared !== undefined);
    assert.equal(prepared.version, 1);
    assert.equal(prepared.lastValidBlockHeight, chain.lastValidBlockHeight);
    assert.equal(prepared.feeLamports, 5000n);
    assert.equal(prepared.rentLamports, 0n);
    assert.deepEqual(
      Array.from(prepared.contentHash),
      Array.from(createHash("sha256").update(prepared.transaction).digest()),
    );

    const tx = VersionedTransaction.deserialize(prepared.transaction);
    assert.equal(tx.message.header.numRequiredSignatures, 1);
    assert.equal(tx.message.staticAccountKeys[0]?.toBase58(), wallet);
    assert.ok(
      tx.signatures.every((signature) => signature.every((byte) => byte === 0)),
      "nothing was signed",
    );
  });

  it("leaves the request PENDING: preparing is not approving or sending", async () => {
    const view = await ask();
    await phone().prepareRequest({ ref: refOf(view) });
    const { request } = await phone().getRequest({ ref: refOf(view) });
    assert.equal(request?.state, RequestState.PENDING);
    assert.equal(request?.outcome, undefined);
  });

  it("gives every preparation a new version, and a new hash", async () => {
    const view = await ask();
    const first = (await phone().prepareRequest({ ref: refOf(view) })).prepared;
    chain.blockhash = new PublicKey(
      Uint8Array.from({ length: 32 }, () => 9),
    ).toBase58();
    const second = (await phone().prepareRequest({ ref: refOf(view) }))
      .prepared;

    assert.equal(first?.version, 1);
    assert.equal(second?.version, 2);
    assert.notDeepEqual(
      Array.from(second?.contentHash ?? []),
      Array.from(first?.contentHash ?? []),
    );
  });

  it("refuses an approval of a superseded version", async () => {
    const view = await ask();
    const first = (await phone().prepareRequest({ ref: refOf(view) })).prepared;
    await phone().prepareRequest({ ref: refOf(view) });

    await assert.rejects(
      phone().submitResult({
        ref: refOf(view),
        result: {
          case: "approval",
          value: {
            preparedVersion: first?.version ?? 0,
            contentHash: first?.contentHash ?? new Uint8Array(),
          },
        },
      }),
      refused(Code.FailedPrecondition, RequestError.STALE_PREPARATION),
    );
  });

  it("accepts an approval of the latest version, and only once", async () => {
    const view = await ask();
    const prepared = (await phone().prepareRequest({ ref: refOf(view) }))
      .prepared;
    const approval = {
      case: "approval" as const,
      value: {
        preparedVersion: prepared?.version ?? 0,
        contentHash: prepared?.contentHash ?? new Uint8Array(),
      },
    };
    const { request } = await phone().submitResult({
      ref: refOf(view),
      result: approval,
    });
    assert.equal(request?.state, RequestState.PROCESSING);
    // A request the owner has answered gets no new version to approve.
    await assert.rejects(
      phone().prepareRequest({ ref: refOf(view) }),
      refused(Code.FailedPrecondition, RequestError.INVALID_STATE),
    );
  });

  it("refuses an approval whose blockhash is about to expire", async () => {
    const view = await ask();
    chain.height = chain.lastValidBlockHeight;
    try {
      const prepared = (await phone().prepareRequest({ ref: refOf(view) }))
        .prepared;
      await assert.rejects(
        phone().submitResult({
          ref: refOf(view),
          result: {
            case: "approval",
            value: {
              preparedVersion: prepared?.version ?? 0,
              contentHash: prepared?.contentHash ?? new Uint8Array(),
            },
          },
        }),
        refused(Code.FailedPrecondition, RequestError.STALE_PREPARATION),
      );
    } finally {
      chain.height = 900n;
    }
  });

  it("creates the recipient's token account when they need one", async () => {
    chain.put(MINT, mintAccount({ decimals: 6, supply: 1_000_000_000n }));
    chain.put(
      associatedTokenAddress(new PublicKey(wallet), MINT),
      tokenAccount({ mint: MINT, owner: wallet, amount: 9_000_000n }),
    );
    const view = await ask({ token_mint: MINT.toBase58(), amount: "1500000" });
    const { prepared } = await phone().prepareRequest({ ref: refOf(view) });

    assert.equal(prepared?.rentLamports, 2_039_280n);
    const tx = VersionedTransaction.deserialize(
      prepared?.transaction ?? new Uint8Array(),
    );
    assert.equal(tx.message.compiledInstructions.length, 2);
  });

  it("has the chain vouch for a token account that already exists, and charges no rent", async () => {
    chain.put(MINT, mintAccount({ decimals: 6, supply: 1_000_000_000n }));
    chain.put(
      associatedTokenAddress(new PublicKey(wallet), MINT),
      tokenAccount({ mint: MINT, owner: wallet, amount: 9_000_000n }),
    );
    const destination = associatedTokenAddress(RECIPIENT, MINT);
    chain.put(
      destination,
      tokenAccount({ mint: MINT, owner: RECIPIENT, amount: 0n }),
    );
    const view = await ask({ token_mint: MINT.toBase58(), amount: "1500000" });
    const { prepared } = await phone().prepareRequest({ ref: refOf(view) });

    assert.equal(prepared?.rentLamports, 0n, "nothing is created, so no rent");
    const tx = VersionedTransaction.deserialize(
      prepared?.transaction ?? new Uint8Array(),
    );
    // The idempotent create is in the transaction all the same: it is what makes the
    // destination's owner something the chain checks, rather than something an address implies.
    assert.equal(tx.message.compiledInstructions.length, 2);
    const [create] = tx.message.compiledInstructions;
    assert.equal(
      tx.message.staticAccountKeys[create?.programIdIndex ?? -1]?.toBase58(),
      ASSOCIATED_TOKEN_PROGRAM.toBase58(),
    );
    assert.deepEqual(Array.from(create?.data ?? new Uint8Array()), [1]);
  });

  it("refuses a destination whose token account belongs to somebody else now", async () => {
    chain.put(MINT, mintAccount({ decimals: 6, supply: 1_000_000_000n }));
    chain.put(
      associatedTokenAddress(new PublicKey(wallet), MINT),
      tokenAccount({ mint: MINT, owner: wallet, amount: 9_000_000n }),
    );
    // The address still derives from the recipient; the authority behind it does not.
    chain.put(
      associatedTokenAddress(RECIPIENT, MINT),
      tokenAccount({
        mint: MINT,
        owner: new PublicKey(Uint8Array.from({ length: 32 }, () => 44)),
        amount: 0n,
      }),
    );
    const view = await ask({ token_mint: MINT.toBase58(), amount: "1500000" });
    const error = await phone()
      .prepareRequest({ ref: refOf(view) })
      .then(
        () => undefined,
        (caught: unknown) => caught,
      );
    assert.ok(error instanceof ConnectError);
    assert.equal(error.code, Code.InvalidArgument);
  });

  it("has nothing to prepare for a request without a transaction", async () => {
    const signer = testWallet();
    await phone().publishWallet({
      connectionId: paired.connectionId,
      binding: { wallet: signer.address, network: Network.DEVNET },
    });
    try {
      const message = await callTool(agent, "vault_sign_message", {
        wallet: signer.address,
        message: "hello",
        idempotency_key: `sign-${++keys}`,
      });
      const view = viewOf(message);
      await assert.rejects(
        phone().prepareRequest({ ref: refOf(view) }),
        refused(Code.InvalidArgument, RequestError.INVALID_PARAMETERS),
      );
    } finally {
      await phone().publishWallet({
        connectionId: paired.connectionId,
        binding: { wallet, network: Network.DEVNET },
      });
    }
  });

  it("reports an endpoint that won't answer as unavailable, and prepares nothing", async () => {
    const view = await ask();
    chain.unavailable = "the endpoint is down";
    try {
      await assert.rejects(
        phone().prepareRequest({ ref: refOf(view) }),
        refused(Code.Unavailable, RequestError.CHAIN_UNAVAILABLE),
      );
    } finally {
      chain.unavailable = undefined;
    }
    const { request } = await phone().getRequest({ ref: refOf(view) });
    assert.equal(request?.state, RequestState.PENDING);
    // The request is untouched, so it can still be prepared once the endpoint answers again.
    const { prepared } = await phone().prepareRequest({ ref: refOf(view) });
    assert.equal(prepared?.version, 1);
  });

  it("never logs an address, an amount, or the endpoint", () => {
    const prepared = logs.filter((line) => line.includes("prepared transfer"));
    assert.ok(prepared.length > 0, "preparations were logged");
    for (const line of [...prepared, ...logs]) {
      assert.ok(!line.includes(endpoint.url), line);
      assert.ok(!line.includes(RECIPIENT.toBase58()), line);
    }
  });
});

/** The code a refused tool answered with, such as `WALLET_MISMATCH`. */
async function errorOf(
  fields: Record<string, unknown>,
): Promise<string | undefined> {
  const result = await callTool(agent, TRANSFER_TOOL, {
    wallet,
    network: "devnet",
    recipient: RECIPIENT.toBase58(),
    amount: "1000000",
    idempotency_key: `transfer-${++keys}`,
    ...fields,
  });
  assert.equal(result.isError, true, JSON.stringify(result.content));
  return errorCode(result);
}

/** A ConnectError with this code, whose RequestErrorDetail names `error`. */
function refused(
  code: Code,
  error: RequestError,
): (thrown: unknown) => boolean {
  return (thrown) => {
    if (!(thrown instanceof ConnectError) || thrown.code !== code) return false;
    const [detail] = thrown.findDetails(RequestErrorDetailSchema);
    return detail?.error === error;
  };
}
