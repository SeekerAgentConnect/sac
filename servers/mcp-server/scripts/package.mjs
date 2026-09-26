/**
 * Build this server's distributable application tree. The staging itself is shared with the SKR
 * staking server (scripts/package-mcp-artifact.mjs); what is this package's own is the QR library:
 * `uqr` is its dependency, and the SDK's pairing page handler is handed the path to it rather than
 * resolving one of its own (SEE-149).
 */
import { cpSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { stageMcpPackage } from "../../../scripts/package-mcp-artifact.mjs";

stageMcpPackage({
  packageRoot: fileURLToPath(new URL("../", import.meta.url)),
  copyExtraAssets(distribution) {
    // The pairing page itself travels with the vendored SDK. This is the one asset beside it.
    const page = join(distribution, "pairing", "page");
    mkdirSync(page, { recursive: true });
    cpSync(fileURLToPath(import.meta.resolve("uqr")), join(page, "uqr.js"));
  },
});
