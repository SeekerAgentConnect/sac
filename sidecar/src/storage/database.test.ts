import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { describe, it } from "node:test";

import { create } from "@bufbuild/protobuf";

import {
  ActionSchema,
  RequestState,
} from "../gen/seekervault/request/v1/request_pb.js";
import { SubmitResultRequestSchema } from "../gen/seekervault/request/v1/service_pb.js";
import { RequestStore } from "../requests/store.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import {
  IN_MEMORY,
  migrate,
  openDatabase,
  schemaVersion,
  transaction,
} from "./database.ts";
import { MIGRATIONS, type Migration } from "./migrations.ts";

const FIXTURE_V1 = readFileSync(
  new URL("./fixtures/schema-v1.sql", import.meta.url),
  "utf8",
);
const FIXTURE_CONNECTION = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f";
const FIXTURE_PENDING = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c";
const FIXTURE_COMPLETED = "8e1d2c3b-4a5f-4e6d-9c8b-7a6f5e4d3c2b";

function text(value: unknown): string {
  assert.equal(typeof value, "string");
  return value as string;
}

/** Every table and index with its SQL, whitespace collapsed, so two databases can be compared. */
function schemaOf(db: DatabaseSync): string[] {
  return db
    .prepare(
      "SELECT type, name, sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
    )
    .all()
    .map(
      (row) =>
        `${text(row.type)} ${text(row.name)}: ${text(row.sql).replace(/\s+/g, " ")}`,
    );
}

function tables(db: DatabaseSync): string[] {
  return db
    .prepare(
      "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
    )
    .all()
    .map((row) => text(row.name));
}

function pragma(db: DatabaseSync, name: string): unknown {
  return Object.values(db.prepare(`PRAGMA ${name}`).get() ?? {})[0];
}

describe("the sidecar database", () => {
  it("creates the current schema, with durable commits and foreign keys", () => {
    const db = openDatabase(temporaryDatabasePath());
    try {
      assert.equal(schemaVersion(db), MIGRATIONS.length);
      assert.equal(pragma(db, "journal_mode"), "wal");
      assert.equal(pragma(db, "synchronous"), 2); // FULL
      assert.equal(pragma(db, "foreign_keys"), 1);
      assert.deepEqual(tables(db), [
        "connections",
        "idempotency_keys",
        "prepared_transactions",
        "requests",
        "results",
      ]);
    } finally {
      db.close();
    }
  });

  it("opens the frozen v1 fixture and keeps its data", () => {
    const path = temporaryDatabasePath();
    const fixture = new DatabaseSync(path);
    fixture.exec(FIXTURE_V1);
    const reference = new DatabaseSync(IN_MEMORY);
    migrate(reference, MIGRATIONS.slice(0, 1));
    assert.deepEqual(
      schemaOf(fixture),
      schemaOf(reference),
      "the fixture's schema is migration 1's",
    );
    assert.equal(schemaVersion(fixture), 1);
    fixture.close();
    reference.close();

    const db = openDatabase(path);
    try {
      assert.equal(schemaVersion(db), MIGRATIONS.length);
      const store = new RequestStore(db, {
        defaultTtlSeconds: 86_400,
        pendingLimit: 100,
        now: () => Date.UTC(2026, 8, 11, 12, 0, 2),
      });
      assert.equal(store.activeConnection(), FIXTURE_CONNECTION);
      const pending = store.get(FIXTURE_PENDING);
      assert.equal(pending.state, RequestState.PENDING);
      assert.equal(pending.agentNote, "Written by schema version 1");
      assert.equal(pending.action?.kind.case, "ack");
      assert.equal(pending.action.kind.value.text, "Deploy finished");
      assert.equal(store.get(FIXTURE_COMPLETED).state, RequestState.COMPLETED);
      // Its idempotency records still answer retries, and its accepted result is still a repeat.
      const retry = store.create({
        action: create(ActionSchema, {
          kind: { case: "ack", value: { text: "Deploy finished" } },
        }),
        agentNote: "",
        idempotencyKey: "fixture-pending",
      });
      assert.equal(retry.created, false);
      assert.equal(retry.request.ref?.requestId, FIXTURE_PENDING);
      const repeat = store.submit(
        FIXTURE_CONNECTION,
        create(SubmitResultRequestSchema, {
          ref: {
            connectionId: FIXTURE_CONNECTION,
            requestId: FIXTURE_COMPLETED,
          },
          result: { case: "acknowledgement", value: {} },
        }),
      );
      assert.equal(repeat.duplicate, true);
    } finally {
      db.close();
    }
  });

  it("refuses a database from a newer sidecar, and leaves it untouched", () => {
    const path = temporaryDatabasePath();
    const newer = new DatabaseSync(path);
    newer.exec(`PRAGMA user_version = ${MIGRATIONS.length + 1}`);
    newer.close();
    assert.throws(
      () => openDatabase(path),
      new RegExp(
        `schema version ${MIGRATIONS.length + 1}, newer than this sidecar's ${MIGRATIONS.length}`,
      ),
    );
    const check = new DatabaseSync(path);
    assert.equal(schemaVersion(check), MIGRATIONS.length + 1);
    assert.deepEqual(tables(check), []);
    check.close();
  });

  it("applies each migration in its own transaction, and stops at the first failure", () => {
    const db = new DatabaseSync(IN_MEMORY);
    const first: Migration = {
      version: 1,
      description: "table a",
      sql: "CREATE TABLE a (x INTEGER) STRICT;",
    };
    const broken: Migration = {
      version: 2,
      description: "table b",
      sql: "CREATE TABLE b (y INTEGER) STRICT; INSERT INTO missing VALUES (1);",
    };
    assert.throws(
      () => migrate(db, [first, broken]),
      /migration 2 \(table b\) failed: no such table: missing/,
    );
    assert.equal(schemaVersion(db), 1);
    assert.deepEqual(tables(db), ["a"]);
    migrate(db, [
      first,
      { ...broken, sql: "CREATE TABLE b (y INTEGER) STRICT;" },
    ]);
    assert.equal(schemaVersion(db), 2);
    assert.deepEqual(tables(db), ["a", "b"]);
    db.close();
  });

  it("refuses migrations that don't count up from 1", () => {
    const db = new DatabaseSync(IN_MEMORY);
    assert.throws(
      () => migrate(db, [{ version: 2, description: "skips 1", sql: "" }]),
      /migration 1 is numbered 2/,
    );
    db.close();
  });

  it("rolls a transaction back completely when its work throws", () => {
    const db = openDatabase(IN_MEMORY);
    db.exec("CREATE TABLE t (x INTEGER) STRICT");
    const count = (): number =>
      Number(db.prepare("SELECT count(*) AS n FROM t").get()?.n);
    assert.throws(
      () =>
        transaction(db, () => {
          db.prepare("INSERT INTO t VALUES (1)").run();
          throw new Error("stopped halfway");
        }),
      /stopped halfway/,
    );
    assert.equal(count(), 0);
    assert.equal(db.isTransaction, false);
    assert.equal(
      transaction(db, () => {
        db.prepare("INSERT INTO t VALUES (2)").run();
        return "committed";
      }),
      "committed",
    );
    assert.equal(count(), 1);
    db.close();
  });
});
