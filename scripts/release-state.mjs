/**
 * What is already published, and what a release run may therefore do (SEE-182).
 *
 * Registries are not transactional: a release can stop between npm and GHCR, between pushing an
 * image and moving `latest`, or between uploading an APK and publishing its GitHub Release. A
 * retry has to finish the missing steps without redoing — or overwriting — the finished ones. So
 * every publication step in .github/workflows/release.yml first asks this module, and the rules
 * are the same everywhere:
 *
 * - An artifact already published under this version is checked against the release's identity
 *   (the commit, and for npm the tarball integrity). If it matches, that step is skipped as
 *   verified; if it does not, the run stops. A published version is never replaced.
 * - "Absent" is only ever concluded from an explicit not-found answer. A transient failure, an
 *   authentication failure or an unreadable reply is an error, never a reason to publish.
 * - A stable alias (`latest`) moves only forwards. A retry of an older version, or two releases
 *   finishing out of order, leave it on the newest stable version.
 *
 * The decisions are pure functions, tested in scripts/release-state.test.mjs with recorded
 * registry replies; the CLI at the bottom wires them to the network for the workflow.
 */
import { appendFileSync } from "node:fs";

import { compareVersions, SEMVER } from "./release-plan.mjs";

// ---------------------------------------------------------------------------------------------
// Decisions
// ---------------------------------------------------------------------------------------------

/**
 * @param {object} options
 * @param {{exists: boolean, distTags?: object, versions?: object}} options.state  npmPackument()
 * @param {string} options.version
 * @param {string} options.commit      the release commit, written into the tarball as gitHead
 * @param {string} options.integrity   the locally built tarball's sha512 integrity
 * @param {boolean} options.prerelease
 */
export function npmDecision({ state, version, commit, integrity, prerelease }) {
  const warnings = [];
  const published = state.exists ? state.versions?.[version] : undefined;
  if (published) {
    const sameCommit = published.gitHead === commit;
    const sameBytes = published.integrity === integrity;
    if (!sameCommit && !sameBytes) {
      throw new Error(
        `${version} is already published from ${published.gitHead ?? "an unknown commit"} ` +
          `with integrity ${published.integrity}; this release is ${commit}. A published ` +
          "version is immutable: bump the version instead of retagging",
      );
    }
    if (!sameBytes) {
      warnings.push(
        `${version} was published from this commit but its integrity ${published.integrity} ` +
          `differs from this rebuild's ${integrity}; the published tarball is kept`,
      );
    }
    return {
      action: "verified",
      distTag: "",
      integrity: published.integrity,
      warnings,
    };
  }

  const alias = prerelease ? "next" : "latest";
  const current = state.exists ? state.distTags?.[alias] : undefined;
  let distTag = alias;
  if (
    current &&
    SEMVER.test(current) &&
    compareVersions(version, current) < 0
  ) {
    // An older version published after a newer one (a backport, or two releases finishing out of
    // order) must not drag the alias backwards. npm needs some tag, so it gets a parking one.
    distTag = `${alias}-backport`;
    warnings.push(
      `${alias} already points at the newer ${current}; publishing under ${distTag} instead`,
    );
  }
  if (!state.exists) {
    warnings.push(
      "first publication of this package: the registry points latest at whatever version is " +
        "published first" +
        (prerelease
          ? ", so this prerelease becomes latest until a stable one replaces it"
          : ""),
    );
  }
  return { action: "publish", distTag, integrity, warnings };
}

/**
 * @param {object} options
 * @param {{exists: boolean, digest?: string, labels?: object}} options.state  for <repo>:<version>
 */
export function imageDecision({ state, version, commit }) {
  if (!state.exists) return { action: "push", digest: "" };
  const revision = state.labels?.["org.opencontainers.image.revision"];
  const labelled = state.labels?.["org.opencontainers.image.version"];
  if (revision !== commit || labelled !== version) {
    throw new Error(
      `the image tag ${version} already exists (${state.digest}) labelled ` +
        `version=${labelled ?? "none"} revision=${revision ?? "none"}; this release is ` +
        `${version} at ${commit}. A released tag is immutable: bump the version`,
    );
  }
  return { action: "verified", digest: state.digest };
}

/**
 * Whether a stable alias may move from the version it holds to `candidate`. It moves forwards or
 * stays (a retry of the same version); it never moves backwards. An alias held by something that
 * is not a version this repository releases is left alone and reported.
 */
