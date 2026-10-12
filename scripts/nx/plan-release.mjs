/**
 * The release plan Nx Release will apply (SEE-187).
 *
 * Current versions come from the `<component>-v<version>` tags, which are the published releases.
 * Commits since each component's own tag are classified by scripts/nx/boundaries.mjs. A commit is
 * counted only for the component whose tag range it falls in, so a change already shipped in one
 * component is not planned again because another component's tag is older. Nx turns the raw bump
 * into a version (`adjustSemverBumpsForZeroMajorVersion`, and `--preid` for rc/alpha/beta).
 * Nothing here tags or publishes.
 */
import { execFileSync } from "node:child_process";
import { resolve } from "node:path";

import { compareVersions, loadManifest, tagFor } from "../release-plan.mjs";
import {
  RELEASED,
  VENDORS_SERVER_SDK,
  classifyCommits,
  maxBump,
} from "./boundaries.mjs";

const ROOT = resolve(import.meta.dirname, "../..");

export function tagCommit(root, tag) {
  try {
    return execFileSync("git", ["rev-list", "-n", "1", tag], {
      cwd: root,
      encoding: "utf8",
    }).trim();
  } catch {
    return "";
  }
}

/**
 * Commits after `from` up to `to`, oldest first, each with the files it touched.
 * `from` is excluded.
 */
export function commitsBetween(root, from, to = "HEAD") {
  let output = "";
  try {
    output = execFileSync(
      "git",
      [
        "log",
        "--reverse",
        `${from}..${to}`,
        "--pretty=format:%x1d%H%x1f%s%x1f%b%x1e",
        "--name-only",
      ],
      { cwd: root, encoding: "utf8" },
    );
  } catch (error) {
    throw new Error(`git log ${from}..${to} failed: ${error.message}`);
  }
  if (!output.trim()) return [];
  const commits = [];
  for (const record of output.split("\x1d").slice(1)) {
    const end = record.indexOf("\x1e");
    const header = end === -1 ? record : record.slice(0, end);
    const rest = end === -1 ? "" : record.slice(end + 1);
    const [hash, subject, body = ""] = header.split("\x1f");
    const files = rest
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean);
    commits.push({ hash, message: subject, body: body.trim(), files });
  }
  return commits;
}

/**
 * @returns {{
 *   pendingPublication: { id: string, disk: string }[],
 *   projects: { id: string, from: string, bump: string, vendored: boolean, commits: object[] }[],
 * }}
 */
export function planRelease(root = ROOT, { to = "HEAD" } = {}) {
  const manifest = loadManifest(root);
  const pendingPublication = [];
  /** @type {Map<string, { id: string, from: string, bump: string, vendored: boolean, commits: object[] }>} */
  const byProject = new Map();

  for (const id of RELEASED) {
    const component = manifest.components.find((entry) => entry.id === id);
    if (!component) throw new Error(`manifest has no ${id}`);
    const tag = tagFor(id, component.version);
    const from = tagCommit(root, tag);
    if (!from) {
      pendingPublication.push({ id, disk: component.version });
      continue;
    }
    const commits = commitsBetween(root, from, to);
    const entry = classifyCommits(commits).get(id);
    if (!entry) continue;
    byProject.set(id, {
      id,
      from: component.version,
      bump: entry.bump,
      vendored: false,
      commits: entry.commits,
    });
  }

  // Bundled SDK bytes ship inside each MCP server. An SDK release that the server did not
  // otherwise touch still has to release that server, at least as a patch.
  if (
    byProject.has("server-sdk") &&
    !pendingPublication.some((item) => item.id === "server-sdk")
  ) {
    for (const id of VENDORS_SERVER_SDK) {
      if (pendingPublication.some((item) => item.id === id)) continue;
      const component = manifest.components.find((entry) => entry.id === id);
      const existing = byProject.get(id);
      if (!existing) {
        byProject.set(id, {
          id,
          from: component.version,
          bump: "patch",
          vendored: true,
          commits: [],
        });
      } else {
        existing.bump = maxBump(existing.bump, "patch");
        existing.vendored = true;
      }
    }
  }

  const projects = [...byProject.values()].sort((left, right) =>
    left.id < right.id ? -1 : 1,
  );
  return { pendingPublication, projects };
}

/**
 * The specifier passed to `nx release version`. A prerelease channel prefixes `pre`, and Nx's
 * 0.x adjustment turns `preminor` into `prepatch` before 1.0. `semver.inc` only honours a preid
 * for the `pre*` bump types.
 */
export function specifierFor(rawBump, preid) {
  if (!preid) return rawBump;
  if (rawBump.startsWith("pre")) return rawBump;
  return `pre${rawBump}`;
}

export function groupBySpecifier(projects, preid) {
  /** @type {Map<string, string[]>} */
  const groups = new Map();
  for (const project of projects) {
    const specifier = specifierFor(project.bump, preid);
    const list = groups.get(specifier) ?? [];
    list.push(project.id);
    groups.set(specifier, list);
  }
  return groups;
}

/** Disk versions that are not on a tag yet. Publishing those has to finish before another plan. */
export function unpublished(root = ROOT) {
  const manifest = loadManifest(root);
  const waiting = [];
  for (const component of manifest.components) {
    if (!RELEASED.includes(component.id)) continue;
    if (!tagCommit(root, tagFor(component.id, component.version))) {
      waiting.push(component.id);
    }
  }
  return waiting;
}

export function versionsMoveForward(left, right) {
  return compareVersions(left, right);
}
