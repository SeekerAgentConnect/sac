/**
 * Stage 1 is a wallet-free hello world (AGENTS.md). These checks fail when the Node side gains a
 * wallet library, key generation, or storage for commands before the stage that adds it on purpose.
 */
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const SRC = fileURLToPath(new URL("./", import.meta.url));

/** The sidecar's shipped sources: no tests, test helpers, or generated code. */
function shippedSources(): string[] {
  return readdirSync(SRC, { recursive: true, encoding: "utf8" })
    .filter((path) => path.endsWith(".ts") && !path.endsWith(".test.ts"))
    .filter((path) => !["testing", "gen"].includes(path.split("/")[0] ?? ""))
    .map((path) => join(SRC, path));
}

describe("Stage 1 boundary", () => {
  it("installs no wallet library", () => {
    const lockfile = readFileSync(join(ROOT, "pnpm-lock.yaml"), "utf8");
    assert.doesNotMatch(
      lockfile,
      /['\s/](@solana|@solana-mobile|@coral-xyz|@metaplex-foundation)\//,
    );
  });

  it("stores nothing and creates no keys in the sidecar", () => {
    const forbidden =
      /from "(node:)?(fs|fs\/promises|sqlite)"|\b(generateKeyPair|generateKeyPairSync|createPrivateKey)\b/;
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
});
