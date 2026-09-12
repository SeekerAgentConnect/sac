/**
 * Stage boundaries on the Node side (AGENTS.md). Nothing creates keys and nothing signs. The
 * sidecar stores durable requests (SAW-010): only src/storage/ imports the file system or SQLite,
 * and only it runs SQL. SAW-019 lets it read a chain, and only from src/solana/, to build a
 * transfer the owner reviews; it still sends nothing. These checks fail when that changes before
 * the stage that changes it on purpose.
 */
import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync } from "node:fs";
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
  it("installs no wallet library beyond the chain client SAW-019 builds with", () => {
    // @solana/web3.js and the packages it pulls in are address maths, message compilation, and
    // codecs. No wallet, no wallet adapter, and no key management library is installed.
    const lockfile = readFileSync(join(ROOT, "pnpm-lock.yaml"), "utf8");
    const solana = [
      ...new Set(
        [...lockfile.matchAll(/@solana\/[a-z0-9.-]+/g)].map((match) =>
          match[0].toString(),
        ),
      ),
    ].sort();
    assert.deepEqual(solana, [
      "@solana/buffer-layout",
      "@solana/codecs-core",
      "@solana/codecs-numbers",
      "@solana/errors",
      "@solana/web3.js",
    ]);
    assert.doesNotMatch(
      lockfile,
      /['\s/](@solana-mobile|@coral-xyz|@metaplex-foundation)\//,
    );
  });

  it("imports the chain client only in src/solana", () => {
    // Everything that knows how to reach a cluster lives in one directory, so there is one place
    // to read before trusting what the sidecar can do on a network.
    const chain = /from "@solana\/web3\.js"/;
    const outside = shippedSources().filter(
      (file) =>
        !relative(SRC, file).startsWith("solana/") &&
        chain.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      outside.map((file) => relative(ROOT, file)),
      [],
    );
    assert.match(
      readFileSync(join(SRC, "solana/transfer.ts"), "utf8"),
      chain,
      "solana/transfer.ts is where a transaction is compiled",
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

  it("serves nothing that swaps, sends, or needs a key of an agent's own", () => {
    // The tools an agent can call are named here on purpose. SAW-019 adds vault_transfer, which
    // only stores a request; a swap tool is Stage 6's work, and until then no agent can ask for
    // one. No agent has a key either: an agent authenticates with the bearer token the owner
    // issued it.
    const known = [
      "DISPLAY_COMMAND_TOOL",
      "GET_ADDRESS_TOOL",
      "GET_CAPABILITIES_TOOL",
      "SIGN_MESSAGE_TOOL",
      "TRANSFER_TOOL",
      "REQUEST_ACK_TOOL",
      "GET_REQUEST_TOOL",
      "CANCEL_REQUEST_TOOL",
    ];
    const registered = shippedSources().flatMap((file) =>
      [
        ...readFileSync(file, "utf8").matchAll(
          /registerTool\(\s*([A-Za-z_$][\w$]*)/g,
        ),
      ].map((match) => match[1] ?? ""),
    );
    assert.ok(
      registered.includes("SIGN_MESSAGE_TOOL"),
      "found the registrations, so an unknown one would show up",
    );
    assert.deepEqual(
      registered.filter((name) => !known.includes(name)),
      [],
    );

    // Nothing broadcasts, nothing signs, nothing swaps, and no key is the sidecar's or an
    // agent's. The sidecar reads the chain (SAW-019) and hands the unsigned bytes to the owner's
    // wallet; sending is the wallet's. SAW-022 lets it read what became of a signature, which is
    // still only reading: it can see a transaction, and it can't make or send one.
    const spending =
      /sendRawTransaction|sendTransaction|sendAndConfirm|signTransaction|signAllTransactions|requestAirdrop|jup\.ag|\bKeypair\b|\bprivateKey\b|\bsecretKey\b|\bkeypair\b/i;
    const hits = shippedSources().filter((file) =>
      spending.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      hits.map((file) => relative(ROOT, file)),
      [],
    );

    // The chain client's methods are named here too: every one of them only reads.
    const methods = [
      ...readFileSync(join(SRC, "solana/rpc.ts"), "utf8").matchAll(
        /"(get[A-Za-z]+)"/g,
      ),
    ].map((match) => match[1] ?? "");
    assert.deepEqual(
      [...new Set(methods)].sort(),
      [
        "getAccountInfo",
        "getBlockHeight",
        "getFeeForMessage",
        "getGenesisHash",
        "getLatestBlockhash",
        "getMinimumBalanceForRentExemption",
        "getSignatureStatuses",
        "getTransaction",
      ],
      "the chain client asks for nothing but these reads",
    );
  });
});

/**
 * Nothing this repository ships, installs, or runs by itself points at a real cluster (SAW-024).
 * A transfer needs SOLANA_RPC_URL, and the owner is the only one who ever sets it: a fresh clone
 * prepares no transaction at all, no script and no CI job supplies an endpoint, and the one check
 * that reaches a real network is behind an environment variable and only reads. These checks are
 * what makes "no default spends real funds" a fact rather than a promise.
 */
describe("spending nothing by default", () => {
  /** A real cluster's JSON-RPC endpoint, a faucet, or the call that asks one for money. */
  const CLUSTER =
    /api\.(mainnet-beta|devnet|testnet)\.solana\.com|faucet\.solana\.com|requestAirdrop|\bairdrop\b/gi;

  /** Whether `text` names one, without carrying the global regexp's own position around. */
  function names(text: string): boolean {
    CLUSTER.lastIndex = 0;
    return CLUSTER.test(text);
  }

  /** Every package.json in the workspace: the root's and each directory's under it. */
  function manifests(): { path: string; json: Record<string, unknown> }[] {
    const directories = [
      ROOT,
      ...readdirSync(ROOT, { withFileTypes: true })
        .filter(
          (entry) =>
            entry.isDirectory() &&
            !entry.name.startsWith(".") &&
            entry.name !== "node_modules",
        )
        .map((entry) => join(ROOT, entry.name)),
    ];
    return directories
      .map((directory) => join(directory, "package.json"))
      .filter((path) => existsSync(path))
      .map((path) => ({
        path: relative(ROOT, path),
        json: JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>,
      }));
  }

  it("ships no endpoint in .env.example, so a fresh clone prepares nothing", () => {
    const example = readFileSync(join(ROOT, ".env.example"), "utf8");
    assert.match(
      example,
      /^SOLANA_RPC_URL=\r?$/m,
      ".env.example must leave SOLANA_RPC_URL empty: the owner chooses the cluster, and without " +
        "one the sidecar serves no vault_transfer at all",
    );
    // A cluster may be named in a comment, as this one's is, to say what an endpoint looks like.
    // What must never appear is one as a value: that would be a cluster nobody chose.
    const assigned = example
      .split("\n")
      .filter((line) => /^[A-Z][A-Z0-9_]*=./.test(line))
      .filter((line) => names(line));
    assert.deepEqual(assigned, []);
  });

  it("runs no script that supplies an endpoint or asks for funds", () => {
    const packages = manifests();
    assert.ok(packages.length >= 3, "found the workspace's package.json files");
    const offenders = packages.flatMap(({ path, json }) =>
      Object.entries((json.scripts ?? {}) as Record<string, string>)
        .filter(
          ([, command]) => names(command) || command.includes("SOLANA_RPC_URL"),
        )
        .map(([name]) => `${path}: ${name}`),
    );
    assert.deepEqual(offenders, []);
  });

  it("runs no CI job that supplies an endpoint or asks for funds", () => {
    const directory = join(ROOT, ".github/workflows");
    const workflows = readdirSync(directory).filter((name) =>
      /\.ya?ml$/.test(name),
    );
    assert.ok(workflows.length > 0, "found the workflows");
    const offenders = workflows.filter((name) => {
      const text = readFileSync(join(directory, name), "utf8");
      return names(text) || text.includes("SOLANA_RPC_URL");
    });
    assert.deepEqual(offenders, []);
  });

  it("names no cluster endpoint in any shipped sidecar source", () => {
    // The endpoint comes from the environment or the sidecar has none. A default here would be a
    // cluster nobody chose, and a hosted URL can carry an API key besides.
    const hits = shippedSources().filter((file) =>
      /solana\.com/.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      hits.map((file) => relative(ROOT, file)),
      [],
    );
  });

  it("keeps the one check that reaches a real network behind its variable", () => {
    // It reads devnet's genesis hash and stops at a refusal, so it needs no funds; the gate is
    // what keeps it out of `pnpm test:transfer`, `pnpm check`, and CI.
    const acceptance = readFileSync(
      join(ROOT, "test-agent/src/stage4.acceptance.ts"),
      "utf8",
    );
    const gate = acceptance.indexOf(
      'skip: process.env.SEEKER_VAULT_NETWORK_CHECKS !== "1"',
    );
    assert.ok(gate > 0, "the opt-in describe is skipped without the variable");
    CLUSTER.lastIndex = 0;
    for (const match of acceptance.matchAll(CLUSTER)) {
      assert.ok(
        (match.index ?? 0) > gate,
        `a real cluster is named at index ${String(match.index)}, before the opt-in gate`,
      );
    }
  });
});
