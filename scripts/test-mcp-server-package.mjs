/**
 * Release gate for the unpublished MCP server artifact. This intentionally exercises the tgz
 * npm produced, outside the workspace, rather than importing or launching the development tree.
 */
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import {
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { basename, join, resolve, sep } from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";

import { vendoredSdkDigest } from "./package-mcp-artifact.mjs";
import { parsePairingUri } from "../packages/server-sdk/src/index.ts";
import {
  connectAgent as connectTestAgent,
  pairingClient,
  requestClient,
} from "../servers/mcp-server/src/testing/clients.ts";

const ROOT = resolve(import.meta.dirname, "..");
const SOURCE_PACKAGE = join(ROOT, "servers", "mcp-server", "package");
const scratch = mkdtempSync(join(tmpdir(), "seeker-mcp-package-"));
const artifacts = join(scratch, "artifacts");
const localPrefix = join(scratch, "local-install");
const globalPrefix = join(scratch, "global-install");
const localCache = join(scratch, "local-cache");
const globalCache = join(scratch, "global-cache");
const transientCache = join(scratch, "transient-cache");
const firstCwd = join(scratch, "first-cwd");
const secondCwd = join(scratch, "second-cwd");
const dataDirectory = join(scratch, "durable-data");
const configPath = join(dataDirectory, "config.env");
// The release manifest decides a component's version; an audit that hardcoded one would keep
// passing after a bump and prove nothing about the artifact actually being released (SEE-168).
const expectedVersion = releaseVersion("mcp-server");
const mcpToken = "m".repeat(64);
const phoneToken = "p".repeat(64);
let running;

try {
  for (const directory of [
    artifacts,
    localPrefix,
    globalPrefix,
    localCache,
    globalCache,
    transientCache,
    firstCwd,
    secondCwd,
    dataDirectory,
  ]) {
    mkdirSync(directory, { recursive: true });
  }

  run("pnpm", ["--filter", "@seekeragentconnect/mcp-server", "run", "build"], {
    cwd: ROOT,
  });

  const dryRun = pack(["--dry-run", "--json", SOURCE_PACKAGE], ROOT)[0];
  const packed = pack(
    ["--json", "--pack-destination", artifacts, SOURCE_PACKAGE],
    ROOT,
  )[0];
  assert.equal(dryRun.name, "@seekeragentconnect/mcp-server");
  assert.equal(dryRun.version, expectedVersion);
  assert.deepEqual(
    fileNames(dryRun),
    fileNames(packed),
    "dry-run and real pack must describe the same complete artifact",
  );
  auditFiles(fileNames(packed));

  const tarball = join(artifacts, packed.filename);
  assert.ok(existsSync(tarball), "npm pack created the reported tarball");
  install("local", localPrefix, localCache, tarball);
  const localPackage = join(
    localPrefix,
    "node_modules",
    "@seekeragentconnect",
    "mcp-server",
  );
  auditManifest(localPackage);
  assert.equal(lstatSync(localPackage).isSymbolicLink(), false);
  assert.ok(
    !isInside(realpathSync(localPackage), realpathSync(ROOT)),
    "the installed package is outside the checkout",
  );
  assert.ok(
    !existsSync(
      join(localPrefix, "node_modules", "@seekeragentconnect", "server-sdk"),
    ),
    "the artifact does not require a separately installed unpublished SDK",
  );

  const localBin = join(
    localPrefix,
    "node_modules",
    ".bin",
    "seeker-agent-connect-mcp",
  );
  assertCli(localBin, firstCwd);
  const port = await freePort();
  writeFileSync(
    configPath,
    [
      `MCP_SERVER_DATA_DIR=${dataDirectory}`,
      "DATABASE_PATH=direct-server.db",
      "SIDECAR_HOST=127.0.0.1",
      `SIDECAR_PORT=${port}`,
      `SIDECAR_PUBLIC_URL=http://127.0.0.1:${port}`,
      `MCP_TOKEN=${mcpToken}`,
      `PHONE_TOKEN=${phoneToken}`,
      "MCP_ENABLED=true",
      "MCP_DEMO_TOOLS=true",
      "LIVE_COMMAND_TIMEOUT_SECONDS=5",
      "REQUEST_TTL_SECONDS=86400",
      "REQUEST_PENDING_LIMIT=100",
      "PAIRING_TOKEN_TTL_SECONDS=600",
      "",
    ].join("\n"),
    { mode: 0o600 },
  );

  running = startServer(localBin, firstCwd, configPath);
  await waitForHealth(port);
  const firstPair = issuePairing(localBin, firstCwd, configPath);
  const phone = await pairPhone(firstPair);
  assert.equal(phone.serverId, firstPair.serverId);

  const agent = await connectAgent(port);
  const tools = await agent.listTools();
  for (const name of ["vault_request_ack", "vault_get_request"]) {
    assert.ok(
      tools.tools.some((tool) => tool.name === name),
      `${name} is discovered`,
    );
  }
  const idempotencyKey = `artifact-${randomUUID()}`;
  const queued = viewOf(
    await agent.callTool({
      name: "vault_request_ack",
      arguments: {
        text: "npm artifact round trip",
        idempotency_key: idempotencyKey,
      },
    }),
  );
  assert.equal(queued.status, "PENDING");
  const requests = requestClient(`http://127.0.0.1:${port}`, phone.phoneToken);
  const pending = await requests.listPending({
    connectionId: phone.connectionId,
  });
  assert.ok(
    pending.requests.some(
      (request) => request.ref?.requestId === queued.request_id,
    ),
    "the protocol test phone received the MCP request",
  );
  await requests.submitResult({
    ref: { connectionId: phone.connectionId, requestId: queued.request_id },
    result: { case: "acknowledgement", value: {} },
  });
  const completed = viewOf(
    await agent.callTool({
      name: "vault_get_request",
      arguments: { request_id: queued.request_id },
    }),
  );
  assert.equal(completed.status, "COMPLETED");
  await agent.close();

  const duplicate = startServer(localBin, secondCwd, configPath, {
    SIDECAR_PORT: String(await freePort()),
  });
  const duplicateExit = await waitForExit(duplicate, 10_000);
  assert.notEqual(duplicateExit.code, 0, "a competing server must fail");
  assert.match(duplicate.output(), /direct store is already in use/);
  await waitForHealth(port);

  running.child.kill("SIGKILL");
  const killed = await waitForExit(running, 10_000);
  assert.equal(killed.signal, "SIGKILL", running.output());
  running = undefined;
  assert.ok(existsSync(join(dataDirectory, "direct-server.db")));
  assert.ok(
    existsSync(join(dataDirectory, "direct-server.db.mcp-server-owner.sqlite")),
  );
  assert.deepEqual(
    JSON.parse(
      readFileSync(
        join(dataDirectory, "direct-server.db.mcp-server.lock"),
        "utf8",
      ),
    ),
    { format: "sqlite-owner-v1" },
  );

  rmSync(localPrefix, { recursive: true, force: true });
  rmSync(localCache, { recursive: true, force: true });
  install("global", globalPrefix, globalCache, tarball);
  const globalBin = join(globalPrefix, "bin", "seeker-agent-connect-mcp");
  assertCli(globalBin, secondCwd);
  running = startServer(globalBin, secondCwd, configPath);
  await waitForHealth(port);
  const secondPair = issuePairing(globalBin, secondCwd, configPath);
  assert.equal(
    secondPair.serverId,
    firstPair.serverId,
    "server identity survived reinstall",
  );
  const status = run(globalBin, ["--config", configPath, "pair", "status"], {
    cwd: secondCwd,
  });
  assert.match(status.stdout, new RegExp(phone.connectionId));

  const restartedAgent = await connectAgent(port);
  const replayed = viewOf(
    await restartedAgent.callTool({
      name: "vault_request_ack",
      arguments: {
        text: "npm artifact round trip",
        idempotency_key: idempotencyKey,
      },
    }),
  );
  assert.equal(replayed.request_id, queued.request_id);
  assert.equal(replayed.status, "COMPLETED");
  await restartedAgent.close();
  await stopServer(running);
  running = undefined;

  const transient = run(
    "npm",
    [
      "exec",
      "--yes",
      `--cache=${transientCache}`,
      `--package=${tarball}`,
      "--",
      "seeker-agent-connect-mcp",
      "--version",
    ],
    { cwd: firstCwd },
  );
  assert.equal(transient.stdout.trim(), expectedVersion);

  for (const directory of [
    globalPrefix,
    globalCache,
    transientCache,
    firstCwd,
    secondCwd,
  ]) {
    assert.deepEqual(
      findNamed(directory, new Set(["direct-server.db", "config.env"])),
      [],
      `writable state did not leak into ${directory}`,
    );
  }
  assert.ok(
    existsSync(join(dataDirectory, "direct-server.db.mcp-server-owner.sqlite")),
  );

  console.log(`PASS npm pack dry-run and real artifact: ${basename(tarball)}`);
  console.log(
    `PASS local and global-style installs plus exact-tarball npm exec`,
  );
  console.log(
    `PASS packaged pairing, health, MCP discovery, phone acknowledgement, and replay`,
  );
  console.log(
    `PASS SIGKILL recovery, changed cwd, reinstall persistence, clean shutdown, and duplicate-store refusal`,
  );
} finally {
  if (running !== undefined) {
    running.child.kill("SIGKILL");
    await waitForExit(running, 5_000).catch(() => undefined);
  }
  rmSync(scratch, { recursive: true, force: true });
}

function releaseVersion(id) {
  const manifest = JSON.parse(
    readFileSync(join(ROOT, "release", "components.json"), "utf8"),
  );
  const component = manifest.components.find((entry) => entry.id === id);
  assert.ok(component, `release/components.json declares no component ${id}`);
  return component.version;
}

function pack(args, cwd) {
  const result = run("npm", ["pack", ...args], { cwd });
  const parsed = JSON.parse(result.stdout);
  assert.equal(parsed.length, 1);
  return parsed;
}

function fileNames(record) {
  return record.files.map((file) => file.path).sort();
}

function auditFiles(files) {
  assert.deepEqual(files.slice(0, 2), ["LICENSE", "README.md"]);
  assert.ok(files.includes("package.json"));
  assert.ok(files.includes("dist/cli.js"));
  assert.ok(files.includes("dist/vendor/server-sdk/index.js"));
  assert.ok(files.includes("dist/vendor/server-sdk/protocol.js"));
  for (const file of files) {
    assert.match(file, /^(?:LICENSE|README\.md|package\.json|dist\/)/);
    assert.doesNotMatch(
      file,
      /(?:^|\/)(?:src|test|tests|testing|fixtures)(?:\/|$)/,
    );
    assert.doesNotMatch(file, /(?:\.ts|\.map|\.db|\.env|\.tgz)$/);
  }
}

function auditManifest(packageRoot) {
  const manifest = JSON.parse(
    readFileSync(join(packageRoot, "package.json"), "utf8"),
  );
  assert.equal(manifest.name, "@seekeragentconnect/mcp-server");
  assert.equal(manifest.version, expectedVersion);
  // The artifact is published now (SEE-168), so the two fields npm reads before it will accept a
  // scoped package are part of what this audit proves. `private: true` would refuse the publish;
  // a missing `publishConfig.access` would silently make it a private package nobody can install.
  assert.notEqual(manifest.private, true);
  assert.deepEqual(manifest.publishConfig, { access: "public" });
  assert.deepEqual(manifest.engines, { node: ">=24.21.0" });
  for (const field of [
    "description",
    "license",
    "homepage",
    "bugs",
    "repository",
    "author",
  ]) {
    assert.ok(manifest[field], `the published manifest is missing ${field}`);
  }
  assert.deepEqual(manifest.bin, {
    "seeker-agent-connect-mcp": "./dist/cli.js",
  });
  assert.deepEqual(manifest.files, ["dist", "README.md", "LICENSE"]);
  assert.equal(manifest.scripts, undefined);
  assert.equal(
    manifest.dependencies["@seekeragentconnect/server-sdk"],
    undefined,
  );
  for (const value of Object.values(manifest.dependencies)) {
    assert.doesNotMatch(value, /^(?:workspace:|catalog:|file:|link:)/);
    assert.doesNotMatch(value, /[/\\](?:Users|home|workspace|worktrees)[/\\]/);
  }
  assert.deepEqual(manifest.repository, {
    type: "git",
    url: "git+https://github.com/SeekerAgentConnect/sac.git",
    directory: "servers/mcp-server",
  });
  const emitted = readAllJavaScript(join(packageRoot, "dist"));
  assert.doesNotMatch(emitted, /["']@seekeragentconnect\/server-sdk/);
  assert.ok(emitted.includes("vendor/server-sdk"));
  // The artifact names the SDK it carries (SEE-182): the server's own version says nothing about
  // the SDK's, so the record is what makes the vendored copy traceable. It must be this
  // workspace's SDK, byte for byte.
  const vendored = join(packageRoot, "dist", "vendor", "server-sdk");
  const record = JSON.parse(
    readFileSync(join(vendored, "package.json"), "utf8"),
  );
  const sdk = JSON.parse(
    readFileSync(join(ROOT, "packages", "server-sdk", "package.json"), "utf8"),
  );
  assert.equal(record.name, "@seekeragentconnect/server-sdk");
  assert.equal(record.version, sdk.version);
  assert.equal(record.type, "module");
  assert.equal(record.contentSha256, vendoredSdkDigest(vendored));
  assert.deepEqual(manifest.seekerAgentConnect, {
    serverSdk: {
      name: record.name,
      version: record.version,
      contentSha256: record.contentSha256,
    },
  });
}

function install(style, prefix, cache, tarball) {
  run(
    "npm",
    [
      "install",
      ...(style === "global" ? ["--global"] : []),
      `--prefix=${prefix}`,
      `--cache=${cache}`,
      "--ignore-scripts",
      "--no-audit",
      "--no-fund",
      "--no-package-lock",
      tarball,
    ],
    { cwd: tmpdir() },
  );
}

function assertCli(executable, cwd) {
  assert.ok(existsSync(executable), `npm installed ${executable}`);
  assert.equal(
    run(executable, ["--version"], { cwd }).stdout.trim(),
    expectedVersion,
  );
  assert.match(run(executable, ["--help"], { cwd }).stdout, /Streamable HTTP/);
  const invalid = run(executable, [], { cwd, expectedStatus: 2 });
  assert.match(invalid.stderr, /Usage:/);
}

function issuePairing(executable, cwd, config) {
  const output = run(executable, ["--config", config, "pair"], { cwd }).stdout;
  const text = output.match(/seekervault:\/\/pair\?[^\s]+/)?.[0];
  assert.ok(text, "the packaged CLI emitted a pairing URI");
  const parsed = parsePairingUri(text);
  assert.equal(parsed.ok, true);
  return parsed.code;
}

async function pairPhone(code) {
  return pairingClient(code.serverUrl, code.token).pair({
    serverUrl: code.serverUrl,
    deviceName: "Artifact test phone",
  });
}

async function connectAgent(port) {
  return connectTestAgent(`http://127.0.0.1:${port}`, mcpToken);
}

function viewOf(result) {
  assert.notEqual(result.isError, true, JSON.stringify(result.content));
  assert.equal(typeof result.structuredContent, "object");
  return result.structuredContent;
}

function startServer(executable, cwd, config, overrides = {}) {
  const child = spawn(executable, ["--config", config, "start"], {
    cwd,
    env: { ...cleanEnvironment(), ...overrides },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8").on("data", (chunk) => (stdout += chunk));
  child.stderr.setEncoding("utf8").on("data", (chunk) => (stderr += chunk));
  return { child, output: () => `${stdout}${stderr}` };
}

async function stopServer(process) {
  process.child.kill("SIGTERM");
  const result = await waitForExit(process, 10_000);
  assert.equal(result.signal, null, process.output());
  assert.equal(result.code, 0, process.output());
  assert.match(process.output(), /stopped/);
}

async function waitForExit(process, timeoutMs) {
  const exit = new Promise((resolve) => {
    process.child.once("exit", (code, signal) => resolve({ code, signal }));
  });
  return Promise.race([
    exit,
    delay(timeoutMs).then(() => {
      throw new Error(
        `process did not exit in ${timeoutMs} ms:\n${process.output()}`,
      );
    }),
  ]);
}

async function waitForHealth(port) {
  const deadline = Date.now() + 15_000;
  let last = "not attempted";
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/healthz`);
      if (response.ok && (await response.text()) === '{"status":"ok"}') return;
      last = `HTTP ${response.status}`;
    } catch (error) {
      last = error instanceof Error ? error.message : String(error);
    }
    await delay(50);
  }
  throw new Error(`health check timed out: ${last}`);
}

function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      assert.ok(address !== null && typeof address === "object");
      const port = address.port;
      server.close((error) => (error ? reject(error) : resolvePort(port)));
    });
  });
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    encoding: "utf8",
    maxBuffer: 20 * 1024 * 1024,
  });
  if (result.error !== undefined) throw result.error;
  const expected = options.expectedStatus ?? 0;
  assert.equal(
    result.status,
    expected,
    `${command} ${args.join(" ")} exited ${result.status}\n${result.stdout}\n${result.stderr}`,
  );
  return result;
}

function cleanEnvironment() {
  const env = { ...process.env };
  for (const name of [
    "MCP_SERVER_CONFIG",
    "MCP_SERVER_DATA_DIR",
    "DATABASE_PATH",
    "SIDECAR_HOST",
    "SIDECAR_PORT",
    "SIDECAR_PUBLIC_URL",
    "SIDECAR_UPDATE_PORT",
    "SIDECAR_TLS_CERT_PATH",
    "SIDECAR_TLS_KEY_PATH",
    "SIDECAR_HEALTH_CA_CERT_PATH",
    "MCP_TOKEN",
    "PHONE_TOKEN",
    "MCP_ENABLED",
    "MCP_DEMO_TOOLS",
    "MCP_ALLOWED_HOSTS",
    "MCP_OAUTH_ISSUER",
    "MCP_OAUTH_RESOURCE",
    "MCP_OAUTH_JWKS_URL",
    "MCP_OAUTH_SCOPE",
    "SOLANA_RPC_URL",
    "FCM_PROJECT_ID",
  ]) {
    delete env[name];
  }
  return env;
}

function readAllJavaScript(directory) {
  return readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".js"))
    .map((entry) => readFileSync(join(entry.parentPath, entry.name), "utf8"))
    .join("\n");
}

function findNamed(directory, names) {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && names.has(entry.name))
    .map((entry) => join(entry.parentPath, entry.name));
}

function isInside(path, parent) {
  return path === parent || path.startsWith(`${parent}${sep}`);
}
