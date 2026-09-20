import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, describe, it } from "node:test";
import { fileURLToPath } from "node:url";

// The real sidecar (in process) and its Connect test client acting as the phone.
import { startSidecar, type Sidecar } from "../../mcp-server/src/server.ts";
import {
  Code,
  ConnectError,
  connectPhone,
  pairPhone,
  requestClient,
} from "../../mcp-server/src/testing/clients.ts";
import {
  FakeChain,
  startFakeRpc,
  walletAccount,
  type FakeRpc,
} from "../../mcp-server/src/testing/chain.ts";
import { temporaryDatabasePath } from "../../mcp-server/src/testing/process.ts";
import { testWallet } from "../../server-sdk/src/testing/wallet.ts";
import { Network } from "@seeker-vault/server-sdk/protocol";

const MAIN = fileURLToPath(new URL("./main.ts", import.meta.url));
const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const WRONG_TOKEN = "w".repeat(64);
const WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW";

interface Run {
  readonly code: number | null;
  readonly stdout: string;
  readonly stderr: string;
}

const outputs: string[] = [];
let sidecar: Sidecar; // 30 s deadline
let quickSidecar: Sidecar; // 1 s deadline

/** Runs the CLI as a real process, with only the given environment. */
function agent(
  args: readonly string[],
  env: Record<string, string>,
  nodeArgs: readonly string[] = [],
): Promise<Run> {
  const child = spawn(process.execPath, [...nodeArgs, MAIN, ...args], {
    env: { PATH: process.env.PATH ?? "", ...env },
  });
  let stdout = "";
  let stderr = "";
  child.stdout
    .setEncoding("utf8")
    .on("data", (chunk: string) => (stdout += chunk));
  child.stderr
    .setEncoding("utf8")
    .on("data", (chunk: string) => (stderr += chunk));
  return new Promise((resolve) => {
    child.on("close", (code) => {
      outputs.push(stdout, stderr);
      resolve({ code, stdout, stderr });
    });
  });
}

function envFor(
  target: Sidecar,
  overrides: Record<string, string> = {},
): Record<string, string> {
  return {
    MCP_URL: `${target.url}/mcp`,
    MCP_TOKEN,
    PHONE_TOKEN, // present in a developer's .env; the CLI must not print it either
    LIVE_COMMAND_TIMEOUT_SECONDS: target === quickSidecar ? "1" : "30",
    // `hello` and `ack` are diagnostics, and the CLI runs them only in development mode. A
    // developer's own .env sets this, and so does the test environment; its own test is below.
    MCP_DEMO_TOOLS: "true",
    ...overrides,
  };
}

async function closedPort(): Promise<number> {
  const server = createServer().listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address() as AddressInfo;
  await new Promise((resolve) => server.close(resolve));
  return port;
}

function isConnectError(code: Code): (error: unknown) => boolean {
  return (error) => error instanceof ConnectError && error.code === code;
}

before(async () => {
  const base = {
    host: "127.0.0.1",
    port: 0,
    mcpToken: MCP_TOKEN,
    phoneToken: PHONE_TOKEN,
    databasePath: ":memory:",
    requestTtlSeconds: 86_400,
    pendingLimit: 100,
  };
  const log = (): void => undefined;
  sidecar = await startSidecar(
    { ...base, liveCommandTimeoutSeconds: 30 },
    { log },
  );
  quickSidecar = await startSidecar(
    { ...base, liveCommandTimeoutSeconds: 1 },
    { log },
  );
});

after(async () => {
  await Promise.all([sidecar.close(), quickSidecar.close()]);
});

