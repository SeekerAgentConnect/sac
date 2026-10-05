/**
 * Partial-retry integrity and alias movement (SEE-182): what a release run does when some of its
 * artifacts are already published. The registry replies are recorded, so this needs no network and
 * no credential.
 */
import assert from "node:assert/strict";
import test from "node:test";

import {
  aliasMoves,
  androidDecision,
  auditPackedManifest,
  formatRecord,
  imageDecision,
  imageState,
  npmDecision,
  npmPackument,
  parseRecord,
} from "./release-state.mjs";

const COMMIT = "0123456789abcdef0123456789abcdef01234567";
const OTHER = "fedcba9876543210fedcba9876543210fedcba98";
const INTEGRITY = "sha512-local";

// --- npm ------------------------------------------------------------------------------------

test("npm: an unpublished version is published, and a stable one asks for latest", () => {
  const decision = npmDecision({
    state: { exists: true, distTags: { latest: "0.0.1" }, versions: {} },
    version: "0.0.2",
    commit: COMMIT,
    integrity: INTEGRITY,
    prerelease: false,
  });
  assert.equal(decision.action, "publish");
  assert.equal(decision.distTag, "latest");
});

test("npm: a prerelease asks for next and leaves latest alone", () => {
  const decision = npmDecision({
    state: { exists: true, distTags: { latest: "0.0.1" }, versions: {} },
    version: "0.1.0-rc.1",
    commit: COMMIT,
    integrity: INTEGRITY,
    prerelease: true,
  });
  assert.equal(decision.distTag, "next");
});

test("npm: the first publication is explicit about npm seeding latest", () => {
  const stable = npmDecision({
    state: { exists: false },
    version: "0.0.1",
    commit: COMMIT,
    integrity: INTEGRITY,
    prerelease: false,
  });
  assert.equal(stable.action, "publish");
  assert.equal(stable.distTag, "latest");
  assert.match(stable.warnings.join(), /first publication/);
  const candidate = npmDecision({
    state: { exists: false },
    version: "0.0.1-rc.1",
    commit: COMMIT,
    integrity: INTEGRITY,
    prerelease: true,
  });
  assert.equal(candidate.distTag, "next");
  assert.match(candidate.warnings.join(), /prerelease becomes latest/);
});

test("npm: an older stable version never drags latest backwards", () => {
  const decision = npmDecision({
    state: { exists: true, distTags: { latest: "0.2.0" }, versions: {} },
    version: "0.1.5",
    commit: COMMIT,
    integrity: INTEGRITY,
    prerelease: false,
  });
  assert.equal(decision.action, "publish");
  assert.equal(decision.distTag, "latest-backport");
});

test("npm: a retry of a version published from this commit is verified and skipped", () => {
  const decision = npmDecision({
    state: {
      exists: true,
      distTags: { latest: "0.0.1" },
      versions: { "0.0.1": { gitHead: COMMIT, integrity: INTEGRITY } },
    },
    version: "0.0.1",
    commit: COMMIT,
    integrity: INTEGRITY,
    prerelease: false,
  });
  assert.equal(decision.action, "verified");
  assert.equal(decision.warnings.length, 0);
});

test("npm: same commit, different bytes keeps the published tarball and says so", () => {
  const decision = npmDecision({
    state: {
      exists: true,
      versions: { "0.0.1": { gitHead: COMMIT, integrity: "sha512-published" } },
    },
    version: "0.0.1",
    commit: COMMIT,
    integrity: INTEGRITY,
    prerelease: false,
  });
  assert.equal(decision.action, "verified");
  assert.equal(decision.integrity, "sha512-published");
  assert.match(decision.warnings.join(), /differs/);
});

test("npm: a version published from another commit stops the run", () => {
  assert.throws(
    () =>
      npmDecision({
        state: {
          exists: true,
          versions: { "0.0.1": { gitHead: OTHER, integrity: "sha512-other" } },
        },
        version: "0.0.1",
        commit: COMMIT,
        integrity: INTEGRITY,
        prerelease: false,
      }),
    /immutable/,
  );
});

