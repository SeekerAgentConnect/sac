/**
 * Build this server's distributable application tree, with the same staging the general MCP
 * server uses (scripts/package-mcp-artifact.mjs).
 *
 * There is no extra asset here. This server's pairing page resolves its QR library through
 * `import.meta.resolve("uqr")` against its own installed dependency (src/pairing/landing-page.ts),
 * so nothing has to be copied next to the compiled sources.
 */
import { fileURLToPath } from "node:url";

import { stageMcpPackage } from "../../../scripts/package-mcp-artifact.mjs";

stageMcpPackage({
  packageRoot: fileURLToPath(new URL("../", import.meta.url)),
});
