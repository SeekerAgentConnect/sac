/**
 * Move one component to a new version, everywhere that version is written (SEE-182).
 *
 *   pnpm release:bump <component> <version> [--version-code <n>] [--allow-lower]
 *
 * release/components.json is the source of truth; this writes the new version there, into the
 * component's package.json when it has one, into each `versionConstants` file, and — for
 * `android` — advances `versionCode` (by one unless `--version-code` names a higher one) and
 * records the code it replaces as `previousVersionCode`. Nothing else moves: bumping the SDK does
 * not bump either MCP server, and a server that wants the new SDK bytes is bumped on its own.
 *
 * The files are edited in place rather than re-serialised, so their formatting survives and the
 * diff is exactly the version lines. `pnpm check:release` then proves they agree.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

import { compareVersions, SEMVER } from "./release-plan.mjs";

const ROOT = resolve(import.meta.dirname, "..");

export function bump(
  { id, version, versionCode, allowLower = false },
  root = ROOT,
) {
  const manifestPath = join(root, "release", "components.json");
  const text = readFileSync(manifestPath, "utf8");
  const manifest = JSON.parse(text);
  const component = manifest.components.find((entry) => entry.id === id);
  if (!component) {
    throw new Error(
      `${id} is not a component (have: ${manifest.components.map((entry) => entry.id).join(", ")})`,
    );
  }
  if (!SEMVER.test(version)) {
    throw new Error(
      `${version} is not X.Y.Z or X.Y.Z-(rc|alpha|beta).N; the release workflow would refuse it`,
    );
  }
  const order = compareVersions(version, component.version);
  if (order === 0) throw new Error(`${id} is already ${version}`);
  if (order < 0 && !allowLower) {
    throw new Error(
      `${version} is lower than ${id}'s current ${component.version}; pass --allow-lower only ` +
        "to set a deliberate new baseline (docs/development/releases.md)",
    );
  }

  const changed = [];
  let next = replaceInComponent(
    text,
    id,
    `"version": "${component.version}"`,
    `"version": "${version}"`,
  );

  if (component.android) {
    const current = component.android.versionCode;
    const code = versionCode ?? current + 1;
    if (!Number.isInteger(code) || code <= current) {
      throw new Error(
        `versionCode must be a whole number above the current ${current}, not ${code}: ` +
          "Android refuses to update an installed app to a lower or equal code",
      );
    }
    next = replaceInComponent(
      next,
      id,
      `"versionCode": ${current}`,
      `"versionCode": ${code}`,
    );
    next = replaceInComponent(
      next,
      id,
      `"previousVersionCode": ${component.android.previousVersionCode}`,
      `"previousVersionCode": ${current}`,
    );
  } else if (versionCode !== undefined) {
    throw new Error(`${id} is not the Android app; it has no versionCode`);
  }

  JSON.parse(next);
  writeFileSync(manifestPath, next);
  changed.push("release/components.json");

  if (component.npm) {
    const path = `${component.directory}/package.json`;
    edit(
      root,
      path,
      `"version": "${component.version}"`,
      `"version": "${version}"`,
    );
    changed.push(path);
  }
  for (const path of component.versionConstants) {
    edit(
      root,
      path,
      `VERSION = "${component.version}"`,
      `VERSION = "${version}"`,
    );
    changed.push(path);
  }
  return { from: component.version, to: version, changed };
}

/** Replace `before` once, inside the one component object that declares `"id": "<id>"`. */
function replaceInComponent(text, id, before, after) {
  const start = text.indexOf(`"id": "${id}"`);
  if (start === -1) throw new Error(`cannot find ${id} in the manifest text`);
  const following = text.indexOf(`"id": "`, start + 1);
  const end = following === -1 ? text.length : following;
  const block = text.slice(start, end);
  if (block.split(before).length !== 2) {
    throw new Error(`expected exactly one ${before} in ${id}'s manifest entry`);
  }
  return text.slice(0, start) + block.replace(before, after) + text.slice(end);
}

function edit(root, path, before, after) {
  const file = join(root, path);
  const text = readFileSync(file, "utf8");
  if (text.split(before).length !== 2) {
    throw new Error(`expected exactly one ${before} in ${path}`);
  }
  writeFileSync(file, text.replace(before, after));
}

export function parseArguments(argv) {
  const positional = [];
  const options = { allowLower: false };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--allow-lower") options.allowLower = true;
    else if (argument === "--version-code") {
      const value = argv[(index += 1)];
      if (!/^\d+$/.test(value ?? "")) {
        throw new Error("--version-code needs a whole number");
      }
      options.versionCode = Number(value);
    } else if (argument.startsWith("--")) {
      throw new Error(`unknown option ${argument}`);
    } else positional.push(argument);
  }
  if (positional.length !== 2) {
    throw new Error(
      "usage: pnpm release:bump <component> <version> [--version-code <n>] [--allow-lower]",
    );
  }
  return { id: positional[0], version: positional[1], ...options };
}

if (process.argv[1] === import.meta.filename) {
  try {
    const result = bump(parseArguments(process.argv.slice(2)));
    console.log(`${result.from} -> ${result.to}`);
    for (const path of result.changed) console.log(`  updated ${path}`);
    console.log(
      `Next: pnpm check:release, commit, merge, then tag ${process.argv[2]}-v${result.to}`,
    );
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
