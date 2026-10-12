/**
 * The merged release plan is the list publication may tag (SEE-187).
 *
 * It checks `release/plan.json` against `release/components.json` at this commit and refuses a
 * tag that already points somewhere else. It does not create tags or publish.
 */
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";

import { loadManifest, tagFor } from "../release-plan.mjs";

const ROOT = resolve(import.meta.dirname, "../..");

export function readReleasePlan(root = ROOT) {
  return JSON.parse(readFileSync(join(root, "release/plan.json"), "utf8"));
}

export function assertReleasePlan(root = ROOT, commit = "") {
  const plan = readReleasePlan(root);
  const manifest = loadManifest(root);
  if (!Array.isArray(plan.components) || plan.components.length === 0) {
    throw new Error("release/plan.json has no components");
  }
  const lines = [];
  for (const entry of plan.components) {
    const component = manifest.components.find((item) => item.id === entry.id);
    if (!component) throw new Error(`${entry.id} is not in the manifest`);
    if (component.version !== entry.version) {
      throw new Error(
        `${entry.id} plan says ${entry.version} but release/components.json pins ${component.version}`,
      );
    }
    const tag = tagFor(entry.id, entry.version);
    if (entry.tag !== tag) {
      throw new Error(`${entry.id} plan tag ${entry.tag} is not ${tag}`);
    }
    if (commit) {
      const existing = tagPointsAt(root, tag);
      if (existing && existing !== commit) {
        throw new Error(
          `${tag} already points at ${existing.slice(0, 12)}, not ${commit.slice(0, 12)}`,
        );
      }
    }
    lines.push({ id: entry.id, version: entry.version, tag });
  }
  return { channel: plan.channel, components: lines };
}

function tagPointsAt(root, tag) {
  try {
    return execFileSync("git", ["rev-list", "-n", "1", tag], {
      cwd: root,
      encoding: "utf8",
    }).trim();
  } catch {
    return "";
  }
}

if (process.argv[1] === import.meta.filename) {
  const commit = process.argv[2] ?? "";
  const plan = assertReleasePlan(ROOT, commit);
  for (const component of plan.components) {
    console.log(`${component.id} ${component.version} ${component.tag}`);
  }
}
