/**
 * Ed25519 verification (SAW-016). The known-answer vectors come from RFC 8032 §7.1, so the checks
 * don't depend on anything this repository produced; the round trip afterwards signs with a key
 * pair made in the test, which is the only place a key is ever created.
 */
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { describe, it } from "node:test";

import { encodeBase58 } from "./action.ts";
import { SIGNATURE_BYTES, verifySignature } from "./signature.ts";

/** RFC 8032 §7.1: the public key, the message, and the signature, all in hex. */
const VECTORS: readonly {
  readonly name: string;
  readonly publicKey: string;
  readonly message: string;
  readonly signature: string;
}[] = [
  {
    name: "TEST 1 (empty message)",
    publicKey:
      "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
    message: "",
    signature:
      "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
  },
  {
    name: "TEST 2 (one byte)",
    publicKey:
      "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
    message: "72",
    signature:
      "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
  },
  {
    name: "TEST 3 (two bytes)",
    publicKey:
      "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
    message: "af82",
    signature:
      "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
  },
];

function bytes(hex: string): Uint8Array {
  return Uint8Array.from(Buffer.from(hex, "hex"));
}

/** The wallet address for a raw public key, the way a Solana address is written. */
function address(publicKeyHex: string): string {
  return encodeBase58(bytes(publicKeyHex));
}

/** A copy of `data` with one bit of the given byte flipped. */
function tamper(data: Uint8Array, index: number): Uint8Array {
  const copy = Uint8Array.from(data);
  copy[index] = (copy[index] ?? 0) ^ 0x01;
  return copy;
}

describe("verifySignature", () => {
  for (const vector of VECTORS) {
    it(`accepts RFC 8032 ${vector.name}`, () => {
      assert.equal(
        verifySignature(
          address(vector.publicKey),
          bytes(vector.message),
          bytes(vector.signature),
        ),
        true,
      );
    });

    it(`rejects a tampered signature for ${vector.name}`, () => {
      assert.equal(
        verifySignature(
          address(vector.publicKey),
          bytes(vector.message),
          tamper(bytes(vector.signature), 0),
        ),
        false,
      );
    });

    it(`rejects another wallet's key for ${vector.name}`, () => {
      const other = VECTORS.find((v) => v !== vector)?.publicKey ?? "";
      assert.equal(
        verifySignature(
          address(other),
          bytes(vector.message),
          bytes(vector.signature),
        ),
        false,
      );
    });
  }

  it("rejects a tampered message", () => {
    const vector = VECTORS[2];
    assert.ok(vector !== undefined);
    assert.equal(
      verifySignature(
        address(vector.publicKey),
        tamper(bytes(vector.message), 0),
        bytes(vector.signature),
      ),
      false,
    );
  });

  it("rejects a message with a byte appended", () => {
    const vector = VECTORS[1];
    assert.ok(vector !== undefined);
    assert.equal(
      verifySignature(
        address(vector.publicKey),
        Uint8Array.from([...bytes(vector.message), 0]),
        bytes(vector.signature),
      ),
      false,
    );
  });

  it("verifies a signature made here, over the exact bytes", () => {
    // The only key pair in the repository, and it exists for the length of this test.
    const { privateKey, publicKey } = generateKeyPairSync("ed25519");
    const raw = publicKey.export({ format: "jwk" }).x ?? "";
    const wallet = encodeBase58(Uint8Array.from(Buffer.from(raw, "base64url")));
    const message = new TextEncoder().encode(
      "Sign in to example.com\nNonce: 7",
    );
    const signature = Uint8Array.from(sign(null, message, privateKey));
    assert.equal(signature.length, SIGNATURE_BYTES);
    assert.equal(verifySignature(wallet, message, signature), true);
    assert.equal(verifySignature(wallet, tamper(message, 3), signature), false);
    assert.equal(
      verifySignature(wallet, message, tamper(signature, 63)),
      false,
    );
  });

  it("refuses anything that isn't a wallet and a 64-byte signature", () => {
    const vector = VECTORS[1];
    assert.ok(vector !== undefined);
    const message = bytes(vector.message);
    const signature = bytes(vector.signature);
    const wallet = address(vector.publicKey);
    assert.equal(verifySignature("", message, signature), false);
    assert.equal(
      verifySignature("not base58 at all: 0OIl", message, signature),
      false,
    );
    // A base58 string that decodes to the wrong number of bytes.
    assert.equal(verifySignature("abcdef", message, signature), false);
    assert.equal(
      verifySignature(wallet, message, signature.slice(0, 63)),
      false,
    );
    assert.equal(
      verifySignature(wallet, message, Uint8Array.from([...signature, 0])),
      false,
    );
  });
});
