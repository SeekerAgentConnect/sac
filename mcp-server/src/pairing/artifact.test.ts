import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const PAGE = fileURLToPath(new URL("./page/", import.meta.url));
const DIST_PAGE = fileURLToPath(
  new URL("../../dist/pairing/page/", import.meta.url),
);
const STAGED_PAGE = fileURLToPath(
  new URL("../../package/dist/pairing/page/", import.meta.url),
);

describe("pairing page assets in source and packaged output", () => {
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

  it("includes those assets and the vendored QR library after a package build", () => {
    const roots = [DIST_PAGE, STAGED_PAGE].filter((dir) =>
      existsSync(join(dir, "page.js")),
    );
    if (roots.length === 0) return;
    for (const root of roots) {
      for (const name of ["page.js", "page.css", "payload.js", "uqr.js"]) {
        assert.equal(existsSync(join(root, name)), true, `${root}${name}`);
      }
      const qr = readFileSync(join(root, "uqr.js"), "utf8");
      assert.match(qr, /export \{[^}]*renderSVG/);
    }
  });
});
