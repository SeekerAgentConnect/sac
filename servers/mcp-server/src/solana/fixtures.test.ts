/**
 * The committed transfer fixtures are the ones this code builds now
 * (docs/testing/transaction-fixtures.md). If they drift, the phone's parser is being checked
 * against transactions the sidecar no longer produces, which is worse than not checking it at all.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

import {
  FIXTURE_PATH,
  serializeFixtures,
  transactionFixtures,
} from "../testing/transaction-fixtures.ts";

const COMMITTED = fileURLToPath(
  new URL("../../../fixtures/transactions/cases.json", import.meta.url),
);

describe("the shared transfer fixtures", () => {
  it("match what the builder produces now", async () => {
    const built = serializeFixtures(await transactionFixtures());
    assert.equal(
      readFileSync(COMMITTED, "utf8"),
      built,
      `${FIXTURE_PATH} is stale; run \`node mcp-server/src/testing/transaction-fixtures.ts\``,
    );
  });

  it("cover a sound transfer, a tampered one, and one that can't be read", async () => {
    const { cases } = await transactionFixtures();
    const verdicts = new Set(cases.map((entry) => entry.verdict));
    assert.deepEqual([...verdicts].sort(), [
      "invalid",
      "unverified",
      "verified",
    ]);
    assert.ok(cases.length >= 20, "every case is built");
    assert.equal(
      new Set(cases.map((entry) => entry.name)).size,
      cases.length,
      "each case has its own name",
    );
    // A tampered case must be a real transaction, not a broken one: the point is that the phone
    // refuses a valid transaction that isn't the one the owner was asked to approve.
    const tampered = cases.find((entry) => entry.name === "changed_recipient");
    assert.ok(tampered !== undefined);
    assert.equal(tampered.verdict, "invalid");
    assert.ok(
      Buffer.from(tampered.transaction, "base64").length > 100,
      "it is a whole transaction",
    );
  });

  it("record each finding the phone must reach, sorted", async () => {
    const { cases } = await transactionFixtures();
    for (const entry of cases) {
      assert.deepEqual(
        [...entry.findings],
        [...entry.findings].sort(),
        `${entry.name}'s findings are sorted, so the phone can compare them directly`,
      );
    }
    const findings = new Set(cases.flatMap((entry) => entry.findings));
    for (const expected of [
      "AmountMismatch",
      "RecipientMismatch",
      "MintMismatch",
      "ExtraSigner",
      "ExtraTransfer",
      "UnreadableValueInstruction",
      "UnrecognizedInstruction",
      "AlreadySigned",
      "Malformed",
      "HashMismatch",
    ]) {
      assert.ok(findings.has(expected), `a case reaches ${expected}`);
    }
  });
});
