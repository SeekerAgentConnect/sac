/**
 * Public HTTPS landing for a pairing code: GET /pair serves a page. New links put the
 * token in the fragment, which this process never receives. The page's button opens the
 * existing seekervault://pair URI; this route does not pair, revoke, or issue codes.
 */
import { existsSync, readFileSync } from "node:fs";
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

export interface PairingLinkOptions {
  /** Configured public origin, not a forwarded Host header. */
  readonly publicOrigin: string;
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
    sendAsset(req, res, path as keyof typeof PAGE_ASSETS);
    return;
  }
  const origin = configuredOrigin(options.publicOrigin);
  const html = pairingPageHtml(origin);
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
): void {
  const asset = PAGE_ASSETS[path];
  let body: Buffer;
  try {
    body = readFileSync(assetFile(asset.name));
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

function assetFile(name: string): string {
  if (name === "uqr.js") {
    const vendored = fileURLToPath(new URL("./page/uqr.js", import.meta.url));
    if (existsSync(vendored)) return vendored;
    return fileURLToPath(import.meta.resolve("uqr"));
  }
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

function pairingPageHtml(origin: string): string {
  const config = JSON.stringify({ origin }).replaceAll("<", "\\u003c");
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pair Seeker Agent Connect</title>
<link rel="stylesheet" href="/pair/page.css">
</head>
<body>
<main>
<h1>Connect your phone</h1>
<p>This page is served by the Seeker Agent Connect MCP server. It does not pair by itself. The button below opens the app; if it does not open, copy or scan the code and paste it under Add connection.</p>
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
<div id="qr"></div>
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
