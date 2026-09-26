import { cpSync, mkdirSync } from "node:fs";
import { join } from "node:path";

// TypeScript follows the generated declarations while compiling but does not copy the paired
// generated JavaScript. Both are runtime package assets, so copy this owned tree after a clean
// build. The package file allowlist excludes this script itself.
cpSync("src/gen", "dist/gen", { recursive: true });

// The pairing page's own files (SEE-149). They are browser assets rather than compiled sources:
// page.js and payload.js are served to the phone's browser exactly as written, page.css with them,
// and the declarations are what a host's TypeScript reads when it imports the page module. The
// host's QR library is its own dependency and is not copied here.
const PAGE = join("dist", "pairing", "page");
mkdirSync(PAGE, { recursive: true });
for (const name of [
  "page.js",
  "page.d.ts",
  "page.css",
  "payload.js",
  "payload.d.ts",
]) {
  cpSync(join("src", "pairing", "page", name), join(PAGE, name));
}
