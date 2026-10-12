/**
 * Per-component changelog entries rendered by Nx's changelog renderer (SEE-187).
 *
 * The commit set is the release plan's, not "every file under the project root". That is what
 * keeps a Centrifugo-only change out of the gateway changelog and the reverse.
 */
import { createRequire } from "node:module";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

import { rawBump } from "./boundaries.mjs";

const require = createRequire(import.meta.url);
const rendererModule = require("nx/release/changelog-renderer");
const DefaultChangelogRenderer = rendererModule.default ?? rendererModule;

const CHANGELOG = {
  "server-sdk": "packages/server-sdk/CHANGELOG.md",
  "mcp-server": "servers/mcp-server/CHANGELOG.md",
  "mcp-skr-staking": "servers/mcp-skr-staking/CHANGELOG.md",
  gateway: "services/gateway/CHANGELOG.md",
  "gateway-centrifugo": "services/gateway/centrifugo/CHANGELOG.md",
  "demo-signals": "examples/demo-signals/CHANGELOG.md",
  "demo-prediction": "examples/demo-prediction/CHANGELOG.md",
  android: "apps/android/CHANGELOG.md",
};

const HEADER =
  /^(?<type>feat|fix|perf|revert|docs|style|refactor|test|build|ci|chore)(?:\((?<scope>[a-z0-9-]+)\))?(?<breaking>!)?: (?<subject>.+)$/;

export function changelogPath(id) {
  const path = CHANGELOG[id];
  if (!path) throw new Error(`no changelog path for ${id}`);
  return path;
}

function changeFromCommit(commit) {
  const match = HEADER.exec(commit.message.split("\n")[0]);
  if (!match?.groups) return null;
  return {
    type: match.groups.type,
    scope: match.groups.scope ?? "",
    description: match.groups.subject,
    affectedProjects: "*",
    body: commit.body,
    isBreaking: rawBump(commit.message, commit.body) === "major",
    shortHash: commit.hash?.slice(0, 7),
    authors: [],
  };
}

export async function renderEntry(id, version, project) {
  const changes = [];
  const seen = new Set();
  for (const commit of project.commits) {
    if (!commit?.hash || seen.has(commit.hash)) continue;
    seen.add(commit.hash);
    const change = changeFromCommit(commit);
    if (change) changes.push(change);
  }
  const dependencyBumps = project.vendored
    ? [
        {
          dependencyName: "@seekeragentconnect/server-sdk",
          newVersion: project.sdkVersion,
        },
      ]
    : [];
  const renderer = new DefaultChangelogRenderer({
    changes,
    changelogEntryVersion: version,
    project: id,
    entryWhenNoChanges: project.vendored
      ? `Bundled server-sdk ${project.sdkVersion}.`
      : false,
    isVersionPlans: false,
    changelogRenderOptions: {
      authors: false,
      applyUsernameToAuthors: false,
      commitReferences: false,
      versionTitleDate: true,
    },
    dependencyBumps,
    conventionalCommitsConfig: {
      types: {
        feat: {
          semverBump: "minor",
          changelog: { title: "Features", hidden: false },
        },
        fix: {
          semverBump: "patch",
          changelog: { title: "Fixes", hidden: false },
        },
        perf: {
          semverBump: "patch",
          changelog: { title: "Performance", hidden: false },
        },
        revert: {
          semverBump: "patch",
          changelog: { title: "Reverts", hidden: false },
        },
        docs: {
          semverBump: "none",
          changelog: { hidden: true, title: "Documentation" },
        },
        style: {
          semverBump: "none",
          changelog: { hidden: true, title: "Styles" },
        },
        refactor: {
          semverBump: "none",
          changelog: { hidden: true, title: "Refactors" },
        },
        test: {
          semverBump: "none",
          changelog: { hidden: true, title: "Tests" },
        },
        build: {
          semverBump: "none",
          changelog: { hidden: true, title: "Build" },
        },
        ci: { semverBump: "none", changelog: { hidden: true, title: "CI" } },
        chore: {
          semverBump: "none",
          changelog: { hidden: true, title: "Chore" },
        },
      },
    },
    remoteReleaseClient: {
      getRemoteRepoData: () => null,
      formatReferences: () => "",
    },
  });
  return renderer.render();
}

export async function writeChangelog(root, id, version, project) {
  const path = changelogPath(id);
  const entry = (await renderEntry(id, version, project)).trim();
  const absolute = join(root, path);
  const title = `# ${id}`;
  const existing = existsSync(absolute)
    ? readFileSync(absolute, "utf8")
    : `${title}\n`;
  const body = existing.startsWith("# ")
    ? existing.replace(/^# .+\n?/, `${title}\n\n${entry}\n\n`)
    : `${title}\n\n${entry}\n\n${existing}`;
  mkdirSync(dirname(absolute), { recursive: true });
  writeFileSync(absolute, body.endsWith("\n") ? body : `${body}\n`);
  return path;
}
