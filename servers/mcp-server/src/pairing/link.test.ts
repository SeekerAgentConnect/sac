import assert from "node:assert/strict";
import { request as httpRequest } from "node:http";
import { after, before, describe, it } from "node:test";

import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  bootPairingPage,
  humanPairingLink,
  landingUrlHasCredential,
  parsePairingUri,
  REPLACEMENT_WARNING,
} from "@seekeragentconnect/server-sdk";
import { renderSVG } from "uqr";

import { startSidecar, type Sidecar } from "../server.ts";
import {
  callTool,
  connectAgent,
  pairPhone,
  pairingClient,
  requestClient,
} from "../testing/clients.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { PairingStore } from "../../../../packages/server-sdk/src/storage/pairing-store.ts";
import { openDatabase } from "../../../../packages/server-sdk/src/storage/database.ts";
import { CREATE_PAIRING_LINK_TOOL } from "./mcp-tool.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const PUBLIC_URL = "https://vault.example.com";
const logs: string[] = [];
let sidecar: Sidecar;
let agent: Client;
let databasePath: string;

describe("pairing links", () => {
  before(async () => {
    databasePath = temporaryDatabasePath();
    sidecar = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath,
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
        publicUrl: PUBLIC_URL,
        pairingTokenTtlSeconds: 600,
      },
      { log: (line) => logs.push(line) },
    );
    agent = await connectAgent(sidecar.url, MCP_TOKEN);
  });

  after(async () => {
    await agent.close();
    await sidecar.close();
  });

  it("issues a fragment HTTPS URL and exact custom-scheme URI over MCP", async () => {
    const result = await callTool(agent, CREATE_PAIRING_LINK_TOOL, {});
    assert.notEqual(result.isError, true, JSON.stringify(result.content));
    const view = result.structuredContent as {
      pairing_uri: string;
      https_url: string;
      server_url: string;
      expires_at: string;
    };
    const parsed = parsePairingUri(view.pairing_uri);
    assert.ok(parsed.ok);
    assert.equal(parsed.code.serverUrl, PUBLIC_URL);
    assert.equal(view.server_url, PUBLIC_URL);
    assert.equal(landingUrlHasCredential(view.https_url), false);
    assert.equal(new URL(view.https_url).pathname, "/pair");
    assert.equal(new URL(view.https_url).search, "");
    assert.match(view.https_url, /^https:\/\/vault\.example\.com\/pair#/);
    assert.match(view.expires_at, /^\d{4}-\d{2}-\d{2}T/);
    const text = result.content[0];
    assert.equal(text?.type, "text");
    if (text?.type === "text") {
      assert.equal(
        text.text.includes(`[Connect your phone](${view.https_url})`),
        true,
      );
      assert.equal(text.text.includes(view.pairing_uri), true);
      assert.equal(
        text.text.includes("```\n" + view.https_url + "\n```"),
        true,
      );
      assert.equal(
        text.text.includes("```\n" + view.pairing_uri + "\n```"),
        true,
      );
      assert.doesNotMatch(text.text, /\.\.\.|…/);
      assert.doesNotMatch(text.text, /seekervault:\\\/\\\//);
    }
    assert.match(agent.getInstructions() ?? "", /complete https_url/);
    assert.doesNotMatch(agent.getInstructions() ?? "", /\.\.\.|…/);
    const { tools } = await agent.listTools();
    const described = tools.find(
      (tool) => tool.name === CREATE_PAIRING_LINK_TOOL,
    );
    assert.match(described?.description ?? "", /complete https_url/);
    assert.doesNotMatch(described?.description ?? "", /\.\.\.|…/);
    assert.ok(logs.includes("pairing link issued"));
    assert.ok(logs.every((line) => !line.includes(parsed.code.token)));
  });

  it("renders a tool-generated link in the page while CSP stays enabled", async () => {
    const landing = await requestPath("/pair");
    assert.match(
      landing.headers["content-security-policy"] ?? "",
      /script-src 'self'/,
    );
    assert.match(
      landing.headers["content-security-policy"] ?? "",
      /connect-src 'none'/,
    );
    const served = await requestPath("/pair/page.js");
    assert.match(served.body, /damaged or incomplete/);
    assert.match(served.body, /decodePairingFragment/);
    const origin = JSON.parse(
      /id="pairing-server">([^<]+)/.exec(landing.body)?.[1] ?? "",
    ) as { origin: string };
    assert.equal(origin.origin, PUBLIC_URL);

    const result = await callTool(agent, CREATE_PAIRING_LINK_TOOL, {});
    const view = result.structuredContent as {
      pairing_uri: string;
      https_url: string;
    };
    const text = result.content[0];
    assert.equal(text?.type, "text");
    const href = /\[Connect your phone\]\(([^)]+)\)/.exec(
      text?.type === "text" ? text.text : "",
    )?.[1];
    assert.equal(href, view.https_url);
    const hash = new URL(view.https_url).hash;
    const fragment = hash.startsWith("#") ? hash.slice(1) : hash;
    assert.equal(new URL(href ?? "").hash, hash);

    const copied: string[] = [];
    const ready = toolPage(origin.origin, hash, {
      clipboard: {
        writeText(value: string) {
          copied.push(value);
          return Promise.resolve();
        },
      },
    });
    const booted = await bootPairingPage(ready.env);
    assert.equal(booted.state, "ready");
    assert.equal(booted.pairingUri, view.pairing_uri);
    assert.equal(ready.el("open-app").getAttribute("href"), view.pairing_uri);
    assert.equal(ready.el("pairing-code").value, view.pairing_uri);
    assert.equal(
      ready.el("qr").getAttribute("data-pairing-uri"),
      view.pairing_uri,
    );
    assert.equal(
      ready.el("qr").getAttribute("data-qr-svg"),
      renderSVG(view.pairing_uri, { ecc: "M", border: 2, pixelSize: 4 }),
    );
    ready.el("copy-code").click();
    await Promise.resolve();
    assert.deepEqual(copied, [view.pairing_uri]);
    assert.equal(
      logs.every((line) => !line.includes(tokenOf(view.pairing_uri))),
      true,
    );

    for (const damagedHash of [
      `#${fragment.slice(0, 6)}...${fragment.slice(-4)}`,
      "#eyJ2Ij...WiJ9",
      `#${fragment.slice(0, Math.floor(fragment.length / 2))}`,
    ]) {
      const damaged = toolPage(origin.origin, damagedHash);
      const rejected = await bootPairingPage(damaged.env);
      assert.equal(rejected.state, "invalid", damagedHash);
      assert.match(
        damaged.el("invalid-reason").textContent,
        /damaged or incomplete/,
      );
      assert.equal(damaged.el("open-app").getAttribute("href"), null);
      assert.equal(damaged.el("pairing-code").value, "");
    }
  });

  it("serves a pairing page, not a redirect, and does not launch the app", async () => {
    const landing = await requestPath("/pair");
    assert.equal(landing.status, 200);
    assert.equal(landing.location, undefined);
    assert.equal(landing.headers["content-type"], "text/html; charset=utf-8");
    assert.equal(landing.headers["cache-control"], "no-store");
    assert.equal(landing.headers["referrer-policy"], "no-referrer");
    assert.equal(landing.headers["x-frame-options"], "DENY");
    assert.match(
      landing.headers["content-security-policy"] ?? "",
      /connect-src 'none'/,
    );
    assert.match(
      landing.headers["content-security-policy"] ?? "",
      /script-src 'self'/,
    );
    assert.match(landing.body, /Connect your phone/);
    assert.doesNotMatch(landing.body, /http-equiv="refresh"/i);
    assert.doesNotMatch(landing.body, /window\.location\s*=/);
    assert.match(
      landing.body,
      /<script type="application\/json" id="pairing-server">/,
    );
    assert.match(landing.body, /"origin":"https:\/\/vault\.example\.com"/);
    const head = await requestPath("/pair", "HEAD");
    assert.equal(head.status, 200);
    assert.equal(head.body, "");
    assert.equal(head.location, undefined);
  });

  it("serves bundled page assets from allowlisted paths only", async () => {
    const page = await requestPath("/pair/page.js");
    assert.equal(page.status, 200);
    assert.match(page.headers["content-type"] ?? "", /javascript/);
    assert.doesNotMatch(page.body, /\bfetch\s*\(/);
    assert.doesNotMatch(
      page.body,
      /localStorage|sessionStorage|document\.cookie/,
    );
    assert.doesNotMatch(page.body, /window\.location\s*=/);
    const css = await requestPath("/pair/page.css");
    assert.equal(css.status, 200);
    const payload = await requestPath("/pair/payload.js");
    assert.equal(payload.status, 200);
    const qr = await requestPath("/pair/uqr.js");
    assert.equal(qr.status, 200);
    assert.match(qr.body, /export \{/);
    const missing = await requestPath("/pair/secret.js");
    assert.equal(missing.status, 404);
    const traversal = await requestPath("/pair/../package.json");
    assert.notEqual(traversal.status, 200);
  });

  it("warns on replacement without disconnecting the paired phone, and voids an unused code", async () => {
    const first = await callTool(agent, CREATE_PAIRING_LINK_TOOL, {});
    const firstView = first.structuredContent as { pairing_uri: string };
    const firstToken = tokenOf(firstView.pairing_uri);
    const phone = await pairPhone(sidecar.url, databasePath, "Seeker");
    const second = await callTool(agent, CREATE_PAIRING_LINK_TOOL, {});
    const view = second.structuredContent as {
      pairing_uri: string;
      https_url: string;
      server_url: string;
      expires_at: string;
      replaces?: string;
      warning?: string;
    };
    assert.equal(view.replaces, phone.connectionId);
    assert.equal(view.warning, REPLACEMENT_WARNING);
    const text = second.content[0];
    assert.equal(text?.type, "text");
    if (text?.type === "text") {
      assert.equal(text.text.includes(REPLACEMENT_WARNING), true);
      assert.equal(text.text, humanPairingLink(view));
    }
    await requestClient(sidecar.url, phone.phoneToken).listPending({
      connectionId: phone.connectionId,
    });
    await assert.rejects(
      pairingClient(sidecar.url, firstToken).pair({
        serverUrl: PUBLIC_URL,
        deviceName: "Stale",
      }),
    );
    const afterVisit = await requestPath("/pair");
    assert.equal(afterVisit.status, 200);
    const queryVisit = await requestPath(
      `/pair${new URL(view.pairing_uri).search}`,
    );
    assert.equal(queryVisit.status, 200);
    assert.equal(queryVisit.location, undefined);
    await requestClient(sidecar.url, phone.phoneToken).listPending({
      connectionId: phone.connectionId,
    });
    const db = openDatabase(databasePath);
    try {
      assert.equal(
        new PairingStore(db).activeConnection()?.connectionId,
        phone.connectionId,
      );
    } finally {
      db.close();
    }
  });
});

function tokenOf(uri: string): string {
  const parsed = parsePairingUri(uri);
  assert.ok(parsed.ok);
  return parsed.code.token;
}

function requestPath(
  path: string,
  method: "GET" | "HEAD" = "GET",
): Promise<{
  status: number;
  location: string | undefined;
  body: string;
  headers: Record<string, string | undefined>;
}> {
  const { hostname, port } = new URL(sidecar.url);
  return new Promise((resolve, reject) => {
    const req = httpRequest({ hostname, port, path, method }, (res) => {
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (chunk: string) => {
        body += chunk;
      });
      res.on("end", () =>
        resolve({
          status: res.statusCode ?? 0,
          location: res.headers.location,
          body,
          headers: {
            "content-type": header(res.headers["content-type"]),
            "cache-control": header(res.headers["cache-control"]),
            "referrer-policy": header(res.headers["referrer-policy"]),
            "x-frame-options": header(res.headers["x-frame-options"]),
            "content-security-policy": header(
              res.headers["content-security-policy"],
            ),
          },
        }),
      );
    });
    req.on("error", reject);
    req.end();
  });
}

