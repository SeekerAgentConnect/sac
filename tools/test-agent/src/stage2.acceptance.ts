/**
 * The Stage 2 acceptance scenario (SAW-014) from the agent's side: the real CLI (`pnpm agent`) as
 * the agent, two sidecars as real processes with their own throwaway databases, and the sidecar's
 * Connect test client as a phone paired with both. Stage2AcceptanceTest, which `pnpm check:android`
 * runs, drives the app's side of the same scenario. Neither counts as the owner's check on the
 * physical Seeker (docs/testing/stage-2.md). Run it with `pnpm test:queue`.
 */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { after, before, describe, it } from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

import {
  RequestError,
  RequestErrorDetailSchema,
  RequestState,
} from "@seeker_agent_connect/server-sdk/protocol";
import { openDatabase } from "../../../packages/server-sdk/src/storage/database.ts";
import {
  Code,
  ConnectError,
  connectPhone,
  pairPhone,
  requestClient,
  type Phone,
  type TestPhone,
} from "../../../servers/mcp-server/src/testing/clients.ts";
import {
  freePort,
  startSidecarProcess,
  temporaryDatabasePath,
  type SidecarProcess,
} from "../../../servers/mcp-server/src/testing/process.ts";
import type { RequestView } from "./agent.ts";

const MAIN = fileURLToPath(new URL("./main.ts", import.meta.url));
const PAIR = fileURLToPath(
  new URL("../../../servers/mcp-server/src/cli.ts", import.meta.url),
);
const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const ACKNOWLEDGE = { case: "acknowledgement", value: {} } as const;
const REVOKED = "The phone's connection was revoked.";

interface Run {
  readonly code: number | null;
  readonly stdout: string;
  readonly stderr: string;
}

/** A sidecar process that restarts onto its own port and database. */
interface Server {
  readonly url: string;
  readonly port: number;
  readonly databasePath: string;
  /**
   * Stops the process with `signal` (default SIGTERM) and starts it again. `clockAheadMs` stands
   * for time that passed while it was down; its clock never goes back.
   */
  restart(options?: {
    readonly signal?: NodeJS.Signals;
    readonly clockAheadMs?: number;
  }): Promise<void>;
  stop(signal?: NodeJS.Signals): Promise<void>;
  /** Everything it has printed, across restarts. */
  output(): string;
}

const outputs: string[] = [];
const servers: Server[] = [];
const credentials: string[] = [];
let a: Server;
let b: Server;
let phoneA: TestPhone;
let phoneB: TestPhone;

async function server(): Promise<Server> {
  const port = await freePort();
  const databasePath = temporaryDatabasePath();
  let clockAheadMs = 0;
  let earlier = "";
  const start = (): Promise<SidecarProcess> =>
    startSidecarProcess({
      port,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 30,
      databasePath,
      demoTools: true,
      clockAheadMs,
    });
  let current: SidecarProcess | undefined = await start();
  const stop = async (signal: NodeJS.Signals = "SIGTERM"): Promise<void> => {
    if (current === undefined) return;
    await current.stop(signal);
    earlier += current.output();
    current = undefined;
  };
  const started: Server = {
    url: `http://127.0.0.1:${port}`,
    port,
    databasePath,
    stop,
    async restart(options = {}) {
      await stop(options.signal);
      clockAheadMs = Math.max(clockAheadMs, options.clockAheadMs ?? 0);
      current = await start();
    },
    output: () => earlier + (current?.output() ?? ""),
  };
  servers.push(started);
  return started;
}

