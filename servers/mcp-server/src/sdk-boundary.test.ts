import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";

const SOURCE = fileURLToPath(new URL(".", import.meta.url));

describe("MCP host to Direct Server SDK boundary", () => {
  it("uses only the package's documented public entry points", () => {
    const violations: string[] = [];
    for (const file of productionFiles(SOURCE)) {
      const source = readFileSync(file, "utf8");
      if (/server-sdk\/(?:src|dist)\//.test(source)) {
        violations.push(
          `${relative(SOURCE, file)} imports SDK build/source internals`,
        );
      }
      for (const match of source.matchAll(
        /from\s+["']([^"']*server-sdk[^"']*)["']/g,
      )) {
        const specifier = match[1] ?? "";
        if (
          specifier !== "@seeker_agent_connect/server-sdk" &&
          specifier !== "@seeker_agent_connect/server-sdk/protocol"
        ) {
          violations.push(
            `${relative(SOURCE, file)} imports unsupported SDK entry ${specifier}`,
          );
        }
      }
    }
    assert.deepEqual(violations, []);
  });

  it("contains no duplicate durable request engine", () => {
    const declarations = productionFiles(SOURCE).filter((file) =>
      /\bclass\s+(?:RequestStore|PairingStore|UpdateStore)\b/.test(
        readFileSync(file, "utf8"),
      ),
    );
    assert.deepEqual(declarations, []);
  });
});

function productionFiles(directory: string): string[] {
  const files: string[] = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.name === "testing") continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...productionFiles(path));
    else if (entry.name.endsWith(".ts") && !entry.name.endsWith(".test.ts")) {
      files.push(path);
    }
  }
  return files;
}
