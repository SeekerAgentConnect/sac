/**
 * The /pair route itself, apart from any host: what it serves, what it refuses, and what it says
 * about the server serving it. A host's own tests cover the page reaching an agent and a phone;
 * this file covers the handler both hosts share (SEE-149).
 */
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, describe, it } from "node:test";

import {
  handlePairingLink,
  isPairingLinkPath,
  SEEKER_MCP_PAIRING_PAGE,
  type PairingPageIdentity,
} from "./link.ts";

const ORIGIN = "https://staking.example.com";
const IDENTITY: PairingPageIdentity = {
  title: "Pair the <SKR> staking server",
  heading: "Connect your phone",
  server:
    'This page is served by the "SKR staking server", a second connection & not the general one.',
};

let server: Server;
let base: string;

before(async () => {
  server = createServer((request, response) => {
    handlePairingLink(request, response, {
      publicOrigin: ORIGIN,
      identity: IDENTITY,
      qrModulePath: () =>
        new URL("./page/payload.js", import.meta.url).pathname,
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

after(() => {
  server.close();
});

async function get(
  path: string,
  method = "GET",
): Promise<{ status: number; headers: Headers; body: string }> {
  const response = await fetch(`${base}${path}`, { method });
  return {
    status: response.status,
    headers: response.headers,
    body: await response.text(),
  };
}

describe("the pairing landing route", () => {
  it("answers only its own paths", () => {
    for (const path of [
      "/pair",
      "/pair/",
      "/pair/page.js",
      "/pair/page.css",
      "/pair/payload.js",
      "/pair/uqr.js",
    ]) {
      assert.equal(isPairingLinkPath(path), true, path);
    }
    for (const path of [
      "/",
      "/mcp",
      "/healthz",
      "/pair/../secrets",
      "/pairs",
    ]) {
      assert.equal(isPairingLinkPath(path), false, path);
    }
  });

  it("serves the page under a strict CSP and never caches it", async () => {
    const page = await get("/pair");
    assert.equal(page.status, 200);
    assert.equal(page.headers.get("cache-control"), "no-store");
    assert.equal(page.headers.get("referrer-policy"), "no-referrer");
    assert.equal(page.headers.get("x-frame-options"), "DENY");
    assert.equal(page.headers.get("x-content-type-options"), "nosniff");
    const csp = page.headers.get("content-security-policy") ?? "";
    for (const directive of [
      "default-src 'none'",
      "script-src 'self'",
      "style-src 'self'",
      "connect-src 'none'",
      "frame-ancestors 'none'",
      "base-uri 'none'",
    ]) {
      assert.ok(csp.includes(directive), `${directive} missing from ${csp}`);
    }
    // Nothing inline: an inline script or style would be refused by that policy.
    assert.doesNotMatch(
      page.body,
      /<script(?![^>]*(?:src=|type="application\/json"))/,
    );
    assert.doesNotMatch(page.body, /<style/);
    assert.match(page.body, /"origin":"https:\/\/staking\.example\.com"/);
  });

  it("says which server the owner is about to pair, with its text escaped", async () => {
    const page = await get("/pair");
    assert.match(
      page.body,
      /<title>Pair the &lt;SKR&gt; staking server<\/title>/,
    );
    assert.match(
      page.body,
      /This page is served by the &quot;SKR staking server&quot;, a second connection &amp; not the general one\. It does not pair by itself\./,
    );
    assert.doesNotMatch(page.body, /"SKR staking server"/);
    // The default identity is the general MCP server's, and this page is not it.
    assert.doesNotMatch(page.body, /Seeker Agent Connect MCP server/);
    assert.equal(
      SEEKER_MCP_PAIRING_PAGE.server,
      "This page is served by the Seeker Agent Connect MCP server.",
    );
  });

  it("serves the page's own script, codec, stylesheet, and the host's QR module", async () => {
    for (const [path, type] of [
      ["/pair/page.js", "text/javascript; charset=utf-8"],
      ["/pair/payload.js", "text/javascript; charset=utf-8"],
      ["/pair/page.css", "text/css; charset=utf-8"],
      ["/pair/uqr.js", "text/javascript; charset=utf-8"],
    ] as const) {
      const asset = await get(path);
      assert.equal(asset.status, 200, path);
      assert.equal(asset.headers.get("content-type"), type);
      assert.equal(asset.headers.get("content-security-policy") !== null, true);
      assert.ok(asset.body.length > 0, path);
    }
  });

  it("serves no QR module when the host supplies none, and the page still works", async () => {
    const bare = createServer((request, response) => {
      handlePairingLink(request, response, { publicOrigin: ORIGIN });
    });
    await new Promise<void>((resolve) => bare.listen(0, "127.0.0.1", resolve));
    const url = `http://127.0.0.1:${(bare.address() as AddressInfo).port}`;
    try {
      const qr = await fetch(`${url}/pair/uqr.js`);
      assert.equal(qr.status, 404);
      const refusal = (await qr.json()) as { error: string };
      assert.equal(refusal.error, "not_found");
      const page = await fetch(`${url}/pair`);
      assert.equal(page.status, 200);
      assert.match(await page.text(), /Seeker Agent Connect MCP server/);
    } finally {
      bare.close();
    }
  });

  it("answers HEAD without a body and refuses anything that could pair or revoke", async () => {
    const head = await get("/pair", "HEAD");
    assert.equal(head.status, 200);
    assert.equal(head.body, "");
    for (const method of ["POST", "PUT", "DELETE", "PATCH"]) {
      const refused = await get("/pair", method);
      assert.equal(refused.status, 405, method);
      assert.equal(refused.headers.get("allow"), "GET, HEAD");
    }
  });
});
