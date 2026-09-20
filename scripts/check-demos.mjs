// Checks the public-feed modules (SEE-95, SEE-96, SEE-134): formatting, `go vet`, and the tests, for
// the shared library and each demo separately.
//
//   pnpm check:publisher-support
//   pnpm check:copytrading
//   pnpm check:prediction
//   pnpm check:demos              # all three, in that order
//
// One script, three entry points, because the three modules are checked identically and a copy of
// this per module would be three slightly different checks. What it must not do is check them
// *together*: each demo builds and runs without the other, and the way that stays true is that the
// command which proves it names one module (docs/development/demos.md).
//
// It is separate from `pnpm check` for the same reason `pnpm check:feed-gateway` and
// `pnpm check:android` are: it needs a toolchain the Node checks do not, and someone working on the
// MCP server should not have to install Go to run them. CI runs all of them, as separate jobs.
//
// Two tests are opt-in, because they need a binary these modules do not build: the ones that run
// the real feed gateway as a separate process — one in publisher-support/publish for a
// caller-authored request, one in demo-prediction/internal/discovery for a discovered market. They
// are what prove that a publisher and the gateway agree about a manifest, a channel and a
// republication rather than assuming it, so this script builds the gateway and runs them — and says
// so, rather than quietly skipping the most interesting test in the module.
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

// Each module, in dependency order: the library both demos are built on, then the two demos. The
// order matters only for the reading of the output; nothing here builds one demo from the other.
const modules = {
  "publisher-support": "the shared library",
  "demo-copytrading": "the CopyTrading demo",
  "demo-prediction": "the Prediction demo",
};

const asked = process.argv.slice(2);
const chosen = asked.length > 0 ? asked : Object.keys(modules);
for (const name of chosen) {
  if (!(name in modules)) {
    console.error(
      [
        `There is no module called ${name}.`,
        `Name one of: ${Object.keys(modules).join(", ")} — or none, for all of them.`,
      ].join("\n"),
    );
    process.exit(1);
  }
}

const root = fileURLToPath(new URL("..", import.meta.url));
const feedGateway = join(root, "feed-gateway");

// The version each go.mod requires, and the one docs/development/toolchain.md records as tested. A
// newer Go builds them too; this is the message for a machine that has none.
const version = "1.27.1";

try {
  execFileSync("go", ["version"], { cwd: root, stdio: "pipe" });
} catch {
  console.error(
    [
      "Go is not on PATH, so the public-feed modules cannot be checked.",
      `Install Go ${version} or newer (https://go.dev/dl/, or \`brew install go\`).`,
      "See docs/development/toolchain.md.",
    ].join("\n"),
  );
  process.exit(1);
}

// The gateway, built into a temporary directory, so the opt-in tests run the real thing. A machine
// that cannot build it still gets every other test: the services are separate modules, and a demo is
// meant to be copyable out of this repository entirely.
const built = mkdtempSync(join(tmpdir(), "seeker-demos-check-"));
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
      "The feed gateway could not be built, so the tests that run it are skipped:",
      `  ${error.message.split("\n")[0]}`,
      "Everything else is still checked.",
    ].join("\n"),
  );
}

try {
  for (const name of chosen) {
    console.log(`\n=== ${name} — ${modules[name]}`);
    check(join(root, name), name);
  }
} finally {
  rmSync(built, { recursive: true, force: true });
}

function check(directory, name) {
  // gofmt lists what it would change rather than failing, so its output is the check.
  const unformatted = execFileSync("gofmt", ["-l", "."], {
    cwd: directory,
    encoding: "utf8",
  }).trim();
  if (unformatted !== "") {
    console.error(
      [
        "These files are not formatted:",
        ...unformatted.split("\n").map((file) => `  ${name}/${file}`),
        `Run \`gofmt -w .\` in ${name}/.`,
      ].join("\n"),
    );
    process.exit(1);
  }

  // `go vet` before the tests, because a vet failure is usually the cause of a test failure rather
  // than a separate problem.
  go(directory, "vet", "./...");
  go(directory, "test", "./...", {
    ...(gateway ? { SEEKERVAULT_FEED_GATEWAY: gateway } : {}),
  });
}

function go(directory, ...args) {
  const environment = typeof args.at(-1) === "object" ? args.pop() : {};
  execFileSync("go", args, {
    cwd: directory,
    stdio: "inherit",
    env: { ...process.env, ...environment },
  });
}
