import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";

const roots = ["app/src/main", "designsystem/src/main"];
const theme = join(
  "designsystem",
  "src",
  "main",
  "java",
  "io",
  "github",
  "brrenat",
  "seekervault",
  "designsystem",
  "theme",
);
const literal = /Color\s*\(\s*0x|\b\d+(?:\.\d+)?\.(?:dp|sp)\b/;

function kotlinFiles(directory) {
  return readdirSync(directory, { withFileTypes: true })
    .flatMap((entry) => {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        return entry.name === "generated" || path === theme ? [] : kotlinFiles(path);
      }
      return entry.isFile() && entry.name.endsWith(".kt") ? [path] : [];
    })
    .sort();
}

const violations = roots.flatMap(kotlinFiles).flatMap((file) =>
  readFileSync(file, "utf8")
    .split(/\r?\n/)
    .flatMap((line, index) =>
      literal.test(line)
        ? [`${relative(".", file)}:${index + 1}: ${line.trim()}`]
        : [],
    ),
);

if (violations.length > 0) {
  console.error("Raw design literals belong in :designsystem's theme package:");
  console.error(violations.join("\n"));
  process.exitCode = 1;
} else {
  console.log("No raw Color, dp, or sp literals outside the design-system theme.");
}
