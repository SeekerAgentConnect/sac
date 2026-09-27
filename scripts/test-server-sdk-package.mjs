/**
 * Audit the exact Direct Server SDK tarball, install it outside this workspace, and exercise only
 * its documented package entry points. This script never publishes or contacts a registry for the
 * SDK itself; npm is used because npm is the artifact format SEE-131 prepares.
 */
import assert from "node:assert/strict";
import {
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../", import.meta.url));
const SDK = join(ROOT, "packages", "server-sdk");
const scratch = mkdtempSync(join(tmpdir(), "seeker-server-sdk-package-"));
const artifacts = join(scratch, "artifacts");
const consumer = join(scratch, "consumer");

function main() {
  try {
    mkdirSync(artifacts);
    mkdirSync(consumer);
    run("pnpm", ["run", "build"], SDK);

    const dryRun = npmPack(["--dry-run", "--json"], SDK)[0];
    const packed = npmPack(["--json", "--pack-destination", artifacts], SDK)[0];
    assert.ok(dryRun && packed, "npm pack returned one package");
    const dryFiles = dryRun.files.map(({ path }) => path).sort();
    const packedFiles = packed.files.map(({ path }) => path).sort();
    assert.deepEqual(packedFiles, dryFiles, "dry-run and real tarball differ");
    assert.deepEqual(
      packedFiles.filter(allowedPackageFile),
      packedFiles,
      "the tarball contains a file outside LICENSE, README, package.json, and dist",
    );
    for (const path of packedFiles) {
      assert.doesNotMatch(
        path,
        /(^|\/)(src|test|tests|testing|fixtures)(\/|$)|\.test\.|\.map$|\.tsx$|(?<!\.d)\.ts$|\.sql$|\.db$|\.sqlite$|pnpm-lock|tsconfig|workspace|Docker|deploy|gateway|proposal|request\/v2|onboarding/i,
        `unexpected private or development artifact: ${path}`,
      );
    }

    const tarball = join(artifacts, packed.filename);
    run("tar", ["-xzf", tarball, "-C", scratch], ROOT);
    const packedManifest = JSON.parse(
      readFileSync(join(scratch, "package/package.json"), "utf8"),
    );
    assert.deepEqual(Object.keys(packedManifest.exports).sort(), [
      ".",
      "./protocol",
    ]);
    // What npm checks before it will accept a scoped package, and what the release manifest says
    // this version is (SEE-168). Hardcoding the version here would let a bump pass unnoticed.
    assert.equal(packedManifest.name, "@seeker_agent_connect/server-sdk");
    assert.equal(packedManifest.version, releaseVersion("server-sdk"));
    assert.notEqual(packedManifest.private, true);
    assert.deepEqual(packedManifest.publishConfig, { access: "public" });
    for (const field of [
      "description",
      "license",
      "homepage",
      "bugs",
      "repository",
      "author",
      "engines",
    ]) {
      assert.ok(
        packedManifest[field],
        `the published manifest is missing ${field}`,
      );
    }
    assert.deepEqual(Object.keys(packedManifest.dependencies).sort(), [
      "@bufbuild/protobuf",
      "@connectrpc/connect",
      "@connectrpc/connect-node",
    ]);
    assert.doesNotMatch(
      JSON.stringify(packedManifest),
      /workspace:|catalog:|link:|file:/,
      "packed metadata contains a workspace-only dependency specifier",
    );
    for (const path of listFiles(join(scratch, "package"))) {
      if (!/\.(?:js|d\.ts|json|md)$/.test(path)) continue;
      assert.doesNotMatch(
        readFileSync(join(scratch, "package", path), "utf8"),
        new RegExp(escapeRegExp(ROOT)),
        `packed ${path} contains the checkout's absolute path`,
      );
    }

    writeFileSync(
      join(consumer, "package.json"),
      JSON.stringify({
        name: "sdk-tarball-consumer",
        private: true,
        type: "module",
      }),
    );
    run(
      "npm",
      [
        "install",
        "--ignore-scripts",
        "--no-audit",
        "--no-fund",
        "--package-lock=false",
        tarball,
        "typescript@6.0.3",
        "@types/node@24.13.4",
        "@bufbuild/protobuf@2.14.1",
        "@connectrpc/connect@2.2.0",
        "@connectrpc/connect-node@2.2.0",
      ],
      consumer,
    );

    const installed = join(
      consumer,
      "node_modules/@seeker_agent_connect/server-sdk",
    );
    assert.equal(lstatSync(installed).isSymbolicLink(), false);
    assert.ok(
      realpathSync(installed).startsWith(
        realpathSync(join(consumer, "node_modules")),
      ),
      "the installed package resolves outside the isolated project's node_modules",
    );
    assert.equal(
      realpathSync(installed).startsWith(realpathSync(ROOT)),
      false,
      "the isolated install links back into the monorepo",
    );

    writeFileSync(
      join(consumer, "tsconfig.json"),
      JSON.stringify({
        compilerOptions: {
          target: "es2024",
          module: "nodenext",
          strict: true,
          noEmit: true,
          types: ["node"],
          skipLibCheck: true,
        },
        include: ["consumer.mts"],
      }),
    );
    writeFileSync(join(consumer, "consumer.mts"), TYPE_CONSUMER);
    run("npx", ["tsc", "-p", "tsconfig.json"], consumer);

    writeFileSync(join(consumer, "import-side-effects.mjs"), IMPORT_CHECK);
    runWithTimeout("node", ["import-side-effects.mjs"], consumer, 10_000);

    writeFileSync(join(consumer, "lifecycle.mjs"), LIFECYCLE_CHECK);
    runWithTimeout("node", ["lifecycle.mjs"], consumer, 20_000);

    console.log(`SDK package audit passed: ${packed.filename}`);
    console.log(`Tarball files (${packedFiles.length}):`);
    for (const path of packedFiles) console.log(`  ${path}`);
    console.log(
      "Isolated typecheck, side-effect import, lifecycle, restart, and duplicate result: PASS",
    );
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

function releaseVersion(id) {
  const manifest = JSON.parse(
    readFileSync(join(ROOT, "release", "components.json"), "utf8"),
  );
  const component = manifest.components.find((entry) => entry.id === id);
  assert.ok(component, `release/components.json declares no component ${id}`);
  return component.version;
}

function npmPack(args, cwd) {
  const stdout = execFileSync("npm", ["pack", ...args], {
    cwd,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "inherit"],
  });
  return JSON.parse(stdout);
}

function run(command, args, cwd) {
  execFileSync(command, args, { cwd, stdio: "inherit" });
}

function runWithTimeout(command, args, cwd, timeout) {
  const result = spawnSync(command, args, {
    cwd,
    encoding: "utf8",
    timeout,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(
      `${command} ${args.join(" ")} failed (${String(result.status)}):\n${result.stdout}${result.stderr}`,
    );
  }
  process.stdout.write(result.stdout);
  process.stderr.write(result.stderr);
}

function allowedPackageFile(path) {
  return (
    ["LICENSE", "README.md", "package.json"].includes(path) ||
    path.startsWith("dist/")
  );
}

function listFiles(directory) {
  return readdirSync(directory, { recursive: true, encoding: "utf8" })
    .filter((path) => !lstatSync(join(directory, path)).isDirectory())
    .sort();
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

const TYPE_CONSUMER = `
import { create } from "@bufbuild/protobuf";
import {
  openDirectServer,
  privateRequest,
  type DirectServer,
} from "@seeker_agent_connect/server-sdk";
import {
  AckActionSchema,
  ActionSchema,
  RequestState,
} from "@seeker_agent_connect/server-sdk/protocol";

const action = create(ActionSchema, {
  kind: { case: "ack", value: create(AckActionSchema, { text: "Review" }) },
});
const options: Parameters<typeof openDirectServer>[0] = {
  databasePath: ":memory:",
  publicOrigin: "http://127.0.0.1:8080",
  requestTtlSeconds: 300,
  pendingLimit: 10,
  pairingTokenTtlSeconds: 600,
  liveCommandTimeoutSeconds: 30,
  log: () => undefined,
};
declare const direct: DirectServer;
const request = direct.requests.createRequest(privateRequest(action, "Review", "typecheck-1", 300));
const state: RequestState = request.request.state;
void options;
void state;
`;

const IMPORT_CHECK = `
import assert from "node:assert/strict";
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";

const empty = mkdtempSync(join(tmpdir(), "sdk-import-only-"));
const previous = process.cwd();
const signals = { SIGINT: process.listenerCount("SIGINT"), SIGTERM: process.listenerCount("SIGTERM") };
const resources = process.getActiveResourcesInfo().sort();
try {
  process.chdir(empty);
  await import("@seeker_agent_connect/server-sdk");
  await import("@seeker_agent_connect/server-sdk/protocol");
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(readdirSync(empty), [], "import created a file");
  assert.equal(process.listenerCount("SIGINT"), signals.SIGINT, "import registered SIGINT");
  assert.equal(process.listenerCount("SIGTERM"), signals.SIGTERM, "import registered SIGTERM");
  assert.deepEqual(process.getActiveResourcesInfo().sort(), resources, "import started a background resource");
  const listener = createServer();
  await new Promise((resolve, reject) => {
    listener.once("error", reject);
    listener.listen(0, "127.0.0.1", resolve);
  });
  await new Promise((resolve) => listener.close(resolve));
  console.log("Import side effects: none");
} finally {
  process.chdir(previous);
  rmSync(empty, { recursive: true, force: true });
}
`;

const LIFECYCLE_CHECK = `
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { create } from "@bufbuild/protobuf";
import { createClient } from "@connectrpc/connect";
import { createConnectTransport } from "@connectrpc/connect-node";
import { openDirectServer, privateRequest, startPhoneApi } from "@seeker_agent_connect/server-sdk";
import {
  AckActionSchema,
  ActionSchema,
  PairingService,
  RequestService,
  RequestState,
} from "@seeker_agent_connect/server-sdk/protocol";

const directory = mkdtempSync(join(tmpdir(), "sdk-installed-lifecycle-"));
const databasePath = join(directory, "direct.db");
let origin = "http://127.0.0.1:1";
const open = () => openDirectServer({
  databasePath,
  publicOrigin: () => origin,
  requestTtlSeconds: 86_400,
  pendingLimit: 100,
  pairingTokenTtlSeconds: 600,
  liveCommandTimeoutSeconds: 30,
  log: () => undefined,
});
const authorized = (service, baseUrl, token) => createClient(service, createConnectTransport({
  baseUrl,
  httpVersion: "1.1",
  interceptors: [(next) => (request) => {
    request.header.set("Authorization", \`Bearer \${token}\`);
    return next(request);
  }],
}));

try {
  const direct = open();
  const serverId = direct.serverId;
  const api = await startPhoneApi(direct, { host: "127.0.0.1", port: 0 });
  origin = api.url;
  const issued = direct.pairing.issue();
  const paired = await authorized(PairingService, api.url, issued.token).pair({
    serverUrl: api.url,
    deviceName: "packed SDK consumer",
  });
  const phone = authorized(RequestService, api.url, paired.phoneToken);
  const action = create(ActionSchema, {
    kind: { case: "ack", value: create(AckActionSchema, { text: "Review once" }) },
  });
  const created = direct.requests.createRequest(
    privateRequest(action, "Packed SDK lifecycle", "packed-sdk-1", 300),
  );
  const requestId = created.request.ref?.requestId ?? "";
  const states = [];
  const stop = direct.requests.observe(requestId, (request) => states.push(request.state));
  await delay(0);
  await delay(0);
  const result = {
    ref: { connectionId: paired.connectionId, requestId },
    result: { case: "acknowledgement", value: {} },
  };
  const first = await phone.submitResult(result);
  const duplicate = await phone.submitResult(result);
  assert.equal(first.request?.state, RequestState.COMPLETED);
  assert.deepEqual(duplicate.request, first.request);
  await delay(0);
  await delay(0);
  assert.deepEqual(states, [RequestState.PENDING, RequestState.COMPLETED]);
  direct.beginShutdown();
  await api.close();
  stop();
  await direct.close();
  await direct.close();

  const restarted = open();
  assert.equal(restarted.serverId, serverId);
  assert.equal(restarted.pairing.active()?.connectionId, paired.connectionId);
  assert.equal(restarted.requests.get(requestId).state, RequestState.COMPLETED);
  assert.equal(
    restarted.requests.createRequest(
      privateRequest(action, "Packed SDK lifecycle", "packed-sdk-1", 300),
    ).request.ref?.requestId,
    requestId,
  );
  await restarted.close();
  console.log("Installed lifecycle, restart, idempotency, and duplicate result: PASS");
} finally {
  rmSync(directory, { recursive: true, force: true });
}
`;

main();
