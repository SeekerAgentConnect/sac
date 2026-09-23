/**
 * The optional stable env file, read before the environment is inspected.
 *
 * It never replaces a variable the process already has: a container's environment, or a value an
 * operator exported for one run, wins over a file written once and forgotten.
 */
import { existsSync } from "node:fs";
import { isAbsolute, join, resolve } from "node:path";
import { loadEnvFile } from "node:process";

import { DEFAULT_DATA_DIRECTORY } from "../config.ts";

export function loadConfigFile(
  explicitPath: string | undefined,
  env: NodeJS.ProcessEnv = process.env,
): string | undefined {
  const configured = explicitPath ?? env.SKR_STAKING_CONFIG?.trim();
  if (configured !== undefined && configured !== "") {
    if (explicitPath === undefined && !isAbsolute(configured)) {
      throw new Error("SKR_STAKING_CONFIG must be an absolute path.");
    }
    const path = resolve(configured);
    loadEnvFile(path);
    return path;
  }

  const dataDirectory = env.SKR_STAKING_DATA_DIR?.trim();
  if (
    dataDirectory !== undefined &&
    dataDirectory !== "" &&
    !isAbsolute(dataDirectory)
  ) {
    throw new Error("SKR_STAKING_DATA_DIR must be an absolute path.");
  }
  const path = join(dataDirectory || DEFAULT_DATA_DIRECTORY, "config.env");
  if (!existsSync(path)) return undefined;
  loadEnvFile(path);
  return path;
}
