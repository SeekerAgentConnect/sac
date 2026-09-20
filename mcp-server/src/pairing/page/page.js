/**
 * Pairing page behaviour. Runs in the browser against the fragment (the server never sees
 * it) and as a module from MCP tests. No fetch, storage, analytics, or automatic navigation.
 */
import {
  CONDITIONAL_REPLACEMENT_WARNING,
  decodePairingFragment,
  parseLegacyPairingQuery,
  pairingUriFromCode,
  sameOrigin,
} from "./payload.js";

const OPEN_LABEL = "Open Seeker Agent Connect";

/**
 * @param {object} env
 * @param {ParentNode & { getElementById(id: string): HTMLElement | null }} env.document
 * @param {{ hash: string, search: string }} env.location
 * @param {string} env.trustedOrigin
 * @param {() => Promise<{ renderSVG: (data: string, options?: object) => string }>} [env.loadQr]
 * @param {{ clipboard?: { writeText(text: string): Promise<void> } }} [env.navigator]
 * @param {(uri: string) => void} [env.onReady]
 */
export async function bootPairingPage(env) {
  const empty = must(env.document, "state-empty");
  const invalid = must(env.document, "state-invalid");
  const ready = must(env.document, "state-ready");
  hide(empty);
  hide(invalid);
  hide(ready);

  const source = readSource(env);
  if (source.kind === "empty") {
    show(empty);
    return { state: "empty" };
  }
  if (source.kind === "invalid") {
    setText(must(env.document, "invalid-reason"), source.message);
    show(invalid);
    return { state: "invalid" };
  }

  const { code, payload, legacyQuery } = source;
  if (!sameOrigin(code.serverUrl, env.trustedOrigin)) {
    setText(
      must(env.document, "invalid-reason"),
      "This pairing code is for a different server. This page will not open it.",
    );
    show(invalid);
    return { state: "invalid" };
  }

  const pairingUri = payload?.pairing_uri ?? pairingUriFromCode(code);

  setText(must(env.document, "server-origin"), originLabel(code.serverUrl));
  setText(must(env.document, "expiry"), expiryText(payload?.expires_at));
  renderWarning(env.document, payload);
  renderCompatibility(env.document, legacyQuery);

  const open = must(env.document, "open-app");
  open.setAttribute("href", pairingUri);
  open.textContent = OPEN_LABEL;

  const codeField = /** @type {HTMLTextAreaElement} */ (
    must(env.document, "pairing-code")
  );
  codeField.value = pairingUri;
  codeField.readOnly = true;

  bindCopy(env, pairingUri);

  const qrHost = must(env.document, "qr");
  qrHost.replaceChildren();
  qrHost.setAttribute("data-pairing-uri", pairingUri);
  if (env.loadQr !== undefined) {
    const qr = await env.loadQr();
    const svg = qr.renderSVG(pairingUri, { ecc: "M", border: 2, pixelSize: 4 });
    qrHost.setAttribute("data-qr-svg", svg);
    const Parser = env.DOMParser ?? globalThis.DOMParser;
    if (typeof Parser === "function") {
      const parsed = new Parser().parseFromString(svg, "image/svg+xml");
      const root = parsed.documentElement;
      if (root !== null && root.tagName.toLowerCase() === "svg") {
        root.setAttribute("role", "img");
        root.setAttribute("aria-label", "QR code for the pairing URI");
        qrHost.appendChild(env.document.importNode?.(root, true) ?? root);
      }
    }
  }

  const expired =
    payload !== undefined && Date.parse(payload.expires_at) < Date.now();
  const expiredNote = env.document.getElementById("expired-note");
  if (expiredNote !== null) {
    expiredNote.hidden = !expired;
  }

  show(ready);
  env.onReady?.(pairingUri);
  return {
    state: expired ? "expired" : "ready",
    pairingUri,
    warning: warningText(payload),
  };
}

