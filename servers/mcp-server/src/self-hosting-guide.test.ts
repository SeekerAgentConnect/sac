/**
 * Keep the direct self-hosting guide tied to the canonical portable deployment, whose Compose
 * presets and runbook live in the SeekerAgentConnect/do-deploy repository.
 */
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../../", import.meta.url));
const GUIDE_PATH = "docs/guides/self-hosting.md";
const GUIDE_DIRECTORY = "docs/guides";

const guide = readFileSync(join(ROOT, GUIDE_PATH), "utf8");

describe("the direct self-hosting guide", () => {
  it("uses the canonical portable MCP deployment in do-deploy", () => {
    assert.match(guide, /do-deploy\/blob\/main\/compose\/README\.md/);
    assert.match(guide, /one step-by-step deployment runbook/);
    assert.equal(existsSync(join(ROOT, "deploy")), false);
  });

  it("keeps ingress and host-specific networking optional", () => {
    assert.match(guide, /compose\/ingress\/direct/);
    assert.match(guide, /independent optional Caddy project/i);
    assert.match(guide, /compose\/operators\/tailscale/);
    assert.doesNotMatch(guide, /gateway-private/);
  });

  it("links only to files that exist", () => {
    const links = [...guide.matchAll(/\]\((\.[^)#]*)(?:#[^)]*)?\)/g)].map(
      (match) => match[1] ?? "",
    );
    assert.ok(links.length >= 4, "found the guide's local links");
    assert.deepEqual(
      links.filter((link) => !existsSync(join(ROOT, GUIDE_DIRECTORY, link))),
      [],
    );
  });
});
