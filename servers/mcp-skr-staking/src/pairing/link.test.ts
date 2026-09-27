/**
 * The pairing flow this server offers an owner who has no shell on the host (SEE-149): an agent
 * asks for a link over MCP, and the owner opens an HTTPS page served by this server.
 *
 * What is worth testing here is not the page — that is the SDK's, and tested there — but that this
 * server wires it to its own pairing, its own origin and its own identity: a link issued here pairs
 * a phone with the staking server, the page says so, and asking for one neither pairs nor revokes
 * anything by itself.
 */
import assert from "node:assert/strict";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, describe, it } from "node:test";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import {
  bootPairingPage,
  humanPairingLink,
  landingUrlHasCredential,
  parsePairingUri,
  type PairingLinkView,
} from "@seeker_agent_connect/server-sdk";
import { renderSVG } from "uqr";

import { MAINNET_GENESIS_HASH } from "../skr/chain.ts";
import { startStakingServer, type StartedServer } from "../server.ts";
import {
  configFor,
  removeTemporaryDirectories,
  stubCluster,
  type StubCluster,
} from "../testing/cluster.ts";
import { CREATE_PAIRING_LINK_TOOL } from "./mcp-tool.ts";

const MCP_TOKEN = "t".repeat(64);

const logs: string[] = [];
let cluster: StubCluster;
let server: StartedServer;
let agent: Client;
/** The configured public origin, which is also where these tests reach the listener. */
let publicUrl: string;

before(async () => {
  cluster = await stubCluster(MAINNET_GENESIS_HASH);
  // A port chosen before the server starts, so the configured public origin is a real one. It
  // names `localhost` while the listener binds 127.0.0.1: a code that carried the bind address
  // instead of the configured origin would be a different string, and these tests would see it.
  const port = await freePort();
  publicUrl = `http://localhost:${port}`;
  server = await startStakingServer(
    configFor(cluster.url, { port, publicUrl }),
    { log: (line) => logs.push(line) },
  );
  agent = new Client({ name: "skr-staking-tests", version: "0.0.0" });
  await agent.connect(
    new StreamableHTTPClientTransport(new URL("/mcp", publicUrl), {
      requestInit: { headers: { Authorization: `Bearer ${MCP_TOKEN}` } },
    }),
  );
});

after(async () => {
  await agent.close();
  await server.close();
  await cluster.close();
  removeTemporaryDirectories();
});

/** A port nothing is listening on, so the public origin can be configured before the server runs. */
async function freePort(): Promise<number> {
  const probe = createServer();
  await new Promise<void>((resolve) => probe.listen(0, "127.0.0.1", resolve));
  const { port } = probe.address() as AddressInfo;
  await new Promise<void>((resolve) => probe.close(() => resolve()));
  return port;
}

async function callPairingTool(): Promise<CallToolResult> {
  return (await agent.callTool(
    { name: CREATE_PAIRING_LINK_TOOL, arguments: {} },
    undefined,
    { timeout: 10_000 },
  )) as CallToolResult;
}

function viewOf(result: CallToolResult): PairingLinkView {
  assert.notEqual(result.isError, true, JSON.stringify(result.content));
  return result.structuredContent as unknown as PairingLinkView;
}

async function get(
  path: string,
  method = "GET",
): Promise<{ status: number; headers: Headers; body: string }> {
  const response = await fetch(`${publicUrl}${path}`, { method });
  return {
    status: response.status,
    headers: response.headers,
    body: await response.text(),
  };
}

