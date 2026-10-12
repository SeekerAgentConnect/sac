/**
 * Release planning on top of Nx Release (SEE-187): file boundaries, vendored SDK releases,
 * Android versionCode stability, and the guarantee that publish/sign are not cacheable.
 */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import {
  cpSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import test from "node:test";

import { loadManifest, releasePlan } from "../release-plan.mjs";
import { classifyCommits, ownersOf, rawBump } from "./boundaries.mjs";
import { renderEntry } from "./changelog.mjs";
import { assertReleasePlan } from "./publish-plan.mjs";
import { planRelease } from "./plan-release.mjs";
import { syncProjectVersionAt } from "./version-sync.mjs";

const ROOT = resolve(import.meta.dirname, "../..");
const require = createRequire(import.meta.url);
const { deriveNewSemverVersion } = require(
  join(ROOT, "node_modules/nx/dist/src/command-line/release/utils/semver.js"),
);
const COMMIT = "0123456789abcdef0123456789abcdef01234567";

function scratch() {
  const root = mkdtempSync(join(tmpdir(), "nx-version-sync-"));
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
      if (
        directory === ROOT &&
        (entry === ".git" || entry === "node_modules")
      ) {
        continue;
      }
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
const core = (version) => version.split("-")[0].split(".").map(Number);
const nextPatch = (version) => {
  const [major, minor, patch] = core(version);
  return `${major}.${minor}.${patch + 1}`;
};

test("a gateway file is not a Centrifugo release, and the broker files are not a gateway release", () => {
  assert.deepEqual(ownersOf("services/gateway/internal/gateway/server.go"), [
    "gateway",
  ]);
  assert.deepEqual(ownersOf("services/gateway/Dockerfile"), ["gateway"]);
  assert.deepEqual(ownersOf("services/gateway/centrifugo.yaml"), [
    "gateway-centrifugo",
  ]);
  assert.deepEqual(ownersOf("services/gateway/Dockerfile.centrifugo"), [
    "gateway-centrifugo",
  ]);
  assert.deepEqual(ownersOf("services/gateway/project.json"), []);
  assert.deepEqual(ownersOf("services/gateway/CHANGELOG.md"), []);

  const gatewayOnly = classifyCommits([
    {
      hash: "a".repeat(40),
      message: "feat(gateway): add a route",
      body: "",
      files: ["services/gateway/internal/gateway/server.go"],
    },
  ]);
  assert.equal(gatewayOnly.get("gateway")?.bump, "minor");
  assert.equal(gatewayOnly.has("gateway-centrifugo"), false);

  const brokerOnly = classifyCommits([
    {
      hash: "b".repeat(40),
      message: "fix(gateway-centrifugo): pin a setting",
      body: "",
      files: [
        "services/gateway/centrifugo.yaml",
        "services/gateway/Dockerfile.centrifugo",
      ],
    },
  ]);
  assert.equal(brokerOnly.has("gateway"), false);
  assert.equal(brokerOnly.get("gateway-centrifugo")?.bump, "patch");
});

test("an SDK change releases the servers that vendor it", () => {
  const plan = classifyCommits([
    {
      hash: "c".repeat(40),
      message: "fix(server-sdk): correct a cursor",
      body: "",
      files: ["packages/server-sdk/src/index.ts"],
    },
  ]);
  assert.equal(plan.get("server-sdk")?.bump, "patch");
  assert.equal(plan.get("mcp-server")?.bump, "patch");
  assert.equal(plan.get("mcp-server")?.vendored, true);
  assert.equal(plan.get("mcp-skr-staking")?.vendored, true);
  assert.equal(plan.has("gateway"), false);
  assert.equal(plan.has("android"), false);
});

test("a breaking change before 1.0 is a minor, and a feature is a patch", () => {
  const options = { adjustSemverBumpsForZeroMajorVersion: true };
  assert.equal(rawBump("feat(server-sdk): add a call", ""), "minor");
  assert.equal(
    rawBump("fix!: drop a field", "BREAKING CHANGE: drop a field"),
    "major",
  );
  assert.equal(
    deriveNewSemverVersion("0.0.2", "minor", undefined, options),
    "0.0.3",
  );
  assert.equal(
    deriveNewSemverVersion("0.0.2", "major", undefined, options),
    "0.1.0",
  );
  assert.equal(
    deriveNewSemverVersion("0.0.2", "prepatch", "rc", options),
    "0.0.3-rc.0",
  );
  assert.equal(
    deriveNewSemverVersion("1.2.3", "minor", undefined, options),
    "1.3.0",
  );
  assert.equal(
    deriveNewSemverVersion("1.2.3", "major", undefined, options),
    "2.0.0",
  );
});

test("syncing a version writes the same places the old bump did, and only those", () => {
  const root = scratch();
  const before = versions(root);
  const to = nextPatch(before["server-sdk"]);
  const result = syncProjectVersionAt(root, "server-sdk", to);
  assert.deepEqual(result.changed, [
    "release/components.json",
    "packages/server-sdk/package.json",
  ]);
  assert.deepEqual(versions(root), { ...before, "server-sdk": to });

  const server = nextPatch(before["mcp-server"]);
  syncProjectVersionAt(root, "mcp-server", server);
  assert.match(
    read(root, "servers/mcp-server/src/version.ts"),
    new RegExp(`VERSION = "${server}"`),
  );
  assert.equal(versions(root)["mcp-skr-staking"], before["mcp-skr-staking"]);
});

test("Android versionCode advances once per planned version and not on a retry", () => {
  const first = scratch();
  const start = component(first, "android");
  const to = nextPatch(start.version);
  syncProjectVersionAt(first, "android", to);
  const code = component(first, "android").android.versionCode;
  assert.equal(code, start.android.versionCode + 1);
  assert.equal(
    component(first, "android").android.previousVersionCode,
    start.android.versionCode,
  );
  const again = syncProjectVersionAt(first, "android", to);
  assert.equal(again.unchanged, true);
  assert.equal(component(first, "android").android.versionCode, code);

  const regenerated = scratch();
  syncProjectVersionAt(regenerated, "android", to);
  assert.equal(component(regenerated, "android").android.versionCode, code);
});

test("a sync keeps the manifest formatting and passes the release checks", () => {
  const root = scratch();
  const before = read(root, "release/components.json");
  const id = "gateway";
  const to = nextPatch(component(root, id).version);
  syncProjectVersionAt(root, id, to);
  const after = read(root, "release/components.json");
  const changed = before
    .split("\n")
    .filter((line, index) => line !== after.split("\n")[index]);
  assert.equal(changed.length, 1);
  execFileSync(
    process.execPath,
    [join(ROOT, "scripts/check-release.mjs"), root],
    {
      stdio: "pipe",
    },
  );
  const resolved = releasePlan(
    { COMMIT, TAG: `${id}-v${to}` },
    loadManifest(root),
  );
  assert.equal(resolved.version, to);
});

test("the migration commit's ancestors have nothing waiting at the published tags", () => {
  const plan = planRelease(ROOT, { to: "server-sdk-v0.0.2" });
  assert.deepEqual(plan.pendingPublication, []);
  assert.deepEqual(plan.projects, []);
});

test("a cached task cannot stand in for publish or sign", () => {
  const nx = ["exec", "nx"];
  const env = {
    ...process.env,
    NX_DAEMON: "false",
    NX_TUI: "false",
    NX_NO_CLOUD: "true",
    CI: "true",
  };
  const run = (target, extra = []) =>
    execFileSync("pnpm", [...nx, "run", target, ...extra], {
      cwd: ROOT,
      encoding: "utf8",
      env,
    });
  run("nx-probes:probe", ["--skip-nx-cache"]);
  const cached = run("nx-probes:probe");
  assert.match(cached, /cache|Nx read the output/i);

  const config = JSON.parse(read(ROOT, "nx.json"));
  assert.equal(config.targetDefaults.publish.cache, false);
  assert.equal(config.targetDefaults.sign.cache, false);
  assert.equal(config.targetDefaults["nx-release-publish"].cache, false);
  assert.equal(config.neverConnectToCloud, true);

  for (const target of [
    "nx-probes:publish",
    "nx-probes:sign",
    "android:sign",
  ]) {
    run(target, ["--skip-nx-cache"]);
    const second = run(target);
    assert.doesNotMatch(
      second,
      /local cache|remote cache|Nx read the output from the cache/i,
    );
    assert.match(second, /publish-ok|sign-ok/);
  }
});

test("changelog entries name the component's own commits", async () => {
  const entry = await renderEntry("gateway", "0.0.3", {
    commits: [
      {
        hash: "d".repeat(40),
        message: "feat(gateway): add a route",
        body: "",
      },
    ],
    vendored: false,
  });
  assert.match(entry, /0\.0\.3/);
  assert.match(entry, /add a route/);
});

test("the publish plan refuses a version the manifest does not pin", () => {
  const root = mkdtempSync(join(tmpdir(), "nx-plan-"));
  mkdirSync(join(root, "release"));
  cpSync(
    join(ROOT, "release/components.json"),
    join(root, "release/components.json"),
  );
  const manifest = loadManifest(root);
  writeFileSync(
    join(root, "release/plan.json"),
    JSON.stringify({
      channel: "stable",
      components: [
        {
          id: "gateway",
          version: manifest.components.find((item) => item.id === "gateway")
            .version,
          tag: `gateway-v${manifest.components.find((item) => item.id === "gateway").version}`,
        },
      ],
    }),
  );
  const ok = assertReleasePlan(root);
  assert.equal(ok.components[0].id, "gateway");
  const plan = JSON.parse(read(root, "release/plan.json"));
  plan.components[0].version = "9.9.9";
  writeFileSync(join(root, "release/plan.json"), JSON.stringify(plan));
  assert.throws(() => assertReleasePlan(root), /9\.9\.9/);
});
