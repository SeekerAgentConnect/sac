/**
 * This sidecar's half of the shared pairing page (SEE-149): which server the page says it is, and
 * where its QR library is. The page, its codec and its security headers are the SDK's.
 *
 * The QR module is the host's dependency rather than the SDK's, so the path is resolved here: the
 * packaged build vendors `uqr` next to the compiled sources, and a source checkout resolves the
 * installed package instead.
 */
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { SEEKER_MCP_PAIRING_PAGE } from "@seeker-vault/server-sdk";

export const PAIRING_PAGE = SEEKER_MCP_PAIRING_PAGE;

export function qrModulePath(): string {
  const vendored = fileURLToPath(new URL("./page/uqr.js", import.meta.url));
  if (existsSync(vendored)) return vendored;
  return fileURLToPath(import.meta.resolve("uqr"));
}
