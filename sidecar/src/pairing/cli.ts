/**
 * The operator's pairing commands (docs/security.md), run with the sidecar's `.env`:
 *
 *   pnpm pair           shows a one-use pairing code, as a QR code and as text
 *   pnpm pair status    shows the paired phone
 *   pnpm pair revoke    revokes the paired phone's connection
 *
 * They work on the sidecar's database directly, whether or not the sidecar is running. The
 * pairing code is the one place a token is shown, because showing it is how pairing works.
 */
import { renderUnicodeCompact } from "uqr";
import { openDirectServer, type PairedPhone } from "@seeker-vault/server-sdk";

import { ConfigError, loadSidecarConfig } from "../config.ts";

const USAGE = `Usage: pnpm pair [status | revoke]

  pnpm pair           Shows a one-use pairing code for the phone, as a QR code and as text.
  pnpm pair status    Shows the paired phone.
  pnpm pair revoke    Revokes the paired phone's connection, and cancels its pending requests.`;

async function main(args: readonly string[]): Promise<number> {
  const [command = "", ...rest] = args;
  if (rest.length > 0 || !["", "status", "revoke"].includes(command)) {
    console.error(USAGE);
    return 2;
  }
  let config;
  try {
    config = loadSidecarConfig(process.env);
  } catch (error) {
    if (!(error instanceof ConfigError)) throw error;
    console.error(error.message);
    return 2;
  }
  const direct = openDirectServer({
    databasePath: config.databasePath,
    publicOrigin: config.publicUrl,
    requestTtlSeconds: config.requestTtlSeconds,
    pendingLimit: config.pendingLimit,
    pairingTokenTtlSeconds: config.pairingTokenTtlSeconds,
    liveCommandTimeoutSeconds: config.liveCommandTimeoutSeconds,
    log: () => undefined,
  });
  try {
    const phone = direct.pairing.active();
    if (command === "status") {
      console.log(
        phone === undefined
          ? "No phone is paired. Run pnpm pair to pair one."
          : `Paired phone: ${describe(phone)}.`,
      );
      return 0;
    }
    if (command === "revoke") {
      if (phone === undefined) {
        console.log("No phone is paired.");
        return 0;
      }
      const { cancelled } = direct.pairing.revoke(phone.connectionId);
      console.log(
        `Revoked ${describe(phone)}. Its credential no longer works, and ${cancelled} pending requests were cancelled.`,
      );
      return 0;
    }

    const issued = direct.pairing.issue();
    const uri = issued.uri;
    const minutes = Math.round(config.pairingTokenTtlSeconds / 60);
    const lines = [
      `Scan this with Seeker Agent Connect on the phone to pair it with ${issued.serverUrl}.`,
      `The code works once, until ${new Date(issued.expiresAtMs).toTimeString().slice(0, 8)} (${minutes} minutes).`,
      "",
      renderUnicodeCompact(uri, { ecc: "M", border: 2 }),
      "",
      "Or enter the code by hand:",
      uri,
      "",
    ];
    if (new URL(issued.serverUrl).protocol === "http:") {
      lines.push(
        "This is the loopback development URL, for a phone connected over adb reverse. For a phone on",
        "another network, set SIDECAR_PUBLIC_URL to an HTTPS endpoint (docs/security.md).",
      );
    }
    if (issued.replaces !== undefined) {
      lines.push(
        `Pairing revokes the phone paired now: ${describe(issued.replaces)}.`,
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
 * The phone chooses its own name, so it's printed with backslashes, control characters, format
 * characters, and line separators escaped. It can't start a line of its own or send the terminal an
 * escape sequence.
 */
function printable(text: string): string {
  return text.replace(/[\\\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, (character) =>
    character === "\\"
      ? "\\\\"
      : `\\u{${(character.codePointAt(0) ?? 0).toString(16)}}`,
  );
}

process.exitCode = await main(process.argv.slice(2));