test("npm: absence is only ever a 404, and a failure is never absence", async () => {
  assert.deepEqual(
    await npmPackument("@seekeragentconnect/server-sdk", {
      fetch: replies([404]),
    }),
    { exists: false },
  );
  await assert.rejects(
    npmPackument("@seekeragentconnect/server-sdk", {
      fetch: replies([503, 503, 503]),
    }),
    /503/,
  );
  await assert.rejects(
    npmPackument("@seekeragentconnect/server-sdk", { fetch: replies([401]) }),
    /401/,
  );
  const fetched = [];
  const state = await npmPackument("@seekeragentconnect/server-sdk", {
    fetch: replies(
      [
        503,
        [
          200,
          {
            "dist-tags": { latest: "0.0.1" },
            versions: {
              "0.0.1": { gitHead: COMMIT, dist: { integrity: INTEGRITY } },
            },
          },
        ],
      ],
      fetched,
    ),
  });
  assert.equal(
    fetched[0],
    "https://registry.npmjs.org/@seekeragentconnect%2Fserver-sdk",
  );
  assert.deepEqual(state.versions["0.0.1"], {
    gitHead: COMMIT,
    integrity: INTEGRITY,
  });
});

test("npm: the packed manifest names this release and nothing workspace-only", () => {
  const packed = {
    name: "@seekeragentconnect/mcp-server",
    version: "0.0.1",
    gitHead: COMMIT,
    publishConfig: { access: "public" },
    repository: { url: "git+https://github.com/SeekerAgentConnect/sac.git" },
    dependencies: { zod: "4.6.1" },
    seekerAgentConnect: {
      serverSdk: {
        name: "@seekeragentconnect/server-sdk",
        version: "0.0.1",
        contentSha256: "ab",
      },
    },
  };
  const identity = { name: packed.name, version: "0.0.1", commit: COMMIT };
  const clean = auditPackedManifest(packed, identity);
  assert.deepEqual(clean.problems, []);
  assert.equal(clean.serverSdk.version, "0.0.1");
  const broken = auditPackedManifest(
    {
      ...packed,
      gitHead: OTHER,
      version: "0.0.2",
      publishConfig: {},
      dependencies: {
        "@seekeragentconnect/server-sdk": "workspace:*",
        x: "catalog:",
      },
      repository: {
        url: "git+https://github.com/BrRenat/SeekerAgentConnect.git",
      },
    },
    identity,
  );
  assert.equal(broken.problems.length, 6);
});

// --- images ---------------------------------------------------------------------------------

const labels = (version, revision) => ({
  "org.opencontainers.image.version": version,
  "org.opencontainers.image.revision": revision,
});

test("image: an absent version tag is pushed", () => {
  assert.deepEqual(
    imageDecision({
      state: { exists: false },
      version: "0.0.1",
      commit: COMMIT,
    }),
    { action: "push", digest: "" },
  );
});

test("image: a retry finds its own image and keeps its digest", () => {
  assert.deepEqual(
    imageDecision({
      state: {
        exists: true,
        digest: "sha256:aa",
        labels: labels("0.0.1", COMMIT),
      },
      version: "0.0.1",
      commit: COMMIT,
    }),
    { action: "verified", digest: "sha256:aa" },
  );
});

test("image: a version tag from another commit is never overwritten", () => {
  assert.throws(
    () =>
      imageDecision({
        state: {
          exists: true,
          digest: "sha256:aa",
          labels: labels("0.0.1", OTHER),
        },
        version: "0.0.1",
        commit: COMMIT,
      }),
    /immutable/,
  );
  assert.throws(
    () =>
      imageDecision({
        state: { exists: true, digest: "sha256:aa", labels: {} },
        version: "0.0.1",
        commit: COMMIT,
      }),
    /revision=none/,
  );
});

