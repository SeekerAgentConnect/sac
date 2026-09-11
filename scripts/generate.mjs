// Generates the protocol code (buf.gen.yaml) and the binary protobuf fixtures:
// proto/fixtures/<package path>/<Message>/<case>.json → <case>.binpb, via `buf convert`.
//
//   pnpm generate           write the output into the repository
//   pnpm check:generated    generate into a temporary directory; fail if committed files differ
//
// Run it through pnpm so the pinned buf and protoc-gen-es from node_modules/.bin are on PATH.
import { execFileSync } from "node:child_process";
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
const generatedDirs = ["sidecar/src/gen", "android/app/src/main/generated"];

const check = process.argv.includes("--check");
const out = check
  ? mkdtempSync(join(tmpdir(), "seeker-vault-generate-"))
  : root;

try {
  buf("generate", "--template", "buf.gen.yaml", "--output", out);

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