export function aliasMoves({ current, candidate }) {
  if (!current) return { move: true, reason: "the alias does not exist yet" };
  if (!SEMVER.test(current)) {
    return {
      move: false,
      reason: `the alias holds "${current}", which is not a released version; left alone`,
    };
  }
  const order = compareVersions(candidate, current);
  if (order >= 0) {
    return {
      move: true,
      reason: order === 0 ? `already ${current}` : `advancing from ${current}`,
    };
  }
  return {
    move: false,
    reason: `the alias holds the newer ${current}; ${candidate} does not move it back`,
  };
}

/**
 * What a packed npm tarball's own package.json must say before it may be published: the release's
 * name, version and commit, public access, and nothing only this workspace could resolve. For an
 * MCP server it also names the SDK it vendors, which is reported with the release.
 *
 * @returns {{problems: string[], serverSdk: object | null}}
 */
export function auditPackedManifest(manifest, { name, version, commit }) {
  const problems = [];
  if (manifest.name !== name)
    problems.push(`name is ${manifest.name}, not ${name}`);
  if (manifest.version !== version) {
    problems.push(`version is ${manifest.version}, not ${version}`);
  }
  if (manifest.gitHead !== commit) {
    problems.push(`gitHead is ${manifest.gitHead ?? "missing"}, not ${commit}`);
  }
  if (manifest.private === true) problems.push("it is marked private");
  if (manifest.publishConfig?.access !== "public") {
    problems.push("publishConfig.access is not public");
  }
  for (const field of [
    "dependencies",
    "optionalDependencies",
    "peerDependencies",
  ]) {
    for (const [dependency, specifier] of Object.entries(
      manifest[field] ?? {},
    )) {
      if (/^(?:workspace|catalog|link|file|portal):/.test(specifier)) {
        problems.push(
          `${field}.${dependency} is ${specifier}, which no registry can resolve`,
        );
      }
    }
  }
  if (
    !manifest.repository?.url?.includes("github.com/SeekerAgentConnect/sac")
  ) {
    problems.push("repository does not point at SeekerAgentConnect/sac");
  }
  const serverSdk = manifest.seekerAgentConnect?.serverSdk ?? null;
  if (
    serverSdk &&
    (!SEMVER.test(serverSdk.version ?? "") || !serverSdk.contentSha256)
  ) {
    problems.push("the vendored SDK record is incomplete");
  }
  return { problems, serverSdk };
}

const RECORD = /<!--\s*sac-release\s+(\{.*?\})\s*-->/s;

/** The machine-readable record a release body carries, or null. */
export function parseRecord(body) {
  const match = RECORD.exec(body ?? "");
  if (!match) return null;
  try {
    return JSON.parse(match[1]);
  } catch {
    return null;
  }
}

export function formatRecord(record) {
  return `<!-- sac-release ${JSON.stringify(record)} -->`;
}

/**
 * The Android release state for `tag`, from the repository's releases (drafts included).
 *
 * @returns {{action: "create" | "resume" | "verified", releaseId?: number, record?: object}}
 */
export function androidDecision({
  releases,
  tag,
  version,
  versionCode,
  commit,
  apk,
  prerelease = version.includes("-"),
}) {
  const own = releases.filter((release) => release.tag_name === tag);
  if (own.length > 1) {
    throw new Error(
      `${own.length} GitHub Releases exist for ${tag}; delete the extra drafts and retry`,
    );
  }

  // Android only installs an update whose versionCode is higher, so every *other* published app
  // release must have a lower code than this one.
  for (const release of releases) {
    if (release.draft || release.tag_name === tag) continue;
    if (!release.tag_name.startsWith("android-v")) continue;
    const record = parseRecord(release.body);
    if (record?.versionCode === undefined) continue;
    if (record.versionCode >= versionCode) {
      throw new Error(
        `${release.tag_name} was published with versionCode ${record.versionCode}; ${tag} has ` +
          `${versionCode}. Bump with \`pnpm release:bump android <version>\` so the code increases`,
      );
    }
  }

  // The repository's "latest release" is the app's download link, so only a stable app release
  // that is not older than another published stable one may take it.
  const newerStable = releases.some((release) => {
    if (release.draft || release.tag_name === tag) return false;
    const record = parseRecord(release.body);
    return (
      record?.component === "android" &&
      SEMVER.test(record.version ?? "") &&
      !record.version.includes("-") &&
      compareVersions(record.version, version) > 0
    );
  });
  const latest = !prerelease && !newerStable;

  const [existing] = own;
  if (!existing) return { action: "create", latest };
  if (existing.draft)
    return { action: "resume", releaseId: existing.id, latest };

  const record = parseRecord(existing.body);
  const hasApk = existing.assets?.some((asset) => asset.name === apk);
  if (!record || !hasApk) {
    throw new Error(
      `${tag} is already a published release but has no ${record ? apk : "release record"}; ` +
        "it was not made by this workflow. Fix it by hand rather than letting a rebuild replace it",
    );
  }
  if (
    record.commit !== commit ||
    record.version !== version ||
    record.versionCode !== versionCode
  ) {
    throw new Error(
      `${tag} is published from ${record.commit} as ${record.version} (${record.versionCode}); ` +
        `this run is ${commit} as ${version} (${versionCode}). A published release is immutable`,
    );
  }
  return { action: "verified", releaseId: existing.id, record, latest };
}

