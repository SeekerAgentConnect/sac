/**
 * The Stage 4 acceptance scenario (SAW-023): a transfer from the agent's first call to the result
 * it reads back, with every part real except the two that would cost money.
 *
 * Real: the sidecar as its own process with its own database, the CLI (`pnpm agent`) as a separate
 * process, the Connect phone client as the phone, and a throwaway key pair as the wallet, which
 * produces the same Ed25519 signature a wallet app would.
 *
 * Fixtures: the chain. `startFakeRpc` serves the accounts, blockhash, fees, and signatures a test
 * sets up, as ordinary Solana JSON-RPC on loopback. **Nothing here reaches a cluster and nothing
 * here spends anything**, on mainnet or anywhere else. The one check that talks to a real network
 * is opt-in, devnet-only, and reads: see the end of this file.
 *
 * Run it with `pnpm test:transfer`. It is not the owner's check on a physical Seeker
 * (docs/testing/stage-4.md).
 */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { after, before, describe, it } from "node:test";
import { fileURLToPath } from "node:url";

import {
  Network,
  RequestState,
  type PreparedTransaction,
} from "@seeker-vault/server-sdk/protocol";
import { encodeBase58 } from "@seeker-vault/server-sdk";
import { transactionMessage } from "../../../servers/mcp-server/src/solana/confirmation.ts";
import {
  FakeChain,
  holdToken,
  mintAccount,
  startFakeRpc,
  walletAccount,
  type FakeRpc,
} from "../../../servers/mcp-server/src/testing/chain.ts";
import {
  pairPhone,
  requestClient,
  type TestPhone,
} from "../../../servers/mcp-server/src/testing/clients.ts";
import {
  freePort,
  startSidecarProcess,
  temporaryDatabasePath,
  type SidecarProcess,
} from "../../../servers/mcp-server/src/testing/process.ts";
import {
  testWallet,
  type TestWallet,
} from "../../../packages/server-sdk/src/testing/wallet.ts";

const MAIN = fileURLToPath(new URL("./main.ts", import.meta.url));
const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);

interface Run {
  readonly code: number | null;
  readonly stdout: string;
  readonly stderr: string;
}

/** Runs the CLI as its own process, with only the settings given here. */
function agent(args: readonly string[]): Promise<Run> {
  const child = spawn(process.execPath, [MAIN, ...args], {
    env: {
      PATH: process.env.PATH ?? "",
      MCP_URL: `${sidecar.url}/mcp`,
      MCP_TOKEN,
      LIVE_COMMAND_TIMEOUT_SECONDS: "30",
    },
  });
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8").on("data", (c: string) => (stdout += c));
  child.stderr.setEncoding("utf8").on("data", (c: string) => (stderr += c));
  return new Promise((resolve) => {
    child.on("close", (code) => resolve({ code, stdout, stderr }));
  });
}

let chain: FakeChain;
let endpoint: FakeRpc;
let sidecar: SidecarProcess;
let port: number;
let databasePath: string;
let phone: TestPhone;
let owner: TestWallet;
let recipient: string;
let mint: string;

/** The phone's own client, as the app's gateway uses it. */
function phoneClient() {
  return requestClient(sidecar.url, phone.phoneToken);
}

async function startSidecar(): Promise<void> {
  sidecar = await startSidecarProcess({
    port,
    mcpToken: MCP_TOKEN,
    phoneToken: PHONE_TOKEN,
    liveCommandTimeoutSeconds: 30,
    databasePath,
    solanaRpcUrl: endpoint.url,
  });
}

/** Queues a transfer through the real CLI, and returns the request it stored. */
async function queue(options: {
  readonly amount: string;
  readonly key: string;
  readonly mint?: string;
  readonly network?: string;
  readonly to?: string;
  /** The state the stored request is expected to be in; PENDING unless a key is being reused. */
  readonly expect?: string;
}): Promise<Record<string, unknown>> {
  const run = await agent([
    "transfer",
    options.to ?? recipient,
    options.amount,
    "--wallet",
    owner.address,
    "--network",
    options.network ?? "devnet",
    "--key",
    options.key,
    ...(options.mint === undefined ? [] : ["--mint", options.mint]),
  ]);
  assert.equal(run.code, 0, run.stderr);
  const view = JSON.parse(run.stdout) as Record<string, unknown>;
  assert.equal(view.status, options.expect ?? "PENDING");
  return view;
}

/** The sidecar builds the transaction the owner reviews. The phone asks for it; it signs nothing. */
async function prepare(requestId: string): Promise<PreparedTransaction> {
  const { prepared } = await phoneClient().prepareRequest({
    ref: { connectionId: phone.connectionId, requestId },
  });
  assert.ok(prepared !== undefined, "the sidecar prepared a transaction");
  return prepared;
}

