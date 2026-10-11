import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

import { isApprovedMessage } from "./approved-message.ts";

// A real SKR stake whose wallet added a compute budget and Lighthouse assertions before sending it.
const fixture = JSON.parse(
  readFileSync(
    new URL(
      "../../../../fixtures/transactions/wallet-added.json",
      import.meta.url,
    ),
    "utf8",
  ),
) as { approvedMessage: string; onChainTransaction: string };

const approved = Buffer.from(fixture.approvedMessage, "base64");
const transaction = Buffer.from(fixture.onChainTransaction, "base64");
// One signature: its count byte, then 64 bytes.
const sent = transaction.subarray(1 + 64 * transaction[0]!);

describe("isApprovedMessage", () => {
  it("takes what the wallet added of its own as the approved message", () => {
    assert.equal(approved.equals(sent), false);
    assert.equal(isApprovedMessage(approved, sent), true);
    assert.equal(isApprovedMessage(approved, approved), true);
  });

  it("refuses any other change", () => {
    // The stake's amount, 1 SKR in base units, changed by one unit.
    const amount = Buffer.from([0x40, 0x42, 0x0f, 0, 0, 0, 0, 0]);
    const at = sent.indexOf(amount);
    assert.ok(at > 0);
    const changed = Buffer.from(sent);
    changed[at] = 0x41;
    assert.equal(isApprovedMessage(approved, changed), false);

    // One more account read-only, which moves writability.
    const header = Buffer.from(sent);
    header[3] = header[3]! + 1;
    assert.equal(isApprovedMessage(approved, header), false);

    // An approved instruction the chain's copy does not carry.
    assert.equal(
      isApprovedMessage(sent, approved.subarray(0, approved.length - 1)),
      false,
    );
    assert.equal(isApprovedMessage(approved, Buffer.from([1, 2, 3])), false);
  });
});
