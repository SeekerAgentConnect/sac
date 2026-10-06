/**
 * The release rules that are worth testing rather than reading: which component a tag or a
 * dispatch names, the refusals (an unknown component, a tag or an input that disagrees with
 * release/components.json, a manual real release that is not its tag's commit), which dist-tag a
 * version asks for, and which image tags a build pushes. No registry, no credential, no network
 * (SEE-168, SEE-182).
 */
import assert from "node:assert/strict";
import test from "node:test";

import {
  apkName,
  compareVersions,
  formatOutputs,
  imageTags,
  loadManifest,
  releasePlan,
  remoteTagCommit,
  SEMVER,
} from "./release-plan.mjs";

const COMMIT = "0123456789abcdef0123456789abcdef01234567";
const OTHER = "fedcba9876543210fedcba9876543210fedcba98";
// The repository's manifest moves with every release, so it is only held to what stays true at any
// version. Scenarios that name a version run against `manifest`: the live manifest's components,
// with every version pinned to the 0.0.1 baseline and Android to versionCode 2.
const live = loadManifest();
const manifest = pinned(live, "0.0.1", {
  versionCode: 2,
  previousVersionCode: 1,
});
const plan = (environment, source = manifest) =>
  releasePlan({ COMMIT, ...environment }, source);

const versionOf = (id, source = manifest) =>
  source.components.find((entry) => entry.id === id).version;
const tagFor = (id, source = manifest) => `${id}-v${versionOf(id, source)}`;

const IDS = [
  "server-sdk",
  "mcp-server",
  "mcp-skr-staking",
  "gateway",
  "gateway-centrifugo",
  "demo-signals",
  "demo-prediction",
  "android",
];

test("the manifest releases exactly the eight public components, each at a usable version", () => {
  assert.deepEqual(
    live.components.map((component) => component.id),
    IDS,
  );
  for (const component of live.components) {
    assert.match(component.version, SEMVER, component.id);
    // Each component's own tag, at whatever version it has reached, resolves to that version.
    const resolved = plan({ TAG: tagFor(component.id, live) }, live);
    assert.equal(resolved.component, component.id);
    assert.equal(resolved.version, component.version);
    assert.equal(resolved.prerelease, component.version.includes("-"));
  }
  const android = live.components.find(({ id }) => id === "android");
  assert.ok(Number.isInteger(android.android.versionCode));
  assert.ok(android.android.versionCode > android.android.previousVersionCode);
  assert.equal(
    plan({ TAG: tagFor("android", live) }, live).apk_name,
    apkName(android),
  );
});

test("each component publishes only its own artifacts", () => {
  const artifacts = Object.fromEntries(
    IDS.map((id) => {
      const resolved = plan({ TAG: tagFor(id) });
      return [
        id,
        [
          resolved.has_npm && resolved.npm_name,
          resolved.has_image && resolved.image_repository,
          resolved.has_android && resolved.apk_name,
        ].filter(Boolean),
      ];
    }),
  );
  assert.deepEqual(artifacts, {
    "server-sdk": ["@seekeragentconnect/server-sdk"],
    "mcp-server": [
      "@seekeragentconnect/mcp-server",
      "ghcr.io/seekeragentconnect/mcp-server",
    ],
    "mcp-skr-staking": [
      "@seekeragentconnect/mcp-skr-staking",
      "ghcr.io/seekeragentconnect/mcp-skr-staking",
    ],
    gateway: ["ghcr.io/seekeragentconnect/gateway"],
    "gateway-centrifugo": ["ghcr.io/seekeragentconnect/gateway-centrifugo"],
    "demo-signals": ["ghcr.io/seekeragentconnect/demo-signals"],
    "demo-prediction": ["ghcr.io/seekeragentconnect/demo-prediction"],
    android: ["sac-0.0.1.apk"],
  });
});

test("the gateway keeps its console target and the broker its own context", () => {
  assert.equal(plan({ TAG: tagFor("gateway") }).target, "console");
  const broker = plan({ TAG: tagFor("gateway-centrifugo") });
  assert.equal(broker.context, "services/gateway");
  assert.equal(broker.dockerfile, "services/gateway/Dockerfile.centrifugo");
});

