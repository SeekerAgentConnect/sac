// Repository-side deployment checks: retired layouts stay gone, each image's health command and each
// package's .env.example stay what the do-deploy presets expect. Needs no Docker CLI.
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../", import.meta.url));

function filesBelow(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? filesBelow(path) : [path];
  });
}

for (const retiredRoot of ["sidecar", "broadcast", "publisher", "gateway"]) {
  if (existsSync(join(ROOT, retiredRoot))) {
    assert.deepEqual(
      filesBelow(join(ROOT, retiredRoot)),
      [],
      `${retiredRoot}/ contains no tracked implementation files`,
    );
  }
}

for (const retired of [
  "gateway/compose.yaml",
  "services/gateway/compose.yaml",
  "servers/mcp-server/compose.yaml",
  "examples/demo-signals/compose.yaml",
  "examples/demo-prediction/compose.yaml",
]) {
  assert.equal(existsSync(join(ROOT, retired)), false, `${retired} is retired`);
}
// The Compose presets, ingress and operator examples live in SeekerAgentConnect/do-deploy, which
// checks them with its own scripts/check-compose.mjs. Nothing deploys from this checkout.
assert.equal(
  existsSync(join(ROOT, "deploy")),
  false,
  "deploy/ moved to do-deploy",
);

const activeLayoutDocs = [
  "README.md",
  "RFC.md",
  "docs/architecture.md",
  "docs/development/feed-gateway.md",
  "docs/development/mcp-server.md",
  "docs/guides/server-development.md",
  "docs/guides/troubleshooting.md",
  "docs/integrations/hermes.md",
  "docs/wiki/feed-gateway.md",
  "docs/wiki/mcp-adapter.md",
  "examples/hermes.config.yaml",
  "examples/hermes.config.hosted.yaml",
];
for (const path of activeLayoutDocs) {
  const source = readFileSync(join(ROOT, path), "utf8");
  for (const retiredInstruction of [
    "gateway/.env",
    "gateway/compose.yaml",
    "deploy/server/compose.yaml",
    "docker compose logs sidecar",
  ]) {
    assert.doesNotMatch(
      source,
      new RegExp(retiredInstruction.replaceAll(".", "\\.")),
      `${path} does not instruct operators to use ${retiredInstruction}`,
    );
  }
}

const mcpPackageEnvironment = readFileSync(
  join(ROOT, "servers/mcp-server/.env.example"),
  "utf8",
);
assert.match(mcpPackageEnvironment, /^SIDECAR_HOST=127\.0\.0\.1$/m);
assert.match(mcpPackageEnvironment, /^SIDECAR_PORT=8080$/m);

// The staking server runs beside the general one, so every name it reads is its own and its
// default port is a different one (SEE-146). Two servers that shared a name could not both be
// configured out of one .env, which is exactly how they are meant to be deployed.
const skrPackageEnvironment = readFileSync(
  join(ROOT, "servers/mcp-skr-staking/.env.example"),
  "utf8",
);
assert.match(skrPackageEnvironment, /^SKR_STAKING_HOST=127\.0\.0\.1$/m);
assert.match(skrPackageEnvironment, /^SKR_STAKING_PORT=8090$/m);
for (const shared of [
  "SIDECAR_PORT=",
  "SIDECAR_HOST=",
  "MCP_TOKEN=",
  "PHONE_TOKEN=",
]) {
  assert.doesNotMatch(
    skrPackageEnvironment,
    new RegExp(`^${shared}`, "m"),
    `servers/mcp-skr-staking/.env.example does not reuse ${shared}`,
  );
}

const androidSources = [
  ...filesBelow(join(ROOT, "apps/android/app/src/main")),
  ...filesBelow(join(ROOT, "apps/android/app/src/test")),
]
  .filter((path) => extname(path) === ".kt")
  .map((path) => readFileSync(path, "utf8"))
  .join("\n");
assert.doesNotMatch(androidSources, /File\(repoRoot,\s*"sidecar"\)/);
assert.doesNotMatch(androidSources, /resolve\(\s*"sidecar"\s*\)/);

const mcpDockerfile = readFileSync(
  join(ROOT, "servers/mcp-server/Dockerfile"),
  "utf8",
);
assert.match(
  mcpDockerfile,
  /CMD \["node", "servers\/mcp-server\/dist\/healthcheck\.js"\]/,
);
assert.doesNotMatch(mcpDockerfile, /fetch\(['"]http:\/\/127\.0\.0\.1/);

const skrDockerfile = readFileSync(
  join(ROOT, "servers/mcp-skr-staking/Dockerfile"),
  "utf8",
);
assert.match(
  skrDockerfile,
  /CMD \["node", "servers\/mcp-skr-staking\/dist\/healthcheck\.js"\]/,
);
assert.doesNotMatch(skrDockerfile, /fetch\(['"]http:\/\/127\.0\.0\.1/);

console.log("deployment check passed");