function header(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value.join(",") : value;
}

function toolPage(
  origin: string,
  hash: string,
  extras: {
    clipboard?: { writeText(text: string): Promise<void> };
  } = {},
) {
  const nodes = new Map<string, TestNode>();
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
    "pairing-server",
  ]) {
    nodes.set(id, testNode(id));
  }
  const serverNode = nodes.get("pairing-server");
  assert.ok(serverNode);
  serverNode.textContent = JSON.stringify({ origin });
  return {
    el(id: string): TestNode {
      const node = nodes.get(id);
      assert.ok(node, id);
      return node;
    },
    env: {
      document: {
        getElementById(id: string) {
          return nodes.get(id) ?? null;
        },
      },
      location: { hash, search: "" },
      trustedOrigin: origin,
      navigator: extras.clipboard ? { clipboard: extras.clipboard } : {},
      loadQr: () => Promise.resolve({ renderSVG }),
    },
  };
}

interface TestNode {
  id: string;
  hidden: boolean;
  textContent: string;
  value: string;
  children: unknown[];
  readOnly: boolean;
  attributes: Record<string, string>;
  listeners: Record<string, Array<() => void>>;
  setAttribute(name: string, value: string): void;
  getAttribute(name: string): string | null;
  replaceChildren(): void;
  appendChild(child: unknown): unknown;
  addEventListener(type: string, fn: () => void): void;
  click(): void;
  focus(): void;
  select(): void;
}

function testNode(id: string): TestNode {
  const attributes: Record<string, string> = {};
  const listeners: Record<string, Array<() => void>> = {};
  return {
    id,
    hidden: true,
    textContent: "",
    value: "",
    children: [],
    readOnly: false,
    attributes,
    listeners,
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
    addEventListener(type, fn) {
      (listeners[type] ??= []).push(fn);
    },
    click() {
      for (const fn of listeners.click ?? []) fn();
    },
    focus() {},
    select() {},
  };
}