// The durable request commands, against a sidecar with a database file, so a phone can pair.
describe("pnpm agent ack, get, and cancel", () => {
  let queue: Sidecar;
  let databasePath: string;

  before(async () => {
    databasePath = temporaryDatabasePath();
    queue = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 30,
        databasePath,
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
        demoTools: true,
      },
      { log: () => undefined },
    );
  });

  after(() => queue.close());

  it("exits 3 when the sidecar doesn't serve the demo tool, and still reads requests", async () => {
    // `sidecar` runs without MCP_DEMO_TOOLS.
    const { code, stdout, stderr } = await agent(
      ["ack", "Deploy finished"],
      envFor(sidecar),
    );
    assert.equal(code, 3);
    assert.equal(stdout, "");
    assert.match(stderr, /does not offer vault_request_ack/);
    assert.match(stderr, /MCP_DEMO_TOOLS=true/);
    const missing = await agent(
      ["get", "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c"],
      envFor(sidecar),
    );
    assert.equal(missing.code, 9);
    assert.match(missing.stderr, /^NOT_FOUND: /m);
  });

  it("exits 9 with NOT_PAIRED until a phone is paired", async () => {
    const { code, stdout, stderr } = await agent(
      ["ack", "Deploy finished"],
      envFor(queue),
    );
    assert.equal(code, 9);
    assert.equal(stdout, "");
    assert.match(stderr, /^NOT_PAIRED: /m);
  });

  it("queues a request, reads it back once the phone answers, and cancels another", async () => {
    const phone = await pairPhone(queue.url, databasePath);
    const queued = await agent(
      ["ack", "Deploy finished", "--key", "deploy-7", "--note", "nightly"],
      envFor(queue),
    );
    assert.equal(queued.code, 0, queued.stderr);
    const view = JSON.parse(queued.stdout) as {
      request_id: string;
      status: string;
      action: string;
    };
    assert.equal(view.status, "PENDING");
    assert.equal(view.action, "ack");
    // A retry with the same key returns the same request.
    const retried = await agent(
      ["ack", "Deploy finished", "--key", "deploy-7"],
      envFor(queue),
    );
    assert.equal(
      (JSON.parse(retried.stdout) as { request_id: string }).request_id,
      view.request_id,
    );

    await requestClient(queue.url, phone.phoneToken).submitResult({
      ref: { connectionId: phone.connectionId, requestId: view.request_id },
      result: { case: "acknowledgement", value: {} },
    });
    const read = await agent(["get", view.request_id], envFor(queue));
    assert.equal(read.code, 0, read.stderr);
    assert.deepEqual(
      (({ status, terminal }) => ({ status, terminal }))(
        JSON.parse(read.stdout) as { status: string; terminal: boolean },
      ),
      { status: "COMPLETED", terminal: true },
    );

    // `status` reports the same outcome with an exit code a script can branch on.
    const done = await agent(["status", view.request_id], envFor(queue));
    assert.equal(done.code, 0, done.stderr);
    assert.equal(
      (JSON.parse(done.stdout) as { status: string }).status,
      "COMPLETED",
    );

    const other = JSON.parse(
      (await agent(["ack", "Never mind", "--expires", "3600"], envFor(queue)))
        .stdout,
    ) as { request_id: string };
    const cancelled = await agent(["cancel", other.request_id], envFor(queue));
    assert.equal(cancelled.code, 0, cancelled.stderr);
    assert.equal(
      (JSON.parse(cancelled.stdout) as { status: string }).status,
      "CANCELLED",
    );
    // A withdrawn request ended, and it didn't end the way it was asked for.
    const gone = await agent(["status", other.request_id], envFor(queue));
    assert.equal(gone.code, 11);
    assert.equal(
      (JSON.parse(gone.stdout) as { status: string }).status,
      "CANCELLED",
    );
  });

  it("prints the idempotency key it chose, and exits 9 with the sidecar's reason", async () => {
    const chosen = await agent(["ack", "Pick a key"], envFor(queue));
    assert.equal(chosen.code, 0, chosen.stderr);
    assert.match(chosen.stderr, /^idempotency key: ack-[0-9a-f-]{36}$/m);
    const missing = await agent(
      ["get", "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c"],
      envFor(queue),
    );
    assert.equal(missing.code, 9);
    assert.match(missing.stderr, /^NOT_FOUND: /m);
    const tooShort = await agent(["ack", "x", "--expires", "5"], envFor(queue));
    assert.equal(tooShort.code, 9);
    assert.match(tooShort.stderr, /^INVALID_PARAMETERS: /m);
  });

  it("prints the owner's wallet only once the phone has published one", async () => {
    // Pairing again replaces the phone, so this connection starts with no wallet.
    const phone = await pairPhone(queue.url, databasePath);
    const none = await agent(["address"], envFor(queue));
    assert.equal(none.code, 9);
    assert.equal(none.stdout, "");
    assert.match(none.stderr, /^WALLET_NOT_CONNECTED: /m);
    await requestClient(queue.url, phone.phoneToken).publishWallet({
      connectionId: phone.connectionId,
      binding: { wallet: WALLET, network: Network.DEVNET },
    });
    const { code, stdout } = await agent(["address"], envFor(queue));
    assert.equal(code, 0);
    const view = JSON.parse(stdout) as Record<string, unknown>;
    assert.equal(view.wallet, WALLET);
    assert.equal(view.network, "devnet");
    assert.equal(typeof view.bound_at, "string");
  });

  it("says the sidecar approves by hand, and lists only what it serves", async () => {
    const { code, stdout } = await agent(["capabilities"], envFor(queue));
    assert.equal(code, 0);
    const view = JSON.parse(stdout) as Record<string, unknown>;
    assert.equal(view.approval, "manual");
    assert.equal(view.signing, "wallet");
    assert.deepEqual(view.operations, ["ack", "sign_message"]);
    assert.equal(view.max_message_bytes, 4096);
  });

  it("queues a message for the owner, and checks the signature it gets back", async () => {
    const signer = testWallet();
    const phone = await pairPhone(queue.url, databasePath);
    const client = requestClient(queue.url, phone.phoneToken);
    await client.publishWallet({
      connectionId: phone.connectionId,
      binding: { wallet: signer.address, network: Network.DEVNET },
    });
    const message = "Sign in to Example\nNonce: 4711";
    // No --wallet: the agent reads the owner's wallet first, as an agent should.
    const queued = await agent(["sign", message], envFor(queue));
    assert.equal(queued.code, 0);
    const created = JSON.parse(queued.stdout) as Record<string, unknown>;
    assert.equal(created.action, "sign_message");
    assert.equal(created.status, "PENDING");
    assert.equal(created.wallet, signer.address);
    assert.match(queued.stderr, /nothing is signed until they approve/);

    // The owner approves on the phone, and the wallet signs.
    const bytes = new TextEncoder().encode(message);
    const ref = {
      connectionId: phone.connectionId,
      requestId: String(created.request_id),
    };
    await client.submitResult({
      ref,
      result: {
        case: "approval",
        value: {
          preparedVersion: 0,
          contentHash: createHash("sha256").update(bytes).digest(),
        },
      },
    });
    await client.submitResult({
      ref,
      result: {
        case: "messageSignature",
        value: { signature: signer.sign(bytes) },
      },
    });

    const read = await agent(
      ["get", String(created.request_id)],
      envFor(queue),
    );
    assert.equal(read.code, 0);
    const view = JSON.parse(read.stdout) as Record<string, unknown>;
    assert.equal(view.status, "COMPLETED");
    assert.equal(view.terminal, true);
    assert.equal(
      view.signed_message_base64,
      Buffer.from(bytes).toString("base64"),
    );
    // The agent verified it itself, with its own verifier.
    assert.equal(view.signature_verified, true);

    // The same request through `status`: a message signature is a signature and nothing more.
    // It names no cluster, it is not a transaction, and it gets no explorer link.
    const status = await agent(
      ["status", String(created.request_id)],
      envFor(queue),
    );
    assert.equal(status.code, 0, status.stderr);
    const settled = JSON.parse(status.stdout) as Record<string, unknown>;
    assert.equal(settled.status, "COMPLETED");
    assert.equal(settled.signature, view.signature);
    assert.equal(settled.signature_is_transaction, false);
    assert.equal(settled.explorer_url, undefined);
    assert.equal(settled.network, undefined);
  });

  it("exits 2 for missing or extra arguments", async () => {
    for (const args of [
      ["ack"],
      ["get"],
      ["status"],
      ["status", "a", "b"],
      ["address", "extra"],
      ["sign"],
      ["capabilities", "extra"],
      ["cancel", "a", "b"],
      ["tools", "extra"],
      ["ack", "x", "--expires", "soon"],
      ["wait"],
      ["wait", "a", "b"],
      ["swap", "one-argument-only"],
    ]) {
      assert.equal((await agent(args, envFor(queue))).code, 2, args.join(" "));
    }
  });

  it("waits for the owner's answer, and prints one JSON document when it comes", async () => {
    const phone = await pairPhone(queue.url, databasePath);
    const created = JSON.parse(
      (await agent(["ack", "Waited for", "--key", "wait-1"], envFor(queue)))
        .stdout,
    ) as { request_id: string };

    // The owner answers while the CLI is waiting, as they would on the phone.
    const answering = setTimeout(() => {
      void requestClient(queue.url, phone.phoneToken).submitResult({
        ref: {
          connectionId: phone.connectionId,
          requestId: created.request_id,
        },
        result: { case: "acknowledgement", value: {} },
      });
    }, 300);
    const waited = await agent(
      ["wait", created.request_id, "--for", "20", "--every", "1"],
      envFor(queue),
    );
    clearTimeout(answering);
    assert.equal(waited.code, 0, waited.stderr);
    // One document, so `| jq` reads it: nothing else is written to stdout.
    const view = JSON.parse(waited.stdout) as Record<string, unknown>;
    assert.equal(waited.stdout.trimEnd().split("\n").length, 1);
    assert.equal(view.status, "COMPLETED");
    assert.equal(view.outcome, "succeeded");
    assert.equal(view.terminal, true);
    assert.equal(view.timed_out, false);
    assert.ok(typeof view.polls === "number" && view.polls >= 1);
  });

  it("gives up at its deadline without touching the request", async () => {
    await pairPhone(queue.url, databasePath);
    const created = JSON.parse(
      (await agent(["ack", "Nobody answers", "--key", "wait-2"], envFor(queue)))
        .stdout,
    ) as { request_id: string };

    const waited = await agent(
      ["wait", created.request_id, "--for", "1", "--every", "1"],
      envFor(queue),
    );
    // Unsettled, not failed: the owner simply has not answered yet.
    assert.equal(waited.code, 10, waited.stderr);
    const view = JSON.parse(waited.stdout) as Record<string, unknown>;
    assert.equal(view.status, "PENDING");
    assert.equal(view.outcome, "unsettled");
    assert.equal(view.timed_out, true);
    assert.match(waited.stderr, /still PENDING after \d+ s/);

    // And the request is exactly as it was: waiting changed nothing.
    const after = await agent(["status", created.request_id], envFor(queue));
    assert.equal(after.code, 10);
    assert.equal(
      (JSON.parse(after.stdout) as { status: string }).status,
      "PENDING",
    );
  });

  it("returns at once for a request that has already ended", async () => {
    const phone = await pairPhone(queue.url, databasePath);
    const created = JSON.parse(
      (await agent(["ack", "Already done", "--key", "wait-3"], envFor(queue)))
        .stdout,
    ) as { request_id: string };
    await requestClient(queue.url, phone.phoneToken).submitResult({
      ref: { connectionId: phone.connectionId, requestId: created.request_id },
      result: { case: "rejection", value: {} },
    });

    const waited = await agent(
      ["wait", created.request_id, "--for", "600", "--every", "60"],
      envFor(queue),
    );
    // It ended, and not the way it was asked for.
    assert.equal(waited.code, 11, waited.stderr);
    const view = JSON.parse(waited.stdout) as Record<string, unknown>;
    assert.equal(view.status, "REJECTED");
    assert.equal(view.outcome, "failed");
    assert.equal(view.polls, 1, "it read the request once and stopped");
  });

  it("prints the request_id before it starts waiting", async () => {
    await pairPhone(queue.url, databasePath);
    const run = await agent(
      ["ack", "Queued and waited", "--key", "wait-4", "--wait", "--for", "1"],
      envFor(queue),
    );
    assert.equal(run.code, 10, run.stderr);
    const view = JSON.parse(run.stdout) as Record<string, unknown>;
    assert.match(run.stderr, /^request_id: [0-9a-f-]{36}$/m);
    assert.equal(
      run.stderr.match(/^request_id: (\S+)$/m)?.[1],
      view.request_id,
    );
    assert.equal(view.timed_out, true);
  });

  it("refuses wait settings it cannot honour, and --wait where it means nothing", async () => {
    for (const args of [
      ["wait", "an-id", "--for", "0"],
      ["wait", "an-id", "--every", "0"],
      ["wait", "an-id", "--for", "soon"],
      ["wait", "an-id", "--every", "99999"],
      ["get", "an-id", "--wait"],
      ["tools", "--wait"],
    ]) {
      const run = await agent(args, envFor(queue));
      assert.equal(run.code, 2, args.join(" "));
      assert.equal(run.stdout, "");
    }
  });

  it("keeps the two diagnostics in development mode", async () => {
    // Neither MCP_DEMO_TOOLS nor --demo: the CLI refuses before it opens a session.
    for (const command of [
      ["hello", "Anyone?"],
      ["ack", "Anything?"],
    ]) {
      const run = await agent(command, envFor(queue, { MCP_DEMO_TOOLS: "" }));
      assert.equal(run.code, 2, command.join(" "));
      assert.equal(run.stdout, "");
      assert.match(run.stderr, /development diagnostic/);
      assert.match(run.stderr, /neither a wallet signature nor a payment/);
    }
    // --demo is the explicit way to run one anyway.
    await pairPhone(queue.url, databasePath);
    const explicit = await agent(
      ["ack", "Asked for on purpose", "--key", "demo-1", "--demo"],
      envFor(queue, { MCP_DEMO_TOOLS: "" }),
    );
    assert.equal(explicit.code, 0, explicit.stderr);
    assert.equal(
      (JSON.parse(explicit.stdout) as { status: string }).status,
      "PENDING",
    );
    assert.match(explicit.stderr, /not a signature and not a payment/);
    // A value that is neither true nor false is a configuration problem, as in the sidecar.
    const wrong = await agent(
      ["tools"],
      envFor(queue, { MCP_DEMO_TOOLS: "yes" }),
    );
    assert.equal(wrong.code, 2);
    assert.match(wrong.stderr, /MCP_DEMO_TOOLS must be true or false/);
  });

  it("says that swaps are not served, and queues nothing", async () => {
    await pairPhone(queue.url, databasePath);
    const run = await agent(
      [
        "swap",
        "So11111111111111111111111111111111111111112",
        "1000000",
        "--wallet",
        WALLET,
        "--network",
        "devnet",
      ],
      envFor(queue),
    );
    assert.equal(run.code, 3);
    assert.equal(run.stdout, "");
    assert.match(run.stderr, /does not offer vault_swap/);
    assert.match(run.stderr, /swaps are a later stage/);

    // And it is held to the same rules as a transfer: a swap that doesn't say whose money moves,
    // or on which cluster, is refused before a session is opened.
    const vague = await agent(
      ["swap", "So11111111111111111111111111111111111111112", "1000000"],
      envFor(queue),
    );
    assert.equal(vague.code, 2);
    assert.match(vague.stderr, /swap needs --wallet and --network/);
  });
});

