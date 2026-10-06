/**
 * `pnpm release:bump` moves one component, everywhere its version is written, and nothing else
 * (SEE-182). Runs against a scratch repository, and proves a bump leaves one the release checks pass.
 */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  cpSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  symlinkSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve, sep } from "node:path";
import test from "node:test";

import { bump, parseArguments } from "./release-bump.mjs";
import { loadManifest, releasePlan } from "./release-plan.mjs";

const ROOT = resolve(import.meta.dirname, "..");
const COMMIT = "0123456789abcdef0123456789abcdef01234567";

/**
 * A scratch repository: a real copy of every file a bump edits, and a symlink to everything else,
 * so the release checks can run against it without a bump ever writing through to the checkout.
 */
function scratch() {
  const root = mkdtempSync(join(tmpdir(), "release-bump-"));
  const files = ["release/components.json"];
  for (const component of loadManifest().components) {
    if (component.npm) files.push(`${component.directory}/package.json`);
    files.push(...component.versionConstants);
  }
  const copied = new Set(files.map((file) => join(ROOT, file)));
  const parents = new Set(
    [...copied].flatMap((file) => {
      const chain = [];
      for (let path = dirname(file); path !== ROOT; path = dirname(path)) {
        chain.push(path);
      }
      return chain;
    }),
  );
  const mirror = (directory) => {
    for (const entry of readdirSync(directory)) {
      if (directory === ROOT && entry === ".git") continue;
      const source = join(directory, entry);
      const target = join(root, relative(ROOT, source));
      if (copied.has(source)) cpSync(source, target);
      else if (parents.has(source)) {
        mkdirSync(target);
        mirror(source);
      } else symlinkSync(source, target);
    }
  };
  mirror(ROOT);
  return root;
}

const read = (root, file) => readFileSync(join(root, file), "utf8");
const component = (root, id) =>
  loadManifest(root).components.find((entry) => entry.id === id);
const versions = (root) =>
  Object.fromEntries(
    loadManifest(root).components.map(({ id, version }) => [id, version]),
  );

// Versions derived from wherever a component starts, so these tests hold at any release.
const core = (version) => version.split("-")[0].split(".").map(Number);
const nextPatch = (version) => {
  const [major, minor, patch] = core(version);
  return `${major}.${minor}.${patch + 1}`;
};
const nextMinor = (version) => {
  const [major, minor] = core(version);
  return `${major}.${minor + 1}.0`;
};
const nextMajor = (version) => `${core(version)[0] + 1}.0.0`;

test("bumping the SDK moves the SDK alone", () => {
  const root = scratch();
  const before = versions(root);
  const to = nextPatch(before["server-sdk"]);
  const result = bump({ id: "server-sdk", version: to }, root);
  assert.deepEqual(result.changed, [
    "release/components.json",
    "packages/server-sdk/package.json",
  ]);
  assert.deepEqual(versions(root), { ...before, "server-sdk": to });
  assert.match(
    read(root, "packages/server-sdk/package.json"),
    new RegExp(`"version": "${to}"`),
  );
});

test("bumping a server moves its manifest, package and runtime constant", () => {
  const root = scratch();
  const to = `${nextMinor(component(root, "mcp-server").version)}-rc.1`;
  bump({ id: "mcp-server", version: to }, root);
  assert.equal(component(root, "mcp-server").version, to);
  assert.match(
    read(root, "servers/mcp-server/src/version.ts"),
    new RegExp(`VERSION = "${to}"`),
  );
  assert.match(
    read(root, "servers/mcp-server/package.json"),
    new RegExp(`"version": "${to}"`),
  );
});

test("bumping Android advances the versionCode and records the one it replaces", () => {
  const root = scratch();
  const { version, android: start } = component(root, "android");
  const first = nextPatch(version);
  bump({ id: "android", version: first }, root);
  assert.deepEqual(component(root, "android").android, {
    ...start,
    versionCode: start.versionCode + 1,
    previousVersionCode: start.versionCode,
  });
  const chosen = start.versionCode + 10;
  bump({ id: "android", version: nextPatch(first), versionCode: chosen }, root);
  assert.equal(component(root, "android").android.versionCode, chosen);
  assert.equal(
    component(root, "android").android.previousVersionCode,
    start.versionCode + 1,
  );
  assert.throws(
    () =>
      bump(
        {
          id: "android",
          version: nextPatch(nextPatch(first)),
          versionCode: chosen,
        },
        root,
      ),
    new RegExp(`above the current ${chosen}`),
  );
});

test("a bump keeps the manifest's formatting", () => {
  const root = scratch();
  const before = read(root, "release/components.json");
  bump(
    { id: "gateway", version: nextPatch(component(root, "gateway").version) },
    root,
  );
  const after = read(root, "release/components.json");
  const changed = before
    .split("\n")
    .filter((line, index) => line !== after.split("\n")[index]);
  assert.equal(changed.length, 1);
});

test("a bump refuses a lower, equal, malformed or unknown version", () => {
  const root = scratch();
  // A controlled baseline: the gateway at the next major, whatever it was before.
  const baseline = nextMajor(component(root, "gateway").version);
  bump({ id: "gateway", version: baseline }, root);
  assert.throws(
    () => bump({ id: "gateway", version: baseline }, root),
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
  const lower = `${baseline}-rc.1`;
  assert.throws(() => bump({ id: "gateway", version: lower }, root), /lower/);
  bump({ id: "gateway", version: lower, allowLower: true }, root);
  assert.equal(component(root, "gateway").version, lower);
  assert.throws(
    () =>
      bump(
        { id: "gateway", version: nextPatch(baseline), versionCode: 4 },
        root,
      ),
    /no versionCode/,
  );
});

// The regression the release checks exist for: a legitimate bump of one component leaves a
// repository the release checks pass, with every other component where it was.
for (const id of ["gateway", "mcp-server", "android"]) {
  test(`bumping ${id} alone passes the release checks`, () => {
    const root = scratch();
    const before = loadManifest(root);
    const to = nextPatch(component(root, id).version);
    bump({ id, version: to }, root);
    execFileSync(
      process.execPath,
      [join(ROOT, "scripts/check-release.mjs"), root],
      { stdio: "pipe" },
    );
    const after = loadManifest(root);
    for (const entry of before.components) {
      const now = after.components.find((other) => other.id === entry.id);
      if (entry.id === id) continue;
      assert.deepEqual(now, entry, `${entry.id} changed`);
    }
    const resolved = releasePlan({ COMMIT, TAG: `${id}-v${to}` }, after);
    assert.equal(resolved.version, to);
    assert.throws(
      () =>
        releasePlan(
          { COMMIT, TAG: `${id}-v${component(ROOT, id).version}` },
          after,
        ),
      /pins/,
    );
  });
}

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
