/**
 * Public HTTPS landing for a pairing code: GET /pair serves a page. New links put the token in
 * the fragment, which this process never receives. The page's button opens the existing
 * seekervault://pair URI; this route does not pair, revoke, or issue codes.
 *
 * Every server that offers the owner a pairing link serves the same page (SEE-149). What differs
 * between them is one sentence — which server the owner is about to connect to — and where the QR
 * library lives, because that is the host's dependency rather than this package's.
 */
import { readFileSync } from "node:fs";
import type { IncomingMessage, ServerResponse } from "node:http";
import { fileURLToPath } from "node:url";

export const PAIRING_LINK_PATH = "/pair";

const PAGE_ASSETS = {
  "/pair/page.js": { name: "page.js", type: "text/javascript; charset=utf-8" },
  "/pair/page.css": { name: "page.css", type: "text/css; charset=utf-8" },
  "/pair/payload.js": {
    name: "payload.js",
    type: "text/javascript; charset=utf-8",
  },
  "/pair/uqr.js": { name: "uqr.js", type: "text/javascript; charset=utf-8" },
} as const;

export function isPairingLinkPath(path: string): boolean {
  return (
    path === PAIRING_LINK_PATH ||
    path === `${PAIRING_LINK_PATH}/` ||
    path in PAGE_ASSETS
  );
}

/**
 * Who is asking the owner to pair. The owner may run more than one server, and each pairing is a
 * separate connection on the phone, so the page has to say which one this is.
 */
export interface PairingPageIdentity {
  /** The browser tab's title. */
  readonly title: string;
  /** The page's heading. */
  readonly heading: string;
  /** Which server serves this page, as a sentence or two the owner reads first. */
  readonly server: string;
}

export const SEEKER_MCP_PAIRING_PAGE: PairingPageIdentity = {
  title: "Pair Seeker Agent Connect",
  heading: "Connect your phone",
  server: "This page is served by the Seeker Agent Connect MCP server.",
};

export interface PairingLinkOptions {
  /** Configured public origin, not a forwarded Host header. */
  readonly publicOrigin: string;
  /** Which server the page says it is; the general MCP server unless told otherwise. */
  readonly identity?: PairingPageIdentity;
  /**
   * Absolute path to the host's ESM build of `uqr`, served as /pair/uqr.js. Without it the page
   * still works: the QR code is replaced by its own fallback line, and the button, the copy value
   * and the pairing code all still carry the URI.
   */
  readonly qrModulePath?: () => string;
}

const CSP = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'none'",
  "connect-src 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
  "base-uri 'none'",
  "object-src 'none'",
].join("; ");

export function handlePairingLink(
  req: IncomingMessage,
  res: ServerResponse,
  options: PairingLinkOptions,
): void {
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.writeHead(405, { Allow: "GET, HEAD", ...securityHeaders() });
    res.end();
    return;
  }
  let path: string;
  try {
    path = new URL(req.url ?? "/", "http://sidecar").pathname;
  } catch {
    res.writeHead(400, securityHeaders());
    res.end();
    return;
  }
  if (path in PAGE_ASSETS) {
    sendAsset(req, res, path as keyof typeof PAGE_ASSETS, options);
    return;
  }
  const origin = configuredOrigin(options.publicOrigin);
  const html = pairingPageHtml(
    origin,
    options.identity ?? SEEKER_MCP_PAIRING_PAGE,
  );
  const headers = {
    ...securityHeaders(),
    "Content-Type": "text/html; charset=utf-8",
    "Content-Length": String(Buffer.byteLength(html)),
  };
  res.writeHead(200, headers);
  if (req.method === "HEAD") {
    res.end();
    return;
  }
  res.end(html);
}

function sendAsset(
  req: IncomingMessage,
  res: ServerResponse,
  path: keyof typeof PAGE_ASSETS,
  options: PairingLinkOptions,
): void {
  const asset = PAGE_ASSETS[path];
  let body: Buffer;
  try {
    // Resolving the host's QR module can fail as readily as reading it can — an installation
    // without one is a page without a QR code, not a request that kills the listener.
    const file = assetFile(asset.name, options);
    if (file === undefined) throw new Error("not served");
    body = readFileSync(file);
  } catch {
    res.writeHead(404, {
      ...securityHeaders(),
      "Content-Type": "application/json",
    });
    res.end(JSON.stringify({ error: "not_found" }));
    return;
  }
  res.writeHead(200, {
    ...securityHeaders(),
    "Content-Type": asset.type,
    "Content-Length": String(body.byteLength),
  });
  if (req.method === "HEAD") {
    res.end();
    return;
  }
  res.end(body);
}

function assetFile(
  name: string,
  options: PairingLinkOptions,
): string | undefined {
  if (name === "uqr.js") return options.qrModulePath?.();
  return fileURLToPath(new URL(`./page/${name}`, import.meta.url));
}

function configuredOrigin(publicOrigin: string): string {
  try {
    return new URL(publicOrigin).origin;
  } catch {
    return "";
  }
}

function securityHeaders(): Record<string, string> {
  return {
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Content-Security-Policy": CSP,
  };
}

/** The identity is the host's own text, and it is still escaped: the page is not a template. */
function escapeHtml(text: string): string {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function pairingPageHtml(
  origin: string,
  identity: PairingPageIdentity,
): string {
  const config = JSON.stringify({ origin }).replaceAll("<", "\\u003c");
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(identity.title)}</title>
<link rel="stylesheet" href="/pair/page.css">
</head>
<body>
<main>
<h1>${escapeHtml(identity.heading)}</h1>
<p>${escapeHtml(identity.server)} It does not pair by itself. The button below opens the app; if it does not open, copy or scan the code and paste it under Add connection.</p>
<script type="application/json" id="pairing-server">${config}</script>
<section id="state-empty" hidden>
<p>This page needs a pairing link from your agent. Opening it with no code does not connect a phone.</p>
</section>
<section id="state-invalid" hidden>
<p id="invalid-reason"></p>
</section>
<section id="state-ready" hidden>
<p>You are connecting to <strong id="server-origin"></strong>.</p>
<p id="legacy-query-note" class="warning" hidden></p>
<p id="replacement-warning" class="warning" role="status"></p>
<p id="expiry" class="hint"></p>
<p id="expired-note" class="warning" hidden>This page's expiry time is in the past. The server still decides whether the code works. Ask for a new link if pairing fails.</p>
<div class="actions">
<a class="button" id="open-app" rel="noreferrer">Open Seeker Agent Connect</a>
<button type="button" class="secondary" id="copy-code">Copy pairing code</button>
</div>
<p id="copy-status" class="hint" aria-live="polite"></p>
<div id="qr" hidden></div>
<p id="qr-fallback" class="hint" hidden></p>
<label for="pairing-code">Pairing code</label>
<textarea id="pairing-code" spellcheck="false"></textarea>
<p class="hint">The code is single-use and private. This page does not know whether a phone is paired now, whether the code was already used, or whether pairing will succeed. Opening the app is not guaranteed in every browser.</p>
</section>
</main>
<script type="module" src="/pair/page.js"></script>
</body>
</html>
`;
}
