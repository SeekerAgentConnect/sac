/**
 * Prepare the release Nx Release will describe (SEE-187).
 *
 *   node scripts/nx/prepare-release.mjs                 # print the plan; write nothing
 *   node scripts/nx/prepare-release.mjs --apply         # write versions, changelogs, release/plan.json
 *   node scripts/nx/prepare-release.mjs --preid=rc      # prerelease channel
 *
 * `--apply` does not commit, tag, or publish. The release-prepare workflow commits the result
 * onto `release/next`. Publication is release-publish.yml dispatching release.yml.
 */
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";

import { releaseVersion } from "nx/release";

import { tagFor } from "../release-plan.mjs";
import { writeChangelog } from "./changelog.mjs";
import { groupBySpecifier, planRelease } from "./plan-release.mjs";

const ROOT = resolve(import.meta.dirname, "../..");

process.env.NX_DAEMON = "false";
process.env.NX_TUI = "false";
process.env.NX_NO_CLOUD = "true";
if (!process.env.CI) process.env.CI = "true";

const apply = process.argv.includes("--apply");
const preidArg = process.argv.find((arg) => arg.startsWith("--preid="));
const preid = preidArg ? preidArg.slice("--preid=".length) : "";
if (preid && !["rc", "alpha", "beta"].includes(preid)) {
  console.error("--preid must be rc, alpha, or beta");
  process.exit(1);
}

const plan = planRelease(ROOT);
if (plan.pendingPublication.length > 0) {
  console.log(
    `Publication is still pending for ${plan.pendingPublication
      .map((item) => item.id)
      .join(
        ", ",
      )}: the manifest version has no <component>-v<version> tag yet.`,
  );
  console.log(
    "Not opening another release PR. Publish or tag that release first (docs/development/releases.md).",
  );
  process.exit(0);
}
if (plan.projects.length === 0) {
  console.log("Nothing to release.");
  process.exit(0);
}

const versionData = {};
for (const [specifier, projects] of groupBySpecifier(plan.projects, preid)) {
  const result = await releaseVersion({
    projects,
    specifier,
    preid: preid || undefined,
    dryRun: !apply,
    gitCommit: false,
    gitTag: false,
    stageChanges: false,
    verbose: true,
  });
  Object.assign(versionData, result.projectsVersionData);
}

const sdkVersion = versionData["server-sdk"]?.newVersion ?? null;
const components = [];
for (const project of plan.projects) {
  const version = versionData[project.id]?.newVersion;
  if (!version) {
    throw new Error(
      `Nx Release did not version ${project.id} (${project.bump})`,
    );
  }
  const unexpected = Object.entries(versionData).filter(
    ([id, entry]) =>
      entry?.newVersion && !plan.projects.some((item) => item.id === id),
  );
  if (unexpected.length > 0) {
    throw new Error(
      `Nx Release versioned projects outside the plan: ${unexpected
        .map(([id]) => id)
        .join(", ")}`,
    );
  }
  components.push({
    id: project.id,
    from: project.from,
    version,
    bump: project.bump,
    tag: tagFor(project.id, version),
    vendored: project.vendored,
  });
  if (!apply) continue;
  await writeChangelog(ROOT, project.id, version, {
    ...project,
    sdkVersion: sdkVersion ?? project.from,
  });
}

const document = {
  channel: preid || "stable",
  components,
};
if (apply) {
  writeFileSync(
    resolve(ROOT, "release/plan.json"),
    `${JSON.stringify(document, null, 2)}\n`,
  );
}
console.log(`RELEASE_PLAN ${JSON.stringify(document)}`);