/** Runs a Node script as a real process, with only the given environment. */
function run(
  script: string,
  args: readonly string[],
  env: Record<string, string>,
): Promise<Run> {
  const child = spawn(process.execPath, [script, ...args], {
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

/** `pnpm agent` against `target`. */
function agent(
  target: Server,
  args: readonly string[],
  env: Record<string, string> = {},
): Promise<Run> {
  return run(MAIN, args, {
    MCP_URL: `${target.url}/mcp`,
    MCP_TOKEN,
    PHONE_TOKEN, // present in a developer's .env; the CLI must not print it either
    LIVE_COMMAND_TIMEOUT_SECONDS: "30",
    MCP_DEMO_TOOLS: "true", // `hello` and `ack` are development diagnostics (SAW-037)
    ...env,
  });
}

/** `pnpm pair` on `target`'s database, as the operator runs it. */
function pairCommand(target: Server, args: readonly string[]): Promise<Run> {
  return run(PAIR, ["pair", ...args], {
    SIDECAR_HOST: "127.0.0.1",
    SIDECAR_PORT: String(target.port),
    MCP_TOKEN,
    PHONE_TOKEN,
    LIVE_COMMAND_TIMEOUT_SECONDS: "30",
    DATABASE_PATH: target.databasePath,
  });
}

/** The request a durable command printed. Fails the test unless the command exited 0. */
function view(result: Run): RequestView {
  assert.equal(result.code, 0, result.stderr);
  return JSON.parse(result.stdout) as RequestView;
}

/** Pairs the phone with `target` the way the owner does, and keeps its credential to check the logs. */
async function pair(target: Server): Promise<TestPhone> {
  const phone = await pairPhone(target.url, target.databasePath, "Seeker");
  credentials.push(phone.phoneToken);
  return phone;
}

function phoneOn(target: Server, phone: TestPhone) {
  return requestClient(target.url, phone.phoneToken);
}

/** What the phone fetches when it opens: the IDs of its PENDING requests on `target`. */
async function pending(target: Server, phone: TestPhone): Promise<string[]> {
  const { requests } = await phoneOn(target, phone).listPending({
    connectionId: phone.connectionId,
  });
  return requests.map((request) => request.ref?.requestId ?? "");
}

function answer(
  target: Server,
  phone: TestPhone,
  requestId: string,
  decision: "acknowledge" | "reject",
) {
  return phoneOn(target, phone).submitResult({
    ref: { connectionId: phone.connectionId, requestId },
    result:
      decision === "reject" ? { case: "rejection", value: {} } : ACKNOWLEDGE,
  });
}

/** A ConnectError with this code, whose RequestErrorDetail names `error` (and `state`). */
function refused(
  code: Code,
  error: RequestError,
  state?: RequestState,
): (thrown: unknown) => boolean {
  return (thrown) => {
    if (!(thrown instanceof ConnectError) || thrown.code !== code) return false;
    const [detail] = thrown.findDetails(RequestErrorDetailSchema);
    return (
      detail?.error === error &&
      (state === undefined || detail.request?.state === state)
    );
  };
}

/** "quiet" when the live phone receives nothing within `ms`. */
function quiet(phone: Phone, ms = 300): Promise<string> {
  return Promise.race([
    phone.next().then(
      () => "event",
      () => "ended",
    ),
    delay(ms).then(() => "quiet"),
  ]);
}

/** How many durable requests `target` has stored, read from its database. */
function storedRequests(target: Server): number {
  const db = openDatabase(target.databasePath);
  try {
    return Number(db.prepare("SELECT count(*) AS n FROM requests").get()?.n);
  } finally {
    db.close();
  }
}

before(async () => {
  [a, b] = await Promise.all([server(), server()]);
  [phoneA, phoneB] = await Promise.all([pair(a), pair(b)]);
});

after(async () => {
  await Promise.all(servers.map((target) => target.stop("SIGKILL")));
});

describe("Stage 2 acceptance: two sidecars and a simulated phone", () => {
  it("a request queued while the app is closed survives a sidecar restart, and completes once the phone opens", async () => {
    const queued = view(
      await agent(a, ["ack", "Deploy finished", "--key", "accept-1"]),
    );
    assert.equal(queued.status, "PENDING");
    // The sidecar is killed outright, then started again on the same database.
    await a.restart({ signal: "SIGKILL" });
    assert.equal(
      view(await agent(a, ["get", queued.request_id])).status,
      "PENDING",
    );

    // The phone opens and fetches. The request is on its own sidecar only.
    assert.ok((await pending(a, phoneA)).includes(queued.request_id));
    assert.ok(!(await pending(b, phoneB)).includes(queued.request_id));
    const { request } = await answer(
      a,
      phoneA,
      queued.request_id,
      "acknowledge",
    );
    assert.equal(request?.state, RequestState.COMPLETED);

    // The agent reads the result after another restart, and a retry returns the same request.
    await a.restart();
    const done = view(await agent(a, ["get", queued.request_id]));
    assert.deepEqual([done.status, done.terminal], ["COMPLETED", true]);
    const retried = view(
      await agent(a, ["ack", "Deploy finished", "--key", "accept-1"]),
    );
    assert.deepEqual(
      [retried.request_id, retried.status],
      [queued.request_id, "COMPLETED"],
    );
    assert.ok(!(await pending(a, phoneA)).includes(queued.request_id));
  });

  it("the owner's rejection on the other sidecar reaches its agent as REJECTED", async () => {
    const queued = view(
      await agent(b, [
        "ack",
        "Rotate the staging keys?",
        "--key",
        "reject-1",
        "--note",
        "weekly rotation",
      ]),
    );
    await b.restart();
    assert.ok((await pending(b, phoneB)).includes(queued.request_id));
    await answer(b, phoneB, queued.request_id, "reject");
    const rejected = view(await agent(b, ["get", queued.request_id]));
    assert.deepEqual(
      [rejected.status, rejected.terminal, rejected.detail],
      ["REJECTED", true, "The owner rejected the request."],
    );
    // A later acknowledgement can't change the answer.
    await assert.rejects(
      answer(b, phoneB, queued.request_id, "acknowledge"),
      refused(
        Code.FailedPrecondition,
        RequestError.INVALID_STATE,
        RequestState.REJECTED,
      ),
    );
  });

  it("a request whose deadline passes while its sidecar is down comes back EXPIRED, and a late answer is refused", async () => {
    const short = view(
      await agent(a, [
        "ack",
        "Only for a minute",
        "--key",
        "expire-1",
        "--expires",
        "60",
      ]),
    );
    const long = view(
      await agent(a, ["ack", "Good for a day", "--key", "expire-2"]),
    );
    // The phone has fetched it, so the owner may still try to answer it.
    assert.ok((await pending(a, phoneA)).includes(short.request_id));

    // Two minutes pass while the sidecar is down.
    await a.restart({ clockAheadMs: 120_000 });
    const expired = view(await agent(a, ["get", short.request_id]));
    assert.deepEqual(
      [expired.status, expired.terminal, expired.detail],
      ["EXPIRED", true, "The request expired before the owner decided."],
    );
    const fetched = await pending(a, phoneA);
    assert.ok(!fetched.includes(short.request_id));
    assert.ok(fetched.includes(long.request_id));
    await assert.rejects(
      answer(a, phoneA, short.request_id, "acknowledge"),
      refused(
        Code.FailedPrecondition,
        RequestError.INVALID_STATE,
        RequestState.EXPIRED,
      ),
    );
    const cancel = await agent(a, ["cancel", short.request_id]);
    assert.equal(cancel.code, 9);
    assert.match(cancel.stderr, /^INVALID_STATE: /m);

    // The request with a day to go is still the owner's to answer.
    await answer(a, phoneA, long.request_id, "acknowledge");
    assert.equal(
      view(await agent(a, ["get", long.request_id])).status,
      "COMPLETED",
    );
  });

  it("revoking a pairing cancels its pending requests and shuts out its phone, and leaves the other sidecar alone", async () => {
    const onB = view(
      await agent(b, ["ack", "Revoked before an answer", "--key", "revoke-1"]),
    );
    const onA = view(
      await agent(a, ["ack", "Unaffected by B", "--key", "revoke-2"]),
    );
    const waiting = (await pending(b, phoneB)).length;

    const revoke = await pairCommand(b, ["revoke"]);
    assert.equal(revoke.code, 0, revoke.stderr);
    assert.match(
      revoke.stdout,
      new RegExp(
        `Its credential no longer works, and ${waiting} pending requests were cancelled\\.`,
      ),
    );
    const cancelled = view(await agent(b, ["get", onB.request_id]));
    assert.deepEqual(
      [cancelled.status, cancelled.detail],
      ["CANCELLED", REVOKED],
    );
    const unauthenticated = refused(
      Code.Unauthenticated,
      RequestError.UNAUTHENTICATED,
    );
    await assert.rejects(pending(b, phoneB), unauthenticated);
    await assert.rejects(
      answer(b, phoneB, onB.request_id, "acknowledge"),
      unauthenticated,
    );
    const unpaired = await agent(b, [
      "ack",
      "Nobody to ask",
      "--key",
      "revoke-3",
    ]);
    assert.equal(unpaired.code, 9);
    assert.match(unpaired.stderr, /^NOT_PAIRED: /m);

    // The phone's connection to the other sidecar is untouched.
    await answer(a, phoneA, onA.request_id, "acknowledge");
    assert.equal(
      view(await agent(a, ["get", onA.request_id])).status,
      "COMPLETED",
    );

    // Pairing again makes a new connection. The revoked credential stays shut out after a restart.
    const again = await pair(b);
    assert.notEqual(again.connectionId, phoneB.connectionId);
    await b.restart({ signal: "SIGKILL" });
    assert.deepEqual(await pending(b, again), []);
    await assert.rejects(pending(b, phoneB), unauthenticated);
    phoneB = again;
  });

  it("a request can't be read or answered with another connection's identity, on either sidecar", async () => {
    const onA = view(
      await agent(a, [
        "ack",
        "For the connection to A",
        "--key",
        "isolation-1",
      ]),
    );
    const onB = view(
      await agent(b, [
        "ack",
        "For the connection to B",
        "--key",
        "isolation-2",
      ]),
    );
    const refA = {
      connectionId: phoneA.connectionId,
      requestId: onA.request_id,
    };
    const refB = {
      connectionId: phoneB.connectionId,
      requestId: onB.request_id,
    };
    const unauthenticated = refused(
      Code.Unauthenticated,
      RequestError.UNAUTHENTICATED,
    );
    const notFound = refused(Code.NotFound, RequestError.NOT_FOUND);

    // One sidecar's phone credential is no credential on the other.
    for (const [target, stranger, ref] of [
      [a, phoneB, refA],
      [b, phoneA, refB],
    ] as const) {
      await assert.rejects(
        phoneOn(target, stranger).getRequest({ ref }),
        unauthenticated,
      );
      await assert.rejects(
        phoneOn(target, stranger).submitResult({ ref, result: ACKNOWLEDGE }),
        unauthenticated,
      );
    }
    // A reference that names another connection, or another connection's request, finds nothing.
    for (const ref of [
      { connectionId: phoneB.connectionId, requestId: onA.request_id },
      { connectionId: phoneA.connectionId, requestId: onB.request_id },
    ]) {
      await assert.rejects(phoneOn(a, phoneA).getRequest({ ref }), notFound);
      await assert.rejects(
        phoneOn(a, phoneA).submitResult({ ref, result: ACKNOWLEDGE }),
        notFound,
      );
    }

    // On one sidecar, pairing again replaces the connection. The replaced credential opens
    // nothing, and the new connection can't reach the old one's request.
    const replaced = phoneB;
    phoneB = await pair(b);
    await assert.rejects(
      phoneOn(b, replaced).getRequest({ ref: refB }),
      unauthenticated,
    );
    await assert.rejects(
      phoneOn(b, replaced).submitResult({ ref: refB, result: ACKNOWLEDGE }),
      unauthenticated,
    );
    for (const ref of [
      refB,
      { connectionId: phoneB.connectionId, requestId: onB.request_id },
    ]) {
      await assert.rejects(phoneOn(b, phoneB).getRequest({ ref }), notFound);
      await assert.rejects(
        phoneOn(b, phoneB).submitResult({ ref, result: ACKNOWLEDGE }),
        notFound,
      );
    }

    // The agent's token isn't a phone credential, and a phone credential doesn't open /mcp.
    await assert.rejects(
      requestClient(a.url, MCP_TOKEN).getRequest({ ref: refA }),
      unauthenticated,
    );
    const asAgent = await agent(a, ["get", onA.request_id], {
      MCP_TOKEN: phoneA.phoneToken,
    });
    assert.equal(asAgent.code, 3);
    assert.match(asAgent.stderr, /HTTP 401/);

    // None of that changed anything. A's request waits for its own phone, and B's ended with the
    // connection it belonged to.
    assert.equal(
      view(await agent(a, ["get", onA.request_id])).status,
      "PENDING",
    );
    const { request } = await phoneOn(a, phoneA).submitResult({
      ref: refA,
      result: ACKNOWLEDGE,
    });
    assert.equal(request?.state, RequestState.COMPLETED);
    const ended = view(await agent(b, ["get", onB.request_id]));
    assert.deepEqual([ended.status, ended.detail], ["CANCELLED", REVOKED]);
  });

  it("the live Stage 1 diagnostic still stores nothing and replays nothing", async () => {
    const stored = storedRequests(a);
    const waiting = await pending(a, phoneA);
    const offline = await agent(a, ["hello", "Live only"]);
    assert.equal(offline.code, 4);
    assert.match(offline.stderr, /^OFFLINE: /m);

    // A live-test screen that connects afterwards receives nothing, before or after a restart.
    const late = await connectPhone(a.url, PHONE_TOKEN);
    assert.equal(await quiet(late), "quiet");
    late.disconnect();
    await a.restart({ signal: "SIGKILL" });
    const reopened = await connectPhone(a.url, PHONE_TOKEN);
    assert.equal(await quiet(reopened), "quiet");
    reopened.disconnect();

    // A live command that the phone answers isn't stored either, and its ID is no request.
    const screen = await connectPhone(a.url, PHONE_TOKEN);
    try {
      const hello = agent(a, ["hello", "Answered live"]);
      const command = await screen.nextCommand();
      await screen.acknowledge(command.id);
      assert.equal((await hello).code, 0);
      const asRequest = await agent(a, ["get", command.id]);
      assert.equal(asRequest.code, 9);
      assert.match(asRequest.stderr, /^NOT_FOUND: /m);
    } finally {
      screen.disconnect();
    }
    assert.equal(storedRequests(a), stored);
    assert.deepEqual(await pending(a, phoneA), waiting);
  });

  it("never prints a token or a phone credential", () => {
    assert.ok(outputs.length > 0);
    const secrets = [MCP_TOKEN, PHONE_TOKEN, ...credentials];
    for (const output of [
      ...outputs,
      ...servers.map((target) => target.output()),
    ]) {
      for (const secret of secrets) {
        assert.ok(!output.includes(secret), "a secret was printed");
      }
    }
  });
});
