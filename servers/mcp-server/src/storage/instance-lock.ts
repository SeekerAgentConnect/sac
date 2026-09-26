/**
 * One running MCP server owns one durable Direct SDK store. Operator pairing commands may still
 * open the application database while it runs, but a second application process must not become
 * a competing request/update owner on another listener.
 *
 * The ownership transaction lives in a separate SQLite file. SQLite's OS/VFS lock is visible to
 * every process that mounts the same local volume, including processes in separate container PID
 * namespaces, and the kernel releases it if its connection disappears after SIGKILL or a crash.
 * No PID, timeout or cleanup convention is used to decide whether an owner is alive.
 */
import { randomUUID } from "node:crypto";
import {
  chmodSync,
  closeSync,
  constants,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  realpathSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";

export interface InstanceLock {
  /** The persistent ownership database. Its transaction, not its presence, is the lock. */
  readonly path?: string;
  release(): void;
}

interface LegacyLockOwner {
  readonly pid: number;
  readonly nonce: string;
}

const OWNER_SUFFIX = ".mcp-server-owner.sqlite";
const LEGACY_SUFFIX = ".mcp-server.lock";
const LEGACY_GUARD = Object.freeze({ format: "sqlite-owner-v1" });
const OWNER_APPLICATION_ID = 0x5341434c; // "SACL": Seeker Agent Connect Lock

/** Acquires an ownership-safe process lock beside the canonical application database path. */
export function acquireInstanceLock(databasePath: string): InstanceLock {
  if (databasePath === ":memory:") return { release: () => undefined };

  const absolute = resolve(databasePath);
  mkdirSync(dirname(absolute), { recursive: true });
  const canonical = existsSync(absolute)
    ? realpathSync(absolute)
    : join(realpathSync(dirname(absolute)), basename(absolute));
  const path = `${canonical}${OWNER_SUFFIX}`;

  // DatabaseSync otherwise creates the file through SQLite's default mode. Creating it first
  // keeps an ownership artifact beside credentials and requests private even with a loose umask.
  const descriptor = openSync(
    path,
    constants.O_CREAT | constants.O_RDWR,
    0o600,
  );
  closeSync(descriptor);
  chmodSync(path, 0o600);

  const ownership = new DatabaseSync(path, { timeout: 0 });
  try {
    const applicationId = ownership
      .prepare("PRAGMA application_id")
      .get()?.application_id;
    if (applicationId === 0) {
      ownership.exec(`PRAGMA application_id = ${OWNER_APPLICATION_ID}`);
    } else if (applicationId !== OWNER_APPLICATION_ID) {
      throw new Error("the file belongs to another application");
    }
    // BEGIN EXCLUSIVE acquires the filesystem-backed write lock now and holds it until close. The
    // file may remain after release: an unlocked SQLite file is not an owner or a stale lease.
    ownership.exec("BEGIN EXCLUSIVE");
  } catch (error) {
    ownership.close();
    if (isSqliteBusy(error)) {
      throw new Error(
        `the direct store is already in use by another MCP server (${path})`,
        { cause: error },
      );
    }
    throw new Error(
      `the direct store ownership file could not be opened (${path}): ${error instanceof Error ? error.message : String(error)}`,
      { cause: error },
    );
  }

  try {
    installLegacyCompatibilityGuard(`${canonical}${LEGACY_SUFFIX}`);
  } catch (error) {
    ownership.close();
    throw error;
  }

  let released = false;
  return {
    path,
    release() {
      if (released) return;
      released = true;
      // Closing a connection rolls its open transaction back and releases the OS lock. This is
      // deliberately the same resource the kernel closes after an uncatchable process exit.
      ownership.close();
    },
  };
}

/**
 * Replaces only the recognized PID/nonce record written by releases before SEE-137 with a guard
 * old binaries fail closed on. That prevents a rollback binary from starting beside a new owner.
 *
 * That record cannot prove liveness across container PID namespaces, which is why it cannot be
 * consulted by the new algorithm. Acquiring the new OS lock serializes migrations among upgraded
 * processes. Operators must stop a pre-SEE-137 process before upgrading because the legacy record
 * cannot distinguish a dead PID from the same PID in another namespace. An unknown file is kept
 * and refused rather than replaced as if it were known stale state.
 */
function installLegacyCompatibilityGuard(path: string): void {
  let value: unknown;
  try {
    value = JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    if (hasCode(error, "ENOENT")) {
      writeLegacyGuard(path);
      return;
    }
    throw new Error(
      `the legacy direct store ownership file is not a recognized PID lock; inspect it before retrying (${path})`,
      { cause: error },
    );
  }
  if (isLegacyGuard(value)) return;
  if (!isLegacyOwner(value)) {
    throw new Error(
      `the legacy direct store ownership file is not a recognized PID lock; inspect it before retrying (${path})`,
    );
  }
  writeLegacyGuard(path);
}

function writeLegacyGuard(path: string): void {
  const candidate = `${path}.${randomUUID()}.candidate`;
  try {
    writeFileSync(candidate, `${JSON.stringify(LEGACY_GUARD)}\n`, {
      encoding: "utf8",
      flag: "wx",
      mode: 0o600,
    });
    renameSync(candidate, path);
  } catch (error) {
    try {
      unlinkSync(candidate);
    } catch {
      // The unique candidate is inert. Preserve the install failure that explains why startup
      // stopped; a later offline inspection may remove the candidate if cleanup itself failed.
    }
    throw error;
  }
}

function isLegacyGuard(value: unknown): boolean {
  return (
    typeof value === "object" &&
    value !== null &&
    Object.keys(value).length === 1 &&
    (value as { format?: unknown }).format === LEGACY_GUARD.format
  );
}

function isLegacyOwner(value: unknown): value is LegacyLockOwner {
  return (
    typeof value === "object" &&
    value !== null &&
    Number.isSafeInteger((value as Partial<LegacyLockOwner>).pid) &&
    ((value as Partial<LegacyLockOwner>).pid ?? 0) > 0 &&
    typeof (value as Partial<LegacyLockOwner>).nonce === "string" &&
    (value as Partial<LegacyLockOwner>).nonce !== ""
  );
}

function isSqliteBusy(error: unknown): boolean {
  if (
    typeof error !== "object" ||
    error === null ||
    !("errcode" in error) ||
    typeof error.errcode !== "number"
  ) {
    return false;
  }
  const primary = error.errcode & 0xff;
  return primary === 5 || primary === 6;
}

function hasCode(error: unknown, code: string): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    error.code === code
  );
}
