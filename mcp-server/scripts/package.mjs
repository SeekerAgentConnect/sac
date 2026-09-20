/**
 * Build the distributable application tree. Source imports the SDK by its public package entries;
 * this step vendors the SDK's built public runtime and rewrites only those emitted package imports.
 * The packed manifest therefore needs no unpublished workspace/registry dependency.
 */
import {
  cpSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const PACKAGE_ROOT = fileURLToPath(new URL("../", import.meta.url));
const REPOSITORY_ROOT = resolve(PACKAGE_ROOT, "..");
const DIST = join(PACKAGE_ROOT, "dist");
const SDK_DIST = join(REPOSITORY_ROOT, "server-sdk", "dist");
const VENDORED_SDK = join(DIST, "vendor", "server-sdk");
const STAGED = join(PACKAGE_ROOT, "package");
const PAGE_SRC = join(PACKAGE_ROOT, "src", "pairing", "page");
const PAGE_DIST = join(DIST, "pairing", "page");

mkdirSync(PAGE_DIST, { recursive: true });
for (const name of ["page.js", "page.css", "payload.js"]) {
  cpSync(join(PAGE_SRC, name), join(PAGE_DIST, name));
}
cpSync(fileURLToPath(import.meta.resolve("uqr")), join(PAGE_DIST, "uqr.js"));

cpSync(SDK_DIST, VENDORED_SDK, {
  recursive: true,
  filter: (source) => !source.endsWith(".d.ts"),
});

let rewrittenImports = 0;
for (const file of javascriptFiles(DIST)) {
  if (file.startsWith(`${VENDORED_SDK}${sep}`)) continue;
  const directory = dirname(file);
  const sdk = moduleSpecifier(directory, join(VENDORED_SDK, "index.js"));
  const protocol = moduleSpecifier(
    directory,
    join(VENDORED_SDK, "protocol.js"),
  );
  const before = readFileSync(file, "utf8");
  rewrittenImports +=
    before.match(/"@seeker-vault\/server-sdk(?:\/protocol)?"/g)?.length ?? 0;
  const source = before
    .replaceAll('"@seeker-vault/server-sdk/protocol"', JSON.stringify(protocol))
    .replaceAll('"@seeker-vault/server-sdk"', JSON.stringify(sdk));
  if (source.includes('"@seeker-vault/server-sdk')) {
    throw new Error(`unbundled SDK import remains in ${relative(DIST, file)}`);
  }
  writeFileSync(file, source);
}
if (rewrittenImports === 0) {
  throw new Error(
    "the application build contained no public SDK imports to bundle",
  );
}

mkdirSync(STAGED, { recursive: true });
cpSync(DIST, join(STAGED, "dist"), { recursive: true });
for (const name of ["README.md", "LICENSE"]) {
  cpSync(join(PACKAGE_ROOT, name), join(STAGED, name));
}

const sourceManifest = JSON.parse(
  readFileSync(join(PACKAGE_ROOT, "package.json"), "utf8"),
);
const dependencies = { ...sourceManifest.dependencies };
delete dependencies["@seeker-vault/server-sdk"];
const manifest = {
  name: sourceManifest.name,
  version: sourceManifest.version,
  private: true,
  description: sourceManifest.description,
  type: sourceManifest.type,
  license: sourceManifest.license,
  repository: sourceManifest.repository,
  engines: sourceManifest.engines,
  bin: sourceManifest.bin,
  files: sourceManifest.files,
  dependencies,
};
writeFileSync(
  join(STAGED, "package.json"),
  `${JSON.stringify(manifest, null, 2)}\n`,
);

function javascriptFiles(directory) {
  return readdirSync(directory, {
    recursive: true,
    withFileTypes: true,
  })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".js"))
    .map((entry) => join(entry.parentPath, entry.name));
}

function moduleSpecifier(from, target) {
  const path = relative(from, target).split(sep).join("/");
  return path.startsWith(".") ? path : `./${path}`;
}
