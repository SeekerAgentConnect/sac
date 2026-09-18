// `pnpm test:integration`: the Stage 7.1 integration and privacy acceptance run (SEE-98).
//
//   pnpm test:integration                 every leg this machine can run
//   pnpm test:integration --no-android    the two Node legs only, for a machine with no SDK
//
// It is one command over four components on purpose. Stage 7.1 added a second transport beside the
// private one, and what needed proving is not any one of them but that they hold together: that a
// paired sidecar still answers its agent while two public feeds are live, that a manifest cannot
// move a subscriber anywhere, and that nothing about a subscriber reaches a publisher or the
// gateway. That is three runtimes and five binaries, so this builds the binaries, runs the
// cross-component suite against them, re-runs the direct-mode acceptance suites unchanged, and
// runs the phone's own cross-component cases.
//
// Three things it deliberately does not do: reach a network, need a credential, or spend anything.
// Both publisher deployments run as sandbox, the provider is this repository's committed captures
// served back on loopback, and the wallet is a throwaway key pair.
//
// Every leg is reported at the end as PASS, FAIL or NOT RUN, with the reason. A leg that could not
// run is never silently a pass — `docs/testing/see-98.md` is written from this summary.
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const BROADCAST = join(ROOT, "broadcast");
const PUBLISHER = join(ROOT, "publisher");

// The version both go.mod files require, and the one docs/development/toolchain.md records as
// tested. A newer Go builds them too; this is the message for a machine that has none.
const GO = "1.27.1";

// The phone's half of SEE-98's scenarios: the feed transport, shared proposals across two devices,
// manifests and plugin compatibility, the wallet's binding, and the direct-mode suites that must
// still pass with all of it in the tree.
const ANDROID_TESTS = [
  "io.github.brrenat.seekervault.feeds.*Test",
  "io.github.brrenat.seekervault.servers.*Test",
  "io.github.brrenat.seekervault.proposals.*Test",
  "io.github.brrenat.seekervault.operations.*Test",
  "io.github.brrenat.seekervault.plugins.*Test",
  "io.github.brrenat.seekervault.wallet.*Test",
  "io.github.brrenat.seekervault.connections.ProposalIsolationTest",
  "io.github.brrenat.seekervault.connections.ProposalRepositoryTest",
  "io.github.brrenat.seekervault.connections.ConnectionManifestTest",
  "io.github.brrenat.seekervault.connections.storage.ProposalStoreTest",
  "io.github.brrenat.seekervault.connections.InboxRealSidecarTest",
  "io.github.brrenat.seekervault.connections.TwoSidecarsTest",
  "io.github.brrenat.seekervault.sync.FeedSynchronizationTest",
  "io.github.brrenat.seekervault.sync.Stage52AcceptanceTest",
  "io.github.brrenat.seekervault.sync.Stage53AcceptanceTest",
  "io.github.brrenat.seekervault.push.Feed*Test",
  "io.github.brrenat.seekervault.notifications.FeedNotificationsTest",
  "io.github.brrenat.seekervault.StageBoundaryTest",
];

const { values } = parseArgs({
  options: { "no-android": { type: "boolean" } },
});
const legs = [];

try {
  execFileSync("go", ["version"], { cwd: BROADCAST, stdio: "pipe" });
} catch {
  console.error(
    [
      "Go is not on PATH, so the gateway and the templates cannot be built.",
      `Install Go ${GO} or newer (https://go.dev/dl/, or \`brew install go\`).`,
      "See docs/development/toolchain.md.",
    ].join("\n"),
  );
  process.exit(1);
}

