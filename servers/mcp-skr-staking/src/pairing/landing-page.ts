/**
 * This server's half of the shared pairing page (SEE-149): what the page says it is, and where its
 * QR library is. The page, its codec and its security headers are the SDK's, so the owner sees the
 * same page here as on the general MCP server — with one difference, which is the one that matters:
 * it says which server they are connecting to, because this pairing is a second connection on the
 * phone and not a replacement for the other one.
 */
import { fileURLToPath } from "node:url";

import type { PairingPageIdentity } from "@seeker-vault/server-sdk";

export const PAIRING_PAGE: PairingPageIdentity = {
  title: "Pair the SKR staking server",
  heading: "Connect your phone to SKR staking",
  server:
    "This page is served by the SKR staking server, which asks you to approve staking with your " +
    "own SKR. It is a separate connection from any other Seeker Agent Connect server you have " +
    "added, and pairing here leaves those alone.",
};

/** `uqr` is this package's dependency rather than the SDK's, so the path is resolved here. */
export function qrModulePath(): string {
  return fileURLToPath(import.meta.resolve("uqr"));
}
