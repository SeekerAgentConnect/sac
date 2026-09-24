import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const PAGE = fileURLToPath(new URL("./page/", import.meta.url));
const DIST_PAGE = fileURLToPath(
  new URL("../../dist/pairing/page/", import.meta.url),
);

describe("pairing page assets", () => {
  it("keeps the page, codec, and stylesheet next to the handler", () => {
    for (const name of ["page.js", "page.css", "payload.js"]) {
      assert.equal(existsSync(join(PAGE, name)), true, name);
    }
    const page = readFileSync(join(PAGE, "page.js"), "utf8");
    assert.doesNotMatch(page, /\bfetch\s*\(/);
    assert.doesNotMatch(page, /localStorage|sessionStorage/);
    assert.doesNotMatch(page, /window\.location\s*=/);
    assert.match(page, /from "\.\/payload\.js"/);
    assert.match(page, /import\("\.\/uqr\.js"\)/);
  });

  it("ships them, and the declarations a host compiles against, in the built package", () => {
    if (!existsSync(join(DIST_PAGE, "page.js"))) return;
    for (const name of [
      "page.js",
      "page.d.ts",
      "page.css",
      "payload.js",
      "payload.d.ts",
    ]) {
      assert.equal(existsSync(join(DIST_PAGE, name)), true, name);
    }
    // The QR library is the host's dependency: this package neither installs nor ships one, and
    // the page falls back to its own line when a host serves none.
    assert.equal(existsSync(join(DIST_PAGE, "uqr.js")), false);
  });
});
