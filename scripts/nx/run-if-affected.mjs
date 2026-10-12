// Run one project's target when Nx considers it affected. A miss is a successful no-op.
// NX_BASE and NX_HEAD select the range. Used by CI (SEE-187).
import { execFileSync } from "node:child_process";

const project = process.argv[2];
const target = process.argv[3] ?? "check";
const base = process.env.NX_BASE;
const head = process.env.NX_HEAD || "HEAD";
if (!project || !base) {
  console.error(
    "usage: NX_BASE=<sha> node scripts/nx/run-if-affected.mjs <project> [target]",
  );
  process.exit(1);
}

const listed = execFileSync(
  "pnpm",
  [
    "exec",
    "nx",
    "show",
    "projects",
    "--affected",
    "--base",
    base,
    "--head",
    head,
  ],
  { encoding: "utf8" },
);
const affected = new Set(
  listed
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean),
);
if (!affected.has(project)) {
  console.log(`${project} is not affected (${base.slice(0, 12)}..${head})`);
  process.exit(0);
}
execFileSync("pnpm", ["exec", "nx", "run", `${project}:${target}`], {
  stdio: "inherit",
});
