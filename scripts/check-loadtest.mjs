// Checks the load harness (SEE-99): formatting, `go vet`, and its tests.
//
//   pnpm check:loadtest
//
// It is separate from `pnpm check` for the reason `pnpm check:feed-gateway` is: it needs a toolchain
// the Node checks do not. What it does **not** need is a broker, a gateway or Redis — the tests
// here are the harness's own (its quantiles, its ported client policy, its profiles, its
// boundaries), and the ones that drive real processes skip with a reason when the binaries are not
// named. Measuring anything is `pnpm test:load`.
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const directory = fileURLToPath(new URL("../loadtest", import.meta.url));

// The version loadtest/go.mod requires, and the one docs/development/toolchain.md records as
// tested. A newer Go builds it too; this is the message for a machine that has none.
const version = "1.27.1";

try {
  execFileSync("go", ["version"], { cwd: directory, stdio: "pipe" });
} catch {
  console.error(
    [
      "Go is not on PATH, so the load harness cannot be checked.",
      `Install Go ${version} or newer (https://go.dev/dl/, or \`brew install go\`).`,
      "See docs/development/toolchain.md.",
    ].join("\n"),
  );
  process.exit(1);
}

// gofmt lists what it would change rather than failing, so its output is the check.
const unformatted = execFileSync("gofmt", ["-l", "."], {
  cwd: directory,
  encoding: "utf8",
}).trim();
if (unformatted !== "") {
  console.error(
    [
      "These files are not formatted:",
      ...unformatted.split("\n").map((file) => `  loadtest/${file}`),
      "Run `gofmt -w .` in loadtest/.",
    ].join("\n"),
  );
  process.exit(1);
}

go("vet", "./...");
go("test", "./...");

function go(...args) {
  execFileSync("go", args, { cwd: directory, stdio: "inherit" });
}
