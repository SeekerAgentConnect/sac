import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

/** A path for a new SDK database, in a new temporary directory. */
export function temporaryDatabasePath(): string {
  return join(mkdtempSync(join(tmpdir(), "seeker-vault-sdk-")), "server.db");
}
