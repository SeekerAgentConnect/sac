/**
 * Stage boundaries on the Node side (AGENTS.md). Nothing creates keys and nothing signs. The
 * Direct Server SDK stores durable requests (SAW-010): only storage packages import the file
 * system or SQLite, and only the SDK storage package runs durable SQL. SEE-137 gives the MCP
 * process one separate SQLite ownership transaction. SAW-019 lets the host read a chain, and only
 * from mcp-server/src/solana/, to build a
 * transfer the owner reviews; it still sends nothing. SAW-048 authorizes an HTTP/2 listener and
 * UpdateService only in server.ts and the SDK's updates package, while durable cursors and
 * snapshots still go through the SDK's storage package. SAW-054 allows Firebase Admin only in
 * mcp-server/src/push/, SAW-055 stores one connection-owned target through the SDK's storage package,
 * and SAW-056 sends only an audited content-free
 * invalidation after a durable commit. SAW-059 closes that optional push scope without giving the
 * sender a request body, credential, policy, transaction authority, or wallet operation. SEE-87
 * makes MCP an optional adapter: it reaches the request core only through the public SDK API,
 * nothing but server.ts knows the endpoint exists, and MCP_ENABLED=false serves no /mcp at all.
 * These checks fail when that narrow boundary changes.
 */
import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const SRC = fileURLToPath(new URL("./", import.meta.url));
const SDK_SRC = join(ROOT, "server-sdk/src");
const STORAGE_IMPORT = /from "(node:)?(fs|fs\/promises|sqlite)"/;

/** The sidecar's shipped sources: no tests, test helpers, or generated code. */
function shippedSources(): string[] {
  return readdirSync(SRC, { recursive: true, encoding: "utf8" })
    .filter((path) => path.endsWith(".ts") && !path.endsWith(".test.ts"))
    .filter((path) => !["testing", "gen"].includes(path.split("/")[0] ?? ""))
    .map((path) => join(SRC, path));
}