test("aliases move forwards or stay, never backwards", () => {
  assert.equal(aliasMoves({ current: "", candidate: "0.0.1" }).move, true);
  assert.equal(aliasMoves({ current: "0.0.1", candidate: "0.0.2" }).move, true);
  assert.equal(aliasMoves({ current: "0.0.2", candidate: "0.0.2" }).move, true);
  assert.equal(
    aliasMoves({ current: "0.0.3", candidate: "0.0.2" }).move,
    false,
  );
  assert.equal(
    aliasMoves({ current: "1.0.0-rc.1", candidate: "0.9.0" }).move,
    false,
  );
  assert.equal(
    aliasMoves({ current: "0.0.0-dev", candidate: "0.0.1" }).move,
    false,
  );
  assert.equal(
    aliasMoves({ current: "unlabelled", candidate: "0.0.1" }).move,
    false,
  );
});

test("image state reads the digest and labels through an index, anonymously", async () => {
  const digest = `sha256:${"a".repeat(64)}`;
  const fetched = [];
  const state = await imageState("ghcr.io/seekeragentconnect/gateway:0.0.1", {
    fetch: replies(
      [
        [200, { token: "t" }],
        [
          200,
          {
            manifests: [
              {
                digest: "sha256:att",
                platform: { os: "unknown", architecture: "unknown" },
              },
              {
                digest: "sha256:amd",
                platform: { os: "linux", architecture: "amd64" },
              },
            ],
          },
          { "docker-content-digest": digest },
        ],
        [200, { config: { digest: "sha256:cfg" } }],
        [200, { config: { Labels: labels("0.0.1", COMMIT) } }],
      ],
      fetched,
    ),
  });
  assert.deepEqual(state, {
    access: "ok",
    exists: true,
    digest,
    labels: labels("0.0.1", COMMIT),
  });
  assert.match(fetched[0], /scope=repository:seekeragentconnect\/gateway:pull/);
  assert.match(fetched[2], /manifests\/sha256:amd$/);
});

test("image state tells absent from denied, and a server error from both", async () => {
  assert.deepEqual(
    await imageState("ghcr.io/seekeragentconnect/gateway:0.0.1", {
      fetch: replies([[200, { token: "t" }], 404]),
    }),
    { access: "ok", exists: false },
  );
  assert.deepEqual(
    await imageState("ghcr.io/seekeragentconnect/gateway:0.0.1", {
      fetch: replies([403]),
    }),
    { access: "denied" },
  );
  await assert.rejects(
    imageState("ghcr.io/seekeragentconnect/gateway:0.0.1", {
      fetch: replies([[200, { token: "t" }], 502, 502, 502]),
    }),
    /502/,
  );
});

// --- Android --------------------------------------------------------------------------------

const record = (overrides) => ({
  component: "android",
  version: "0.0.1",
  versionCode: 2,
  commit: COMMIT,
  apk: "sac-0.0.1.apk",
  sha256: "abc",
  ...overrides,
});
const release = ({ tag, draft = false, body, assets = [] }) => ({
  id: tag.length,
  tag_name: tag,
  draft,
  body: body ?? "",
  assets: assets.map((name) => ({ name })),
});
const android = (releases, overrides = {}) =>
  androidDecision({
    releases,
    tag: "android-v0.0.1",
    version: "0.0.1",
    versionCode: 2,
    commit: COMMIT,
    apk: "sac-0.0.1.apk",
    ...overrides,
  });

test("android: a first release is created as a draft", () => {
  assert.deepEqual(android([]), { action: "create", latest: true });
});

