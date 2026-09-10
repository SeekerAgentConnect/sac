import { ConfigError, loadSidecarConfig } from "./config.ts";

try {
  const config = loadSidecarConfig(process.env);
  console.log(
    `Sidecar configuration is valid: ${config.host}:${config.port}, ` +
      `live command timeout ${config.liveCommandTimeoutSeconds}s, MCP and phone tokens set.`,
  );
  console.log(
    "No endpoints are served yet: the Connect API and the MCP endpoint land in SAW-003.",
  );
} catch (error) {
  if (!(error instanceof ConfigError)) throw error;
  console.error(error.message);
  console.error(
    "Copy .env.example to .env and fill it in, or set the variables in the environment.",
  );
  process.exitCode = 1;
}
