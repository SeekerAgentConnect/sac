/**
 * Stage boundaries on the Node side (AGENTS.md). There's no wallet library, and nothing creates
 * keys. The sidecar stores durable requests (SAW-010): only src/storage/ imports the file system
 * or SQLite, and only it runs SQL. These checks fail when that changes before the stage that changes it on
 * purpose.
 */
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const SRC = fileURLToPath(new URL("./", import.meta.url));
const STORAGE_IMPORT = /from "(node:)?(fs|fs\/promises|sqlite)"/;

/** The sidecar's shipped sources: no tests, test helpers, or generated code. */
function shippedSources(): string[] {
  return readdirSync(SRC, { recursive: true, encoding: "utf8" })
    .filter((path) => path.endsWith(".ts") && !path.endsWith(".test.ts"))
    .filter((path) => !["testing", "gen"].includes(path.split("/")[0] ?? ""))
    .map((path) => join(SRC, path));
}

describe("stage boundary", () => {
  it("installs no wallet library", () => {
    const lockfile = readFileSync(join(ROOT, "pnpm-lock.yaml"), "utf8");
    assert.doesNotMatch(
      lockfile,
      /['\s/](@solana|@solana-mobile|@coral-xyz|@metaplex-foundation)\//,
    );
  });

  it("creates no keys in the sidecar", () => {
    const forbidden =
      /\b(generateKeyPair|generateKeyPairSync|createPrivateKey)\b/;
    const sources = shippedSources();
    assert.ok(sources.length > 5, "found the sidecar's sources");
    const hits = sources.filter((file) =>
      forbidden.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      hits.map((file) => relative(ROOT, file)),
      [],
    );
  });

  it("signs nothing in the sidecar, and only verifies", () => {
    // SAW-016 lets the sidecar check an Ed25519 signature the owner's wallet made. Making one is
    // a different thing, and stays impossible here: there is no key to make it with.
    const signing = /\bcreateSign\b|\bsign\s*\(/;
    const hits = shippedSources().filter((file) =>
      signing.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      hits.map((file) => relative(ROOT, file)),
      [],
    );
    assert.match(
      readFileSync(join(SRC, "requests/signature.ts"), "utf8"),
      /\bverify\s*\(/,
      "requests/signature.ts verifies signatures, and that is all it does",
    );
  });

  it("imports the file system and SQLite only in src/storage", () => {
    const sources = shippedSources();
    const outside = sources.filter(
      (file) =>
        !relative(SRC, file).startsWith("storage/") &&
        STORAGE_IMPORT.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      outside.map((file) => relative(ROOT, file)),
      [],
    );
    assert.match(
      readFileSync(join(SRC, "storage/database.ts"), "utf8"),
      STORAGE_IMPORT,
      "storage/database.ts is where the database is opened",
    );
  });

  it("runs SQL only in src/storage", () => {
    // Statements, queries, and transactions stay in storage's modules; the rest of the sidecar
    // calls their APIs.
    const sql = /\.prepare\(|\btransaction\(|\.exec\(\s*["'`]/;
    const outside = shippedSources().filter(
      (file) =>
        !relative(SRC, file).startsWith("storage/") &&
        sql.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      outside.map((file) => relative(ROOT, file)),
      [],
    );
    assert.match(
      readFileSync(join(SRC, "storage/request-store.ts"), "utf8"),
      sql,
      "the request store runs its SQL in storage",
    );
  });
});
