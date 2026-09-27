/**
 * Backing the sidecar up, and restoring it (SAW-038).
 *
 * This runs the procedure `docs/guides/self-hosting.md#backing-up-and-restoring` gives an operator,
 * against a real sidecar and a real database file: a hot backup taken with `VACUUM INTO` while the
 * sidecar is running, and a restore of that file over the live one while it is stopped.
 *
 * What it is really checking is the promise the guide makes about recovery. Restoring an older
 * database moves the sidecar's own record backwards — a request that was answered is PENDING
 * again — and that must be all it does. Nothing is re-executed, nothing is re-signed, no
 * transaction is rebuilt or resent, and no wallet is opened: the owner's phone still holds the
 * answer it gave, and re-delivering it is the whole of the recovery.
 */
import assert from "node:assert/strict";
import { copyFileSync, existsSync, rmSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
// The operator's own one-liner opens the file this way, from outside the sidecar's storage module,
// which is why this test does too (`docs/guides/self-hosting.md#backing-up-and-restoring`).
import { DatabaseSync } from "node:sqlite";
import { after, before, describe, it } from "node:test";

import { GET_REQUEST_TOOL, REQUEST_ACK_TOOL } from "./requests/mcp-tools.ts";
import { startSidecar, type Sidecar } from "./server.ts";
import {
  openDatabase,
  schemaVersion,
} from "../../../packages/server-sdk/src/storage/database.ts";
import {
  callTool,
  connectAgent,
  pairPhone,
  requestClient,
  viewOf,
  type TestPhone,
} from "./testing/clients.ts";
import { temporaryDatabasePath } from "./testing/process.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);

const databasePath = temporaryDatabasePath();
const backupPath = join(dirname(databasePath), "sidecar-backup.db");
const logs: string[] = [];

function startOn(path: string): Promise<Sidecar> {
  return startSidecar(
    {
      host: "127.0.0.1",
      port: 0,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 30,
      databasePath: path,
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
      demoTools: true,
      // No Solana endpoint, as a deployment has by default: this sidecar cannot reach a chain at
      // all, which is worth having in the restore test explicitly.
    },
    { log: (line) => logs.push(line) },
  );
}

/**
 * The hot backup from the guide: a second connection, and one `VACUUM INTO`. It writes a single
 * consistent file, so nothing has to reason about the write-ahead log beside it.
 */
function backup(from: string, to: string): void {
  rmSync(to, { force: true });
  const db = new DatabaseSync(from, { readOnly: true, timeout: 5000 });
  try {
    db.exec(`VACUUM INTO '${to}'`);
  } finally {
    db.close();
  }
}

/** The restore from the guide: the backup over the live file, and no stale log beside it. */
function restore(from: string, to: string): void {
  for (const suffix of ["-wal", "-shm"])
    rmSync(`${to}${suffix}`, { force: true });
  copyFileSync(from, to);
}

let sidecar: Sidecar;
let phone: TestPhone;
let answered: string;
let untouched: string;
let serverId: string;

before(async () => {
  sidecar = await startOn(databasePath);
  serverId = sidecar.serverId;
  phone = await pairPhone(sidecar.url, databasePath);

  const agent = await connectAgent(sidecar.url, MCP_TOKEN);
  try {
    answered = viewOf(
      await callTool(agent, REQUEST_ACK_TOOL, {
        text: "Answered after the backup",
        idempotency_key: "backup-1",
      }),
    ).request_id;
    untouched = viewOf(
      await callTool(agent, REQUEST_ACK_TOOL, {
        text: "Still waiting",
        idempotency_key: "backup-2",
      }),
    ).request_id;
  } finally {
    await agent.close();
  }
});

after(async () => {
  await sidecar.close();
});

describe("backing up a running sidecar", () => {
  it("writes one consistent file while the sidecar keeps serving", async () => {
    backup(databasePath, backupPath);
    assert.ok(existsSync(backupPath), "the backup file exists");
    assert.ok(statSync(backupPath).size > 0);

    // The sidecar is unaffected: it is still serving, and still has both requests.
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const view = viewOf(
        await callTool(agent, GET_REQUEST_TOOL, { request_id: untouched }),
      );
      assert.equal(view.status, "PENDING");
    } finally {
      await agent.close();
    }

    // And the backup is a database this sidecar can open, at the same schema version.
    const live = openDatabase(databasePath);
    const copy = openDatabase(backupPath);
    try {
      assert.equal(schemaVersion(copy), schemaVersion(live));
    } finally {
      copy.close();
      live.close();
    }
  });
});

describe("restoring an older backup", () => {
  it("moves the record back, and re-executes nothing", async () => {
    // The owner answers one of the two requests — after the backup was taken.
    await requestClient(sidecar.url, phone.phoneToken).submitResult({
      ref: { connectionId: phone.connectionId, requestId: answered },
      result: { case: "acknowledgement", value: {} },
    });
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      assert.equal(
        viewOf(
          await callTool(agent, GET_REQUEST_TOOL, { request_id: answered }),
        ).status,
        "COMPLETED",
      );
    } finally {
      await agent.close();
    }

    // Stop, restore, start again: exactly what the guide says to do.
    await sidecar.close();
    restore(backupPath, databasePath);
    logs.length = 0;
    sidecar = await startOn(databasePath);

    // The server is the same server. A restored backup is not a new deployment, so a paired phone
    // does not have to be told about it.
    assert.equal(sidecar.serverId, serverId);

    const restored = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      // The answer is gone, because it happened after the backup. The request is waiting again.
      assert.equal(
        viewOf(
          await callTool(restored, GET_REQUEST_TOOL, { request_id: answered }),
        ).status,
        "PENDING",
      );
      // And the request that was never answered is exactly as it was.
      assert.equal(
        viewOf(
          await callTool(restored, GET_REQUEST_TOOL, { request_id: untouched }),
        ).status,
        "PENDING",
      );
    } finally {
      await restored.close();
    }

    // Nothing ran on the way up. The startup log is the whole story: no request was executed, no
    // signature was asked for, and this sidecar has no chain endpoint to send anything to.
    const startup = logs.join("\n");
    assert.match(startup, /no Solana RPC endpoint is configured/);
    assert.doesNotMatch(startup, /submit|resend|retry|execut/i);
  });

  it("lets the phone re-deliver the answer it already has, with no wallet involved", async () => {
    // This is what recovery looks like from the owner's side: their phone kept the answer, and
    // delivering it again settles the request. Nothing asks the wallet for anything a second time.
    await requestClient(sidecar.url, phone.phoneToken).submitResult({
      ref: { connectionId: phone.connectionId, requestId: answered },
      result: { case: "acknowledgement", value: {} },
    });
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const view = viewOf(
        await callTool(agent, GET_REQUEST_TOOL, { request_id: answered }),
      );
      assert.equal(view.status, "COMPLETED");
      assert.equal(view.terminal, true);
    } finally {
      await agent.close();
    }
    // The pairing survived the restore: the credential in the backup is the one the phone holds.
    // A backup from before pairing would not be, and the guide says to pair again.
  });
});

describe("a database from a newer sidecar", () => {
  it("is refused rather than guessed at", () => {
    const path = temporaryDatabasePath();
    const db = openDatabase(path);
    const version = schemaVersion(db);
    db.exec(`PRAGMA user_version = ${version + 5}`);
    db.close();

    assert.throws(
      () => openDatabase(path),
      /newer than this sidecar's .*restore a backup/s,
      "opening it names the versions and says what to do",
    );
  });
});
