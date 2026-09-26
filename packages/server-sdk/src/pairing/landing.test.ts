import assert from "node:assert/strict";
import { describe, it } from "node:test";

import type { IssuedPairing } from "../direct-server.ts";

import {
  decodeLandingFragment,
  decodePairingFragment,
  encodePairingFragment,
  landingUrlHasCredential,
  pairingLandingUrl,
  REPLACEMENT_WARNING,
} from "./landing.ts";
import { parsePairingUri } from "./uri.ts";

const TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDE_-";
const SERVER_ID = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a";
const SERVER_URL = "https://vault.example.com";
const EXPIRES_AT = "2026-09-21T12:00:00.000Z";

describe("pairing fragment codec", () => {
  it("round-trips a landing URL with base64url underscores and hyphens", () => {
    const issued = issuedPairing();
    const url = pairingLandingUrl(issued);
    const parsedUrl = new URL(url);
    assert.equal(parsedUrl.origin, SERVER_URL);
    assert.equal(parsedUrl.pathname, "/pair");
    assert.equal(parsedUrl.search, "");
    assert.equal(landingUrlHasCredential(url), false);
    assert.match(TOKEN, /[_-]/);
    const decoded = decodeLandingFragment(parsedUrl.hash.slice(1), SERVER_URL);
    assert.ok(decoded.ok);
    assert.equal(decoded.code.token, TOKEN);
    assert.equal(decoded.payload.pairing_uri, issued.uri);
    assert.deepEqual(parsePairingUri(decoded.payload.pairing_uri), {
      ok: true,
      code: {
        serverUrl: SERVER_URL,
        serverId: SERVER_ID,
        token: TOKEN,
      },
    });
  });

  it("round-trips a fragment whose base64url alphabet includes underscores and hyphens", () => {
    let warning = "pad";
    let fragment = "";
    for (let i = 0; i < 64; i += 1) {
      warning += ">";
      fragment = encodePairingFragment({
        v: 1,
        pairing_uri: issuedPairing().uri,
        expires_at: EXPIRES_AT,
        warning,
      });
      if (/[_-]/.test(fragment)) break;
    }
    assert.match(fragment, /[_-]/);
    const decoded = decodePairingFragment(fragment);
    assert.ok(decoded.ok);
    assert.equal(decoded.payload.warning, warning);
    assert.equal(decoded.code.token, TOKEN);
  });

  it("round-trips Unicode and spaces in display metadata", () => {
    const warning = "Reconnect café 连接 🔔 and keep the other phone";
    const fragment = encodePairingFragment({
      v: 1,
      pairing_uri: issuedPairing().uri,
      expires_at: EXPIRES_AT,
      warning,
      replaces: "prior phone",
    });
    const decoded = decodePairingFragment(fragment);
    assert.ok(decoded.ok);
    assert.equal(decoded.payload.warning, warning);
    assert.equal(decoded.payload.replaces, "prior phone");
  });

  it("includes the replacement warning when a phone would be replaced", () => {
    const issued = issuedPairing({
      replaces: {
        connectionId: "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        deviceName: "Seeker",
        pairedAtMs: 1,
      },
    });
    const url = pairingLandingUrl(issued);
    const decoded = decodeLandingFragment(
      new URL(url).hash.slice(1),
      SERVER_URL,
    );
    assert.ok(decoded.ok);
    assert.equal(decoded.payload.warning, REPLACEMENT_WARNING);
    assert.equal(decoded.payload.replaces, issued.replaces?.connectionId);
  });

  it("rejects malformed fragments", () => {
    assert.equal(decodePairingFragment("").ok, false);
    assert.equal(decodePairingFragment("@@@").ok, false);
    assert.equal(decodePairingFragment(btoa("{not json")).ok, false);
    const valid = encodePairingFragment({
      v: 1,
      pairing_uri: issuedPairing().uri,
      expires_at: EXPIRES_AT,
    });
    const bytes = Uint8Array.from(Buffer.from(valid, "base64url"));
    const json = JSON.parse(Buffer.from(bytes).toString("utf8")) as {
      pairing_uri: string;
    };
    json.pairing_uri = "https://evil.example/pair?token=no";
    const flipped = Buffer.from(JSON.stringify(json)).toString("base64url");
    assert.equal(decodePairingFragment(flipped).ok, false);
  });

  it("rejects a fragment for a foreign server origin", () => {
    const issued = issuedPairing({
      serverUrl: "https://other.example",
      uri: pairingUri("https://other.example"),
    });
    const url = pairingLandingUrl(issued);
    const decoded = decodeLandingFragment(
      new URL(url).hash.slice(1),
      SERVER_URL,
    );
    assert.deepEqual(decoded, { ok: false, reason: "foreign_origin" });
  });
});

function issuedPairing(overrides: Partial<IssuedPairing> = {}): IssuedPairing {
  const serverUrl = overrides.serverUrl ?? SERVER_URL;
  return {
    token: TOKEN,
    serverUrl,
    serverId: SERVER_ID,
    expiresAtMs: Date.parse(EXPIRES_AT),
    replaces: undefined,
    uri: pairingUri(serverUrl),
    ...overrides,
  };
}

function pairingUri(serverUrl: string): string {
  return `seekervault://pair?${new URLSearchParams({
    v: "1",
    url: serverUrl,
    server: SERVER_ID,
    token: TOKEN,
  }).toString()}`;
}
