/**
 * Release gate for the SKR staking MCP server's npm artifact (SEE-168). Like its sibling for the
 * general server, it exercises the tgz npm produced — installed outside the workspace — rather
 * than importing or launching the development tree, because the tarball is what a consumer runs
 * and the staged tree is the only place the vendored SDK exists.
 *
 * It reaches no cluster: the staking server refuses to start against anything but mainnet-beta, so
 * the chain here is the package's own stub endpoint serving the real accounts recorded in
 * `src/testing/accounts.ts`. Nothing is published and no credential is used.
 */
import assert from "node:assert/strict";
import {
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  realpathSync,
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
import { Network } from "../packages/server-sdk/src/protocol.ts";
import { MAINNET_GENESIS_HASH } from "../servers/mcp-skr-staking/src/skr/chain.ts";
import {
  STAKING_PROGRAM_ID,
  stakingAddresses,
} from "../servers/mcp-skr-staking/src/skr/program.ts";
import {
  STATUS_TOOL,
  TOOLS,
} from "../servers/mcp-skr-staking/src/requests/tools.ts";
import {
  GUARDIAN_POOL_ACCOUNT,
  STAKE_CONFIG_ACCOUNT,
  USER_STAKE_ACCOUNT,
  USER_STAKE_ADDRESS,
  USER_STAKE_OWNER,
} from "../servers/mcp-skr-staking/src/testing/accounts.ts";
import { stubCluster } from "../servers/mcp-skr-staking/src/testing/cluster.ts";
import {
  callTool,
  connectAgent,
  pairPhone,
  requestClient,
} from "../servers/mcp-skr-staking/src/testing/clients.ts";

const ROOT = resolve(import.meta.dirname, "..");
const SOURCE_PACKAGE = join(ROOT, "servers", "mcp-skr-staking", "package");
const scratch = mkdtempSync(join(tmpdir(), "seeker-skr-staking-package-"));
const artifacts = join(scratch, "artifacts");
const localPrefix = join(scratch, "local-install");
const localCache = join(scratch, "local-cache");
const transientCache = join(scratch, "transient-cache");
const workingDirectory = join(scratch, "cwd");
const dataDirectory = join(scratch, "durable-data");
const configPath = join(dataDirectory, "config.env");
// The release manifest decides a component's version; an audit that hardcoded one would keep
// passing after a bump and prove nothing about the artifact actually being released (SEE-168).
const expectedVersion = releaseVersion("mcp-skr-staking");
// The one credential this server has, and it is the agent's: the phone authenticates with what
// pairing issued it, so there is no second token to configure here.
const mcpToken = "s".repeat(64);
const databaseFile = "skr-staking-server.db";
let cluster;
let running;

try {
  for (const directory of [
    artifacts,
    localPrefix,
    localCache,
    transientCache,
    workingDirectory,
    dataDirectory,
  ]) {
    mkdirSync(directory, { recursive: true });
  }

  run(
    "pnpm",
    ["--filter", "@seekeragentconnect/mcp-skr-staking", "run", "build"],
    { cwd: ROOT },
  );

  const dryRun = pack(["--dry-run", "--json", SOURCE_PACKAGE], ROOT)[0];
  const packed = pack(
    ["--json", "--pack-destination", artifacts, SOURCE_PACKAGE],
    ROOT,
  )[0];
  assert.equal(dryRun.name, "@seekeragentconnect/mcp-skr-staking");
  assert.equal(dryRun.version, expectedVersion);
  assert.deepEqual(
    fileNames(dryRun),
    fileNames(packed),
    "dry-run and real pack must describe the same complete artifact",
  );
  auditFiles(fileNames(packed));

  const tarball = join(artifacts, packed.filename);
  assert.ok(existsSync(tarball), "npm pack created the reported tarball");
  install(localPrefix, localCache, tarball);
  const localPackage = join(
    localPrefix,
    "node_modules",
    "@seekeragentconnect",
    "mcp-skr-staking",
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
    "seeker-skr-staking-mcp",
  );
  assertCli(localBin);

  cluster = await stakingCluster();
  const port = await freePort();
  writeFileSync(
    configPath,
    [
      `SKR_STAKING_DATA_DIR=${dataDirectory}`,
      // A relative database path, so this also proves the artifact resolves it under the data
      // directory rather than under whatever directory it happens to be started from.
      `SKR_STAKING_DATABASE_PATH=${databaseFile}`,
      "SKR_STAKING_HOST=127.0.0.1",
      `SKR_STAKING_PORT=${port}`,
      `SKR_STAKING_PUBLIC_URL=http://127.0.0.1:${port}`,
      `SKR_STAKING_MCP_TOKEN=${mcpToken}`,
      `SKR_STAKING_RPC_URL=${cluster.url}`,
      "SKR_STAKING_RPC_TIMEOUT_MS=5000",
      "SKR_STAKING_REQUEST_TTL_SECONDS=3600",
      "SKR_STAKING_PENDING_LIMIT=10",
      "SKR_STAKING_PAIRING_TOKEN_TTL_SECONDS=600",
      "",
    ].join("\n"),
    { mode: 0o600 },
  );

  running = startServer(localBin);
  await waitForHealth(port);
  const baseUrl = `http://127.0.0.1:${port}`;
  const phone = await pairPhone(baseUrl, issuePairing(localBin));

  // The wallet is never a tool parameter: it comes from the connection's own binding, so the
  // phone publishes one before the agent can read anything about a position.
  const requests = requestClient(baseUrl, phone.phoneToken);
  await requests.publishWallet({
    connectionId: phone.connectionId,
    binding: { wallet: USER_STAKE_OWNER, network: Network.MAINNET },
  });

  const agent = await connectAgent(baseUrl, mcpToken);
  try {
    const discovered = (await agent.listTools()).tools.map((tool) => tool.name);
    for (const tool of TOOLS) {
      assert.ok(discovered.includes(tool), `${tool} is discovered`);
    }
    // The only tool worth running against a released artifact unattended: it reads the chain and
    // does nothing else — no request is created, the owner is asked nothing, and no transaction is
    // built. The four that ask would leave a pending approval behind and prove no more than this.
    const status = viewOf(await callTool(agent, STATUS_TOOL, {}));
    assert.equal(status.wallet, USER_STAKE_OWNER);
    assert.equal(status.network, "mainnet");
    assert.equal(status.program, STAKING_PROGRAM_ID.toBase58());
    assert.equal(status.stake_account, USER_STAKE_ADDRESS);
    assert.ok(BigInt(status.staked_skr) > 0n, status.display);
  } finally {
    await agent.close();
  }

  // The phone's own side of the same conversation, and the proof that the read was a read: the
  // durable request API answers, and it has nothing pending to answer with.
  const pending = await requests.listPending({
    connectionId: phone.connectionId,
  });
  assert.deepEqual(pending.requests, [], "a status read creates no request");

  const status = run(localBin, ["--config", configPath, "pair", "status"]);
  assert.match(status.stdout, new RegExp(phone.connectionId));

  await stopServer(running);
  running = undefined;
  assert.ok(
    existsSync(join(dataDirectory, databaseFile)),
    "the pairing outlives the process that made it",
  );

  const transient = run("npm", [
    "exec",
    "--yes",
    `--cache=${transientCache}`,
    `--package=${tarball}`,
    "--",
    "seeker-skr-staking-mcp",
    "--version",
  ]);
  assert.equal(transient.stdout.trim(), expectedVersion);

  for (const directory of [
    localPrefix,
    localCache,
    transientCache,
    workingDirectory,
  ]) {
    assert.deepEqual(
      findNamed(directory, new Set([databaseFile, "config.env"])),
      [],
      `writable state did not leak into ${directory}`,
    );
  }

  console.log(`PASS npm pack dry-run and real artifact: ${basename(tarball)}`);
  console.log(
    "PASS published manifest, vendored SDK, local install, and exact-tarball npm exec",
  );
  console.log(
    "PASS packaged pairing, health, MCP discovery, and a read-only staking status round trip",
  );
  console.log(
    "PASS clean SIGTERM shutdown, a database that persists, and no state outside the data directory",
  );
} finally {
  if (running !== undefined) {
    running.child.kill("SIGKILL");
    await waitForExit(running, 5_000).catch(() => undefined);
  }
  await cluster?.close();
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
    // `.d.ts` would be fine in a library artifact; this one is an executable and emits none, so
    // any TypeScript here means source was shipped by accident.
    assert.doesNotMatch(file, /(?:\.ts|\.map|\.db|\.env|\.tgz)$/);
  }
}

