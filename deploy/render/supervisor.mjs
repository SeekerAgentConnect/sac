import { spawn } from "node:child_process";
import { accessSync, constants } from "node:fs";

// Keep deployment settings explicit; never print environment values or tokens.
function validate() {
  const port = process.env.PORT || "10000";
  if (
    !/^\d+$/.test(port) ||
    Number(port) < 1024 ||
    Number(port) > 65535 ||
    Number(port) === 8080
  ) {
    throw new Error("PORT must be 1024-65535 and different from 8080");
  }
  // Render sets RENDER_EXTERNAL_URL to the service's own https://*.onrender.com origin. It is the
  // default public URL, so a first deployment needs no hostname typed in; SIDECAR_PUBLIC_URL still
  // wins when set, which is how a custom domain is configured.
  const publicUrlText =
    process.env.SIDECAR_PUBLIC_URL || process.env.RENDER_EXTERNAL_URL || "";
  if (!publicUrlText) {
    throw new Error(
      "SIDECAR_PUBLIC_URL is not set and Render did not provide RENDER_EXTERNAL_URL",
    );
  }
  let publicUrl;
  try {
    publicUrl = new URL(publicUrlText);
  } catch {
    throw new Error("SIDECAR_PUBLIC_URL is not a URL");
  }
  if (
    publicUrl.protocol !== "https:" ||
    publicUrl.username ||
    publicUrl.password ||
    publicUrl.port ||
    publicUrl.pathname !== "/" ||
    publicUrl.search ||
    publicUrl.hash
  ) {
    throw new Error(
      "SIDECAR_PUBLIC_URL must be an HTTPS origin without credentials, path or port",
    );
  }
  for (const name of [
    "SIDECAR_UPDATE_PORT",
    "SIDECAR_TLS_CERT_PATH",
    "SIDECAR_TLS_KEY_PATH",
  ]) {
    if (process.env[name])
      throw new Error(`${name} is not supported by this HTTP deployment`);
  }
  if (
    process.env.DATABASE_PATH &&
    process.env.DATABASE_PATH !== "/data/sidecar.db"
  ) {
    throw new Error(
      "DATABASE_PATH must be /data/sidecar.db for this deployment",
    );
  }
  accessSync("/data", constants.W_OK);
  Object.assign(process.env, {
    PORT: port,
    SIDECAR_PUBLIC_URL: publicUrl.origin + "/",
    RENDER_PUBLIC_HOST: publicUrl.hostname,
    MCP_ALLOWED_HOSTS: publicUrl.hostname,
    SIDECAR_HOST: "127.0.0.1",
    SIDECAR_PORT: "8080",
    DATABASE_PATH: "/data/sidecar.db",
  });
}

try {
  validate();
} catch (error) {
  // The messages above name a variable, never its value; a missing /data reports the path only.
  const reason = error instanceof Error ? error.message : String(error);
  console.error(
    `[render] Invalid deployment configuration: ${reason}. Check deploy/render/README.md.`,
  );
  process.exit(1);
}

const children = new Set();
let stopping = false;
let exitCode = 0;
let deadline;

function finishIfStopped() {
  if (stopping && children.size === 0) {
    clearTimeout(deadline);
    process.exit(exitCode);
  }
}

function stop(code) {
  if (stopping) return;
  stopping = true;
  exitCode = code;
  // Bound shutdown so a stuck child cannot keep the deployment alive forever.
  deadline = setTimeout(() => {
    for (const child of children) child.kill("SIGKILL");
    process.exit(exitCode || 1);
  }, 25000);
  for (const child of children) child.kill("SIGTERM");
  finishIfStopped();
}

process.on("SIGTERM", () => stop(0));
process.on("SIGINT", () => stop(0));

function start(name, command, args) {
  const child = spawn(command, args, { stdio: "inherit", env: process.env });
  children.add(child);
  child.on("error", () => {
    console.error(`[render] Could not start ${name}`);
    children.delete(child);
    stop(1);
    finishIfStopped();
  });
  child.on("exit", () => {
    children.delete(child);
    if (!stopping) {
      console.error(`[render] ${name} exited; stopping the service`);
      stop(1);
    }
    finishIfStopped();
  });
}

start("sidecar", process.execPath, ["sidecar/dist/main.js"]);
start("gateway", "caddy", [
  "run",
  "--config",
  "/etc/caddy/Caddyfile",
  "--adapter",
  "caddyfile",
]);
