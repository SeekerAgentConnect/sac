/**
 * The operator's pairing commands for this server:
 *
 *   seeker-skr-staking-mcp pair           shows a one-use pairing code
 *   seeker-skr-staking-mcp pair status    shows the paired phone
 *   seeker-skr-staking-mcp pair revoke    revokes the paired phone
 *
 * They work on this server's own database, whether or not it is running, and on nothing else: the
 * general MCP server's pairing is a separate database and a separate connection on the phone. A
 * phone paired here sees a second connection, with its own name, its own source label and its own
 * rules, which is what makes "an independent server" mean something to the owner.
 *
 * The pairing code is the one place a token is printed, because printing it is how pairing works.
 */
import { renderUnicodeCompact } from "uqr";
import { openDirectServer, type PairedPhone } from "@seeker-vault/server-sdk";

import {
  ConfigError,
  UNUSED_LIVE_COMMAND_TIMEOUT_SECONDS,
  loadConfig,
} from "../config.ts";

const USAGE = `Usage: seeker-skr-staking-mcp pair [status | revoke]

  seeker-skr-staking-mcp pair           Shows a one-use pairing code as a QR and URI.
  seeker-skr-staking-mcp pair status    Shows the paired phone.
  seeker-skr-staking-mcp pair revoke    Revokes it and cancels its pending requests.`;

export async function runPairingCommand(
  args: readonly string[],
): Promise<number> {
  const [command = "", ...rest] = args;
  if (rest.length > 0 || !["", "status", "revoke"].includes(command)) {
    console.error(USAGE);
    return 2;
  }
  let config;
  try {
    config = loadConfig(process.env);
  } catch (error) {
    if (!(error instanceof ConfigError)) throw error;
    console.error(error.message);
    return 2;
  }
  // No staking provider here: pairing reads and writes this server's own database and reaches no
  // chain. A pairing command that needed an endpoint would be one an operator could not run while
  // their endpoint was down.
  const direct = openDirectServer({
    databasePath: config.databasePath,
    publicOrigin: config.publicUrl ?? "",
    requestTtlSeconds: config.requestTtlSeconds,
    pendingLimit: config.pendingLimit,
    pairingTokenTtlSeconds: config.pairingTokenTtlSeconds,
    liveCommandTimeoutSeconds: UNUSED_LIVE_COMMAND_TIMEOUT_SECONDS,
    log: () => undefined,
  });
  try {
    const phone = direct.pairing.active();
    if (command === "status") {
      console.log(
        phone === undefined
          ? "No phone is paired with the staking server. Run seeker-skr-staking-mcp pair to pair one."
          : `Paired phone: ${describe(phone)}.`,
      );
      return 0;
    }
    if (command === "revoke") {
      if (phone === undefined) {
        console.log("No phone is paired with the staking server.");
        return 0;
      }
      const { cancelled } = direct.pairing.revoke(phone.connectionId);
      console.log(
        `Revoked ${describe(phone)}. Its credential no longer works, and ${cancelled} pending requests were cancelled.`,
      );
      return 0;
    }

    const issued = direct.pairing.issue();
    const minutes = Math.round(config.pairingTokenTtlSeconds / 60);
    const lines = [
      `Scan this with Seeker Agent Connect on the phone to pair it with the SKR staking server at ${issued.serverUrl}.`,
      `The code works once, until ${new Date(issued.expiresAtMs).toTimeString().slice(0, 8)} (${minutes} minutes).`,
      "",
      renderUnicodeCompact(issued.uri, { ecc: "M", border: 2 }),
      "",
      "Or enter the code by hand:",
      issued.uri,
      "",
    ];
    if (new URL(issued.serverUrl).protocol === "http:") {
      lines.push(
        "This is a cleartext URL, for a phone connected over adb reverse. For a phone on another",
        "network, put an HTTPS endpoint in SKR_STAKING_PUBLIC_URL and terminate TLS in front of this",
        "server (docs/development/skr-staking-server.md).",
        "",
      );
    }
    if (issued.replaces !== undefined) {
      lines.push(
        `Connecting a phone with this link will disconnect the previously paired phone (${describe(issued.replaces)}) and cancel its pending requests. Creating or opening this link does not disconnect it.`,
        "",
      );
    }
    lines.push(
      "Keep the code private: whoever pairs with it first becomes the paired phone.",
    );
    console.log(lines.join("\n"));
    return 0;
  } finally {
    await direct.close();
  }
}

function describe(phone: PairedPhone): string {
  const name =
    phone.deviceName === "" ? "" : ` ("${printable(phone.deviceName)}")`;
  return `connection ${phone.connectionId}${name}, paired ${new Date(phone.pairedAtMs).toISOString()}`;
}

/**
 * The phone chooses its own name, so it is printed with backslashes, control characters, format
 * characters and line separators escaped. It cannot start a line of its own or send the terminal an
 * escape sequence.
 */
function printable(text: string): string {
  return text.replace(/[\\\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, (character) =>
    character === "\\"
      ? "\\\\"
      : `\\u{${(character.codePointAt(0) ?? 0).toString(16)}}`,
  );
}
