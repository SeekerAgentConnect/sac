import { cpSync } from "node:fs";

// TypeScript follows the generated declarations while compiling but does not copy the paired
// generated JavaScript. Both are runtime package assets, so copy this owned tree after a clean
// build. The package file allowlist excludes this script itself.
cpSync("src/gen", "dist/gen", { recursive: true });