describe("the staking server's pairing link", () => {
  it("issues a complete landing link and deep link over MCP, and pairs nothing by itself", async () => {
    const pairedBefore = server.direct.pairing.active();
    const result = await callPairingTool();
    const view = viewOf(result);

    const parsed = parsePairingUri(view.pairing_uri);
    assert.ok(parsed.ok);
    // The configured public origin, not the address the listener was bound to.
    assert.equal(parsed.code.serverUrl, publicUrl);
    assert.equal(view.server_url, publicUrl);
    assert.equal(new URL(view.https_url).origin, publicUrl);
    assert.equal(new URL(view.https_url).pathname, "/pair");
    assert.equal(new URL(view.https_url).search, "");
    assert.ok(new URL(view.https_url).hash.length > 1);
    assert.equal(landingUrlHasCredential(view.https_url), false);
    assert.match(view.expires_at, /^\d{4}-\d{2}-\d{2}T/);

    const text = result.content[0];
    assert.equal(text?.type, "text");
    if (text?.type === "text") {
      assert.equal(text.text, humanPairingLink(view));
      assert.ok(text.text.includes(`[Connect your phone](${view.https_url})`));
      assert.ok(text.text.includes("```\n" + view.https_url + "\n```"));
      assert.ok(text.text.includes("```\n" + view.pairing_uri + "\n```"));
      assert.doesNotMatch(text.text, /\.\.\.|…/);
    }

    // Asking for a link is not pairing: the phone still confirms, and nothing was revoked.
    assert.deepEqual(server.direct.pairing.active(), pairedBefore);
    assert.ok(logs.includes("pairing link issued"));
    assert.ok(logs.every((line) => !line.includes(parsed.code.token)));
  });

  it("tells the agent to show the whole link, and says which server it pairs", async () => {
    const instructions = agent.getInstructions() ?? "";
    assert.match(instructions, /complete https_url/);
    assert.doesNotMatch(instructions, /\.\.\.|…/);
    const { tools } = await agent.listTools();
    const described = tools.find(
      (tool) => tool.name === CREATE_PAIRING_LINK_TOOL,
    );
    assert.ok(described, "the pairing tool is registered");
    assert.match(described.description ?? "", /complete https_url/);
    assert.match(described.description ?? "", /SKR staking server/);
    assert.doesNotMatch(described.description ?? "", /\.\.\.|…/);
    // The general MCP server's tool is a different tool, on a different server.
    assert.equal(
      tools.some((tool) => tool.name === "vault_create_pairing_link"),
      false,
    );
  });

  it("serves the page beside /healthz, /mcp and the phone API, under its CSP", async () => {
    const landing = await get("/pair");
    assert.equal(landing.status, 200);
    assert.equal(
      landing.headers.get("content-type"),
      "text/html; charset=utf-8",
    );
    assert.equal(landing.headers.get("cache-control"), "no-store");
    assert.equal(landing.headers.get("x-frame-options"), "DENY");
    const csp = landing.headers.get("content-security-policy") ?? "";
    assert.match(csp, /script-src 'self'/);
    assert.match(csp, /connect-src 'none'/);
    assert.match(csp, /default-src 'none'/);
    assert.doesNotMatch(landing.body, /http-equiv="refresh"/i);
    assert.doesNotMatch(landing.body, /window\.location\s*=/);
    assert.match(landing.body, /served by the SKR staking server/);
    assert.match(landing.body, /separate connection/);
    const embedded = JSON.parse(
      /id="pairing-server">([^<]+)/.exec(landing.body)?.[1] ?? "{}",
    ) as { origin?: string };
    assert.equal(embedded.origin, publicUrl);

    for (const path of [
      "/pair/page.js",
      "/pair/page.css",
      "/pair/payload.js",
      "/pair/uqr.js",
    ]) {
      const asset = await get(path);
      assert.equal(asset.status, 200, path);
      assert.ok(asset.body.length > 0, path);
    }
    assert.equal((await get("/pair/secret.js")).status, 404);

    // The rest of the listener is untouched.
    assert.equal((await get("/healthz")).status, 200);
    const mcp = await get("/mcp");
    assert.equal(mcp.status, 401);
  });

  it("renders a tool-generated link, and refuses a shortened or foreign one", async () => {
    const view = viewOf(await callPairingTool());
    const hash = new URL(view.https_url).hash;
    const fragment = hash.slice(1);

    const copied: string[] = [];
    const ready = fakePage(publicUrl, hash, {
      writeText: (value) => {
        copied.push(value);
        return Promise.resolve();
      },
    });
    const booted = await bootPairingPage(ready.env);
    assert.equal(booted.state, "ready");
    assert.equal(booted.pairingUri, view.pairing_uri);
    assert.equal(ready.el("open-app").getAttribute("href"), view.pairing_uri);
    assert.equal(ready.el("pairing-code").value, view.pairing_uri);
    assert.equal(
      ready.el("qr").getAttribute("data-qr-svg"),
      renderSVG(view.pairing_uri, { ecc: "M", border: 2, pixelSize: 4 }),
    );
    ready.el("copy-code").click();
    await Promise.resolve();
    assert.deepEqual(copied, [view.pairing_uri]);

    for (const damaged of [
      `#${fragment.slice(0, 6)}...${fragment.slice(-4)}`,
      `#${fragment.slice(0, 6)}…${fragment.slice(-4)}`,
      `#${fragment.slice(0, Math.floor(fragment.length / 2))}`,
    ]) {
      const page = fakePage(publicUrl, damaged);
      const rejected = await bootPairingPage(page.env);
      assert.equal(rejected.state, "invalid", damaged);
      assert.equal(page.el("open-app").getAttribute("href"), null);
      assert.equal(page.el("pairing-code").value, "");
    }

    // A code for another server — the owner's general MCP server, say — is not opened here.
    const foreign = fakePage("https://vault.example.com", hash);
    const refused = await bootPairingPage(foreign.env);
    assert.equal(refused.state, "invalid");
    assert.match(foreign.el("invalid-reason").textContent, /different server/);
    assert.equal(foreign.el("open-app").getAttribute("href"), null);
  });
});

