/**
 * Public HTTPS landing for a pairing code: GET /pair?<query> opens the same
 * `seekervault://pair` deep link the CLI prints. The token is in the query; the
 * route is unauthenticated the way a QR code is unauthenticated.
 */
import type { IncomingMessage, ServerResponse } from "node:http";

import { parsePairingUri } from "@seeker-vault/server-sdk";

export const PAIRING_LINK_PATH = "/pair";

export function handlePairingLink(
  req: IncomingMessage,
  res: ServerResponse,
): void {
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.writeHead(405, { Allow: "GET, HEAD" });
    res.end();
    return;
  }
  let search: string;
  try {
    search = new URL(req.url ?? "/", "http://sidecar").search;
  } catch {
    res.writeHead(400);
    res.end();
    return;
  }
  const uri = `seekervault://pair${search}`;
  if (!parsePairingUri(uri).ok) {
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not_found" }));
    return;
  }
  const href = escapeHtml(uri);
  res.writeHead(302, {
    Location: uri,
    "Content-Type": "text/html; charset=utf-8",
    "Cache-Control": "no-store",
  });
  if (req.method === "HEAD") {
    res.end();
    return;
  }
  res.end(
    `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Seeker Agent Connect</title><meta http-equiv="refresh" content="0;url=${href}"></head><body><p><a href="${href}">Open Seeker Agent Connect</a></p></body></html>`,
  );
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => {
    switch (character) {
      case "&":
        return "&amp;";
      case "<":
        return "&lt;";
      case ">":
        return "&gt;";
      case '"':
        return "&quot;";
      default:
        return "&#39;";
    }
  });
}
