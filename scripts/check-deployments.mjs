import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { extname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../", import.meta.url));

function filesBelow(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? filesBelow(path) : [path];
  });
}

function compose(args, environment = {}) {
  return execFileSync("docker", ["compose", ...args, "config"], {
    cwd: ROOT,
    encoding: "utf8",
    env: { ...process.env, ...environment },
    stdio: ["ignore", "pipe", "pipe"],
  });
}

function services(args, environment = {}) {
  return execFileSync("docker", ["compose", ...args, "config", "--services"], {
    cwd: ROOT,
    encoding: "utf8",
    env: { ...process.env, ...environment },
    stdio: ["ignore", "pipe", "pipe"],
  })
    .trim()
    .split("\n")
    .filter(Boolean)
    .sort();
}

const presets = [
  {
    name: "feed",
    args: [
      "--env-file",
      "deploy/feed/.env.example",
      "-f",
      "deploy/feed/compose.yaml",
    ],
    services: ["centrifugo", "feed-gateway", "redis"],
  },
  {
    name: "mcp",
    args: [
      "--env-file",
      "deploy/mcp/.env.example",
      "-f",
      "deploy/mcp/compose.yaml",
    ],
    services: ["mcp-server"],
  },
  {
    name: "copytrading",
    args: [
      "--env-file",
      "deploy/copytrading/.env.example",
      "-f",
      "deploy/copytrading/compose.yaml",
    ],
    services: ["copytrading"],
  },
  {
    name: "prediction",
    args: [
      "--env-file",
      "deploy/prediction/.env.example",
      "-f",
      "deploy/prediction/compose.yaml",
    ],
    services: ["prediction"],
  },
  {
    name: "feed ingress",
    args: [
      "--env-file",
      "deploy/ingress/feed/.env.example",
      "-f",
      "deploy/ingress/feed/compose.yaml",
    ],
    services: ["feed-ingress"],
  },
  {
    name: "direct ingress",
    args: [
      "--env-file",
      "deploy/ingress/direct/.env.example",
      "-f",
      "deploy/ingress/direct/compose.yaml",
    ],
    services: ["direct-ingress"],
  },
  {
    name: "Tailscale feed ingress",
    args: [
      "--env-file",
      "deploy/operators/tailscale/.env.example",
      "-f",
      "deploy/operators/tailscale/compose.feed.yaml",
    ],
    services: ["feed-ingress"],
  },
  {
    name: "Tailscale direct TLS overlay",
    args: [
      "--env-file",
      "deploy/mcp/.env.example",
      "-f",
      "deploy/mcp/compose.yaml",
      "-f",
      "deploy/operators/tailscale/compose.direct.yaml",
    ],
    environment: { TAILSCALE_TLS_DIR: "/tmp/seeker-tailscale-certificates" },
    services: ["mcp-server"],
  },
];

const yamlFiles = filesBelow(join(ROOT, "deploy")).filter((path) =>
  [".yaml", ".yml"].includes(extname(path)),
);
assert.ok(yamlFiles.length >= 8, "found the canonical deployment files");
for (const path of yamlFiles) {
  const source = readFileSync(path, "utf8");
  assert.doesNotMatch(source, /\bnetwork_mode\s*:/, relative(ROOT, path));
  assert.doesNotMatch(source, /\bnetwork_mode:\s*host\b/, relative(ROOT, path));
}

for (const retired of [
  "gateway/compose.yaml",
  "feed-gateway/compose.yaml",
  "mcp-server/compose.yaml",
  "demo-copytrading/compose.yaml",
  "demo-prediction/compose.yaml",
  "deploy/server/compose.yaml",
]) {
  assert.equal(existsSync(join(ROOT, retired)), false, `${retired} is retired`);
}

for (const preset of presets) {
  const resolved = compose(preset.args, preset.environment);
  assert.doesNotMatch(resolved, /\bnetwork_mode\s*:/, preset.name);
  assert.deepEqual(
    services(preset.args, preset.environment),
    [...preset.services].sort(),
    `${preset.name} starts only its declared base services`,
  );
}

assert.deepEqual(
  services([
    "--env-file",
    "deploy/feed/.env.example",
    "-f",
    "deploy/feed/compose.yaml",
    "--profile",
    "operator",
  ]),
  ["centrifugo", "feed-gateway", "gateway-ctl", "redis"],
);
assert.deepEqual(
  services([
    "--env-file",
    "deploy/copytrading/.env.example",
    "-f",
    "deploy/copytrading/compose.yaml",
    "--profile",
    "operator",
  ]),
  ["copytrading", "copytrading-pass", "ctl"],
);
assert.deepEqual(
  services([
    "--env-file",
    "deploy/prediction/.env.example",
    "-f",
    "deploy/prediction/compose.yaml",
    "--profile",
    "operator",
  ]),
  ["ctl", "prediction"],
);