/** The owner approves exactly the transaction they were shown, which commits before any wallet. */
async function approve(
  requestId: string,
  prepared: PreparedTransaction,
): Promise<void> {
  await phoneClient().submitResult({
    ref: { connectionId: phone.connectionId, requestId },
    result: {
      case: "approval",
      value: {
        preparedVersion: prepared.version,
        contentHash: prepared.contentHash,
      },
    },
  });
}

/**
 * The wallet's part, which is the one part no test can do for real: it signs the approved
 * transaction and reports the signature. The signature is a real Ed25519 signature over the
 * message, made with the key the published wallet address belongs to.
 */
function walletSigns(prepared: PreparedTransaction): {
  readonly signature: Uint8Array;
  readonly wire: Uint8Array;
} {
  const message = transactionMessage(prepared.transaction);
  assert.ok(message !== undefined, "the prepared transaction has a message");
  const signature = owner.sign(message);
  const wire = Uint8Array.from(prepared.transaction);
  assert.equal(wire[0], 1, "the wallet is the only signer");
  wire.set(signature, 1);
  return { signature, wire };
}

/** Reports what the wallet sent, which is where SAW-021 ends: SUBMITTED, not confirmed. */
async function submit(
  requestId: string,
  signature: Uint8Array,
): Promise<RequestState> {
  const { request } = await phoneClient().submitResult({
    ref: { connectionId: phone.connectionId, requestId },
    result: { case: "transactionSubmission", value: { signature } },
  });
  return request?.state ?? RequestState.UNSPECIFIED;
}

/** Reads the outcome the way a script does: the CLI's own `status`, with its exit code. */
async function status(
  requestId: string,
): Promise<{ readonly code: number | null; readonly view: StatusView }> {
  const run = await agent(["status", requestId]);
  return { code: run.code, view: JSON.parse(run.stdout) as StatusView };
}

interface StatusView {
  readonly status: string;
  readonly terminal: boolean;
  readonly network?: string;
  readonly signature?: string;
  readonly signature_is_transaction?: boolean;
  readonly confirmation?: string;
  readonly slot?: number;
  readonly chain_error?: string;
  readonly checked_with?: string;
  readonly detail?: string;
  readonly explorer_url?: string;
}

before(async () => {
  chain = new FakeChain();
  endpoint = await startFakeRpc(chain);
  port = await freePort();
  databasePath = temporaryDatabasePath();
  await startSidecar();
  phone = await pairPhone(sidecar.url, databasePath);
  owner = testWallet();
  recipient = testWallet().address;
  mint = testWallet().address;
  await phoneClient().publishWallet({
    connectionId: phone.connectionId,
    binding: { wallet: owner.address, network: Network.DEVNET },
  });
  // The chain this run reads: the owner's wallet, a token they hold, and nothing else.
  chain.put(owner.address, walletAccount());
  chain.put(recipient, walletAccount());
  chain.put(mint, mintAccount({ decimals: 6, supply: 1_000_000_000n }));
  holdToken(chain, { mint, owner: owner.address, amount: 5_000_000n });
});

after(async () => {
  await sidecar.stop();
  await endpoint.close();
});

describe("Stage 4: a SOL transfer, end to end", () => {
  it("goes PENDING, PROCESSING, SUBMITTED, CONFIRMED, and never skips a step", async () => {
    const view = await queue({ amount: "2500000", key: "stage4-sol" });
    const requestId = String(view.request_id);
    assert.equal(view.network, "devnet");

    // Nothing is settled and nothing is on chain: the owner hasn't even seen it.
    const queued = await status(requestId);
    assert.equal(queued.code, 10);
    assert.equal(queued.view.signature, undefined);
    assert.equal(queued.view.explorer_url, undefined);

    const prepared = await prepare(requestId);
    assert.equal(prepared.version, 1);
    assert.equal(prepared.rentLamports, 0n, "SOL needs no account created");
    await approve(requestId, prepared);

    const { signature, wire } = walletSigns(prepared);
    assert.equal(await submit(requestId, signature), RequestState.SUBMITTED);

    // The transaction lands. Only now can anything say it went through.
    chain.land(encodeBase58(signature), {
      slot: 310_000_001n,
      commitment: "finalized",
      transaction: wire,
    });
    const settled = await status(requestId);
    assert.equal(settled.code, 0, JSON.stringify(settled.view));
    assert.equal(settled.view.status, "CONFIRMED");
    assert.equal(settled.view.terminal, true);
    assert.equal(settled.view.confirmation, "finalized");
    assert.equal(settled.view.slot, 310_000_001);
    assert.equal(settled.view.signature, encodeBase58(signature));
    assert.equal(settled.view.signature_is_transaction, true);
    assert.equal(settled.view.checked_with, new URL(endpoint.url).host);
    // The link names the cluster the request was bound to, and no other.
    assert.equal(
      settled.view.explorer_url,
      `https://explorer.solana.com/tx/${encodeBase58(signature)}?cluster=devnet`,
    );
  });
});

