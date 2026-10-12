/**
 * Which files belong to which independently released component (SEE-187).
 *
 * Nx owns one directory per project, and `gateway` / `gateway-centrifugo` share `services/gateway`.
 * The longest project root would give every file under that directory to `gateway`, including the
 * broker config. These boundaries are the explicit split the release plan uses: a gateway-only
 * change does not release Centrifugo, and a Centrifugo-only change does not release the gateway.
 * `project.json` and `CHANGELOG.md` are Nx's own files, so adding them is not a product change.
 */
export const RELEASED = [
  "server-sdk",
  "mcp-server",
  "mcp-skr-staking",
  "gateway",
  "gateway-centrifugo",
  "demo-signals",
  "demo-prediction",
  "android",
];

const CENTRIFUGO_FILES = new Set([
  "services/gateway/centrifugo.yaml",
  "services/gateway/Dockerfile.centrifugo",
]);

/** Projects whose published artifact vendors server-sdk's built output. */
export const VENDORS_SERVER_SDK = ["mcp-server", "mcp-skr-staking"];

const BUMP_RANK = { none: 0, patch: 1, minor: 2, major: 3 };

/**
 * @param {string} file workspace-relative path, posix
 * @returns {string[]} released project names that file can cause a release of
 */
export function ownersOf(file) {
  const path = file.replaceAll("\\", "/");
  if (
    path.endsWith("/project.json") ||
    path.endsWith("/CHANGELOG.md") ||
    path.endsWith("/probe-input.txt")
  ) {
    return [];
  }
  if (
    path.startsWith("packages/server-sdk/") &&
    !path.startsWith("packages/server-sdk/dist/")
  ) {
    return ["server-sdk"];
  }
  if (
    path.startsWith("servers/mcp-server/") &&
    !path.includes("/dist/") &&
    !path.includes("/package/")
  ) {
    return ["mcp-server"];
  }
  if (
    path.startsWith("servers/mcp-skr-staking/") &&
    !path.includes("/dist/") &&
    !path.includes("/package/")
  ) {
    return ["mcp-skr-staking"];
  }
  if (CENTRIFUGO_FILES.has(path)) return ["gateway-centrifugo"];
  if (path.startsWith("services/gateway/centrifugo/")) return [];
  if (path.startsWith("services/gateway/")) return ["gateway"];
  if (path.startsWith("examples/demo-signals/")) return ["demo-signals"];
  if (path.startsWith("examples/demo-prediction/")) return ["demo-prediction"];
  if (
    path.startsWith("apps/android/") &&
    !path.includes("/build/") &&
    !path.startsWith("apps/android/.gradle/")
  ) {
    return ["android"];
  }
  return [];
}

const HEADER =
  /^(?<type>feat|fix|perf|revert|docs|style|refactor|test|build|ci|chore)(?:\((?<scope>[a-z0-9-]+)\))?(?<breaking>!)?: (?<subject>.+)$/;

/**
 * The raw Conventional Commit bump, before Nx's 0.x adjustment.
 * `feat` is minor, `fix`/`perf`/`revert` are patch, a breaking marker is major.
 * Anything else does not release. Nx then applies `adjustSemverBumpsForZeroMajorVersion`.
 */
export function rawBump(message, body = "") {
  const header = message.split("\n")[0];
  const match = HEADER.exec(header);
  if (!match?.groups) return "none";
  const breaking =
    Boolean(match.groups.breaking) || /BREAKING CHANGE/.test(body);
  if (breaking) return "major";
  switch (match.groups.type) {
    case "feat":
      return "minor";
    case "fix":
    case "perf":
    case "revert":
      return "patch";
    default:
      return "none";
  }
}

export function maxBump(left, right) {
  return BUMP_RANK[left] >= BUMP_RANK[right] ? left : right;
}

/**
 * @param {{ message: string, body?: string, files: string[] }[]} commits
 * @returns {Map<string, { bump: string, commits: object[], vendored: boolean }>}
 */
export function classifyCommits(commits) {
  /** @type {Map<string, { bump: string, commits: object[], vendored: boolean }>} */
  const plan = new Map();
  const touch = (id, bump, commit, vendored) => {
    const current = plan.get(id) ?? {
      bump: "none",
      commits: [],
      vendored: false,
    };
    current.bump = maxBump(current.bump, bump);
    if (commit) current.commits.push(commit);
    current.vendored = current.vendored || vendored;
    plan.set(id, current);
  };

  for (const commit of commits) {
    const bump = rawBump(commit.message, commit.body ?? "");
    if (bump === "none") continue;
    const seen = new Set();
    for (const file of commit.files) {
      for (const id of ownersOf(file)) {
        if (seen.has(id)) continue;
        seen.add(id);
        touch(id, bump, commit, false);
      }
    }
  }

  if (plan.get("server-sdk")?.bump && plan.get("server-sdk").bump !== "none") {
    for (const id of VENDORS_SERVER_SDK) {
      const existing = plan.get(id);
      if (!existing || existing.bump === "none") {
        touch(id, "patch", null, true);
      } else {
        existing.vendored = true;
      }
    }
  }

  for (const [id, entry] of [...plan]) {
    if (entry.bump === "none") plan.delete(id);
  }
  return plan;
}
