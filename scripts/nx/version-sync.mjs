/**
 * Write one component's version everywhere Nx Release decided it (SEE-187).
 *
 * `release/components.json` stays the file Gradle and the publish workflow read. Nx Release is the
 * only writer: this updates that version, the npm manifest, and the in-source constant, in place,
 * so the diff is the version lines. Android's `versionCode` advances by one when the version
 * changes and is left alone when the version is already the one being written — regenerating a
 * release PR, or retrying publication, must not mint a second code.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { compareVersions, SEMVER } from "../release-plan.mjs";

export function syncProjectVersion(io, id, version) {
  if (!SEMVER.test(version)) {
    throw new Error(
      `${version} is not X.Y.Z or X.Y.Z-(rc|alpha|beta).N; the release workflow would refuse it`,
    );
  }
  const manifestPath = "release/components.json";
  const text = io.read(manifestPath);
  const manifest = JSON.parse(text);
  const component = manifest.components.find((entry) => entry.id === id);
  if (!component) {
    throw new Error(
      `${id} is not a component (have: ${manifest.components.map((entry) => entry.id).join(", ")})`,
    );
  }
  if (component.version === version) {
    return {
      changed: [],
      unchanged: true,
      from: version,
      to: version,
      versionCode: component.android?.versionCode ?? null,
    };
  }
  const order = compareVersions(version, component.version);
  if (order < 0) {
    throw new Error(
      `${version} is lower than ${id}'s current ${component.version}`,
    );
  }

  const changed = [];
  let next = replaceInComponent(
    text,
    id,
    `"version": "${component.version}"`,
    `"version": "${version}"`,
  );
  let versionCode = null;
  if (component.android) {
    const current = component.android.versionCode;
    versionCode = current + 1;
    next = replaceInComponent(
      next,
      id,
      `"versionCode": ${current}`,
      `"versionCode": ${versionCode}`,
    );
    next = replaceInComponent(
      next,
      id,
      `"previousVersionCode": ${component.android.previousVersionCode}`,
      `"previousVersionCode": ${current}`,
    );
  }
  JSON.parse(next);
  io.write(manifestPath, next);
  changed.push(manifestPath);

  if (component.npm) {
    const path = `${component.directory}/package.json`;
    edit(
      io,
      path,
      `"version": "${component.version}"`,
      `"version": "${version}"`,
    );
    changed.push(path);
  }
  for (const path of component.versionConstants) {
    edit(
      io,
      path,
      `VERSION = "${component.version}"`,
      `VERSION = "${version}"`,
    );
    changed.push(path);
  }
  return {
    changed,
    unchanged: false,
    from: component.version,
    to: version,
    versionCode,
  };
}

/** Filesystem adapter around {@link syncProjectVersion}. */
export function syncProjectVersionAt(root, id, version) {
  return syncProjectVersion(
    {
      read: (path) => readFileSync(join(root, path), "utf8"),
      write: (path, contents) => writeFileSync(join(root, path), contents),
    },
    id,
    version,
  );
}

function edit(io, path, before, after) {
  const text = io.read(path);
  if (text.split(before).length !== 2) {
    throw new Error(`expected exactly one ${before} in ${path}`);
  }
  io.write(path, text.replace(before, after));
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
