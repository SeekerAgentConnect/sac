import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

/**
 * After SEE-149 the page itself belongs to the SDK and travels with the vendored copy of it; what
 * this package still owns is the QR library the page imports. Both have to survive the build, or
 * the served page loses its script, its stylesheet or its QR code in the packaged artifact only.
 */
const DIST = fileURLToPath(new URL("../../dist/", import.meta.url));
const STAGED = fileURLToPath(new URL("../../package/dist/", import.meta.url));

describe("pairing page assets in the packaged output", () => {
  it("vendors the SDK's page and this package's QR library after a package build", () => {
    const roots = [DIST, STAGED].filter((root) =>
      existsSync(join(root, "pairing/page/uqr.js")),
    );
    if (roots.length === 0) return;
    for (const root of roots) {
      const qr = readFileSync(join(root, "pairing/page/uqr.js"), "utf8");
      assert.match(qr, /export \{[^}]*renderSVG/);
      const page = join(root, "vendor/server-sdk/pairing/page");
      for (const name of ["page.js", "page.css", "payload.js"]) {
        assert.equal(existsSync(join(page, name)), true, `${page}/${name}`);
      }
    }
  });
});