// ---------------------------------------------------------------------------------------------
// Registry reads
// ---------------------------------------------------------------------------------------------

const MANIFEST_TYPES = [
  "application/vnd.oci.image.index.v1+json",
  "application/vnd.docker.distribution.manifest.list.v2+json",
  "application/vnd.oci.image.manifest.v1+json",
  "application/vnd.docker.distribution.manifest.v2+json",
].join(", ");

/**
 * Fetch with a bounded retry on what is plausibly transient (a network error, 429, 5xx). The last
 * failure is thrown, never turned into an answer.
 */
async function request(
  url,
  init,
  { fetch = globalThis.fetch, attempts = 3 } = {},
) {
  let failure;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetch(url, init);
      if (response.status !== 429 && response.status < 500) return response;
      failure = new Error(`${url} answered ${response.status}`);
    } catch (error) {
      failure = new Error(`${url}: ${error.message}`);
    }
    if (attempt < attempts) {
      await new Promise((done) => setTimeout(done, 500 * attempt));
    }
  }
  throw failure;
}

export async function npmPackument(
  name,
  { registry = "https://registry.npmjs.org", fetch } = {},
) {
  const url = `${registry}/${name.replace("/", "%2F")}`;
  const response = await request(
    url,
    { headers: { accept: "application/json" } },
    { fetch },
  );
  if (response.status === 404) return { exists: false };
  if (response.status !== 200) {
    throw new Error(
      `${url} answered ${response.status}; cannot tell what is published`,
    );
  }
  const document = await response.json();
  const versions = {};
  for (const [version, entry] of Object.entries(document.versions ?? {})) {
    versions[version] = {
      gitHead: entry.gitHead,
      integrity: entry.dist?.integrity,
    };
  }
  return { exists: true, distTags: document["dist-tags"] ?? {}, versions };
}

/** `ghcr.io/owner/name:tag` → its parts. */
export function parseReference(reference) {
  const match = /^([^/]+)\/(.+?)(?::([^:/@]+)|@(sha256:[0-9a-f]{64}))?$/.exec(
    reference,
  );
  if (!match) throw new Error(`${reference} is not an image reference`);
  return {
    host: match[1],
    name: match[2],
    reference: match[3] ?? match[4] ?? "latest",
  };
}

/**
 * The state of one image tag on an OCI registry with token auth (GHCR).
 *
 * @param {object} options
 * @param {string} [options.token]   a GitHub token; omitted means anonymous, which is the check
 *                                   that a package is really public
 * @returns {{access: "ok" | "denied", exists?: boolean, digest?: string, labels?: object}}
 */
