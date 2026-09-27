/**
 * The release rules that are worth testing rather than reading: which dist-tag a version gets,
 * which image tags a build pushes, and the two refusals — an unknown component, and a tag that
 * disagrees with release/components.json. No registry, no credential, no network (SEE-168).
 */
import assert from "node:assert/strict";
import test from "node:test";

import {
  formatOutputs,
  imageTags,
  loadManifest,
  releasePlan,
} from "./release-plan.mjs";

const COMMIT = "0123456789abcdef0123456789abcdef01234567";
const manifest = loadManifest();
const plan = (environment) => releasePlan({ COMMIT, ...environment }, manifest);

// Tags are derived from the manifest rather than written out, so a version bump does not turn
// these into tests of a version nobody is releasing.
const versionOf = (id) =>
  manifest.components.find((entry) => entry.id === id).version;
const tagFor = (id) => `${id}-v${versionOf(id)}`;
// Whichever channel the repository is currently on, both sets of rules have to hold, so the other
// channel is constructed here rather than waited for.
const asStable = (id) => versionOf(id).replace(/-.*$/, "");

const REPOSITORY = "docker.io/brenat/seeker-agent-connect";

test("a stable tag publishes latest on both registries", () => {
  const version = asStable("mcp-server");
  const resolved = releasePlan(
    { TAG: `mcp-server-v${version}`, COMMIT },
    withVersion(manifest, "mcp-server", version),
  );
  assert.equal(resolved.npm_tag, "latest");
  assert.equal(resolved.image_ref, REPOSITORY);
  assert.equal(resolved.image_version_tag, `${REPOSITORY}:mcp-${version}`);
  assert.deepEqual(resolved.image_tags.split("\n"), [
    `${REPOSITORY}:mcp-sha-0123456789ab`,
    `${REPOSITORY}:mcp-${version}`,
    `${REPOSITORY}:mcp-latest`,
  ]);
});

test("a release candidate never advances latest", () => {
  const candidate = imageTags({
    reference: REPOSITORY,
    prefix: "gateway",
    version: "1.4.0-rc.2",
    prerelease: true,
    development: false,
    shortCommit: "0123456789ab",
  });
  assert.deepEqual(candidate, [
    `${REPOSITORY}:gateway-sha-0123456789ab`,
    `${REPOSITORY}:gateway-1.4.0-rc.2`,
  ]);
  assert.ok(!candidate.some((tag) => tag.endsWith("-latest")));
});

test("a prerelease version resolves to the next dist-tag", () => {
  const candidate = releasePlan(
    { TAG: "server-sdk-v1.0.0-rc.1", COMMIT },
    withVersion(manifest, "server-sdk", "1.0.0-rc.1"),
  );
  assert.equal(candidate.npm_tag, "next");
  assert.equal(candidate.prerelease, true);
  assert.ok(!candidate.image_tags.includes("-latest"));
});

test("a development build pushes moving tags only, and nothing to npm", () => {
  const development = plan({
    INPUT_COMPONENT: "gateway",
    INPUT_CHANNEL: "development",
    INPUT_DRY_RUN: "false",
  });
  assert.equal(development.has_npm, false);
  assert.deepEqual(development.image_tags.split("\n"), [
    `${REPOSITORY}:gateway-sha-0123456789ab`,
    `${REPOSITORY}:gateway-develop`,
  ]);
});

test("every image shares one repository and keeps the tag prefix it had before", () => {
  const prefixes = Object.fromEntries(
    manifest.components
      .filter((component) => component.image)
      .map((component) => [component.id, component.image.tagPrefix]),
  );
  assert.deepEqual(prefixes, {
    "mcp-server": "mcp",
    "mcp-skr-staking": "skr-staking-mcp",
    gateway: "gateway",
    "gateway-centrifugo": "centrifugo",
    "demo-signals": "copytrading",
    "demo-prediction": "prediction",
  });
  for (const id of Object.keys(prefixes)) {
    assert.equal(plan({ TAG: tagFor(id) }).image_ref, REPOSITORY);
  }
});

test("npm stays a dry run while the manifest says so, even from a tag", () => {
  const tagged = plan({ TAG: tagFor("server-sdk") });
  assert.equal(tagged.dry_run, false);
  assert.equal(tagged.npm_dry_run, manifest.registries.npmDryRun === true);
  const released = releasePlan(
    { TAG: tagFor("server-sdk"), COMMIT },
    {
      ...manifest,
      registries: { ...manifest.registries, npmDryRun: false },
    },
  );
  assert.equal(released.npm_dry_run, false);
});

test("a dispatch publishes nothing unless it says so", () => {
  assert.equal(
    plan({ INPUT_COMPONENT: "gateway", INPUT_CHANNEL: "release" }).dry_run,
    true,
  );
});

test("an identifier with hyphens survives the tag", () => {
  assert.equal(
    plan({ TAG: tagFor("mcp-skr-staking") }).component,
    "mcp-skr-staking",
  );
});

test("a tag that disagrees with the manifest stops the release", () => {
  assert.throws(() => plan({ TAG: "gateway-v9.9.9" }), /pins gateway at/);
});

test("an unknown component stops the release", () => {
  assert.throws(
    () => plan({ TAG: "not-a-component-v1.0.0" }),
    /is not a component/,
  );
});

test("every manifest component resolves to a plan", () => {
  for (const component of manifest.components) {
    const resolved = plan({ TAG: `${component.id}-v${component.version}` });
    assert.equal(resolved.component, component.id);
    assert.equal(resolved.has_npm, Boolean(component.npm));
    assert.equal(resolved.has_image, Boolean(component.image));
  }
});

test("a multi-line output uses the heredoc form", () => {
  const written = formatOutputs(plan({ TAG: tagFor("gateway") }));
  assert.match(written, /^image_tags<<ghadelimiter_image_tags$/m);
  assert.match(written, /^component=gateway$/m);
});

function withVersion(source, id, version) {
  return {
    ...source,
    components: source.components.map((component) =>
      component.id === id ? { ...component, version } : component,
    ),
  };
}