function auditManifest(packageRoot) {
  const manifest = JSON.parse(
    readFileSync(join(packageRoot, "package.json"), "utf8"),
  );
  assert.equal(manifest.name, "@seekeragentconnect/mcp-skr-staking");
  assert.equal(manifest.version, expectedVersion);
  // The two fields npm reads before it will accept a scoped package: `private: true` refuses the
  // publish outright, and a missing `publishConfig.access` quietly makes it private instead.
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
    "seeker-skr-staking-mcp": "./dist/cli.js",
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
    directory: "servers/mcp-skr-staking",
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
  // Nothing in the artifact may name the machine it was built on. A rewritten import that kept an
  // absolute specifier would still run here and resolve nowhere else.
  const checkout = realpathSync(ROOT);
  for (const file of textFiles(packageRoot)) {
    assert.ok(
      !readFileSync(file, "utf8").includes(checkout),
      `${file} names the checkout it was built in`,
    );
  }
}

function install(prefix, cache, tarball) {
  run(
    "npm",
    [
      "install",
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

function assertCli(executable) {
  assert.ok(existsSync(executable), `npm installed ${executable}`);
  assert.equal(run(executable, ["--version"]).stdout.trim(), expectedVersion);
  const help = run(executable, ["--help"]).stdout;
  assert.match(help, /Usage: seeker-skr-staking-mcp/);
  for (const command of ["start", "pair"]) {
    assert.match(help, new RegExp(`^ {2}${command}\\b`, "m"));
  }
  const invalid = run(executable, [], { expectedStatus: 2 });
  assert.match(invalid.stderr, /Usage:/);
}

/**
 * A mainnet endpoint holding one real staker's position: the configuration, the official
 * guardian's pool, and a stake account with shares and an unstake in progress. The staking server
 * compares genesis hashes at startup and refuses anything but mainnet-beta, so a chain that knows
 * which cluster it is — rather than a live one — is what lets this run offline.
 */
function stakingCluster() {
  const addresses = stakingAddresses();
  const program = STAKING_PROGRAM_ID.toBase58();
  return stubCluster(
    MAINNET_GENESIS_HASH,
    new Map([
      [
        addresses.stakeConfig.toBase58(),
        { owner: program, data: STAKE_CONFIG_ACCOUNT },
      ],
      [
        addresses.guardianPool.toBase58(),
        { owner: program, data: GUARDIAN_POOL_ACCOUNT },
      ],
      [USER_STAKE_ADDRESS, { owner: program, data: USER_STAKE_ACCOUNT }],
    ]),
  );
}

function issuePairing(executable) {
  const output = run(executable, ["--config", configPath, "pair"]).stdout;
  const text = output.match(/seekervault:\/\/pair\?[^\s]+/)?.[0];
  assert.ok(text, "the packaged CLI emitted a pairing URI");
  const parsed = parsePairingUri(text);
  assert.equal(parsed.ok, true);
  return parsed.code;
}

function viewOf(result) {
  assert.notEqual(result.isError, true, JSON.stringify(result.content));
  assert.equal(typeof result.structuredContent, "object");
  return result.structuredContent;
}

function startServer(executable) {
  const child = spawn(executable, ["--config", configPath, "start"], {
    cwd: workingDirectory,
    env: cleanEnvironment(),
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8").on("data", (chunk) => (stdout += chunk));
  child.stderr.setEncoding("utf8").on("data", (chunk) => (stderr += chunk));
  return { child, output: () => `${stdout}${stderr}` };
}

async function stopServer(server) {
  server.child.kill("SIGTERM");
  const result = await waitForExit(server, 10_000);
  assert.equal(result.signal, null, server.output());
  assert.equal(result.code, 0, server.output());
  assert.match(server.output(), /SIGTERM: shutting down/);
}

async function waitForExit(server, timeoutMs) {
  const exit = new Promise((resolveExit) => {
    server.child.once("exit", (code, signal) => resolveExit({ code, signal }));
  });
  return Promise.race([
    exit,
    delay(timeoutMs).then(() => {
      throw new Error(
        `process did not exit in ${timeoutMs} ms:\n${server.output()}`,
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
  throw new Error(
    `health check timed out: ${last}\n${running?.output() ?? ""}`,
  );
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
    cwd: options.cwd ?? workingDirectory,
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

/**
 * The environment the packaged server is started in: this process's, minus every setting it reads.
 * A developer's own `SKR_STAKING_*` export must not be able to change what is being audited.
 */
function cleanEnvironment() {
  const env = { ...process.env };
  for (const name of Object.keys(env)) {
    if (name.startsWith("SKR_STAKING_")) delete env[name];
  }
  return env;
}

function readAllJavaScript(directory) {
  return readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".js"))
    .map((entry) => readFileSync(join(entry.parentPath, entry.name), "utf8"))
    .join("\n");
}

function textFiles(directory) {
  return readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter(
      (entry) =>
        entry.isFile() && /(?:\.(?:js|json|css|md)|LICENSE)$/.test(entry.name),
    )
    .map((entry) => join(entry.parentPath, entry.name));
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
