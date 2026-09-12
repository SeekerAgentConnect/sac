/**
 * The agent's own check of a message signature. It's deliberately self-contained: an agent that
 * asked for a signature shouldn't have to take the sidecar's word that it's good, so nothing here
 * is shared with the sidecar's code. A COMPLETED sign_message request carries the wallet, the
 * exact bytes that were signed, and the signature, which is everything this needs.
 */
import { createPublicKey, verify } from "node:crypto";

const BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
// RFC 8410's SPKI wrapper for a raw Ed25519 public key; the 32 key bytes follow it.
const SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");
const PUBLIC_KEY_BYTES = 32;
const SIGNATURE_BYTES = 64;

/** Decodes a base58 address or signature, or returns undefined for anything else. */
export function decodeBase58(text: string): Buffer | undefined {
  if (text === "") return undefined;
  let value = 0n;
  for (const char of text) {
    const digit = BASE58.indexOf(char);
    if (digit < 0) return undefined;
    value = value * 58n + BigInt(digit);
  }
  const bytes: number[] = [];
  for (; value > 0n; value >>= 8n) bytes.unshift(Number(value & 0xffn));
  for (let i = 0; text[i] === "1"; i++) bytes.unshift(0);
  return Buffer.from(bytes);
}

/**
 * Whether `wallet` signed exactly `message`. `signature` is base58 and `message` base64, as the
 * request view gives them. It never throws: anything malformed is simply not verified.
 */
export function verifyMessageSignature(
  wallet: string,
  messageBase64: string,
  signature: string,
): boolean {
  const key = decodeBase58(wallet);
  const bytes = decodeBase58(signature);
  if (key?.length !== PUBLIC_KEY_BYTES || bytes?.length !== SIGNATURE_BYTES) {
    return false;
  }
  let message: Buffer;
  try {
    message = Buffer.from(messageBase64, "base64");
    if (message.toString("base64") !== messageBase64) return false;
  } catch {
    return false;
  }
  try {
    const publicKey = createPublicKey({
      key: Buffer.concat([SPKI_PREFIX, key]),
      format: "der",
      type: "spki",
    });
    return verify(null, message, publicKey, bytes);
  } catch {
    return false;
  }
}
