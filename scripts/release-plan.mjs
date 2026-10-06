/**
 * Turn a release tag (or a workflow dispatch) into the exact plan .github/workflows/release.yml
 * executes, and write it as `key=value` lines for `$GITHUB_OUTPUT` (SEE-168, SEE-182).
 *
 * The decisions that matter all live here rather than in YAML expressions, because they are the
 * ones worth testing: which component a tag names and whether the manifest at that commit agrees,
 * whether a manual run may publish at all, which dist-tag a version asks for, and which image tags
 * a build pushes. Every component has its own GHCR repository, so tags carry no component prefix:
 * `ghcr.io/seekeragentconnect/gateway:0.0.1`. Whether a stable alias actually moves is decided at
 * publication time against the live registry (scripts/release-state.mjs), because only the registry
 * knows whether a newer version already holds it. scripts/release-plan.test.mjs is the test, and it
 * runs with no registry and no credential.
 */
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(import.meta.dirname, "..");

export const SEMVER =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:rc|alpha|beta)\.(?:0|[1-9]\d*))?$/;

export function loadManifest(root = ROOT) {
  return JSON.parse(
    readFileSync(join(root, "release", "components.json"), "utf8"),
  );
}

/**
 * Semantic version precedence for the versions SEMVER admits: a release ranks above its own
 * prereleases, and prerelease identifiers compare left to right, numerically where both are.
 * Negative, zero or positive, like a sort comparator.
 */
export function compareVersions(left, right) {
  const split = (version) => {
    const [core, prerelease] = version.split("-");
    return [
      core.split(".").map(Number),
      prerelease ? prerelease.split(".") : null,
    ];
  };
  const [leftCore, leftPre] = split(left);
  const [rightCore, rightPre] = split(right);
  for (let index = 0; index < 3; index += 1) {
    if (leftCore[index] !== rightCore[index])
      return leftCore[index] - rightCore[index];
  }
  if (leftPre === null && rightPre === null) return 0;
  if (leftPre === null) return 1;
  if (rightPre === null) return -1;
  for (
    let index = 0;
    index < Math.max(leftPre.length, rightPre.length);
    index += 1
  ) {
    const a = leftPre[index];
    const b = rightPre[index];
    if (a === undefined) return -1;
    if (b === undefined) return 1;
    if (a === b) continue;
    const numeric = /^\d+$/.test(a) && /^\d+$/.test(b);
    return numeric ? Number(a) - Number(b) : a < b ? -1 : 1;
  }
  return 0;
}

export const tagFor = (id, version) => `${id}-v${version}`;
export const apkName = (component) =>
  component.android.apk.replace("<version>", component.version);

/**
 * @param {object} environment  the workflow's env block: TAG, INPUT_COMPONENT, INPUT_VERSION,
 *                              INPUT_CHANNEL, INPUT_DRY_RUN, COMMIT, TAG_COMMIT
 * @param {object} manifest     release/components.json at COMMIT
 */
export function releasePlan(environment, manifest) {
  const {
    TAG = "",
    INPUT_COMPONENT = "",
    INPUT_VERSION = "",
    INPUT_CHANNEL = "",
    COMMIT = "",
    TAG_COMMIT = "",
  } = environment;

  // A tag is the real trigger; a dispatch is the deliberate one, and it publishes nothing unless
  // it says `dry_run: false` in so many words.
  const tagged = TAG ? parseTag(TAG, manifest) : null;
  if (!tagged && !INPUT_COMPONENT) {
    throw new Error("no tag and no component input");
  }
  if (!/^[0-9a-f]{40}$/.test(COMMIT)) {
    throw new Error(`no usable commit sha: "${COMMIT}"`);
  }
  const id = tagged ? tagged.id : INPUT_COMPONENT;
  const development = tagged ? false : INPUT_CHANNEL === "development";
  const dryRun = tagged ? false : environment.INPUT_DRY_RUN !== "false";

  const component = manifest.components.find((entry) => entry.id === id);
  if (!component) {
    throw new Error(
      `${id} is not a component in release/components.json (have: ` +
        `${manifest.components.map((entry) => entry.id).join(", ")})`,
    );
  }

  const version = component.version;
  if (!SEMVER.test(version)) {
    throw new Error(`${id} has an unusable version: ${version}`);
  }
  // The tag is how a human names a release; the manifest at the tagged commit is what the build
  // uses. If they disagree, one of them is a mistake, and guessing which would publish the wrong
  // bytes under the right name.
  if (tagged && tagged.version !== version) {
    throw new Error(
      `tag ${TAG} asks for ${tagged.version} but release/components.json at ` +
        `${COMMIT.slice(0, 12)} pins ${id} at ${version}`,
    );
  }
  if (INPUT_VERSION && INPUT_VERSION !== version) {
    throw new Error(
      `the dispatch expects ${id} ${INPUT_VERSION} but release/components.json at ` +
        `${COMMIT.slice(0, 12)} pins ${version}`,
    );
  }

  const tag = tagFor(id, version);
  // A real release made by hand — a retry, usually — must be the same release the tag names: the
  // same component, version and commit. A dry run may validate any candidate commit.
  if (!tagged && !dryRun && !development) {
    if (!TAG_COMMIT) {
      throw new Error(
        `${tag} does not exist. A real release starts by pushing that tag; a manual run can ` +
          "only retry a tag that already exists",
      );
    }
    if (TAG_COMMIT !== COMMIT) {
      throw new Error(
        `${tag} points at ${TAG_COMMIT.slice(0, 12)}, but this run is on ${COMMIT.slice(0, 12)}. ` +
          `Run the workflow from the tag (Use workflow from: ${tag})`,
      );
    }
  }
  if (development && !component.image) {
    throw new Error(
      `${id} has no image; the development channel only pushes development images`,
    );
  }

  const prerelease = version.includes("-");
  const image = component.image;
  const plan = {
    component: id,
    version,
    tag,
    commit: COMMIT,
    prerelease,
    development,
    dry_run: dryRun,
    title: component.description,
    has_npm: Boolean(component.npm) && !development,
    has_image: Boolean(image),
    has_android: Boolean(component.android) && !development,
    checks: JSON.stringify(component.checks),
    go_version_file: component.toolchains?.go ?? "",
    java_version: component.toolchains?.java ?? "",
    npm_name: component.npm?.name ?? "",
    npm_directory: component.npm?.packDirectory ?? "",
    npm_version: manifest.toolchain.npm,
    // A prerelease asks for `next`. Nothing but a stable version ever asks for `latest`, which is
    // what a plain `npm install <name>` resolves to. Whether the ask is granted — whether a newer
    // version already holds the tag — is decided against the registry when publishing.
    npm_tag: prerelease ? "next" : "latest",
    image_repository: image?.repository ?? "",
    dockerfile: image?.dockerfile ?? "",
    context: image?.context ?? ".",
    target: image?.target ?? "",
    platforms: image?.platforms?.join(",") ?? "",
    image_version_tag: "",
    image_tags: "",
    image_stable_alias: "",
    android_application_id: component.android?.applicationId ?? "",
    android_version_code: component.android?.versionCode ?? "",
    android_requires: component.android?.releaseRequires?.join(",") ?? "",
    apk_name: component.android ? apkName(component) : "",
  };

  if (image) {
    const tags = imageTags({
      repository: image.repository,
      version,
      prerelease,
      development,
      commit: COMMIT,
    });
    plan.image_version_tag = tags.version;
    plan.image_tags = tags.push.join("\n");
    plan.image_stable_alias = tags.stableAlias;
  }
  return plan;
}

