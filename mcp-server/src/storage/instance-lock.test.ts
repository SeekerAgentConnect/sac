import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";

import { acquireInstanceLock } from "./instance-lock.ts";

describe("MCP server instance lock", () => {
  it("refuses a second owner and releases idempotently", () => {
    const database = join(
      mkdtempSync(join(tmpdir(), "mcp-lock-")),
      "direct.db",
    );
    const first = acquireInstanceLock(database);
    assert.throws(
      () => acquireInstanceLock(database),
      /already in use by another MCP server/,
    );
    first.release();
    first.release();

    const second = acquireInstanceLock(database);
    second.release();
    assert.equal(existsSync(`${database}.mcp-server.lock`), false);
  });

  it("recovers a complete stale owner record after a crash", () => {
    const database = join(
      mkdtempSync(join(tmpdir(), "mcp-lock-")),
      "direct.db",
    );
    const path = `${database}.mcp-server.lock`;
    writeFileSync(
      path,
      `${JSON.stringify({ pid: 2_147_483_647, nonce: "stale" })}\n`,
    );

    const lock = acquireInstanceLock(database);
    const owner: unknown = JSON.parse(readFileSync(path, "utf8"));
    assert.ok(typeof owner === "object" && owner !== null && "pid" in owner);
    assert.equal(owner.pid, process.pid);
    lock.release();
  });

  it("does not lock independent in-memory test stores", () => {
    const first = acquireInstanceLock(":memory:");
    const second = acquireInstanceLock(":memory:");
    first.release();
    second.release();
  });
});
