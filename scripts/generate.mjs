// Generates the protocol code (buf.gen.yaml for the phone, the two TypeScript templates for the
// direct SDK and the MCP server's remaining feed fixture, buf.gen.feed-gateway.yaml for
// the feed gateway, buf.gen.centrifugo.yaml for the phone's vendored broker schema,
// buf.gen.loadtest.yaml for the load harness) and the binary protobuf fixtures:
// proto/fixtures/<package path>/<Message>/<case>.json → <case>.binpb, via `buf convert`.
//
//   pnpm generate           write the output into the repository
//   pnpm check:generated    generate into a temporary directory; fail if committed files differ
//
// Run it through pnpm so the pinned buf and protoc-gen-es from node_modules/.bin are on PATH.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const fixturesDir = "proto/fixtures";
// Owned entirely by `buf generate` (clean: true); --check compares every file in them.
const generatedDirs = [
  "server-sdk/src/gen",
  "mcp-server/src/gen",
  "android/app/src/main/generated",
  "feed-gateway/internal/gen",
  "publisher-support/gen",
  "loadtest/internal/gen",
];
// One template per runtime pair. buf.gen.yaml writes the phone's Kotlin and the sidecar's
// TypeScript; buf.gen.feed-gateway.yaml writes the feed gateway's Go, which is a different subset of
// the protocol (SEE-90); buf.gen.publisher-support.yaml writes the public-feed demos' shared Go,
// which is a third subset — it publishes and never reads a feed (SEE-95, SEE-134); buf.gen.centrifugo.yaml writes the
// phone's client for the vendored broker schema, which none of the others speaks (SEE-91); and
// buf.gen.loadtest.yaml writes the load harness's Go, which is the only place both sides of a feed
// and that broker schema are compiled together, because measuring a publication's journey means
// holding all three ends of it (SEE-99). Each template cleans only its own output directories, so
// the order is not load-bearing.
const templates = [
  "buf.gen.yaml",
  "buf.gen.server-sdk.yaml",
  "buf.gen.mcp-server.yaml",
  "buf.gen.feed-gateway.yaml",
  "buf.gen.publisher-support.yaml",
  "buf.gen.centrifugo.yaml",
  "buf.gen.loadtest.yaml",
];

// Schemas we did not write, with the digest of the release they were copied from
// (third_party/<name>/SHA256SUMS). Generating from an edited copy would produce a client for a
// protocol no server speaks, so the digest is checked before anything is generated: an upgrade is
// an edit to the file and to its digest, in one commit, on purpose.
const vendored = ["third_party/centrifugo"];

const check = process.argv.includes("--check");
const out = check
  ? mkdtempSync(join(tmpdir(), "seeker-vault-generate-"))
  : root;

try {
  for (const directory of vendored) verifyVendored(directory);
  for (const template of templates) {
    buf("generate", "--template", template, "--output", out);
  }

  const fixtures = listFiles(join(root, fixturesDir));
  if (!check) {
    for (const file of fixtures.filter((f) => f.endsWith(".binpb"))) {
      rmSync(join(root, fixturesDir, file));
    }
  }
  for (const file of fixtures.filter((f) => f.endsWith(".json"))) {
    const target = join(out, fixturesDir, file.replace(/\.json$/, ".binpb"));
    mkdirSync(dirname(target), { recursive: true });
    const type = dirname(file).split(sep).join(".");
    buf(
      "convert",
      ".",
      "--type",
      type,
      "--from",
      join(fixturesDir, file),
      "--to",
      target,
    );
  }

  if (check) {
    const differences = [
      ...generatedDirs.flatMap((dir) => compare(dir)),
      ...compare(fixturesDir, (file) => file.endsWith(".binpb")),
    ];
    if (differences.length > 0) {
      console.error(
        [
          "Generated files are out of date:",
          ...differences.map((difference) => `  ${difference}`),
          "Run `pnpm generate` and commit the result.",
        ].join("\n"),
      );
      process.exitCode = 1;
    } else {
      console.log("Generated code and fixtures are up to date.");
    }
  }
} finally {
  if (check) rmSync(out, { recursive: true, force: true });
}

// Reads a `shasum -a 256` file and checks every line of it, so the same file a human can run
// `shasum -a 256 -c SHA256SUMS` against is the one this script trusts.
function verifyVendored(directory) {
  const sums = join(root, directory, "SHA256SUMS");
  for (const line of readFileSync(sums, "utf8").trim().split("\n")) {
    const [expected, file] = line.trim().split(/\s+/);
    const actual = createHash("sha256")
      .update(readFileSync(join(root, directory, file)))
      .digest("hex");
    if (actual !== expected) {
      console.error(
        [
          `${directory}/${file} is not the copy ${directory}/SHA256SUMS records.`,
          `  recorded ${expected}`,
          `  found    ${actual}`,
          `Restore the vendored file, or record the new digest deliberately: see ${directory}/README.md.`,
        ].join("\n"),
      );
      process.exit(1);
    }
  }
}

function buf(...args) {
  execFileSync("buf", args, { cwd: root, stdio: "inherit" });
}

function listFiles(dir) {
  try {
    return readdirSync(dir, { recursive: true, withFileTypes: true })
      .filter((entry) => entry.isFile())
      .map((entry) => relative(dir, join(entry.parentPath, entry.name)));
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
}

function compare(dir, include = () => true) {
  const generated = listFiles(join(out, dir)).filter(include);
  const committed = listFiles(join(root, dir)).filter(include);
  const differences = [];
  for (const file of new Set([...generated, ...committed])) {
    const path = join(dir, file);
    if (!generated.includes(file)) {
      differences.push(`${path} (no longer generated)`);
    } else if (!committed.includes(file)) {
      differences.push(`${path} (missing)`);
    } else if (
      !readFileSync(join(out, path)).equals(readFileSync(join(root, path)))
    ) {
      differences.push(`${path} (changed)`);
    }
  }
  return differences;
}
