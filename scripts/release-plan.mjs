/**
 * Turn a release tag (or a workflow dispatch) into the exact plan .github/workflows/release.yml
 * executes, and write it as `key=value` lines for `$GITHUB_OUTPUT`.
 *
 * The decisions that matter to the ticket all live here rather than in YAML expressions, because
 * they are the ones worth testing: which dist-tag a version gets, which image tags are pushed, and
 * the rule that a release candidate never advances `latest`. scripts/release-plan.test.mjs is that
 * test, and it runs with no registry and no credential.
 */
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(import.meta.dirname, "..");

const SEMVER =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:rc|alpha|beta)\.(?:0|[1-9]\d*))?$/;

export function loadManifest(root = ROOT) {
  return JSON.parse(
    readFileSync(join(root, "release", "components.json"), "utf8"),
  );
}

/**
 * @param {object} environment  the workflow's env block: TAG, INPUT_COMPONENT, INPUT_CHANNEL,
 *                              INPUT_DRY_RUN, COMMIT
 * @param {object} manifest     release/components.json
 */
export function releasePlan(environment, manifest) {
  const {
    TAG = "",
    INPUT_COMPONENT = "",
    INPUT_CHANNEL = "",
    COMMIT = "",
  } = environment;
  const ghcr = manifest.registries.ghcr;

  // A tag is the real trigger; a dispatch is the deliberate one. A dispatch always has to say
  // whether it is a release or a development build, and it defaults to publishing nothing.
  const tagged = TAG ? parseTag(TAG, manifest) : null;
  if (!tagged && !INPUT_COMPONENT) {
    throw new Error("no tag and no component input");
  }
  const id = tagged ? tagged.id : INPUT_COMPONENT;
  const development = tagged ? false : INPUT_CHANNEL !== "release";
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
  // The tag is how a human names a release; the manifest is what the build uses. If they disagree,
  // one of them is a mistake and guessing which would publish the wrong bytes under the right name.
  if (tagged && tagged.version !== version) {
    throw new Error(
      `tag ${TAG} asks for ${tagged.version} but release/components.json pins ${id} at ${version}`,
    );
  }

  const prerelease = version.includes("-");
  if (!COMMIT) throw new Error("no commit sha");
  const shortCommit = COMMIT.slice(0, 12);

  const plan = {
    component: id,
    version,
    prerelease,
    development,
    dry_run: dryRun,
    title: component.description,
    has_npm: Boolean(component.npm) && !development,
    has_image: Boolean(component.image),
    npm_name: component.npm?.name ?? "",
    npm_directory: component.npm?.packDirectory ?? "",
    // A prerelease goes to `next`. Nothing but a stable version is ever allowed to move `latest`,
    // which is what a plain `npm install <name>` resolves to.
    npm_tag: prerelease ? "next" : "latest",
    dockerfile: component.image?.dockerfile ?? "",
    context: component.image?.context ?? ".",
    target: component.image?.target ?? "",
    platforms: component.image?.platforms?.join(",") ?? "",
    image_ref: component.image ? `${ghcr}/${component.image.name}` : "",
    image_tags: "",
  };

  if (component.image) {
    plan.image_tags = imageTags({
      reference: plan.image_ref,
      version,
      prerelease,
      development,
      shortCommit,
    }).join("\n");
  }
  return plan;
}

/**
 * The tags a build pushes.
 *
 * `sha-<commit>` is on every image, so any image can be traced to a revision without reading its
 * labels. A development build gets nothing else but `develop`. A stable release gets `latest`; a
 * release candidate deliberately does not, so `docker pull <image>` keeps resolving to the last
 * stable one.
 */
export function imageTags({
  reference,
  version,
  prerelease,
  development,
  shortCommit,
}) {
  const tags = [`${reference}:sha-${shortCommit}`];
  if (development) {
    tags.push(`${reference}:develop`);
    return tags;
  }
  tags.push(`${reference}:${version}`);
  if (!prerelease) tags.push(`${reference}:latest`);
  return tags;
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

// Executed by the workflow; imported by the test.
if (process.argv[1] === import.meta.filename) {
  const plan = releasePlan(process.env, loadManifest());
  console.log(formatOutputs(plan));
}
