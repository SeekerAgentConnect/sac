import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { encode, renderSVG } from "uqr";

import {
  CONDITIONAL_REPLACEMENT_WARNING,
  encodePairingFragment,
  pairingUriFromCode,
  REPLACEMENT_WARNING,
} from "./page/payload.js";
import { bootPairingPage } from "./page/page.js";

const TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDE_-";
const SERVER_ID = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a";
const ORIGIN = "https://vault.example.com";
const EXPIRES_AT = "2099-01-01T00:00:00.000Z";
const PAST = "2000-01-01T00:00:00.000Z";

describe("pairing page behaviour", () => {
  it("shows empty and invalid states without a launch target", async () => {
    const empty = page("");
    const emptyResult = await bootPairingPage(empty.env);
    assert.equal(emptyResult.state, "empty");
    assert.equal(empty.el("state-empty").hidden, false);
    assert.equal(empty.el("open-app").getAttribute("href"), null);

    const invalid = page("", "#%%%%");
    const invalidResult = await bootPairingPage(invalid.env);
    assert.equal(invalidResult.state, "invalid");
    assert.match(invalid.el("invalid-reason").textContent, /damaged/);
    assert.equal(invalid.el("open-app").getAttribute("href"), null);
  });

  it("refuses a foreign origin and HTML from the fragment", async () => {
    const uri = pairingUri(ORIGIN);
    const fragment = encodePairingFragment({
      v: 1,
      pairing_uri: uri,
      expires_at: EXPIRES_AT,
      warning: `<img src=x onerror="alert(1)">`,
    });
    const foreign = page(ORIGIN.replace("vault", "evil"), `#${fragment}`);
    const refused = await bootPairingPage(foreign.env);
    assert.equal(refused.state, "invalid");
    assert.match(foreign.el("invalid-reason").textContent, /different server/);
    assert.equal(foreign.el("open-app").getAttribute("href"), null);

    const local = page(ORIGIN, `#${fragment}`);
    await bootPairingPage(local.env);
    assert.equal(
      local.el("replacement-warning").textContent,
      CONDITIONAL_REPLACEMENT_WARNING,
    );
    assert.equal(local.el("replacement-warning").children.length, 0);
    assert.equal(
      local.el("replacement-warning").textContent.includes("<img"),
      false,
    );
  });

  it("ignores a tampered fragment warning and keeps the application-owned warning", async () => {
    const uri = pairingUri(ORIGIN);
    const tampered = page(
      ORIGIN,
      `#${encodePairingFragment({
        v: 1,
        pairing_uri: uri,
        expires_at: EXPIRES_AT,
        warning: "No existing phone will be disconnected.",
      })}`,
    );
    const tamperedResult = await bootPairingPage(tampered.env);
    assert.equal(tamperedResult.state, "ready");
    assert.equal(tamperedResult.warning, CONDITIONAL_REPLACEMENT_WARNING);
    assert.equal(
      tampered.el("replacement-warning").textContent,
      CONDITIONAL_REPLACEMENT_WARNING,
    );
    assert.equal(tampered.el("open-app").getAttribute("href"), uri);

    const whitespace = page(
      ORIGIN,
      `#${encodePairingFragment({
        v: 1,
        pairing_uri: uri,
        expires_at: EXPIRES_AT,
        warning: "   ",
      })}`,
    );
    const whitespaceResult = await bootPairingPage(whitespace.env);
    assert.equal(whitespaceResult.state, "ready");
    assert.equal(
      whitespace.el("replacement-warning").textContent,
      CONDITIONAL_REPLACEMENT_WARNING,
    );
    assert.equal(whitespace.el("open-app").getAttribute("href"), uri);
  });

  it("sets the same validated URI on the button, QR, and copy control", async () => {
    const uri = pairingUri(ORIGIN);
    const fragment = encodePairingFragment({
      v: 1,
      pairing_uri: uri,
      expires_at: EXPIRES_AT,
      warning: REPLACEMENT_WARNING,
      replaces: "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
    });
    const copied: string[] = [];
    const view = page(ORIGIN, `#${fragment}`, {
      clipboard: {
        writeText: (text: string) => {
          copied.push(text);
          return Promise.resolve();
        },
      },
    });
    const result = await bootPairingPage(view.env);
    assert.equal(result.state, "ready");
    assert.equal(result.pairingUri, uri);
    assert.equal(view.el("open-app").getAttribute("href"), uri);
    assert.equal(view.el("pairing-code").value, uri);
    assert.equal(view.el("qr").getAttribute("data-pairing-uri"), uri);
    assert.equal(
      view.el("replacement-warning").textContent,
      REPLACEMENT_WARNING,
    );
    const svg = view.el("qr").getAttribute("data-qr-svg");
    assert.ok(svg);
    assert.equal(svg, renderSVG(uri, { ecc: "M", border: 2, pixelSize: 4 }));
    assert.deepEqual(modulesOf(svg), encode(uri, { ecc: "M", border: 2 }).data);
    view.el("copy-code").click();
    await Promise.resolve();
    assert.deepEqual(copied, [uri]);
  });

  it("shows a conditional warning when replacement state is absent, and a legacy-query notice", async () => {
    const uri = pairingUri(ORIGIN);
    const fragment = encodePairingFragment({
      v: 1,
      pairing_uri: uri,
      expires_at: PAST,
    });
    const expired = page(ORIGIN, `#${fragment}`);
    const expiredResult = await bootPairingPage(expired.env);
    assert.equal(expiredResult.state, "expired");
    assert.equal(
      expired.el("replacement-warning").textContent,
      CONDITIONAL_REPLACEMENT_WARNING,
    );
    assert.equal(expired.el("expired-note").hidden, false);
    assert.equal(expired.el("open-app").getAttribute("href"), uri);

    const query = `?${new URL(uri).searchParams.toString()}`;
    const legacy = page(ORIGIN, "", { search: query });
    await bootPairingPage(legacy.env);
    assert.equal(legacy.el("legacy-query-note").hidden, false);
    assert.match(legacy.el("legacy-query-note").textContent, /request log/);
    assert.equal(
      legacy.el("open-app").getAttribute("href"),
      pairingUriFromCode({
        serverUrl: ORIGIN,
        serverId: SERVER_ID,
        token: TOKEN,
      }),
    );
  });

  it("keeps launch and copy controls when QR loading or rendering fails", async () => {
    const uri = pairingUri(ORIGIN);
    const fragment = encodePairingFragment({
      v: 1,
      pairing_uri: uri,
      expires_at: EXPIRES_AT,
    });
    const copied: string[] = [];
    const rejected = page(ORIGIN, `#${fragment}`, {
      clipboard: {
        writeText: (text: string) => {
          copied.push(text);
          return Promise.resolve();
        },
      },
      loadQr: () => {
        assert.equal(rejected.el("state-ready").hidden, false);
        assert.equal(rejected.el("open-app").getAttribute("href"), uri);
        return Promise.reject(new Error("interrupted"));
      },
    });
    const rejectedResult = await bootPairingPage(rejected.env);
    assert.equal(rejectedResult.state, "ready");
    assert.equal(rejected.el("state-empty").hidden, true);
    assert.equal(rejected.el("state-invalid").hidden, true);
    assert.equal(rejected.el("state-ready").hidden, false);
    assert.equal(rejected.el("open-app").getAttribute("href"), uri);
    assert.equal(rejected.el("pairing-code").value, uri);
    assert.equal(rejected.el("qr").hidden, true);
    assert.equal(rejected.el("qr-fallback").hidden, false);
    assert.match(
      rejected.el("qr-fallback").textContent,
      /could not be generated/,
    );
    rejected.el("copy-code").click();
    await Promise.resolve();
    assert.deepEqual(copied, [uri]);

    const throwing = page(ORIGIN, `#${fragment}`, {
      loadQr: () =>
        Promise.resolve({
          renderSVG() {
            throw new Error("renderer failed");
          },
        }),
    });
    const throwingResult = await bootPairingPage(throwing.env);
    assert.equal(throwingResult.state, "ready");
    assert.equal(throwing.el("state-ready").hidden, false);
    assert.equal(throwing.el("open-app").getAttribute("href"), uri);
    assert.equal(throwing.el("pairing-code").value, uri);
    assert.equal(throwing.el("qr").hidden, true);
    assert.equal(throwing.el("qr-fallback").hidden, false);
    assert.match(
      throwing.el("qr-fallback").textContent,
      /could not be generated/,
    );
  });
});

