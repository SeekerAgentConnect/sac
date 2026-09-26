/**
 * Telling the approved transaction from anything else by its bytes (SAW-022). A wallet fills in
 * the signatures, so the comparison is over the message: everything the signatures cover.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { isApprovedTransaction, transactionMessage } from "./confirmation.ts";

/** A wire transaction with `signatures` slots of `fill`, then `message`. */
function wire(signatures: number, message: Uint8Array, fill = 0): Uint8Array {
  const bytes = new Uint8Array(1 + signatures * 64 + message.length);
  bytes[0] = signatures;
  bytes.fill(fill, 1, 1 + signatures * 64);
  bytes.set(message, 1 + signatures * 64);
  return bytes;
}

const MESSAGE = Uint8Array.from({ length: 40 }, (_, index) => index + 1);

describe("comparing a transaction with the approved one", () => {
  it("reads the message out from after the signatures", () => {
    assert.deepEqual(transactionMessage(wire(1, MESSAGE)), MESSAGE);
    assert.deepEqual(transactionMessage(wire(3, MESSAGE)), MESSAGE);
  });

  it("calls the same transaction a match once the wallet has signed it", () => {
    // The approved bytes carry empty slots and the chain's carry a real signature. Same
    // transaction; only the part no one approved differs.
    const approved = wire(1, MESSAGE);
    const signed = wire(1, MESSAGE, 0xab);
    assert.notDeepEqual(approved, signed);
    assert.equal(isApprovedTransaction(approved, signed), true);
  });

  it("calls a different message no match, however small the difference", () => {
    const changed = Uint8Array.from(MESSAGE);
    changed[changed.length - 1] = 0;
    assert.equal(
      isApprovedTransaction(wire(1, MESSAGE), wire(1, changed, 0xab)),
      false,
    );
  });

  it("calls bytes it can't take apart no match, rather than guessing", () => {
    for (const bytes of [
      new Uint8Array(),
      Uint8Array.of(0),
      Uint8Array.of(1), // a signature count with no signature after it
      wire(1, new Uint8Array()), // signatures and no message
      Uint8Array.of(0xff, 0xff, 0xff, 0xff), // a compact-u16 that never ends
    ]) {
      assert.equal(transactionMessage(bytes), undefined);
      assert.equal(isApprovedTransaction(wire(1, MESSAGE), bytes), false);
      assert.equal(isApprovedTransaction(bytes, wire(1, MESSAGE)), false);
    }
  });
});