describe("pnpm agent", () => {
  it("prints the acknowledgement of the same command once the phone taps OK", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      const run = agent(["hello", "Hello Seeker 👋"], envFor(sidecar));
      const command = await phone.nextCommand();
      assert.equal(command.text, "Hello Seeker 👋");
      await phone.acknowledge(command.id);
      const { code, stdout, stderr } = await run;
      assert.equal(code, 0, stderr);
      assert.deepEqual(JSON.parse(stdout), { id: command.id, result: "OK" });
    } finally {
      phone.disconnect();
    }
  });

  it("sends Hello Seeker when no text is given", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      const run = agent(["hello"], envFor(sidecar));
      const command = await phone.nextCommand();
      assert.equal(command.text, "Hello Seeker");
      await phone.acknowledge(command.id);
      assert.equal((await run).code, 0);
    } finally {
      phone.disconnect();
    }
  });

  it("exits 4 with OFFLINE when no phone is watching", async () => {
    const { code, stdout, stderr } = await agent(
      ["hello", "Anyone there?"],
      envFor(sidecar),
    );
    assert.equal(code, 4);
    assert.equal(stdout, "");
    assert.match(stderr, /^OFFLINE: /m);
  });

  it("exits 5 with BUSY while another command waits", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      const first = agent(["hello", "first"], envFor(sidecar));
      const command = await phone.nextCommand();
      const second = await agent(["hello", "second"], envFor(sidecar));
      assert.equal(second.code, 5);
      assert.match(second.stderr, /^BUSY: /m);
      await phone.acknowledge(command.id);
      assert.equal((await first).code, 0);
    } finally {
      phone.disconnect();
    }
  });

  it("exits 8 with INVALID_TEXT for blank text", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      const { code, stderr } = await agent(["hello", "   "], envFor(sidecar));
      assert.equal(code, 8);
      assert.match(stderr, /^INVALID_TEXT: /m);
    } finally {
      phone.disconnect();
    }
  });

  it("exits 6 when the sidecar's deadline passes without OK", async () => {
    const phone = await connectPhone(quickSidecar.url, PHONE_TOKEN);
    try {
      const run = agent(["hello", "Nobody taps OK"], envFor(quickSidecar));
      await phone.nextCommand();
      const { code, stderr } = await run;
      assert.equal(code, 6);
      assert.match(stderr, /^TIMEOUT: no acknowledgement within 1 seconds/m);
    } finally {
      phone.disconnect();
    }
  });

  it("exits 6 on its own client timeout and cancels the waiting command", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      const run = agent(
        ["hello", "Too slow", "--timeout", "1"],
        envFor(sidecar),
      );
      const command = await phone.nextCommand();
      const { code, stderr } = await run;
      assert.equal(code, 6);
      assert.match(stderr, /client timeout/);
      await assert.rejects(
        phone.acknowledge(command.id),
        isConnectError(Code.Canceled),
      );
    } finally {
      phone.disconnect();
    }
  });

  it("exits 3 when the sidecar is unreachable or rejects the token", async () => {
    const unreachable = await agent(
      ["hello"],
      envFor(sidecar, {
        MCP_URL: `http://127.0.0.1:${await closedPort()}/mcp`,
      }),
    );
    assert.equal(unreachable.code, 3);
    assert.match(
      unreachable.stderr,
      /could not reach http:\/\/127\.0\.0\.1:\d+\/mcp/,
    );
    const rejected = await agent(
      ["hello"],
      envFor(sidecar, { MCP_TOKEN: WRONG_TOKEN }),
    );
    assert.equal(rejected.code, 3);
    assert.match(
      rejected.stderr,
      /the sidecar rejected MCP_TOKEN \(HTTP 401\)/,
    );
    const phoneToken = await agent(
      ["hello"],
      envFor(sidecar, { MCP_TOKEN: PHONE_TOKEN }),
    );
    assert.equal(phoneToken.code, 3);
  });

  it("exits 2 for missing configuration and bad arguments", async () => {
    const missing = await agent(["hello"], { MCP_URL: `${sidecar.url}/mcp` });
    assert.equal(missing.code, 2);
    assert.match(missing.stderr, /MCP_TOKEN is not set/);
    assert.equal((await agent(["launch"], envFor(sidecar))).code, 2);
    assert.equal((await agent(["hello", "a", "b"], envFor(sidecar))).code, 2);
    assert.equal(
      (await agent(["hello", "--timeout", "soon"], envFor(sidecar))).code,
      2,
    );
    const help = await agent(["--help"], {});
    assert.equal(help.code, 0);
    assert.match(help.stdout, /^Usage: pnpm agent/);
  });

  it("lists the sidecar's tools", async () => {
    const { code, stdout } = await agent(["tools"], envFor(sidecar));
    assert.equal(code, 0);
    const tools = JSON.parse(stdout) as { name: string }[];
    // The durable request tools (SAW-010), vault_get_address (SAW-015), and message signing
    // (SAW-016) follow the live one. Without MCP_DEMO_TOOLS, there's no vault_request_ack.
    assert.deepEqual(
      tools.map((tool) => tool.name),
      [
        "vault_display_command",
        "vault_sign_message",
        "vault_get_capabilities",
        "vault_get_address",
        "vault_get_request",
        "vault_cancel_request",
      ],
    );
  });

  it("reads .env while variables already in the environment win", async () => {
    const dir = mkdtempSync(join(tmpdir(), "test-agent-env-"));
    try {
      // .env points at a dead port but holds the token; the environment's MCP_URL wins.
      writeFileSync(
        join(dir, ".env"),
        `MCP_URL=http://127.0.0.1:${await closedPort()}/mcp\nMCP_TOKEN=${MCP_TOKEN}\nLIVE_COMMAND_TIMEOUT_SECONDS=30\n`,
      );
      const { code, stdout } = await agent(
        ["tools"],
        { MCP_URL: `${sidecar.url}/mcp` },
        [`--env-file-if-exists=${join(dir, ".env")}`],
      );
      assert.equal(code, 0);
      assert.match(stdout, /vault_display_command/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("never prints a token", () => {
    assert.ok(outputs.length > 0);
    for (const output of outputs) {
      for (const secret of [MCP_TOKEN, PHONE_TOKEN, WRONG_TOKEN]) {
        assert.ok(!output.includes(secret), output);
      }
    }
  });
});

// `pnpm agent transfer`, against a sidecar with a chain endpoint. The chain is a local fake, so
// nothing reaches a real cluster; the CLI still only queues a request, and signs and sends nothing.
describe("pnpm agent transfer", () => {
  let queue: Sidecar;
  let databasePath: string;
  let chain: FakeChain;
  let endpoint: FakeRpc;
  let wallet: string;

  /** A complete transfer command line: every part of a payment named, none of it guessed. */
  const spend = (amount: string): string[] => [
    "transfer",
    WALLET,
    amount,
    "--wallet",
    wallet,
    "--network",
    "devnet",
  ];

  before(async () => {
    chain = new FakeChain();
    endpoint = await startFakeRpc(chain);
    databasePath = temporaryDatabasePath();
    queue = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 30,
        databasePath,
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
        solanaRpcUrl: endpoint.url,
      },
      { log: () => undefined },
    );
    const phone = await pairPhone(queue.url, databasePath);
    wallet = testWallet().address;
    await requestClient(queue.url, phone.phoneToken).publishWallet({
      connectionId: phone.connectionId,
      binding: { wallet, network: Network.DEVNET },
    });
    chain.put(WALLET, walletAccount());
  });

  after(async () => {
    await queue.close();
    await endpoint.close();
  });

  it("lists transfer among the sidecar's operations", async () => {
    const { code, stdout } = await agent(["capabilities"], envFor(queue));
    assert.equal(code, 0);
    const view = JSON.parse(stdout) as { operations: string[] };
    assert.deepEqual(view.operations, ["sign_message", "transfer"]);
  });

  it("queues a SOL transfer as PENDING, without signing or sending anything", async () => {
    const { code, stdout, stderr } = await agent(
      [...spend("2500000"), "--key", "cli-transfer-1"],
      envFor(queue),
    );
    assert.equal(code, 0, stderr);
    const view = JSON.parse(stdout) as Record<string, unknown>;
    assert.equal(view.action, "transfer");
    assert.equal(view.status, "PENDING");
    assert.equal(view.terminal, false);
    assert.equal(view.wallet, wallet);
    assert.equal(view.network, "devnet");
    assert.equal(view.signature, undefined);
    assert.match(stderr, /nothing is signed or sent until they approve/);

    // The same key returns the same request rather than queueing a second payment.
    const again = await agent(
      [...spend("2500000"), "--key", "cli-transfer-1"],
      envFor(queue),
    );
    assert.equal(again.code, 0);
    assert.equal(
      (JSON.parse(again.stdout) as Record<string, unknown>).request_id,
      view.request_id,
    );

    // Nothing is settled, so `status` says so and exits 10 rather than 0. There is no signature
    // and no explorer link: the owner hasn't even seen the request yet.
    const status = await agent(
      ["status", String(view.request_id)],
      envFor(queue),
    );
    assert.equal(status.code, 10);
    const waiting = JSON.parse(status.stdout) as Record<string, unknown>;
    assert.equal(waiting.status, "PENDING");
    assert.equal(waiting.network, "devnet");
    assert.equal(waiting.signature, undefined);
    assert.equal(waiting.explorer_url, undefined);
  });

  it("exits 9 when the sidecar refuses the transfer", async () => {
    const { code, stdout, stderr } = await agent(
      [
        "transfer",
        "not-an-address",
        "2500000",
        "--wallet",
        wallet,
        "--network",
        "devnet",
        "--key",
        "cli-transfer-bad-recipient",
      ],
      envFor(queue),
    );
    assert.equal(code, 9);
    assert.equal(stdout, "");
    assert.match(stderr, /^INVALID_PARAMETERS: /m);
  });

  it("refuses to pay without being told which wallet and which cluster", async () => {
    // A payment that picks its own wallet or its own cluster is a payment nobody asked for.
    // Neither is filled in from vault_get_address, however convenient that would be.
    for (const args of [
      ["transfer", WALLET, "2500000"],
      ["transfer", WALLET, "2500000", "--wallet", WALLET],
      ["transfer", WALLET, "2500000", "--network", "devnet"],
    ]) {
      const { code, stdout, stderr } = await agent(args, envFor(queue));
      assert.equal(code, 2, args.join(" "));
      assert.equal(stdout, "");
      assert.match(stderr, /--wallet and --network/);
    }
  });

  it("refuses a cluster it doesn't know and an amount that isn't base units", async () => {
    const cluster = await agent(
      ["transfer", WALLET, "2500000", "--wallet", wallet, "--network", "beta"],
      envFor(queue),
    );
    assert.equal(cluster.code, 2);
    assert.match(cluster.stderr, /--network must be one of/);
    // A decimal is the mistake that sends a billionth of what was meant, or a billion times it.
    for (const amount of ["1.5", "0", "-1", "2_500_000", "1e6"]) {
      const { code, stdout, stderr } = await agent(
        [...spend(amount)],
        envFor(queue),
      );
      assert.equal(code, 2, amount);
      assert.equal(stdout, "");
      assert.match(stderr, /base units/);
    }
  });

  it("exits 2 without both a recipient and an amount", async () => {
    const { code, stderr } = await agent(
      ["transfer", WALLET, "--wallet", WALLET, "--network", "devnet"],
      envFor(queue),
    );
    assert.equal(code, 2);
    assert.match(stderr, /Usage: pnpm agent/);
  });
});
