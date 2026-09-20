import assert from "node:assert/strict";
import { request as httpRequest } from "node:http";
import { after, before, describe, it } from "node:test";

import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { pairingHttpsUrl, parsePairingUri } from "@seeker-vault/server-sdk";

import { startSidecar, type Sidecar } from "../server.ts";
import { callTool, connectAgent } from "../testing/clients.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { CREATE_PAIRING_LINK_TOOL } from "./mcp-tool.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const PUBLIC_URL = "https://vault.example.com";
const logs: string[] = [];
let sidecar: Sidecar;
let agent: Client;

describe("pairing links", () => {
  before(async () => {
    sidecar = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath: temporaryDatabasePath(),
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

  it("issues a deep link and HTTPS landing page over MCP", async () => {
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
    assert.equal(view.https_url, pairingHttpsUrl(parsed.code));
    assert.deepEqual(parsePairingUri(view.https_url), parsed);
    assert.match(view.expires_at, /^\d{4}-\d{2}-\d{2}T/);
    assert.ok(logs.includes("pairing link issued"));
    assert.ok(logs.every((line) => !line.includes(parsed.code.token)));
  });

  it("opens the HTTPS landing page onto the same deep link", async () => {
    const result = await callTool(agent, CREATE_PAIRING_LINK_TOOL, {});
    const view = result.structuredContent as {
      pairing_uri: string;
      https_url: string;
    };
    const path =
      new URL(view.https_url).pathname + new URL(view.https_url).search;
    const landing = await getPath(path);
    assert.equal(landing.status, 302);
    assert.equal(landing.location, view.pairing_uri);
    assert.match(landing.body, /Open Seeker Agent Connect/);
    assert.ok(
      !landing.body.includes("&token=") || landing.body.includes("&amp;"),
    );
  });

  it("hides an invalid pairing query", async () => {
    const missing = await getPath("/pair");
    assert.equal(missing.status, 404);
    const junk = await getPath("/pair?v=1&token=no");
    assert.equal(junk.status, 404);
  });
});

function getPath(path: string): Promise<{
  status: number;
  location: string | undefined;
  body: string;
}> {
  const { hostname, port } = new URL(sidecar.url);
  return new Promise((resolve, reject) => {
    const req = httpRequest({ hostname, port, path, method: "GET" }, (res) => {
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
        }),
      );
    });
    req.on("error", reject);
    req.end();
  });
}
