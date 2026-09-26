// Checks the feed gateway (SEE-90): formatting, `go vet`, and its tests.
//
//   pnpm check:gateway
//
// It is separate from `pnpm check` for the same reason `pnpm check:android` is: it needs a
// toolchain the Node checks do not, and someone working on the sidecar should not have to install
// Go to run them. CI runs both.
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const directory = fileURLToPath(
  new URL("../services/gateway", import.meta.url),
);

// The version services/gateway/go.mod requires, and the one docs/development/toolchain.md records as
// tested. A newer Go builds it too; this is the message for a machine that has none.
const version = "1.27.1";

try {
  execFileSync("go", ["version"], { cwd: directory, stdio: "pipe" });
} catch {
  console.error(
    [
      "Go is not on PATH, so the feed gateway cannot be checked.",
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
      ...unformatted.split("\n").map((file) => `  services/gateway/${file}`),
      "Run `gofmt -w .` in services/gateway/.",
    ].join("\n"),
  );
  process.exit(1);
}

// `go vet` before the tests, because a vet failure is usually the cause of a test failure rather
// than a separate problem.
go("vet", "./...");
go("test", "./...");

function go(...args) {
  execFileSync("go", args, { cwd: directory, stdio: "inherit" });
}