interface FakeNode {
  hidden: boolean;
  textContent: string;
  value: string;
  children: unknown[];
  readOnly: boolean;
  setAttribute(name: string, value: string): void;
  getAttribute(name: string): string | null;
  replaceChildren(): void;
  appendChild(child: unknown): unknown;
  addEventListener(type: string, fn: () => void): void;
  click(): void;
  focus(): void;
  select(): void;
}

function fakeNode(): FakeNode {
  const attributes: Record<string, string> = {};
  const listeners: Array<() => void> = [];
  return {
    hidden: true,
    textContent: "",
    value: "",
    children: [],
    readOnly: false,
    setAttribute(name, value) {
      attributes[name] = value;
    },
    getAttribute(name) {
      return attributes[name] ?? null;
    },
    replaceChildren() {
      this.children = [];
    },
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    addEventListener(_type, fn) {
      listeners.push(fn);
    },
    click() {
      for (const fn of listeners) fn();
    },
    focus() {},
    select() {},
  };
}

/** The page's elements, as the served HTML declares them, without a browser. */
function fakePage(
  origin: string,
  hash: string,
  clipboard?: { writeText(text: string): Promise<void> },
) {
  const nodes = new Map<string, FakeNode>();
  for (const id of [
    "state-empty",
    "state-invalid",
    "state-ready",
    "invalid-reason",
    "server-origin",
    "expiry",
    "replacement-warning",
    "legacy-query-note",
    "expired-note",
    "open-app",
    "copy-code",
    "copy-status",
    "qr",
    "qr-fallback",
    "pairing-code",
  ]) {
    nodes.set(id, fakeNode());
  }
  return {
    el(id: string): FakeNode {
      const node = nodes.get(id);
      assert.ok(node, id);
      return node;
    },
    env: {
      document: {
        getElementById: (id: string) => nodes.get(id) ?? null,
      },
      location: { hash, search: "" },
      trustedOrigin: origin,
      navigator: clipboard === undefined ? {} : { clipboard },
      loadQr: () => Promise.resolve({ renderSVG }),
    },
  };
}