export async function imageState(reference, { token, fetch } = {}) {
  const { host, name, reference: tag } = parseReference(reference);
  const headers = {};
  if (token) {
    headers.authorization = `Basic ${Buffer.from(`token:${token}`).toString("base64")}`;
  }
  const tokenResponse = await request(
    `https://${host}/token?scope=repository:${name}:pull&service=${host}`,
    { headers },
    { fetch },
  );
  if (tokenResponse.status === 401 || tokenResponse.status === 403) {
    return { access: "denied" };
  }
  if (tokenResponse.status !== 200) {
    throw new Error(`${host} token endpoint answered ${tokenResponse.status}`);
  }
  const { token: bearer } = await tokenResponse.json();
  const authorization = { authorization: `Bearer ${bearer}` };

  const manifest = await request(
    `https://${host}/v2/${name}/manifests/${tag}`,
    { headers: { ...authorization, accept: MANIFEST_TYPES } },
    { fetch },
  );
  if (manifest.status === 404) return { access: "ok", exists: false };
  if (manifest.status === 401 || manifest.status === 403)
    return { access: "denied" };
  if (manifest.status !== 200) {
    throw new Error(`${reference} answered ${manifest.status}`);
  }
  const digest = manifest.headers.get("docker-content-digest");
  if (!digest) throw new Error(`${reference} answered without a digest`);
  const body = await manifest.json();

  // An index lists one manifest per platform plus attestation manifests (platform unknown/unknown).
  // Labels are the same on every platform; the first real one is read.
  let imageManifest = body;
  if (Array.isArray(body.manifests)) {
    const platform = body.manifests.find(
      (entry) => entry.platform && entry.platform.os !== "unknown",
    );
    if (!platform) throw new Error(`${reference} lists no platform image`);
    const child = await request(
      `https://${host}/v2/${name}/manifests/${platform.digest}`,
      { headers: { ...authorization, accept: MANIFEST_TYPES } },
      { fetch },
    );
    if (child.status !== 200) {
      throw new Error(
        `${reference} platform manifest answered ${child.status}`,
      );
    }
    imageManifest = await child.json();
  }
  const config = await request(
    `https://${host}/v2/${name}/blobs/${imageManifest.config.digest}`,
    { headers: authorization },
    { fetch },
  );
  if (config.status !== 200) {
    throw new Error(`${reference} config blob answered ${config.status}`);
  }
  const labels = (await config.json()).config?.Labels ?? {};
  return { access: "ok", exists: true, digest, labels };
}

/**
 * Whether a GHCR container package exists at all, from the GitHub Packages API. Used only to tell
 * "not created yet" from "this token may not read it" when the registry answers DENIED.
 */
export async function packageExists({ owner, name, token, fetch }) {
  const url = `https://api.github.com/orgs/${owner}/packages/container/${encodeURIComponent(name)}`;
  const response = await request(
    url,
    {
      headers: {
        authorization: `Bearer ${token}`,
        accept: "application/vnd.github+json",
      },
    },
    { fetch },
  );
  if (response.status === 404) return false;
  if (response.status === 200) return true;
  throw new Error(`${url} answered ${response.status}`);
}

/** Every release of the repository, drafts included (they are listed to a token with push access). */
export async function listReleases({ repository, token, fetch }) {
  const releases = [];
  for (let page = 1; page <= 20; page += 1) {
    const url = `https://api.github.com/repos/${repository}/releases?per_page=100&page=${page}`;
    const response = await request(
      url,
      {
        headers: {
          authorization: `Bearer ${token}`,
          accept: "application/vnd.github+json",
        },
      },
      { fetch },
    );
    if (response.status !== 200)
      throw new Error(`${url} answered ${response.status}`);
    const batch = await response.json();
    releases.push(...batch);
    if (batch.length < 100) return releases;
  }
  throw new Error("more than 2000 releases; refusing to guess");
}

// ---------------------------------------------------------------------------------------------
// CLI for .github/workflows/release.yml
// ---------------------------------------------------------------------------------------------

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set`);
  return value;
}

function output(values) {
  const lines = Object.entries(values)
    .map(([key, value]) => `${key}=${value}`)
    .join("\n");
  if (process.env.GITHUB_OUTPUT)
    appendFileSync(process.env.GITHUB_OUTPUT, `${lines}\n`);
  console.log(lines);
}

const warn = (message) => console.log(`::warning::${message}`);

async function authenticatedImageState(reference, token) {
  const state = await imageState(reference, { token });
  if (state.access === "ok") return state;
  const { name } = parseReference(reference);
  const [owner, ...rest] = name.split("/");
  if (!(await packageExists({ owner, name: rest.join("/"), token }))) {
    return { access: "ok", exists: false };
  }
  throw new Error(
    `${reference}: the package exists but this workflow's token may not read it. In the ` +
      "package's settings, under Manage Actions access, give SeekerAgentConnect/sac the Write role",
  );
}