describe("Stage 4: an SPL transfer to someone with no token account", () => {
  it("creates the account, charges rent for it, and confirms", async () => {
    const view = await queue({
      amount: "1500000",
      key: "stage4-spl",
      mint,
    });
    const requestId = String(view.request_id);
    const prepared = await prepare(requestId);
    // The recipient holds no account for this mint, so the transaction makes one and the owner
    // is shown what it costs on top of the fee.
    assert.ok(
      prepared.rentLamports > 0n,
      "the preparation charges rent for the recipient's new token account",
    );
    await approve(requestId, prepared);
    const { signature, wire } = walletSigns(prepared);
    assert.equal(await submit(requestId, signature), RequestState.SUBMITTED);
    chain.land(encodeBase58(signature), {
      slot: 310_000_002n,
      commitment: "confirmed",
      transaction: wire,
    });
    const settled = await status(requestId);
    assert.equal(settled.code, 0, JSON.stringify(settled.view));
    assert.equal(settled.view.status, "CONFIRMED");
    assert.equal(settled.view.confirmation, "confirmed");
  });
});

describe("Stage 4: a transaction the chain rejected", () => {
  it("ends FAILED with the chain's own error, and nothing is sent again", async () => {
    const view = await queue({ amount: "900000", key: "stage4-chain-failure" });
    const requestId = String(view.request_id);
    const prepared = await prepare(requestId);
    await approve(requestId, prepared);
    const { signature, wire } = walletSigns(prepared);
    await submit(requestId, signature);
    chain.land(encodeBase58(signature), {
      slot: 310_000_003n,
      commitment: "finalized",
      transaction: wire,
      chainError: "InsufficientFundsForRent",
    });
    const settled = await status(requestId);
    assert.equal(settled.code, 11, JSON.stringify(settled.view));
    assert.equal(settled.view.status, "FAILED");
    assert.equal(settled.view.terminal, true);
    assert.equal(settled.view.chain_error, "InsufficientFundsForRent");
    // It failed on chain, so there is still a transaction to look at.
    assert.match(String(settled.view.explorer_url), /cluster=devnet$/);
    // A failure is not an invitation to try again: the sidecar builds nothing new, and the
    // request is terminal.
    const again = await status(requestId);
    assert.equal(again.view.status, "FAILED");
    assert.equal(again.view.signature, settled.view.signature);
  });
});

describe("Stage 4: the owner says no", () => {
  it("ends REJECTED with nothing signed and nothing on chain", async () => {
    const view = await queue({ amount: "100000", key: "stage4-rejected" });
    const requestId = String(view.request_id);
    await prepare(requestId);
    await phoneClient().submitResult({
      ref: { connectionId: phone.connectionId, requestId },
      result: { case: "rejection", value: {} },
    });
    const settled = await status(requestId);
    assert.equal(settled.code, 11);
    assert.equal(settled.view.status, "REJECTED");
    assert.equal(settled.view.signature, undefined);
    assert.equal(settled.view.explorer_url, undefined);
    assert.equal(settled.view.confirmation, undefined);
  });
});

describe("Stage 4: the same result twice", () => {
  it("keeps one record, one signature, and one payment", async () => {
    const view = await queue({ amount: "700000", key: "stage4-duplicate" });
    const requestId = String(view.request_id);
    const prepared = await prepare(requestId);
    await approve(requestId, prepared);
    // The approval again, the way a phone retries an answer whose response was lost.
    await approve(requestId, prepared);
    const { signature, wire } = walletSigns(prepared);
    assert.equal(await submit(requestId, signature), RequestState.SUBMITTED);
    assert.equal(await submit(requestId, signature), RequestState.SUBMITTED);
    chain.land(encodeBase58(signature), {
      slot: 310_000_004n,
      commitment: "finalized",
      transaction: wire,
    });
    const settled = await status(requestId);
    assert.equal(settled.code, 0, JSON.stringify(settled.view));
    assert.equal(settled.view.signature, encodeBase58(signature));
    // Reporting it again after it settled changes nothing either.
    await submit(requestId, signature);
    const after = await status(requestId);
    assert.equal(after.view.status, "CONFIRMED");
    assert.equal(after.view.signature, settled.view.signature);

    // And the agent's own key still names that one request: a retry is never a second payment.
    const retried = await queue({
      amount: "700000",
      key: "stage4-duplicate",
      expect: "CONFIRMED",
    });
    assert.equal(retried.request_id, requestId);
  });
});

