/**
 * Stage a publishable MCP server artifact.
 *
 * Both MCP servers are built the same way and for the same reason (SEE-131, SEE-168): source
 * imports the Direct Server SDK by its public package entries, and this step vendors the SDK's
 * built public runtime and rewrites only those emitted package imports. The staged manifest
 * therefore carries no workspace dependency, and the tarball runs under `npx` with nothing from
 * this checkout.
 *
 * The SDK is vendored rather than depended on from the registry deliberately: an MCP release then
 * needs no SDK release to land first, and the artifact an external consumer runs is the exact tree
 * this repository tested. The cost is that an SDK change requires re-releasing both dependents,
 * which `scripts/check-release.mjs` and docs/development/releases.md make explicit.
 */
import {
  cpSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";

export const SDK_PACKAGE = "@seeker_agent_connect/server-sdk";
// Everything a published manifest may not contain: a specifier only this workspace can resolve.
const WORKSPACE_ONLY = /^(?:workspace|catalog|link|file|portal):/;

/**
 * @param {object} options
 * @param {string} options.packageRoot            the MCP package's own directory
 * @param {(distributionRoot: string) => void} [options.copyExtraAssets]
 *        runtime files the compiler does not emit, copied into `dist` before the SDK is vendored
 */
export function stageMcpPackage({ packageRoot, copyExtraAssets }) {
  const repositoryRoot = resolve(packageRoot, "..", "..");
  const distribution = join(packageRoot, "dist");
  const sdkDistribution = join(
    repositoryRoot,
    "packages",
    "server-sdk",
    "dist",
  );
  const vendoredSdk = join(distribution, "vendor", "server-sdk");
  const staged = join(packageRoot, "package");

  copyExtraAssets?.(distribution);

  cpSync(sdkDistribution, vendoredSdk, {
    recursive: true,
    filter: (source) => !source.endsWith(".d.ts"),
  });

  let rewrittenImports = 0;
  for (const file of javascriptFiles(distribution)) {
    if (file.startsWith(`${vendoredSdk}${sep}`)) continue;
    const directory = dirname(file);
    const sdk = moduleSpecifier(directory, join(vendoredSdk, "index.js"));
    const protocol = moduleSpecifier(
      directory,
      join(vendoredSdk, "protocol.js"),
    );
    const before = readFileSync(file, "utf8");
    rewrittenImports +=
      before.match(
        new RegExp(`"${escapeRegExp(SDK_PACKAGE)}(?:/protocol)?"`, "g"),
      )?.length ?? 0;
    const source = before
      .replaceAll(`"${SDK_PACKAGE}/protocol"`, JSON.stringify(protocol))
      .replaceAll(`"${SDK_PACKAGE}"`, JSON.stringify(sdk));
    if (source.includes(`"${SDK_PACKAGE}`)) {
      throw new Error(
        `unbundled SDK import remains in ${relative(distribution, file)}`,
      );
    }
    writeFileSync(file, source);
  }
  if (rewrittenImports === 0) {
    throw new Error(
      "the application build contained no public SDK imports to bundle",
    );
  }

  mkdirSync(staged, { recursive: true });
  cpSync(distribution, join(staged, "dist"), { recursive: true });
  for (const name of ["README.md", "LICENSE"]) {
    cpSync(join(packageRoot, name), join(staged, name));
  }

  const source = JSON.parse(
    readFileSync(join(packageRoot, "package.json"), "utf8"),
  );
  const dependencies = publishedDependencies(
    source.dependencies,
    repositoryRoot,
  );
  assertVendoredSdkIsSatisfied(sdkDistribution, dependencies, source.name);
  // An explicit allowlist rather than a copy of the source manifest: the staged package is what
  // the registry serves, so a field arrives here only because a consumer or npm needs it. `files`
  // travels with it so `npm pack` produces the same three entries from either directory.
  const manifest = {
    name: source.name,
    version: source.version,
    description: source.description,
    keywords: source.keywords,
    homepage: source.homepage,
    bugs: source.bugs,
    repository: source.repository,
    license: source.license,
    author: source.author,
    type: source.type,
    engines: source.engines,
    bin: source.bin,
    files: source.files,
    publishConfig: source.publishConfig,
    dependencies,
  };
  for (const [key, value] of Object.entries(manifest)) {
    if (value === undefined) {
      throw new Error(`${source.name} is missing the published field ${key}`);
    }
  }
  writeFileSync(
    join(staged, "package.json"),
    `${JSON.stringify(manifest, null, 2)}\n`,
  );
  return staged;
}

/**
 * The vendored SDK's own runtime dependencies become this package's, because its code is now this
 * package's code. Nothing else installs them. A dependency that is dev-only here would resolve in
 * the workspace and fail on the first `npx` of the published artifact, which is exactly the
 * failure this catches at build time.
 */
function assertVendoredSdkIsSatisfied(sdkDistribution, dependencies, name) {
  const sdk = JSON.parse(
    readFileSync(join(sdkDistribution, "..", "package.json"), "utf8"),
  );
  const missing = Object.keys(sdk.dependencies ?? {}).filter(
    (dependency) => !(dependency in dependencies),
  );
  if (missing.length > 0) {
    throw new Error(
      `${name} vendors the SDK but does not depend on ${missing.join(", ")}; ` +
        "move them out of devDependencies",
    );
  }
}

/**
 * The dependency map as the registry must see it: the vendored SDK dropped, and every `catalog:`
 * specifier replaced by the version the workspace catalog pins. The catalog is the one place the
 * shared versions are declared, so resolving here keeps that true instead of duplicating them into
 * two manifests that would then drift.
 */
export function publishedDependencies(declared, repositoryRoot) {
  const catalog = workspaceCatalog(repositoryRoot);
  const dependencies = {};
  for (const [name, specifier] of Object.entries(declared ?? {})) {
    if (name === SDK_PACKAGE) continue;
    let resolved = specifier;
    if (specifier === "catalog:" || specifier === "catalog:default") {
      resolved = catalog[name];
      if (resolved === undefined) {
        throw new Error(
          `${name} asks for the default catalog, which does not pin it`,
        );
      }
    }
    if (WORKSPACE_ONLY.test(resolved)) {
      throw new Error(
        `${name}@${resolved} cannot be published: no registry can resolve that specifier`,
      );
    }
    dependencies[name] = resolved;
  }
  return dependencies;
}

/**
 * The default catalog out of pnpm-workspace.yaml. Deliberately a narrow reader rather than a YAML
 * parser: the block is a flat map of package name to version, and anything else here — a named
 * catalog, a nested value — should stop a release rather than be guessed at.
 */
export function workspaceCatalog(repositoryRoot) {
  const lines = readFileSync(
    join(repositoryRoot, "pnpm-workspace.yaml"),
    "utf8",
  ).split("\n");
  const start = lines.findIndex((line) => line === "catalog:");
  if (start === -1)
    throw new Error("pnpm-workspace.yaml declares no default catalog");
  const catalog = {};
  for (const line of lines.slice(start + 1)) {
    if (line.trim() === "" || line.trimStart().startsWith("#")) continue;
    if (!line.startsWith("  ")) break;
    const entry = /^ {2}("?)([^"]+)\1: *(\S+)$/.exec(line);
    if (!entry) throw new Error(`unreadable catalog entry: ${line}`);
    catalog[entry[2]] = entry[3];
  }
  return catalog;
}

function javascriptFiles(directory) {
  return readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".js"))
    .map((entry) => join(entry.parentPath, entry.name));
}

function moduleSpecifier(from, target) {
  const path = relative(from, target).split(sep).join("/");
  return path.startsWith(".") ? path : `./${path}`;
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