const commands = {
  async "npm-decide"() {
    const decision = npmDecision({
      state: await npmPackument(required("NAME")),
      version: required("VERSION"),
      commit: required("COMMIT"),
      integrity: required("INTEGRITY"),
      prerelease: required("PRERELEASE") === "true",
    });
    decision.warnings.forEach(warn);
    output({
      action: decision.action,
      dist_tag: decision.distTag,
      integrity: decision.integrity,
    });
  },

  // After `npm publish`, the registry has to serve exactly the tarball that was uploaded.
  async "npm-confirm"() {
    const name = required("NAME");
    const version = required("VERSION");
    const integrity = required("INTEGRITY");
    for (let attempt = 1; attempt <= 10; attempt += 1) {
      const state = await npmPackument(name);
      const published = state.exists ? state.versions[version] : undefined;
      if (published) {
        if (published.integrity !== integrity) {
          throw new Error(
            `${name}@${version} is served with ${published.integrity}, not ${integrity}`,
          );
        }
        output({
          integrity: published.integrity,
          git_head: published.gitHead ?? "",
          dist_tags: JSON.stringify(state.distTags),
        });
        return;
      }
      await new Promise((done) => setTimeout(done, 3000 * attempt));
    }
    throw new Error(
      `${name}@${version} is not visible on the registry after publishing`,
    );
  },

  // The packed package.json arrives on stdin (`tar -xzOf <tgz> package/package.json`).
  async "npm-audit"() {
    const chunks = [];
    for await (const chunk of process.stdin) chunks.push(chunk);
    const { problems, serverSdk } = auditPackedManifest(
      JSON.parse(Buffer.concat(chunks).toString("utf8")),
      {
        name: required("NAME"),
        version: required("VERSION"),
        commit: required("COMMIT"),
      },
    );
    if (problems.length > 0)
      throw new Error(`the packed tarball: ${problems.join("; ")}`);
    output({
      server_sdk_version: serverSdk?.version ?? "",
      server_sdk_sha256: serverSdk?.contentSha256 ?? "",
    });
  },

  async "image-decide"() {
    const repository = required("REPOSITORY");
    const version = required("VERSION");
    const state = await authenticatedImageState(
      `${repository}:${version}`,
      required("GITHUB_TOKEN"),
    );
    const decision = imageDecision({
      state,
      version,
      commit: required("COMMIT"),
    });
    output({ action: decision.action, digest: decision.digest });
  },

  async "image-alias"() {
    const repository = required("REPOSITORY");
    const state = await authenticatedImageState(
      `${repository}:latest`,
      required("GITHUB_TOKEN"),
    );
    const current = state.exists
      ? (state.labels["org.opencontainers.image.version"] ?? "unlabelled")
      : "";
    const decision = aliasMoves({ current, candidate: required("VERSION") });
    console.log(`latest: ${decision.reason}`);
    output({ move: decision.move, current });
  },

  // The proof a package is public: the tags resolve, to the expected digest, with no credential.
  async "image-anonymous"() {
    const repository = required("REPOSITORY");
    const digest = required("DIGEST");
    for (const tag of required("TAGS").split(/\s+/).filter(Boolean)) {
      const reference = `${repository}:${tag}`;
      const state = await imageState(reference);
      if (state.access !== "ok" || !state.exists) {
        const { name } = parseReference(repository);
        const [owner, ...rest] = name.split("/");
        throw new Error(
          `${reference} cannot be pulled anonymously. Make the package public once: ` +
            `https://github.com/orgs/${owner}/packages/container/package/${rest.join("/")} → ` +
            "Package settings → Change visibility → Public, then re-run this job",
        );
      }
      if (state.digest !== digest) {
        throw new Error(
          `${reference} resolves to ${state.digest}, not ${digest}`,
        );
      }
      console.log(`${reference} -> ${state.digest} (anonymous)`);
    }
  },

  async "android-decide"() {
    const decision = androidDecision({
      releases: await listReleases({
        repository: required("GITHUB_REPOSITORY"),
        token: required("GITHUB_TOKEN"),
      }),
      tag: required("TAG"),
      version: required("VERSION"),
      versionCode: Number(required("VERSION_CODE")),
      commit: required("COMMIT"),
      apk: required("APK"),
      prerelease: required("PRERELEASE") === "true",
    });
    output({
      action: decision.action,
      release_id: decision.releaseId ?? "",
      sha256: decision.record?.sha256 ?? "",
      latest: decision.latest,
    });
  },

  // The record a release body carries, printed for the workflow to embed.
  record() {
    console.log(formatRecord(JSON.parse(required("RECORD"))));
  },
};

if (process.argv[1] === import.meta.filename) {
  const command = commands[process.argv[2]];
  if (!command) {
    console.error(
      `usage: node scripts/release-state.mjs <${Object.keys(commands).join("|")}>`,
    );
    process.exit(2);
  }
  try {
    await command();
  } catch (error) {
    console.log(`::error::${error.message}`);
    process.exit(1);
  }
}