describe("Stage 4: a restart while a transaction is in flight", () => {
  it("keeps the signature, and settles it from the chain afterwards", async () => {
    const view = await queue({ amount: "1250000", key: "stage4-restart" });
    const requestId = String(view.request_id);
    const prepared = await prepare(requestId);
    await approve(requestId, prepared);
    const { signature, wire } = walletSigns(prepared);
    await submit(requestId, signature);

    // The sidecar goes down with the transaction in the air, and comes back onto the same
    // database. Nothing ran while it was down, and nothing runs by itself when it returns.
    await sidecar.stop();
    chain.land(encodeBase58(signature), {
      slot: 310_000_005n,
      commitment: "finalized",
      transaction: wire,
    });
    await startSidecar();

    const settled = await status(requestId);
    assert.equal(settled.code, 0, JSON.stringify(settled.view));
    assert.equal(settled.view.status, "CONFIRMED");
    assert.equal(settled.view.signature, encodeBase58(signature));
    // The comparison with the approved transaction survived the restart too: it was made
    // against the stored preparation, not against anything held in memory.
    assert.equal(settled.view.slot, 310_000_005);
  });
});

describe("Stage 4: the endpoint stops answering", () => {
  it("settles nothing, and says so without touching the transaction", async () => {
    const view = await queue({ amount: "400000", key: "stage4-outage" });
    const requestId = String(view.request_id);
    const prepared = await prepare(requestId);
    await approve(requestId, prepared);
    const { signature, wire } = walletSigns(prepared);
    await submit(requestId, signature);
    chain.land(encodeBase58(signature), {
      slot: 310_000_006n,
      commitment: "finalized",
      transaction: wire,
    });

    chain.unavailable = "the endpoint refused the connection";
    const unknown = await status(requestId);
    assert.equal(unknown.code, 10, JSON.stringify(unknown.view));
    assert.equal(unknown.view.status, "SUBMITTED");
    assert.equal(unknown.view.signature, encodeBase58(signature));

    // The owner's own check ignores the interval between checks, so the recovery is visible at
    // once rather than after a wait. It opens no wallet and sends nothing.
    chain.unavailable = undefined;
    const { request } = await phoneClient().checkStatus({
      ref: { connectionId: phone.connectionId, requestId },
    });
    assert.equal(request?.state, RequestState.CONFIRMED);
  });
});

/**
 * The one check that talks to a real network. It is off unless `SEEKER_VAULT_NETWORK_CHECKS=1`,
 * it reads devnet and only devnet, and it spends nothing: it needs no funded wallet and it never
 * gets as far as a transaction. What it proves is the guard that no fixture can prove — that the
 * sidecar compares a real cluster's genesis hash with the network a request names, and refuses
 * when they differ.
 */
describe(
  "Stage 4: the opt-in devnet check",
  { skip: process.env.SEEKER_VAULT_NETWORK_CHECKS !== "1" },
  () => {
    const url = process.env.SOLANA_RPC_URL ?? "https://api.devnet.solana.com";
    let live: SidecarProcess;
    let livePath: string;
    let livePhone: TestPhone;

    before(async () => {
      livePath = temporaryDatabasePath();
      live = await startSidecarProcess({
        port: await freePort(),
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 30,
        databasePath: livePath,
        solanaRpcUrl: url,
      });
      livePhone = await pairPhone(live.url, livePath);
    });

    after(async () => {
      await live.stop();
    });

    it("refuses a mainnet transfer against the devnet endpoint, by name", async () => {
      const wallet = testWallet().address;
      const client = requestClient(live.url, livePhone.phoneToken);
      await client.publishWallet({
        connectionId: livePhone.connectionId,
        binding: { wallet, network: Network.MAINNET },
      });
      const run = await (async () => {
        const child = spawn(
          process.execPath,
          [
            MAIN,
            "transfer",
            testWallet().address,
            "1",
            "--wallet",
            wallet,
            "--network",
            "mainnet",
            "--key",
            "stage4-live-mismatch",
          ],
          {
            env: {
              PATH: process.env.PATH ?? "",
              MCP_URL: `${live.url}/mcp`,
              MCP_TOKEN,
              LIVE_COMMAND_TIMEOUT_SECONDS: "30",
            },
          },
        );
        let stdout = "";
        child.stdout
          .setEncoding("utf8")
          .on("data", (c: string) => (stdout += c));
        await new Promise((resolve) => child.on("close", resolve));
        return stdout;
      })();
      const requestId = String(
        (JSON.parse(run) as Record<string, unknown>).request_id,
      );
      // The endpoint is devnet's, the request says mainnet, and the sidecar says so rather than
      // building anything. Nothing is signed, nothing is sent, and nothing is spent.
      await assert.rejects(
        client.prepareRequest({
          ref: { connectionId: livePhone.connectionId, requestId },
        }),
        /mainnet|network|cluster/i,
      );
    });
  },
);
