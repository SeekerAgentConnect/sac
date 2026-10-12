/**
 * Squash-merge uses the pull request title as the commit Nx Release will read (SEE-187).
 *
 *   TITLE='feat(gateway): describe the change' node scripts/check-commit-title.mjs
 *
 * A pull request that changes `release/plan.json` must be titled `chore(release): ...`, so merging
 * it publishes that plan instead of being classified as another product change.
 */
import { execFileSync } from "node:child_process";

const HEADER =
  /^(build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test)(\([a-z0-9-]+\))?!?: [^ ].+/;

const title = process.env.TITLE ?? process.argv[2] ?? "";
if (!HEADER.test(title)) {
  console.error(
    [
      `Pull request title is not a Conventional Commit: ${JSON.stringify(title)}`,
      "Use `<type>(<scope>): <subject>`, for example `feat(gateway): describe the change`.",
      "Types: build, chore, ci, docs, feat, fix, perf, refactor, revert, style, test.",
      "A breaking change is `feat!:` or `feat(scope)!:`, or a BREAKING CHANGE footer on the squash commit.",
      "This repository squash-merges, and Nx Release versions from that title (docs/development/releases.md).",
    ].join("\n"),
  );
  process.exit(1);
}

const base = process.env.BASE ?? "";
if (base) {
  let names = "";
  try {
    names = execFileSync("git", ["diff", "--name-only", `${base}...HEAD`], {
      encoding: "utf8",
    });
  } catch {
    names = "";
  }
  const releasePlan = names
    .split("\n")
    .some((file) => file === "release/plan.json");
  if (releasePlan && !title.startsWith("chore(release):")) {
    console.error(
      "This pull request changes release/plan.json, so its title must start with `chore(release):`.",
    );
    process.exit(1);
  }
}

console.log(title);
