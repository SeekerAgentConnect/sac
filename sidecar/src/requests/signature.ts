/**
 * Ed25519 signature checking (docs/protocol.md#message-results). The sidecar verifies what the
 * wallet signed, and nothing more: it holds no key, it never signs, and it never asks anyone to.
 * A Solana address is an Ed25519 public key written in base58, so a signature is checked against
 * the address the request already names.
 */
import { createPublicKey, verify } from "node:crypto";

import { decodeBase58 } from "./action.ts";

/** An Ed25519 signature is 64 bytes, and its public key 32. */
export const SIGNATURE_BYTES = 64;
const PUBLIC_KEY_BYTES = 32;

/**
 * The SPKI header for an Ed25519 public key (RFC 8410): SEQUENCE { SEQUENCE { OID 1.3.101.112 },
 * BIT STRING }. Node reads raw keys only in this wrapper, so the 32 key bytes are appended to it.
 */
const SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");

/**
 * Whether `signature` is `wallet`'s Ed25519 signature over exactly `message`. It returns false for
 * an address that isn't a public key, a signature of the wrong size, or bytes the key doesn't
 * verify; it never throws, so a malformed result from a phone is simply refused.
 */
export function verifySignature(
  wallet: string,
  message: Uint8Array,
  signature: Uint8Array,
): boolean {
  if (signature.length !== SIGNATURE_BYTES) return false;
  const key = decodeBase58(wallet);
  if (key === undefined || key.length !== PUBLIC_KEY_BYTES) return false;
  try {
    const publicKey = createPublicKey({
      key: Buffer.concat([SPKI_PREFIX, key]),
      format: "der",
      type: "spki",
    });
    // Ed25519 hashes the message itself, so the algorithm argument is null.
    return verify(null, message, publicKey, signature);
  } catch {
    // A key the curve rejects, for example: not a signature this wallet made.
    return false;
  }
}
