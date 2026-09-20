// Checks the publisher templates (SEE-95): formatting, `go vet`, and their tests.
//
//   pnpm check:publisher
//
// It is separate from `pnpm check` for the same reason `pnpm check:feed-gateway` and
// `pnpm check:android` are: it needs a toolchain the Node checks do not, and someone working on
// the sidecar should not have to install Go to run them. CI runs all of them.
//
// One test in the module is opt-in, because it needs a binary this module does not build: the one
// that runs the real feed gateway as a separate process. It is what proves that the two agree
// about a manifest, a channel and a republication rather than assuming it, so this script builds
// the gateway and runs it — and says so, rather than quietly skipping the most interesting test in
// the module (docs/development/publisher.md).
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const publisher = fileURLToPath(new URL("../publisher", import.meta.url));
const feedGateway = fileURLToPath(new URL("../feed-gateway", import.meta.url));

// The version publisher/go.mod requires, and the one docs/development/toolchain.md records as
// tested. A newer Go builds it too; this is the message for a machine that has none.
const version = "1.27.1";

try {
  execFileSync("go", ["version"], { cwd: publisher, stdio: "pipe" });
} catch {
  console.error(
    [
      "Go is not on PATH, so the publisher templates cannot be checked.",
      `Install Go ${version} or newer (https://go.dev/dl/, or \`brew install go\`).`,
      "See docs/development/toolchain.md.",
    ].join("\n"),
  );
  process.exit(1);
}

// gofmt lists what it would change rather than failing, so its output is the check.
const unformatted = execFileSync("gofmt", ["-l", "."], {
  cwd: publisher,
  encoding: "utf8",
}).trim();
if (unformatted !== "") {
  console.error(
    [
      "These files are not formatted:",
      ...unformatted.split("\n").map((file) => `  publisher/${file}`),
      "Run `gofmt -w .` in publisher/.",
    ].join("\n"),
  );
  process.exit(1);
}

// `go vet` before the tests, because a vet failure is usually the cause of a test failure rather
// than a separate problem.
go(publisher, "vet", "./...");

// The gateway, built into a temporary directory, so the opt-in test runs the real thing. A machine
// that cannot build it still gets every other test: the two services are separate modules, and a
// publisher template is meant to be copyable out of this repository entirely.
const built = mkdtempSync(join(tmpdir(), "seeker-publisher-check-"));
let gateway;
try {
  go(
    feedGateway,
    "build",
    "-o",
    join(built, "feed-gateway"),
    "./cmd/feed-gateway",
  );
  go(
    feedGateway,
    "build",
    "-o",
    join(built, "feed-gatewayctl"),
    "./cmd/feed-gatewayctl",
  );
  gateway = join(built, "feed-gateway");
} catch (error) {
  console.warn(
    [
      "The feed gateway could not be built, so the test that runs it is skipped:",
      `  ${error.message.split("\n")[0]}`,
      "Everything else in publisher/ is still checked.",
    ].join("\n"),
  );
}

try {
  go(publisher, "test", "./...", {
    ...(gateway ? { SEEKERVAULT_FEED_GATEWAY: gateway } : {}),
  });
} finally {
  rmSync(built, { recursive: true, force: true });
}

function go(directory, ...args) {
  const environment = typeof args.at(-1) === "object" ? args.pop() : {};
  execFileSync("go", args, {
    cwd: directory,
    stdio: "inherit",
    env: { ...process.env, ...environment },
  });
}
