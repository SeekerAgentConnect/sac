import {
  ConfigError,
  loadSidecarConfig,
  type SidecarConfig,
} from "./config.ts";
import { startSidecar } from "./server.ts";

let config: SidecarConfig;
try {
  config = loadSidecarConfig(process.env);
} catch (error) {
  if (!(error instanceof ConfigError)) throw error;
  console.error(error.message);
  console.error(
    "Copy .env.example to .env and fill it in, or set the variables in the environment.",
  );
  process.exit(1);
}

const sidecar = await startSidecar(config).catch((error: unknown) => {
  console.error(
    `[sidecar] could not start: ${error instanceof Error ? error.message : String(error)}`,
  );
  process.exit(1);
});

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => {
    console.log(
      `[sidecar] ${signal}: shutting down; the in-flight command, if any, is cancelled`,
    );
    void sidecar.close().then(() => process.exit(0));
  });
}