export function trustedOriginFromDocument(document) {
  const node = document.getElementById("pairing-server");
  if (
    node === null ||
    node.textContent === null ||
    node.textContent.trim() === ""
  ) {
    throw new Error("missing pairing-server origin");
  }
  const parsed = JSON.parse(node.textContent);
  if (typeof parsed.origin !== "string" || parsed.origin.length === 0) {
    throw new Error("pairing-server origin is missing");
  }
  return parsed.origin;
}

function readSource(env) {
  const hash = env.location.hash.startsWith("#")
    ? env.location.hash.slice(1)
    : env.location.hash;
  if (hash.length > 0) {
    const decoded = decodePairingFragment(hash);
    if (!decoded.ok) {
      return {
        kind: "invalid",
        message:
          "This pairing link is damaged or incomplete. Ask your agent for a new one.",
      };
    }
    return {
      kind: "fragment",
      code: decoded.code,
      payload: decoded.payload,
      legacyQuery: false,
    };
  }
  const search = env.location.search ?? "";
  if (search.length > 1) {
    const parsed = parseLegacyPairingQuery(search);
    if (!parsed.ok) {
      return {
        kind: "invalid",
        message:
          "This address is not a usable pairing code. Ask your agent for a fresh HTTPS link.",
      };
    }
    return {
      kind: "query",
      code: parsed.code,
      payload: undefined,
      legacyQuery: true,
    };
  }
  return { kind: "empty" };
}

function renderWarning(document, payload) {
  const node = must(document, "replacement-warning");
  setText(node, warningText(payload));
  node.hidden = false;
}

function warningText(payload) {
  if (payload?.warning !== undefined && payload.warning.length > 0) {
    return payload.warning;
  }
  return CONDITIONAL_REPLACEMENT_WARNING;
}

function renderCompatibility(document, legacyQuery) {
  const node = document.getElementById("legacy-query-note");
  if (node === null) return;
  if (!legacyQuery) {
    node.hidden = true;
    node.textContent = "";
    return;
  }
  node.hidden = false;
  setText(
    node,
    "This old-style link put the pairing code in the page address, so it may already appear in this browser's history and in the server's request log. Moving it into a fragment now does not undo that. Prefer a fresh link from your agent.",
  );
}

function expiryText(expiresAt) {
  if (expiresAt === undefined) {
    return "This code works once. Its exact expiry is enforced when the phone pairs, not by this page.";
  }
  return `This code works once and is shown as expiring at ${expiresAt}. The server still decides whether it can be used. Keep it private; sharing the link shares the code.`;
}

function originLabel(serverUrl) {
  return new URL(serverUrl).origin;
}

function bindCopy(env, pairingUri) {
  const button = env.document.getElementById("copy-code");
  const status = env.document.getElementById("copy-status");
  const field = env.document.getElementById("pairing-code");
  if (button === null) return;
  button.addEventListener("click", () => {
    const write = env.navigator?.clipboard?.writeText;
    const done = (ok) => {
      if (status !== null)
        setText(
          status,
          ok ? "Copied." : "Select the pairing code and copy it.",
        );
    };
    if (typeof write === "function") {
      Promise.resolve(write.call(env.navigator.clipboard, pairingUri)).then(
        () => done(true),
        () => {
          field?.focus?.();
          field?.select?.();
          done(false);
        },
      );
      return;
    }
    field?.focus?.();
    field?.select?.();
    done(false);
  });
}

function must(document, id) {
  const node = document.getElementById(id);
  if (node === null) throw new Error(`missing #${id}`);
  return node;
}

function setText(node, text) {
  node.textContent = text;
}

function show(node) {
  node.hidden = false;
}

function hide(node) {
  node.hidden = true;
}

function defaultLoadQr() {
  return import("./uqr.js");
}

function start() {
  if (typeof document === "undefined") return;
  const trustedOrigin = trustedOriginFromDocument(document);
  void bootPairingPage({
    document,
    location,
    navigator,
    trustedOrigin,
    loadQr: defaultLoadQr,
  });
}

if (typeof document !== "undefined") {
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
}