test("android: only the newest stable app release becomes the repository's latest", () => {
  const published = (version, versionCode) =>
    release({
      tag: `android-v${version}`,
      body: formatRecord(
        record({ version, versionCode, apk: `sac-${version}.apk` }),
      ),
      assets: [`sac-${version}.apk`],
    });
  const candidate = android([published("0.0.1", 2)], {
    tag: "android-v0.1.0-rc.1",
    version: "0.1.0-rc.1",
    versionCode: 3,
  });
  assert.equal(candidate.latest, false);
  const backport = android([published("0.2.0", 5)], {
    tag: "android-v0.1.1",
    version: "0.1.1",
    versionCode: 6,
  });
  assert.equal(backport.latest, false);
  const next = android([published("0.0.1", 2)], {
    tag: "android-v0.0.2",
    version: "0.0.2",
    versionCode: 3,
  });
  assert.equal(next.latest, true);
});

test("android: a draft left by a failed run is resumed, not duplicated", () => {
  const decision = android([release({ tag: "android-v0.0.1", draft: true })]);
  assert.equal(decision.action, "resume");
  assert.throws(
    () =>
      android([
        release({ tag: "android-v0.0.1", draft: true }),
        release({ tag: "android-v0.0.1", draft: true }),
      ]),
    /2 GitHub Releases/,
  );
});

test("android: a published release of this commit is verified, never rebuilt", () => {
  const decision = android([
    release({
      tag: "android-v0.0.1",
      body: `notes\n${formatRecord(record())}`,
      assets: ["sac-0.0.1.apk", "SHA256SUMS"],
    }),
  ]);
  assert.equal(decision.action, "verified");
  assert.equal(decision.record.sha256, "abc");
});

test("android: a published release from another commit stops the run", () => {
  assert.throws(
    () =>
      android([
        release({
          tag: "android-v0.0.1",
          body: formatRecord(record({ commit: OTHER })),
          assets: ["sac-0.0.1.apk"],
        }),
      ]),
    /immutable/,
  );
  assert.throws(
    () =>
      android([release({ tag: "android-v0.0.1", assets: ["sac-0.0.1.apk"] })]),
    /no release record/,
  );
});

test("android: versionCode must exceed every other published app release", () => {
  const previous = release({
    tag: "android-v0.0.1",
    body: formatRecord(record()),
    assets: ["sac-0.0.1.apk"],
  });
  assert.throws(
    () =>
      android([previous], {
        tag: "android-v0.0.2",
        version: "0.0.2",
        versionCode: 2,
        apk: "sac-0.0.2.apk",
      }),
    /versionCode 2/,
  );
  assert.equal(
    android([previous], {
      tag: "android-v0.0.2",
      version: "0.0.2",
      versionCode: 3,
      apk: "sac-0.0.2.apk",
    }).action,
    "create",
  );
  // Drafts and other components' releases are not distributed app builds.
  assert.equal(
    android([
      release({
        tag: "android-v0.0.0",
        draft: true,
        body: formatRecord(record({ versionCode: 9 })),
      }),
      release({
        tag: "gateway-v0.0.1",
        body: formatRecord({ component: "gateway", versionCode: 9 }),
      }),
    ]).action,
    "create",
  );
});

test("a release record survives the body it is embedded in", () => {
  const body = `# Notes\n\nText\n\n${formatRecord(record())}\n`;
  assert.deepEqual(parseRecord(body), record());
  assert.equal(parseRecord("no record"), null);
  assert.equal(parseRecord("<!-- sac-release {broken -->"), null);
});

/**
 * A fetch that answers from a script: each entry is a status, or [status, json, headers].
 */
function replies(script, seen = []) {
  const queue = [...script];
  return async (url) => {
    seen.push(String(url));
    const next = queue.shift();
    if (next === undefined) throw new Error(`unexpected request ${url}`);
    const [status, json = {}, headers = {}] = Array.isArray(next)
      ? next
      : [next];
    return {
      status,
      headers: { get: (name) => headers[name.toLowerCase()] ?? null },
      json: async () => json,
    };
  };
}
