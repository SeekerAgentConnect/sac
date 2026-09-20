import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  invalidServerUrlReason,
  isSecret,
  normalizeServerUrl,
  pairingHttpsUrl,
  pairingUri,
  parsePairingUri,
} from "./uri.ts";

const SERVER_ID = "1f0e2d3c-4b5a-4698-8776-5a4b3c2d1e0f";
const TOKEN = "Lq3v7Yk2Qm9XwTzR4bN8cJ1dH6fG0sA5eP-uV_iKoLw";

describe("server URLs", () => {
  it("accepts HTTPS, and plain HTTP only on a loopback host", () => {
    for (const url of [
      "https://vault.example.com",
      "https://vault.tailnet.ts.net:8443/seeker",
      "http://127.0.0.1:8080",
      "http://localhost:8080",
      "http://[::1]:8080",
    ]) {
      assert.equal(invalidServerUrlReason(url), undefined, url);
    }
  });

  it("refuses anything else, and says why", () => {
    const cases: ReadonlyArray<readonly [string, string]> = [
      [
        "http://192.168.1.20:8080",
        "the server URL must use HTTPS; plain HTTP is allowed only on loopback, for development",
      ],
      [
        "http://vault.example.com",
        "the server URL must use HTTPS; plain HTTP is allowed only on loopback, for development",
      ],
      [
        "ws://127.0.0.1:8080",
        "the server URL must use HTTPS; plain HTTP is allowed only on loopback, for development",
      ],
      [
        "https://owner:secret@vault.example.com",
        "the server URL must not contain a user name or password",
      ],
      [
        "https://vault.example.com/?token=1",
        "the server URL must not have a query or a fragment",
      ],
      [
        "https://vault.example.com/#pair",
        "the server URL must not have a query or a fragment",
      ],
      ["vault.example.com", "the server URL isn't a URL"],
      [
        "https://vault.example.com:0",
        "the server URL's port must be 1 to 65535",
      ],
      ["http://127.0.0.1:0", "the server URL's port must be 1 to 65535"],
    ];
    for (const [url, reason] of cases) {
      assert.equal(invalidServerUrlReason(url), reason, url);
    }
  });

  it("normalizes the host's case, the default port, and a trailing slash", () => {
    assert.equal(
      normalizeServerUrl("https://Vault.Example.com:443/seeker/"),
      "https://vault.example.com/seeker",
    );
    assert.equal(
      normalizeServerUrl("http://127.0.0.1:8080/"),
      "http://127.0.0.1:8080",
    );
  });
});

describe("pairing URIs", () => {
  it("carry the URL, the server ID, and the token, and read back the same", () => {
    const code = {
      serverUrl: "https://vault.example.com/seeker",
      serverId: SERVER_ID,
      token: TOKEN,
    };
    const uri = pairingUri(code);
    assert.equal(
      uri,
      `seekervault://pair?v=1&url=https%3A%2F%2Fvault.example.com%2Fseeker&server=${SERVER_ID}&token=${TOKEN}`,
    );
    assert.deepEqual(parsePairingUri(uri), { ok: true, code });
    assert.ok(isSecret(TOKEN));
  });

  it("refuses codes that aren't Seeker Agent Connect's, are for another version, or are incomplete", () => {
    const good = new URL(
      pairingUri({
        serverUrl: "https://vault.example.com",
        serverId: SERVER_ID,
        token: TOKEN,
      }),
    );
    const without = (name: string): string => {
      const copy = new URL(good);
      copy.searchParams.delete(name);
      return copy.toString();
    };
    const withValue = (name: string, value: string): string => {
      const copy = new URL(good);
      copy.searchParams.set(name, value);
      return copy.toString();
    };
    const cases: ReadonlyArray<readonly [string, string]> = [
      [
        "https://vault.example.com",
        "this isn't a Seeker Agent Connect pairing code",
      ],
      [
        "seekervault://connect?v=1",
        "this isn't a Seeker Agent Connect pairing code",
      ],
      ["not a code", "this isn't a pairing code"],
      [
        withValue("v", "2"),
        "this pairing code is for another version of Seeker Agent Connect",
      ],
      [without("url"), "the server URL isn't a URL"],
      [without("server"), "server is missing"],
      [withValue("server", "vault"), "server is not a lowercase UUID"],
      [without("token"), "the pairing token is malformed"],
      [withValue("token", `${TOKEN}=`), "the pairing token is malformed"],
    ];
    for (const [text, reason] of cases) {
      assert.deepEqual(parsePairingUri(text), { ok: false, reason }, text);
    }
  });

  it("refuse a code that points at plain HTTP off loopback", () => {
    const uri = pairingUri({
      serverUrl: "http://192.168.1.20:8080",
      serverId: SERVER_ID,
      token: TOKEN,
    });
    assert.deepEqual(parsePairingUri(uri), {
      ok: false,
      reason:
        "the server URL must use HTTPS; plain HTTP is allowed only on loopback, for development",
    });
  });

  it("reads the HTTPS landing page as the same code as the deep link", () => {
    const code = {
      serverUrl: "https://vault.example.com",
      serverId: SERVER_ID,
      token: TOKEN,
    };
    const httpsUrl = pairingHttpsUrl(code);
    assert.equal(
      httpsUrl,
      `https://vault.example.com/pair?v=1&url=https%3A%2F%2Fvault.example.com&server=${SERVER_ID}&token=${TOKEN}`,
    );
    assert.deepEqual(parsePairingUri(httpsUrl), { ok: true, code });
    const withSlash = new URL(httpsUrl);
    withSlash.pathname = "/pair/";
    assert.deepEqual(parsePairingUri(withSlash.toString()), { ok: true, code });
    assert.deepEqual(
      parsePairingUri(
        pairingHttpsUrl({
          ...code,
          serverUrl: "http://127.0.0.1:8080",
        }),
      ),
      {
        ok: true,
        code: { ...code, serverUrl: "http://127.0.0.1:8080" },
      },
    );
  });
});