/**
 * The tags an image build pushes, and the stable alias it may move afterwards.
 *
 * A release pushes its immutable `X.Y.Z` and `sha-<commit>`; a stable release may then move
 * `latest`, a prerelease never does. A development build is kept apart from all of them: it pushes
 * `dev-sha-<commit>` and `develop` only, so it can never be mistaken for, or overwrite, a release
 * tag of the same commit.
 */
export function imageTags({
  repository,
  version,
  prerelease,
  development,
  commit,
}) {
  if (development) {
    return {
      version: "",
      push: [`${repository}:dev-sha-${commit}`, `${repository}:develop`],
      stableAlias: "",
    };
  }
  return {
    version: `${repository}:${version}`,
    push: [`${repository}:${version}`, `${repository}:sha-${commit}`],
    stableAlias: prerelease ? "" : `${repository}:latest`,
  };
}

function parseTag(tag, manifest) {
  // Identifiers contain hyphens (`mcp-skr-staking`), so the split has to be on the last `-v`.
  const match = /^(.+)-v(.+)$/.exec(tag);
  if (!match) {
    throw new Error(
      `${tag} is not <component>-v<version> (${manifest.tagPattern})`,
    );
  }
  return { id: match[1], version: match[2] };
}

/**
 * `$GITHUB_OUTPUT` syntax. A value with a newline in it — the image tag list — has to use the
 * heredoc form, or the runner reads only its first line and the image is pushed under one tag.
 */
export function formatOutputs(plan) {
  return Object.entries(plan)
    .map(([key, value]) => {
      const text = String(value);
      if (!text.includes("\n")) return `${key}=${text}`;
      const delimiter = `ghadelimiter_${key}`;
      return `${key}<<${delimiter}\n${text}\n${delimiter}`;
    })
    .join("\n");
}

/**
 * The commit a tag points at on `origin`, peeled through an annotated tag, or "" when the tag does
 * not exist. A failing `git` is an error, never an absent tag.
 */
export function remoteTagCommit(tag, run = execFileSync) {
  const output = run(
    "git",
    [
      "ls-remote",
      "--tags",
      "origin",
      `refs/tags/${tag}`,
      `refs/tags/${tag}^{}`,
    ],
    { encoding: "utf8" },
  );
  const lines = output.trim().split("\n").filter(Boolean);
  const peeled = lines.find((line) => line.endsWith(`refs/tags/${tag}^{}`));
  const plain = lines.find((line) => line.endsWith(`refs/tags/${tag}`));
  return (peeled ?? plain ?? "").split("\t")[0];
}

// Executed by the workflow; imported by the test.
if (process.argv[1] === import.meta.filename) {
  const manifest = loadManifest();
  const environment = { ...process.env };
  if (!environment.TAG && environment.INPUT_COMPONENT) {
    const component = manifest.components.find(
      (entry) => entry.id === environment.INPUT_COMPONENT,
    );
    if (component) {
      environment.TAG_COMMIT = remoteTagCommit(
        tagFor(component.id, component.version),
      );
    }
  }
  const plan = releasePlan(environment, manifest);
  console.log(formatOutputs(plan));
}
