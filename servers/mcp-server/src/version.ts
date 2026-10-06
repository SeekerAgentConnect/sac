/**
 * This server's released version, in one place.
 *
 * Two things report it and they used to disagree: the CLI's `--version`, and the `serverInfo` the
 * MCP endpoint hands every agent that connects. `scripts/check-release.mjs` holds this constant to
 * the version in `release/components.json`, so a release cannot ship a server that misreports
 * which one it is (SEE-168).
 */
export const VERSION = "0.0.1";
