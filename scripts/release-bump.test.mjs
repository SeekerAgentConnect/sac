/**
 * `pnpm release:bump` moves one component, everywhere its version is written, and nothing else
 * (SEE-182). Runs against a scratch copy of the files it edits.
 */
import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";

import { bump, parseArguments } from "./release-bump.mjs";

const ROOT = resolve(import.meta.dirname, "..");

function scratch() {
  const root = mkdtempSync(join(tmpdir(), "release-bump-"));
  const manifest = JSON.parse(
    readFileSync(join(ROOT, "release/components.json"), "utf8"),
  );
  const files = ["release/components.json"];
  for (const component of manifest.components) {
    if (component.npm) files.push(`${component.directory}/package.json`);
    files.push(...component.versionConstants);
  }
  for (const file of files) {
    mkdirSync(dirname(join(root, file)), { recursive: true });
    cpSync(join(ROOT, file), join(root, file));
  }
  return root;
}

const read = (root, file) => readFileSync(join(root, file), "utf8");
const component = (root, id) =>
  JSON.parse(read(root, "release/components.json")).components.find(
    (entry) => entry.id === id,
  );

test("bumping the SDK moves the SDK alone", () => {
  const root = scratch();
  const result = bump({ id: "server-sdk", version: "0.0.2" }, root);
  assert.deepEqual(result.changed, [
    "release/components.json",
    "packages/server-sdk/package.json",
  ]);
  assert.equal(component(root, "server-sdk").version, "0.0.2");
  assert.equal(component(root, "mcp-server").version, "0.0.1");
  assert.match(
    read(root, "packages/server-sdk/package.json"),
    /"version": "0.0.2"/,
  );
});

test("bumping a server moves its manifest, package and runtime constant", () => {
  const root = scratch();
  bump({ id: "mcp-server", version: "0.1.0-rc.1" }, root);
  assert.equal(component(root, "mcp-server").version, "0.1.0-rc.1");
  assert.match(
    read(root, "servers/mcp-server/src/version.ts"),
    /VERSION = "0.1.0-rc.1"/,
  );
  assert.match(
    read(root, "servers/mcp-server/package.json"),
    /"version": "0.1.0-rc.1"/,
  );
});

test("bumping Android advances the versionCode and records the one it replaces", () => {
  const root = scratch();
  bump({ id: "android", version: "0.0.2" }, root);
  assert.deepEqual(component(root, "android").android, {
    ...component(root, "android").android,
    versionCode: 3,
    previousVersionCode: 2,
  });
  bump({ id: "android", version: "0.0.3", versionCode: 10 }, root);
  assert.equal(component(root, "android").android.versionCode, 10);
  assert.equal(component(root, "android").android.previousVersionCode, 3);
  assert.throws(
    () => bump({ id: "android", version: "0.0.4", versionCode: 10 }, root),
    /above the current 10/,
  );
});

test("a bump keeps the manifest's formatting", () => {
  const root = scratch();
  const before = read(root, "release/components.json");
  bump({ id: "gateway", version: "0.0.2" }, root);
  const after = read(root, "release/components.json");
  const changed = before
    .split("\n")
    .filter((line, index) => line !== after.split("\n")[index]);
  assert.equal(changed.length, 1);
});

test("a bump refuses a lower, equal, malformed or unknown version", () => {
  const root = scratch();
  assert.throws(
    () => bump({ id: "gateway", version: "0.0.1" }, root),
    /already/,
  );
  assert.throws(
    () => bump({ id: "gateway", version: "1.0" }, root),
    /not X.Y.Z/,
  );
  assert.throws(
    () => bump({ id: "nope", version: "1.0.0" }, root),
    /not a component/,
  );
  assert.throws(
    () => bump({ id: "gateway", version: "0.0.1-rc.1" }, root),
    /lower/,
  );
  bump({ id: "gateway", version: "0.0.1-rc.1", allowLower: true }, root);
  assert.equal(component(root, "gateway").version, "0.0.1-rc.1");
  assert.throws(
    () => bump({ id: "gateway", version: "0.0.2", versionCode: 4 }, root),
    /no versionCode/,
  );
});

test("the command line", () => {
  assert.deepEqual(
    parseArguments(["android", "0.0.2", "--version-code", "7"]),
    {
      id: "android",
      version: "0.0.2",
      allowLower: false,
      versionCode: 7,
    },
  );
  assert.throws(() => parseArguments(["android"]), /usage/);
  assert.throws(
    () => parseArguments(["a", "1.0.0", "--force"]),
    /unknown option/,
  );
});