function pairingUri(serverUrl: string): string {
  return `seekervault://pair?${new URLSearchParams({
    v: "1",
    url: serverUrl,
    server: SERVER_ID,
    token: TOKEN,
  }).toString()}`;
}

function modulesOf(svg: string): boolean[][] {
  const viewBox = /viewBox="0 0 (\d+) (\d+)"/.exec(svg);
  assert.ok(viewBox);
  const width = Number(viewBox[1]);
  const pixel = 4;
  const size = width / pixel;
  const data = Array.from({ length: size }, () =>
    Array.from({ length: size }, () => false),
  );
  const d = /<path fill="black" d="([^"]+)"/.exec(svg)?.[1] ?? "";
  for (const match of d.matchAll(/M(\d+),(\d+)h4v4h-4z/g)) {
    const x = Number(match[1]) / pixel;
    const y = Number(match[2]) / pixel;
    const row = data[y];
    assert.ok(row);
    row[x] = true;
  }
  return data;
}

function page(
  origin: string,
  hash = "",
  extras: {
    search?: string;
    clipboard?: { writeText(text: string): Promise<void> };
    loadQr?: () => Promise<{
      renderSVG: (data: string, options?: object) => string;
    }>;
  } = {},
) {
  const nodes = new Map<string, TestNode>();
  const ids = [
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
  ];
  for (const id of ids) nodes.set(id, testNode(id));
  const serverNode = nodes.get("pairing-server");
  assert.ok(serverNode);
  serverNode.textContent = JSON.stringify({ origin });
  const document = {
    getElementById(id: string) {
      return nodes.get(id) ?? null;
    },
  };
  return {
    el(id: string): TestNode {
      const node = nodes.get(id);
      assert.ok(node, id);
      return node;
    },
    env: {
      document,
      location: { hash, search: extras.search ?? "" },
      trustedOrigin: origin,
      navigator: extras.clipboard ? { clipboard: extras.clipboard } : {},
      loadQr: extras.loadQr ?? (() => Promise.resolve({ renderSVG })),
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