/** The SDK's shipped sources: no tests, test helpers, or generated code. */
function sdkSources(): string[] {
  return readdirSync(SDK_SRC, { recursive: true, encoding: "utf8" })
    .filter((path) => path.endsWith(".ts") && !path.endsWith(".test.ts"))
    .filter((path) => !["testing", "gen"].includes(path.split("/")[0] ?? ""))
    .map((path) => join(SDK_SRC, path));
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
    const hits = [...shippedSources(), ...sdkSources()].filter((file) =>
      signing.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      hits.map((file) => relative(ROOT, file)),
      [],
    );
    assert.match(
      readFileSync(join(SDK_SRC, "requests/signature.ts"), "utf8"),
      /\bverify\s*\(/,
      "requests/signature.ts verifies signatures, and that is all it does",
    );
  });

  it("imports the file system and SQLite only in storage packages", () => {
    const sources = [...shippedSources(), ...sdkSources()];
    const outside = sources.filter((file) => {
      const path = relative(ROOT, file);
      return (
        !path.startsWith("mcp-server/src/storage/") &&
        !path.startsWith("server-sdk/src/storage/") &&
        STORAGE_IMPORT.test(readFileSync(file, "utf8"))
      );
    });
    assert.deepEqual(
      outside.map((file) => relative(ROOT, file)),
      [],
    );
    assert.match(
      readFileSync(join(SDK_SRC, "storage/database.ts"), "utf8"),
      STORAGE_IMPORT,
      "storage/database.ts is where the database is opened",
    );
  });

  it("runs durable SQL only in the SDK storage package", () => {
    // Statements, queries, and application transactions stay in the SDK storage modules. The MCP
    // process's one exception is the separate ownership database: it holds exactly one transaction
    // and has no schema or data, so pairing commands can keep using the application database.
    const sql = /\.prepare\(|\btransaction\(|\.exec\(\s*["'`]/;
    const ownership = join(SRC, "storage/instance-lock.ts");
    const outside = [...shippedSources(), ...sdkSources()].filter((file) => {
      const path = relative(ROOT, file);
      return (
        !path.startsWith("server-sdk/src/storage/") &&
        file !== ownership &&
        sql.test(readFileSync(file, "utf8"))
      );
    });
    assert.deepEqual(
      outside.map((file) => relative(ROOT, file)),
      [],
    );
    assert.match(
      readFileSync(join(SDK_SRC, "storage/request-store.ts"), "utf8"),
      sql,
      "the request store runs its SQL in storage",
    );
    const owner = readFileSync(ownership, "utf8");
    assert.equal(owner.match(/\.exec\(\s*["'`]/g)?.length, 2);
    assert.match(owner, /ownership\.exec\("BEGIN EXCLUSIVE"\)/);
    assert.match(owner, /ownership\s*\.prepare\("PRAGMA application_id"\)/);
    assert.match(owner, /ownership\.exec\(`PRAGMA application_id =/);
    assert.equal(owner.match(/\.prepare\(/g)?.length, 1);
    assert.doesNotMatch(owner, /\b(?:CREATE|SELECT|INSERT|UPDATE|DELETE)\s/);
  });

  it("keeps the production update transport in its server and updates packages", () => {
    const sources = shippedSources();
    const http2 = /from "node:http2"/;
    const updateProtocol = /gen\/seekervault\/update\/v1\/update_pb\.js/;
    const http2OutsideServer = sources.filter(
      (file) =>
        relative(SRC, file) !== "server.ts" &&
        http2.test(readFileSync(file, "utf8")),
    );
    const protocolOutsideUpdates = sources.filter((file) => {
      const path = relative(SRC, file);
      return (
        path !== "server.ts" &&
        !path.startsWith("updates/") &&
        updateProtocol.test(readFileSync(file, "utf8"))
      );
    });
    assert.deepEqual(
      http2OutsideServer.map((file) => relative(ROOT, file)),
      [],
    );
    assert.deepEqual(
      protocolOutsideUpdates.map((file) => relative(ROOT, file)),
      [],
    );
  });

  it("keeps the optional Firebase sender inside the audited Stage 5.3 boundary", () => {
    const firebaseAdmin = /from "firebase-admin\//;
    const sources = shippedSources();
    const outside = sources.filter(
      (file) =>
        !relative(SRC, file).startsWith("push/") &&
        firebaseAdmin.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      outside.map((file) => relative(ROOT, file)),
      [],
    );
    assert.match(
      readFileSync(join(SRC, "push/fcm.ts"), "utf8"),
      firebaseAdmin,
      "push/fcm.ts is the one Firebase Admin boundary",
    );
    assert.doesNotMatch(
      readFileSync(join(SRC, "push/fcm.ts"), "utf8"),
      /console\.|\blog\(/,
      "the credential-bearing sender logs nothing",
    );
    assert.doesNotMatch(
      readFileSync(join(SRC, "server.ts"), "utf8"),
      /fcmSender\??\.send/,
      "server routes sends through SAW-056's audited invalidation dispatcher",
    );
    assert.match(
      readFileSync(join(SRC, "server.ts"), "utf8"),
      /invalidationSender: fcmSender/,
      "the host supplies the audited sender through the public SDK option",
    );
    const invalidation = readFileSync(
      join(SDK_SRC, "push/invalidation.ts"),
      "utf8",
    );
    assert.match(
      invalidation,
      /data: \{ \.\.\.FCM_INVALIDATION_DATA \}/,
      "the app-visible data is copied only from the fixed two-field constant",
    );
    assert.doesNotMatch(
      invalidation,
      /notification\s*:/,
      "the sidecar sends no Firebase notification body",
    );
  });

  it("keeps the optional OAuth profile on the agent's endpoint, and issues nothing", () => {
    // SAW-036 makes the sidecar an OAuth resource server for /mcp and no more than that. The
    // authorization server is somebody else's product: nothing here authorizes a user, registers a
    // client, or mints a token, and the only thing it reads from that server is public keys.
    const sources = shippedSources();
    const jose = /from "jose"/;
    const outside = sources.filter(
      (file) =>
        relative(SRC, file) !== "oauth.ts" &&
        jose.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      outside.map((file) => relative(ROOT, file)),
      [],
      "token validation lives in oauth.ts",
    );

    // Three files know OAuth exists: the configuration that turns it on, the agent's endpoint
    // that enforces it, and the server that publishes the metadata document. The phone's API,
    // pairing, the request service, and the update stream have never heard of it.
    const importers = sources
      .filter((file) => /from "\.\/oauth\.ts"/.test(readFileSync(file, "utf8")))
      .map((file) => relative(SRC, file))
      .sort();
    assert.deepEqual(importers, ["config.ts", "mcp-endpoint.ts", "server.ts"]);

    const issuing =
      /client_secret|SignJWT|registration_endpoint|token_endpoint/;
    const issuers = sources.filter((file) =>
      issuing.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(
      issuers.map((file) => relative(ROOT, file)),
      [],
      "nothing here issues or exchanges a token",
    );
  });

  it("keeps MCP an optional adapter that reaches the core through one boundary", () => {
    // SEE-87: MCP is one way an agent reaches this sidecar, not what the sidecar is
    // (docs/wiki/mcp-adapter.md). The adapter asks the request core through
    // the package root's AgentRequests interface, so it cannot reach around idempotency,
    // validation, the lifecycle or the pending limit — and a second adapter has one named surface
    // rather than a new set of reach-ins.
    const adapters = ["mcp-endpoint.ts", "requests/mcp-tools.ts"].map((path) =>
      join(SRC, path),
    );
    for (const file of adapters) assert.ok(existsSync(file), file);
    const collaborators =
      /\b(RequestStore|TransactionPreparer|ConfirmationTracker)\b/;
    assert.deepEqual(
      adapters
        .filter((file) => collaborators.test(readFileSync(file, "utf8")))
        .map((file) => relative(SRC, file)),
      [],
      "an adapter names the core's classes again instead of the boundary",
    );

    // And the core knows nothing about the adapter. Only the file that composes the process may
    // name it, so turning it off cannot leave a dangling reference in the request core, the phone
    // API, pairing, updates, or push.
    const composition = new Set(["server.ts", "mcp-endpoint.ts"]);
    const importers = shippedSources()
      .filter((file) => !composition.has(relative(SRC, file)))
      .filter((file) =>
        /from "\.{1,2}\/(mcp-endpoint|requests\/mcp-tools)\.ts"/.test(
          readFileSync(file, "utf8"),
        ),
      )
      .map((file) => relative(SRC, file));
    assert.deepEqual(importers, []);

    // The boundary itself forwards and holds nothing: no listener, no credential, no SQL, and no
    // store of its own. The host imports it through the package root, never its source path.
    const api = readFileSync(join(SDK_SRC, "requests/agent-api.ts"), "utf8");
    for (const forbidden of [
      /createServer/,
      /\bSELECT\b/,
      /\bnew RequestStore\b/,
      /Authorization/,
      /bearerToken/,
    ]) {
      assert.ok(
        !forbidden.test(api),
        `agent-api.ts matches ${String(forbidden)}`,
      );
    }
    for (const file of adapters) {
      assert.doesNotMatch(
        readFileSync(file, "utf8"),
        /server-sdk\/src|@seeker-vault\/server-sdk\/(?!protocol["'])/,
        `${relative(SRC, file)} bypasses the package's public root API`,
      );
    }

    // The switch is one setting, and the endpoint is built in one place.
    const config = readFileSync(join(SRC, "config.ts"), "utf8");
    assert.match(config, /MCP_ENABLED/);
    const constructions = shippedSources().filter((file) =>
      /\bcreateMcpEndpoint\(/.test(readFileSync(file, "utf8")),
    );
    assert.deepEqual(constructions.map((file) => relative(SRC, file)).sort(), [
      "mcp-endpoint.ts",
      "server.ts",
    ]);
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
