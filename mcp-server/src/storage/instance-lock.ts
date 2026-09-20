/**
 * One running MCP server owns one durable Direct SDK store. Operator pairing commands may still
 * open the SQLite database while it runs, but a second application process must not become a
 * competing request/update owner on another listener.
 */
import { randomUUID } from "node:crypto";
import {
  linkSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, join, resolve } from "node:path";

export interface InstanceLock {
  readonly path?: string;
  release(): void;
}

interface LockOwner {
  readonly pid: number;
  readonly nonce: string;
}

/** Acquires an ownership-safe process lock beside the canonical database path. */
export function acquireInstanceLock(databasePath: string): InstanceLock {
  if (databasePath === ":memory:") return { release: () => undefined };

  const absolute = resolve(databasePath);
  mkdirSync(dirname(absolute), { recursive: true });
  const canonical = join(realpathSync(dirname(absolute)), basename(absolute));
  const path = `${canonical}.mcp-server.lock`;
  const owner: LockOwner = { pid: process.pid, nonce: randomUUID() };
  const candidate = `${path}.${owner.nonce}.candidate`;
  writeFileSync(candidate, `${JSON.stringify(owner)}\n`, {
    encoding: "utf8",
    flag: "wx",
    mode: 0o600,
  });

  try {
    for (;;) {
      try {
        // The fully written candidate is linked atomically, so nobody can observe a half-written
        // owner record and incorrectly reclaim a live process's lock.
        linkSync(candidate, path);
        break;
      } catch (error) {
        if (!hasCode(error, "EEXIST")) throw error;
        const current = readOwner(path);
        if (current === undefined || processIsRunning(current.pid)) {
          throw new Error(
            `the direct store is already in use by another MCP server (${path})`,
            { cause: error },
          );
        }
        // Rename first: two reclaimers cannot both delete whichever lock happens to be at `path`.
        const stale = `${path}.${owner.nonce}.stale`;
        try {
          renameSync(path, stale);
        } catch (renameError) {
          if (hasCode(renameError, "ENOENT")) continue;
          throw renameError;
        }
        unlinkSync(stale);
      }
    }
  } finally {
    unlinkSync(candidate);
  }

  let released = false;
  return {
    path,
    release() {
      if (released) return;
      released = true;
      const current = readOwner(path);
      if (current?.nonce !== owner.nonce) return;
      try {
        unlinkSync(path);
      } catch (error) {
        if (!hasCode(error, "ENOENT")) throw error;
      }
    },
  };
}

function readOwner(path: string): LockOwner | undefined {
  let value: unknown;
  try {
    value = JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    if (hasCode(error, "ENOENT")) return undefined;
    return undefined;
  }
  if (
    typeof value !== "object" ||
    value === null ||
    !Number.isSafeInteger((value as Partial<LockOwner>).pid) ||
    ((value as Partial<LockOwner>).pid ?? 0) <= 0 ||
    typeof (value as Partial<LockOwner>).nonce !== "string"
  ) {
    return undefined;
  }
  return value as LockOwner;
}

function processIsRunning(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (hasCode(error, "ESRCH")) return false;
    if (hasCode(error, "EPERM")) return true;
    throw error;
  }
}

function hasCode(error: unknown, code: string): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    error.code === code
  );
}
