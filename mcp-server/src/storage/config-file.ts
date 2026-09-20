import { existsSync } from "node:fs";
import { isAbsolute, join, resolve } from "node:path";
import { loadEnvFile } from "node:process";

import { DEFAULT_DATA_DIRECTORY } from "../config.ts";

/** Loads an optional stable env file without replacing variables already in the process. */
export function loadConfigFile(
  explicitPath: string | undefined,
  env: NodeJS.ProcessEnv = process.env,
): string | undefined {
  const configured = explicitPath ?? env.MCP_SERVER_CONFIG?.trim();
  if (configured !== undefined && configured !== "") {
    if (explicitPath === undefined && !isAbsolute(configured)) {
      throw new Error("MCP_SERVER_CONFIG must be an absolute path.");
    }
    const path = resolve(configured);
    loadEnvFile(path);
    return path;
  }

  const dataDirectory = env.MCP_SERVER_DATA_DIR?.trim();
  if (
    dataDirectory !== undefined &&
    dataDirectory !== "" &&
    !isAbsolute(dataDirectory)
  ) {
    throw new Error("MCP_SERVER_DATA_DIR must be an absolute path.");
  }
  const path = join(dataDirectory || DEFAULT_DATA_DIRECTORY, "config.env");
  if (!existsSync(path)) return undefined;
  loadEnvFile(path);
  return path;
}