const feed = compose(presets[0].args);
const mcp = compose(presets[1].args);
const copytrading = compose(presets[2].args);
const prediction = compose(presets[3].args);
assert.match(feed, /host_ip: 127\.0\.0\.1/);
assert.doesNotMatch(feed, /target: (?:6379|8000)/);
assert.match(feed, /BROADCAST_STREAM_URL: ""/);
assert.match(feed, /BROADCAST_STREAM_API_KEY: ""/);
assert.match(feed, /BROADCAST_STREAM_TOKEN_KEY: ""/);
assert.match(mcp, /name: seeker-agent-connect-mcp_mcp-data/);
assert.match(copytrading, /name: seeker-publisher_publisher-data/);
assert.match(prediction, /name: seeker-prediction_prediction-data/);

const externalRedis = compose(presets[0].args, {
  CENTRIFUGO_REDIS_URL:
    "rediss://cache-user:cache-password@redis.example:6380/4",
  CENTRIFUGO_REDIS_PREFIX: "tenant-135",
  CENTRIFUGO_REDIS_TLS_SERVER_NAME: "redis.example",
  CENTRIFUGO_REDIS_TLS_CERT_PEM: "base64-client-certificate",
  CENTRIFUGO_REDIS_TLS_KEY_PEM: "base64-client-key",
});
assert.match(
  externalRedis,
  /CENTRIFUGO_ENGINE_REDIS_ADDRESS: rediss:\/\/cache-user:cache-password@redis\.example:6380\/4/,
);
assert.match(externalRedis, /CENTRIFUGO_ENGINE_REDIS_PREFIX: tenant-135/);
assert.match(
  externalRedis,
  /CENTRIFUGO_ENGINE_REDIS_TLS_SERVER_NAME: redis\.example/,
);
assert.match(
  externalRedis,
  /CENTRIFUGO_ENGINE_REDIS_TLS_CERT_PEM: base64-client-certificate/,
);
assert.match(
  externalRedis,
  /CENTRIFUGO_ENGINE_REDIS_TLS_KEY_PEM: base64-client-key/,
);

const feedIngress = readFileSync(
  join(ROOT, "deploy/ingress/feed/Caddyfile"),
  "utf8",
);
assert.match(
  feedIngress,
  /\/centrifugal\.centrifugo\.unistream\.CentrifugoUniStream\/Consume/,
);
assert.match(feedIngress, /reverse_proxy h2c:\/\/centrifugo:11000/);
assert.doesNotMatch(feedIngress, /\/trader|\/healthz|redis:6379/);

const directIngress = readFileSync(
  join(ROOT, "deploy/ingress/direct/Caddyfile"),
  "utf8",
);
assert.match(directIngress, /\/mcp/);
assert.doesNotMatch(
  directIngress,
  /seekervault\.(?:update|live)\.v1|\/healthz/,
);

const migrationGuide = readFileSync(join(ROOT, "deploy/README.md"), "utf8");
for (const durableIdentity of [
  "seeker-agent-wallet_sidecar-data",
  "seeker-agent-connect-mcp_mcp-data",
  "seeker-agent-wallet-server_sidecar-data",
  "seeker-broadcast_broadcast-data",
  "seeker-agent-wallet-server_broadcast-data",
  "seeker-publisher_publisher-data",
  "seeker-agent-wallet-server_copytrading-data",
  "/data/copytrading.db",
  "seeker-prediction_prediction-data",
  "seeker-agent-wallet-server_prediction-data",
  "seeker-broadcast_proxy-data",
  "seeker-broadcast_proxy-config",
  "seeker-agent-wallet_gateway-data",
  "seeker-agent-wallet_gateway-config",
  "seeker-agent-wallet-server_gateway-caddy-data",
  "seeker-agent-wallet-server_gateway-caddy-config",
]) {
  assert.match(
    migrationGuide,
    new RegExp(durableIdentity.replaceAll(".", "\\.")),
  );
}
assert.match(migrationGuide, /do not use.*down -v.*--remove-orphans/is);

console.log(`deployment check passed (${presets.length} resolved presets)`);
