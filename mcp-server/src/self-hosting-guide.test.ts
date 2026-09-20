/**
 * Keep the direct self-hosting guide tied to the canonical portable deployment.
 */
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const GUIDE_PATH = "docs/guides/self-hosting.md";
const GUIDE_DIRECTORY = "docs/guides";
const COMPOSE_PATH = "deploy/mcp/compose.yaml";
const DEPLOYMENT_PATH = "deploy/README.md";

const guide = readFileSync(join(ROOT, GUIDE_PATH), "utf8");
const compose = readFileSync(join(ROOT, COMPOSE_PATH), "utf8");
const deployment = readFileSync(join(ROOT, DEPLOYMENT_PATH), "utf8");

describe("the direct self-hosting guide", () => {
  it("uses the canonical portable MCP deployment", () => {
    assert.match(guide, /deploy\/README\.md/);
    assert.match(guide, /one step-by-step deployment runbook/);
    assert.match(deployment, /deploy\/mcp\/compose\.yaml/);
    assert.match(compose, /^name: seeker-agent-connect-mcp$/m);
    assert.match(compose, /^ {2}mcp-server:$/m);
    assert.doesNotMatch(
      compose,
      /^ {2}(?:feed-gateway|redis|copytrading|prediction|gateway):$/m,
    );
    assert.doesNotMatch(compose, /network_mode:/);
  });

  it("publishes only the application on host loopback by default", () => {
    assert.match(
      compose,
      /\$\{MCP_SERVER_BIND:-127\.0\.0\.1\}:\$\{MCP_SERVER_PORT:-8080\}:8080/,
    );
    assert.match(deployment, /loopback defaults/);
    assert.match(deployment, /native HTTPS and HTTP\/2/);
    assert.match(deployment, /preserves ALPN `h2`/i);
  });

  it("documents the exact durable identity without destructive shortcuts", () => {
    assert.match(
      compose,
      /name: \$\{MCP_VOLUME_NAME:-seeker-agent-connect-mcp_mcp-data\}/,
    );
    assert.match(
      compose,
      /DATABASE_PATH: \$\{DATABASE_PATH:-\/data\/sidecar\.db\}/,
    );
    assert.match(deployment, /seeker-agent-wallet_sidecar-data/);
    assert.match(deployment, /seeker-agent-wallet-server_sidecar-data/);
    assert.match(deployment, /Do not use.*down -v.*--remove-orphans/is);
  });

  it("keeps ingress and host-specific networking optional", () => {
    assert.match(guide, /deploy\/ingress\/direct/);
    assert.match(guide, /independent optional Caddy project/i);
    assert.match(guide, /deploy\/operators\/tailscale/);
    assert.match(deployment, /no Tailscale dependency/i);
    assert.doesNotMatch(guide, /gateway-private/);
  });

  it("links only to files that exist", () => {
    const links = [...guide.matchAll(/\]\((\.[^)#]*)(?:#[^)]*)?\)/g)].map(
      (match) => match[1] ?? "",
    );
    assert.ok(links.length >= 5, "found the guide's local links");
    assert.deepEqual(
      links.filter((link) => !existsSync(join(ROOT, GUIDE_DIRECTORY, link))),
      [],
    );
  });
});