const built = mkdtempSync(join(tmpdir(), "seeker-vault-integration-"));
try {
  console.log("Building the gateway, both templates and their CLIs…\n");
  for (const [module, command] of [
    [BROADCAST, "broadcast"],
    [BROADCAST, "broadcastctl"],
    [PUBLISHER, "copytrading"],
    [PUBLISHER, "prediction"],
    [PUBLISHER, "publishctl"],
  ]) {
    execFileSync(
      "go",
      ["build", "-o", join(built, command), `./cmd/${command}`],
      { cwd: module, stdio: "inherit" },
    );
  }

  const binaries = {
    SEEKERVAULT_BROADCAST: join(built, "broadcast"),
    SEEKERVAULT_BROADCASTCTL: join(built, "broadcastctl"),
    SEEKERVAULT_COPYTRADING: join(built, "copytrading"),
    SEEKERVAULT_PREDICTION: join(built, "prediction"),
    SEEKERVAULT_PUBLISHCTL: join(built, "publishctl"),
  };

  const broker = process.env.SEEKERVAULT_CENTRIFUGO ?? "";
  console.log(
    broker === ""
      ? "\nNo broker: set SEEKERVAULT_CENTRIFUGO to the pinned binary to run the stream leg.\n"
      : `\nThe stream leg runs against ${broker}.\n`,
  );

  legs.push(
    leg(
      "the cross-component run (gateway, both templates, two devices, the sidecar and the agent)",
      () =>
        node(["test-agent/src/stage71.acceptance.ts"], {
          ...binaries,
          ...(broker === "" ? {} : { SEEKERVAULT_CENTRIFUGO: broker }),
        }),
    ),
  );
  legs.push(
    leg("the direct-mode acceptance suites, unchanged", () =>
      node([
        "test-agent/src/stage2.acceptance.ts",
        "test-agent/src/stage4.acceptance.ts",
      ]),
    ),
  );
  legs.push(
    broker === ""
      ? notRun(
          "the stream, against the pinned broker",
          "SEEKERVAULT_CENTRIFUGO is not set",
        )
      : {
          name: "the stream, against the pinned broker",
          state: "PASS",
          detail: "in the run above",
        },
  );
  legs.push(android());
} finally {
  rmSync(built, { recursive: true, force: true });
}

console.log("\nStage 7.1 integration (SEE-98)\n");
for (const { name, state, detail } of legs) {
  console.log(
    `  ${state.padEnd(7)} ${name}${detail === undefined ? "" : ` — ${detail}`}`,
  );
}
console.log("");
process.exitCode = legs.some(({ state }) => state === "FAIL") ? 1 : 0;

/** Runs one leg and records what happened, without stopping the others. */
function leg(name, run) {
  console.log(`\n=== ${name}\n`);
  const status = run();
  return status === 0
    ? { name, state: "PASS" }
    : { name, state: "FAIL", detail: `exit ${status}` };
}

function notRun(name, detail) {
  return { name, state: "NOT RUN", detail };
}

/** `node --test` over the acceptance files named, with the binaries this run built. */
function node(files, environment = {}) {
  const run = spawnSync(
    process.execPath,
    ["--test", "--test-reporter=spec", ...files],
    { cwd: ROOT, stdio: "inherit", env: { ...process.env, ...environment } },
  );
  return run.status ?? 1;
}

/** The phone's cross-component cases, when this machine has an SDK to build them with. */
function android() {
  const name = "the phone's cross-component cases";
  if (values["no-android"] === true) return notRun(name, "--no-android");
  const sdk =
    process.env.ANDROID_HOME ??
    process.env.ANDROID_SDK_ROOT ??
    (existsSync(join(ROOT, "android", "local.properties"))
      ? "local.properties"
      : undefined);
  if (sdk === undefined) {
    return notRun(
      name,
      "no Android SDK: set ANDROID_HOME, or run `pnpm check:android` on a machine that has one",
    );
  }
  return leg(name, () => {
    const run = spawnSync(
      join(ROOT, "android", "gradlew"),
      [
        "-p",
        "android",
        ":app:testDebugUnitTest",
        ...ANDROID_TESTS.flatMap((tests) => ["--tests", tests]),
        // The phone's own opt-in stream test, when this machine has the two binaries for it.
        ...(process.env.SEEKERVAULT_CENTRIFUGO === undefined
          ? []
          : [`-Dseekervault.centrifugo=${process.env.SEEKERVAULT_CENTRIFUGO}`]),
        ...(process.env.SEEKERVAULT_REDIS === undefined
          ? []
          : [`-Dseekervault.redis=${process.env.SEEKERVAULT_REDIS}`]),
      ],
      { cwd: ROOT, stdio: "inherit" },
    );
    return run.status ?? 1;
  });
}
