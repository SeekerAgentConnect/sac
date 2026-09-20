import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const SOURCE = join(ROOT, "src");

interface PackageManifest {
  readonly exports: Record<string, unknown>;
  readonly files: string[];
  readonly private: boolean;
  readonly dependencies: Record<string, string>;
}

describe("Direct Server SDK package boundary", () => {
  it("has exact public exports and no mandatory host integration", () => {
    const manifest = JSON.parse(
      readFileSync(join(ROOT, "package.json"), "utf8"),
    ) as unknown as PackageManifest;
    assert.deepEqual(Object.keys(manifest.exports).sort(), [".", "./protocol"]);
    assert.deepEqual(manifest.files, ["dist", "README.md", "LICENSE"]);
    assert.equal(manifest.private, true);
    assert.deepEqual(Object.keys(manifest.dependencies).sort(), [
      "@bufbuild/protobuf",
      "@connectrpc/connect",
      "@connectrpc/connect-node",
    ]);
  });

  it("does not import MCP, Solana, Firebase, host config or environment loading", () => {
    const production = files(SOURCE).filter(
      (file) =>
        !file.endsWith(".test.ts") &&
        !file.includes(`${join("src", "testing")}`),
    );
    const source = production
      .map((file) => readFileSync(file, "utf8"))
      .join("\n");
    for (const forbidden of [
      "@modelcontextprotocol/",
      "@solana/",
      "firebase-admin",
      "process.env",
      "dotenv",
      "node:process",
    ]) {
      assert.equal(source.includes(forbidden), false, forbidden);
    }
  });
});

function files(directory: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) found.push(...files(path));
    else if (entry.name.endsWith(".ts")) found.push(path);
  }
  return found;
}
