// `pnpm test:hello`: the Stage 1 acceptance checks (docs/testing/stage-1.md).
//
//   pnpm test:hello            The acceptance suite on a simulated device: the real CLI, the sidecar
//                              as a real process, and the sidecar's Connect test client as the phone.
//   pnpm test:hello --device   The round trip on the one attached device or emulator: a sidecar with
//                              throwaway tokens, `adb reverse`, the app's instrumentation UI test,
//                              which taps OK, and the CLI sending the text over MCP.
//
// Only a run on the physical Seeker counts as the device check; an emulator run says so.
import { spawn, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

import {
  freePort,
  startSidecarProcess,
} from "../mcp-server/src/testing/process.ts";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const TEXT = "Hello Seeker — device check 👋";
const DEADLINE_SECONDS = 120;

const { values } = parseArgs({ options: { device: { type: "boolean" } } });
process.exitCode = values.device === true ? await device() : simulated();

function simulated() {
  console.log(
    "Stage 1 acceptance on a simulated device: the sidecar's Connect test client acts as the phone.\n" +
      "It never counts as the physical Seeker check (docs/testing/stage-1.md).\n",
  );
  const run = spawnSync(
    process.execPath,
    ["--test", "--test-reporter=spec", "test-agent/src/stage1.acceptance.ts"],
    { cwd: ROOT, stdio: "inherit" },
  );
  return run.status ?? 1;
}

function fail(code, message) {
  console.error(`test:hello --device: ${message}`);
  return code;
}

/** `adb` from PATH, or from the SDK in ANDROID_HOME. */
function findAdb() {
  if (spawnSync("adb", ["version"]).error === undefined) return "adb";
  const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
  return sdk === undefined ? undefined : join(sdk, "platform-tools", "adb");
}

/** Runs a command to completion and collects its output. */
function run(command, args, options) {
  return new Promise((resolve) => {
    const child = spawn(command, args, options);
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8").on("data", (chunk) => (stdout += chunk));
    child.stderr.setEncoding("utf8").on("data", (chunk) => (stderr += chunk));
    child.on("close", (code) => resolve({ code, stdout, stderr }));
  });
}

async function device() {
  const adbPath = findAdb();
  if (adbPath === undefined) {
    return fail(
      2,
      "adb not found; add $ANDROID_HOME/platform-tools to PATH (docs/guides/macbook-seeker-quickstart.md)",
    );
  }
  const adb = (...args) => spawnSync(adbPath, args, { encoding: "utf8" });
  const serials = adb("devices")
    .stdout.split("\n")
    .slice(1)
    .map((line) => line.trim().split(/\s+/))
    .filter(([serial, state]) => serial && state === "device")
    .map(([serial]) => serial);
  const serial =
    process.env.ANDROID_SERIAL ??
    (serials.length === 1 ? serials[0] : undefined);
  if (serial === undefined) {
    return fail(
      2,
      serials.length === 0
        ? "no device; connect and authorize one, then check adb devices -l"
        : `${serials.length} devices; set ANDROID_SERIAL to pick one`,
    );
  }
  const prop = (name) =>
    adb("-s", serial, "shell", "getprop", name).stdout.trim();
  const emulator =
    prop("ro.kernel.qemu") === "1" || prop("ro.boot.qemu") === "1";
  // Only the Seeker counts as the physical device check. It reports brand "solanamobile" and
  // model "Seeker" (manufacturer "Solana Mobile Inc.").
  const seeker =
    !emulator &&
    prop("ro.product.brand") === "solanamobile" &&
    prop("ro.product.model") === "Seeker";
  const kind = emulator
    ? "an emulator"
    : seeker
      ? "the Seeker"
      : "a phone that isn't a Seeker";
  const target = `${kind} (${prop("ro.product.model")}, Android ${prop("ro.build.version.release")}, API ${prop("ro.build.version.sdk")})`;
  console.log(`Stage 1 round trip on ${target}\n`);

  // Throwaway tokens and a free port: never the developer's .env or a running sidecar.
  const mcpToken = randomBytes(32).toString("hex");
  const phoneToken = randomBytes(32).toString("hex");
  const port = await freePort();
  const sidecar = await startSidecarProcess({
    port,
    mcpToken,
    phoneToken,
    liveCommandTimeoutSeconds: DEADLINE_SECONDS,
  });
  let gradle;
  try {
    const reverse = adb("-s", serial, "reverse", `tcp:${port}`, `tcp:${port}`);
    if (reverse.status !== 0) {
      return fail(1, `adb reverse failed: ${reverse.stderr.trim()}`);
    }
    const argument = (name, value) =>
      `-Pandroid.testInstrumentationRunnerArguments.${name}=${value}`;
    gradle = spawn(
      join(ROOT, "android", "gradlew"),
      [
        "-p",
        join(ROOT, "android"),
        ":app:connectedDebugAndroidTest",
        argument("serverUrl", `http://127.0.0.1:${port}`),
        argument("phoneToken", phoneToken),
        argument("text", Buffer.from(TEXT, "utf8").toString("base64url")),
      ],
      {
        cwd: ROOT,
        env: { ...process.env, ANDROID_SERIAL: serial },
        stdio: ["ignore", "inherit", "inherit"],
      },
    );
    const gradleExit = new Promise((resolve) => gradle.on("close", resolve));

    // Gradle builds and installs the app first; the UI test then connects like the owner would.
    let gradleCode;
    void gradleExit.then((code) => (gradleCode = code));
    while (!sidecar.output().includes("phone connected")) {
      if (gradleCode !== undefined) {
        return fail(
          1,
          `the instrumentation test ended (Gradle exit code ${gradleCode}) before the app connected`,
        );
      }
      await delay(200);
    }

    const agent = await run(
      process.execPath,
      [join(ROOT, "test-agent", "src", "main.ts"), "hello", TEXT],
      {
        cwd: ROOT,
        env: {
          PATH: process.env.PATH ?? "",
          MCP_URL: `${sidecar.url}/mcp`,
          MCP_TOKEN: mcpToken,
          LIVE_COMMAND_TIMEOUT_SECONDS: String(DEADLINE_SECONDS),
        },
      },
    );
    process.stderr.write(agent.stderr);
    if (agent.code !== 0) {
      return fail(1, `the agent exited with ${agent.code}`);
    }
    const acknowledgement = JSON.parse(agent.stdout);
    const code = await gradleExit;
    if (code !== 0) {
      return fail(
        1,
        `the instrumentation test failed (Gradle exit code ${code})`,
      );
    }
    const acknowledged = sidecar
      .output()
      .split("\n")
      .filter((line) =>
        line.endsWith(`command ${acknowledgement.id} acknowledged`),
      ).length;
    if (acknowledgement.result !== "OK" || acknowledged !== 1) {
      return fail(
        1,
        `expected one acknowledgement of ${acknowledgement.id}, the sidecar logged ${acknowledged}`,
      );
    }
    console.log(
      `\nPASS on ${target}: the app showed the exact text, the double tap sent one OK, and the agent printed ${agent.stdout.trim()}`,
    );
    if (!seeker) {
      console.log(
        `This was ${emulator ? "an emulator" : "a phone that isn't a Seeker"}. It doesn't count as the physical Seeker check.`,
      );
    }
    return 0;
  } finally {
    if (gradle !== undefined && gradle.exitCode === null) gradle.kill();
    adb("-s", serial, "reverse", "--remove", `tcp:${port}`);
    await sidecar.stop();
  }
}