test("a stable tag asks for latest and pushes its version and commit tags", () => {
  const resolved = plan({ TAG: "mcp-server-v0.0.1" });
  const repository = "ghcr.io/seekeragentconnect/mcp-server";
  assert.equal(resolved.prerelease, false);
  assert.equal(resolved.dry_run, false);
  assert.equal(resolved.npm_tag, "latest");
  assert.equal(resolved.image_version_tag, `${repository}:0.0.1`);
  assert.deepEqual(resolved.image_tags.split("\n"), [
    `${repository}:0.0.1`,
    `${repository}:sha-${COMMIT}`,
  ]);
  assert.equal(resolved.image_stable_alias, `${repository}:latest`);
});

test("a prerelease asks for next and never has a stable alias", () => {
  const candidate = plan(
    { TAG: "gateway-v0.1.0-rc.1" },
    withVersion(manifest, "gateway", "0.1.0-rc.1"),
  );
  assert.equal(candidate.prerelease, true);
  assert.equal(candidate.npm_tag, "next");
  assert.equal(candidate.image_stable_alias, "");
  assert.ok(!candidate.image_tags.includes(":latest"));
  const sdk = plan(
    { TAG: "server-sdk-v1.0.0-beta.2" },
    withVersion(manifest, "server-sdk", "1.0.0-beta.2"),
  );
  assert.equal(sdk.npm_tag, "next");
});

test("a development build is isolated from every release tag, and never reaches npm", () => {
  const development = plan({
    INPUT_COMPONENT: "mcp-server",
    INPUT_CHANNEL: "development",
    INPUT_DRY_RUN: "false",
  });
  const repository = "ghcr.io/seekeragentconnect/mcp-server";
  assert.equal(development.has_npm, false);
  assert.equal(development.image_version_tag, "");
  assert.equal(development.image_stable_alias, "");
  assert.deepEqual(development.image_tags.split("\n"), [
    `${repository}:dev-sha-${COMMIT}`,
    `${repository}:develop`,
  ]);
  assert.throws(
    () => plan({ INPUT_COMPONENT: "server-sdk", INPUT_CHANNEL: "development" }),
    /no image/,
  );
  assert.throws(
    () => plan({ INPUT_COMPONENT: "android", INPUT_CHANNEL: "development" }),
    /no image/,
  );
});

test("a dispatch is a dry run unless it says otherwise, and may use any commit", () => {
  const dry = plan({ INPUT_COMPONENT: "gateway", INPUT_CHANNEL: "release" });
  assert.equal(dry.dry_run, true);
  const explicit = plan({
    INPUT_COMPONENT: "android",
    INPUT_CHANNEL: "release",
    INPUT_DRY_RUN: "true",
    TAG_COMMIT: OTHER,
  });
  assert.equal(explicit.dry_run, true);
  assert.equal(explicit.has_android, true);
});

test("a manual real release must be the tag's own commit", () => {
  const real = {
    INPUT_COMPONENT: "gateway",
    INPUT_CHANNEL: "release",
    INPUT_DRY_RUN: "false",
  };
  assert.throws(() => plan(real), /does not exist/);
  assert.throws(
    () => plan({ ...real, TAG_COMMIT: OTHER }),
    /points at fedcba987654/,
  );
  const retry = plan({ ...real, TAG_COMMIT: COMMIT });
  assert.equal(retry.dry_run, false);
  assert.equal(retry.tag, "gateway-v0.0.1");
});

test("a dispatch that expects another version stops", () => {
  assert.throws(
    () =>
      plan({
        INPUT_COMPONENT: "server-sdk",
        INPUT_VERSION: "0.0.2",
      }),
    /expects server-sdk 0.0.2/,
  );
  assert.equal(
    plan({ INPUT_COMPONENT: "server-sdk", INPUT_VERSION: "0.0.1" }).version,
    "0.0.1",
  );
});

test("a tag that disagrees with the manifest stops the release", () => {
  assert.throws(() => plan({ TAG: "gateway-v9.9.9" }), /pins gateway at/);
  // An SDK tag says nothing about the servers that vendor it.
  assert.throws(() => plan({ TAG: "mcp-server-v0.0.2" }), /pins mcp-server/);
});

test("an unknown component or a malformed tag stops the release", () => {
  assert.throws(
    () => plan({ TAG: "not-a-component-v1.0.0" }),
    /is not a component/,
  );
  assert.throws(() => plan({ TAG: "v0.0.1" }), /is not <component>-v<version>/);
  assert.throws(
    () => plan({ INPUT_COMPONENT: "protocol" }),
    /is not a component/,
  );
});

