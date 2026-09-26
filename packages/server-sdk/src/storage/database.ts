/**
 * The sidecar's SQLite database, through Node's built-in `node:sqlite`. It's one local file,
 * opened in WAL mode with full synchronous commits, so a committed change survives a crash or a
 * power cut. Migrations run when the database opens. This directory is the only code in the
 * sidecar that touches the file system or SQLite (stage-boundary.test.ts).
 */
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { DatabaseSync, type SQLOutputValue } from "node:sqlite";

import { MIGRATIONS, type Migration } from "./migrations.ts";

export type { DatabaseSync } from "node:sqlite";

/** One result row, keyed by column name. */
export type Row = Record<string, SQLOutputValue>;

/** Opens a database that lives only as long as the process, for tests. */
export const IN_MEMORY = ":memory:";

/**
 * Opens (or creates) the database at `path` and brings its schema up to date. The directory is
 * created if it's missing.
 */
export function openDatabase(
  path: string,
  migrations: readonly Migration[] = MIGRATIONS,
): DatabaseSync {
  if (path !== IN_MEMORY) mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path, {
    enableForeignKeyConstraints: true,
    timeout: 5000,
  });
  try {
    // WAL lets a reader run while a write commits; FULL makes every commit durable before it
    // returns, which is what lets the sidecar answer a caller right after committing.
    db.exec("PRAGMA journal_mode = WAL; PRAGMA synchronous = FULL;");
    migrate(db, migrations);
    return db;
  } catch (error) {
    db.close();
    throw error;
  }
}

/** The schema version recorded in the database: the last migration applied, or 0. */
export function schemaVersion(db: DatabaseSync): number {
  const version = db.prepare("PRAGMA user_version").get()?.user_version;
  return typeof version === "number" ? version : 0;
}

/**
 * Applies the migrations the database doesn't have yet, in order. Each one runs in its own
 * transaction together with its version number, so a failure leaves the database at the last
 * complete version. A database from a newer sidecar is refused rather than guessed at.
 */
export function migrate(
  db: DatabaseSync,
  migrations: readonly Migration[],
): void {
  migrations.forEach((migration, index) => {
    if (migration.version !== index + 1) {
      throw new Error(
        `migration ${index + 1} is numbered ${migration.version}; migrations must count up from 1`,
      );
    }
  });
  const current = schemaVersion(db);
  if (current > migrations.length) {
    throw new Error(
      `the database has schema version ${current}, newer than this sidecar's ${migrations.length}; run a newer sidecar, or restore a backup`,
    );
  }
  for (const migration of migrations.slice(current)) {
    try {
      transaction(db, () => {
        db.exec(migration.sql);
        db.exec(`PRAGMA user_version = ${migration.version}`);
      });
    } catch (error) {
      throw new Error(
        `migration ${migration.version} (${migration.description}) failed: ${error instanceof Error ? error.message : String(error)}`,
        { cause: error },
      );
    }
  }
}

/**
 * Runs `work` in one write transaction. BEGIN IMMEDIATE takes the write lock up front, so what
 * `work` reads can't change before it writes, and any exception rolls everything back.
 */
export function transaction<T>(db: DatabaseSync, work: () => T): T {
  db.exec("BEGIN IMMEDIATE");
  try {
    const result = work();
    db.exec("COMMIT");
    return result;
  } catch (error) {
    // Some errors end the transaction on their own; rolling back again would throw.
    if (db.isTransaction) db.exec("ROLLBACK");
    throw error;
  }
}
