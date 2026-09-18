// `pnpm test:load`: the load, isolation and failover run (SEE-99).
//
//   pnpm test:load                          every scenario this machine can
//   pnpm test:load -- --scenario drain      one of them
//   pnpm test:load -- --list                what there is to run
//   pnpm test:load -- --report out.json     the evidence, as JSON
//
// It builds the gateway, `broadcastctl` and the harness, then hands the harness the paths — the
// same arrangement `pnpm test:integration` uses, and for the same reason: what is measured has to
// be the binaries this checkout builds rather than something on the PATH.
//
// The broker and Redis are **not** built here. They are services, they are not vendored, and they
// are named by the operator:
//
//   SEEKERVAULT_CENTRIFUGO=/path/to/centrifugo \
//   SEEKERVAULT_REDIS=/path/to/redis-server \
//     pnpm test:load
//
// Without the first, every scenario that streams is reported NOT RUN rather than passing quietly.
// Without the second, the two-node ones are: Redis is what makes two broker nodes one broker.
// `docs/development/load.md` says where to get both and how to verify them.
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const BROADCAST = join(ROOT, "broadcast");
const LOADTEST = join(ROOT, "loadtest");

// The version the go.mod files require, and the one docs/development/toolchain.md records as
// tested. A newer Go builds them too; this is the message for a machine that has none.
const GO = "1.27.1";

try {
  execFileSync("go", ["version"], { cwd: BROADCAST, stdio: "pipe" });
} catch {
  console.error(
    [
      "Go is not on PATH, so the gateway and the harness cannot be built.",
      `Install Go ${GO} or newer (https://go.dev/dl/, or \`brew install go\`).`,
      "See docs/development/toolchain.md.",
    ].join("\n"),
  );
  process.exit(1);
}

const broker = process.env.SEEKERVAULT_CENTRIFUGO ?? "";
const redis = process.env.SEEKERVAULT_REDIS ?? "";
console.log(
  [
    broker === ""
      ? "No broker: set SEEKERVAULT_CENTRIFUGO to the pinned binary. Every scenario that streams will be NOT RUN."
      : `The broker is ${broker}.`,
    redis === ""
      ? "No Redis: set SEEKERVAULT_REDIS to run two broker nodes as one broker."
      : `Redis is ${redis}.`,
    "",
  ].join("\n"),
);

const built = mkdtempSync(join(tmpdir(), "seeker-vault-loadtest-"));
try {
  console.log("Building the gateway, broadcastctl and the harness…\n");
  for (const [module, command] of [
    [BROADCAST, "broadcast"],
    [BROADCAST, "broadcastctl"],
    [LOADTEST, "loadtest"],
  ]) {
    execFileSync(
      "go",
      ["build", "-o", join(built, command), `./cmd/${command}`],
      { cwd: module, stdio: "inherit" },
    );
  }

  // `pnpm test:load -- --scenario drain` hands this script a literal `--` as its first argument,
  // and Go's flag package stops parsing at one — so every flag after it would be silently ignored
  // and the run would quietly do something else. It is dropped here rather than documented away.
  const passed = process.argv.slice(2);
  while (passed[0] === "--") passed.shift();

  const run = spawnSync(join(built, "loadtest"), passed, {
    cwd: ROOT,
    stdio: "inherit",
    env: {
      ...process.env,
      SEEKERVAULT_BROADCAST: join(built, "broadcast"),
      SEEKERVAULT_BROADCASTCTL: join(built, "broadcastctl"),
    },
  });
  process.exitCode = run.status ?? 1;
} finally {
  rmSync(built, { recursive: true, force: true });
}