test("an unusable version or commit stops the release", () => {
  assert.throws(
    () =>
      plan({ TAG: "gateway-v1.0" }, withVersion(manifest, "gateway", "1.0")),
    /unusable version/,
  );
  assert.throws(
    () => releasePlan({ TAG: tagFor("gateway"), COMMIT: "abc" }, manifest),
    /no usable commit/,
  );
});

test("an identifier with hyphens survives the tag", () => {
  assert.equal(
    plan({ TAG: tagFor("mcp-skr-staking") }).component,
    "mcp-skr-staking",
  );
  assert.equal(
    plan({ TAG: tagFor("gateway-centrifugo") }).component,
    "gateway-centrifugo",
  );
});

test("the Android release carries its application id, code and requirements", () => {
  const app = plan({ TAG: "android-v0.0.1" });
  assert.equal(app.android_application_id, "io.github.brrenat.seekervault");
  assert.equal(app.android_version_code, 2);
  assert.equal(app.apk_name, "sac-0.0.1.apk");
  assert.equal(app.java_version, "21");
  assert.equal(app.has_image, false);
  assert.equal(app.has_npm, false);
  assert.match(app.checks, /check:android/);
});

test("every component runs its own checks on the released commit", () => {
  assert.match(plan({ TAG: tagFor("gateway") }).checks, /check:gateway/);
  assert.equal(
    plan({ TAG: tagFor("gateway") }).go_version_file,
    "services/gateway/go.mod",
  );
  assert.match(
    plan({ TAG: tagFor("mcp-server") }).checks,
    /test:mcp-server-package/,
  );
  assert.match(
    plan({ TAG: tagFor("server-sdk") }).checks,
    /test:server-sdk-package/,
  );
});

test("npm is pinned to the manifest's CLI version", () => {
  assert.equal(
    plan({ TAG: tagFor("server-sdk") }).npm_version,
    manifest.toolchain.npm,
  );
});

test("image tags for a release and a development build", () => {
  const repository = "ghcr.io/seekeragentconnect/gateway";
  assert.deepEqual(
    imageTags({
      repository,
      version: "1.4.0-rc.2",
      prerelease: true,
      development: false,
      commit: COMMIT,
    }),
    {
      version: `${repository}:1.4.0-rc.2`,
      push: [`${repository}:1.4.0-rc.2`, `${repository}:sha-${COMMIT}`],
      stableAlias: "",
    },
  );
});

test("semantic version precedence", () => {
  assert.ok(compareVersions("0.0.2", "0.0.1") > 0);
  assert.ok(compareVersions("0.1.0-rc.1", "0.1.0") < 0);
  assert.ok(compareVersions("0.1.0-rc.10", "0.1.0-rc.9") > 0);
  assert.ok(compareVersions("0.1.0-beta.1", "0.1.0-rc.1") < 0);
  assert.equal(compareVersions("1.2.3", "1.2.3"), 0);
});

test("a multi-line output uses the heredoc form", () => {
  const written = formatOutputs(plan({ TAG: tagFor("gateway") }));
  assert.match(written, /^image_tags<<ghadelimiter_image_tags$/m);
  assert.match(written, /^component=gateway$/m);
});

test("a remote tag resolves to its peeled commit, and its absence to nothing", () => {
  const annotated = `${OTHER}\trefs/tags/gateway-v0.0.1\n${COMMIT}\trefs/tags/gateway-v0.0.1^{}\n`;
  assert.equal(
    remoteTagCommit("gateway-v0.0.1", () => annotated),
    COMMIT,
  );
  assert.equal(
    remoteTagCommit(
      "gateway-v0.0.1",
      () => `${COMMIT}\trefs/tags/gateway-v0.0.1\n`,
    ),
    COMMIT,
  );
  assert.equal(
    remoteTagCommit("gateway-v0.0.1", () => ""),
    "",
  );
  assert.throws(() =>
    remoteTagCommit("gateway-v0.0.1", () => {
      throw new Error("network down");
    }),
  );
});

function withVersion(source, id, version) {
  return {
    ...source,
    components: source.components.map((component) =>
      component.id === id ? { ...component, version } : component,
    ),
  };
}

/** `source` with every component at `version`, and the Android app at `android`'s codes. */
function pinned(source, version, android) {
  return {
    ...source,
    components: source.components.map((component) => ({
      ...component,
      version,
      android: component.android && { ...component.android, ...android },
    })),
  };
}
