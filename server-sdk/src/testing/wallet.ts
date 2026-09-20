/**
 * A throwaway wallet for the tests: a key pair made in the test process, which stands in for the
 * wallet app on the owner's phone. It exists only so a test can produce a real Ed25519 signature
 * the sidecar will accept. Nothing here ships: the sidecar creates no keys and signs nothing
 * (stage-boundary.test.ts).
 */
import { generateKeyPairSync, sign, type KeyObject } from "node:crypto";

import { encodeBase58 } from "../requests/action.ts";

export interface TestWallet {
  /** The base58 address, as the protocol and the phone write it. */
  readonly address: string;
  /** The 64-byte Ed25519 signature of exactly these bytes. */
  sign(message: Uint8Array | string): Uint8Array;
}

/** A new wallet, different on every call. */
export function testWallet(): TestWallet {
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  return {
    address: addressOf(publicKey),
    sign: (message) =>
      Uint8Array.from(
        sign(
          null,
          typeof message === "string"
            ? new TextEncoder().encode(message)
            : message,
          privateKey,
        ),
      ),
  };
}

function addressOf(publicKey: KeyObject): string {
  const raw = publicKey.export({ format: "jwk" }).x ?? "";
  return encodeBase58(Uint8Array.from(Buffer.from(raw, "base64url")));
}
