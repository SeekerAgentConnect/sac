import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  AmountError,
  MAX_U64,
  amountForShares,
  cooldownComplete,
  cooldownEndsAt,
  formatSkr,
  parseBaseUnits,
  planUnstake,
  requirePositive,
  sharesForAmount,
} from "./shares.ts";
import { SHARE_PRICE_AT_CAPTURE } from "../testing/accounts.ts";

const PRICE = SHARE_PRICE_AT_CAPTURE;

describe("amounts on the wire", () => {
  it("reads a decimal integer in base units", () => {
    assert.equal(parseBaseUnits("0", "amount"), 0n);
    assert.equal(parseBaseUnits("100000000", "amount"), 100_000_000n);
    assert.equal(parseBaseUnits(MAX_U64.toString(), "amount"), MAX_U64);
  });

  it("refuses everything that is not one", () => {
    for (const bad of [
      "",
      " 1",
      "1 ",
      "+1",
      "-1",
      "1.0",
      "1e6",
      "01",
      "0x10",
      "１",
      "one",
    ]) {
      assert.throws(
        () => parseBaseUnits(bad, "amount"),
        AmountError,
        `${JSON.stringify(bad)} should be refused`,
      );
    }
  });

  it("refuses an amount past the largest a transaction can carry", () => {
    assert.throws(
      () => parseBaseUnits((MAX_U64 + 1n).toString(), "amount"),
      AmountError,
    );
  });

  it("refuses zero where an amount is required", () => {
    assert.throws(() => requirePositive(0n, "amount"), AmountError);
    assert.throws(() => requirePositive(-1n, "amount"), AmountError);
    assert.equal(requirePositive(1n, "amount"), 1n);
  });
});

describe("converting between SKR and shares", () => {
  it("floors both ways, as the program does", () => {
    // 100 SKR at the captured price. Worked out independently:
    //   100000000 * 1e9 / 1142885034 = 87497864.xx -> 87497864
    //   87497864 * 1142885034 / 1e9  = 99999999.xx -> 99999999
    const shares = sharesForAmount(100_000_000n, PRICE);
    assert.equal(shares, 87_497_864n);
    assert.equal(amountForShares(shares, PRICE), 99_999_999n);
  });

  it("loses at most one base unit on a round trip", () => {
    for (const amount of [1n, 7n, 1_000_000n, 123_456_789n, 999_999_999_999n]) {
      const back = amountForShares(sharesForAmount(amount, PRICE), PRICE);
      assert.ok(back <= amount, `${amount}: round trip must never gain`);
      assert.ok(
        amount - back <= 1n,
        `${amount}: round trip lost ${amount - back} base units`,
      );
    }
  });

  it("is exact when a share is worth exactly one unit of scale", () => {
    const one = 1_000_000_000n;
    assert.equal(sharesForAmount(5_000_000n, one), 5_000_000n);
    assert.equal(amountForShares(5_000_000n, one), 5_000_000n);
  });

  it("refuses a zero or negative share price rather than dividing by it", () => {
    assert.throws(() => sharesForAmount(1n, 0n), AmountError);
    assert.throws(() => amountForShares(1n, -1n), AmountError);
  });
});

describe("planning an unstake", () => {
  const staked = 87_497_864n; // worth 99999999 at PRICE

  it("passes the whole share balance when the request covers the position", () => {
    const exact = planUnstake(99_999_999n, staked, PRICE);
    assert.equal(exact.full, true);
    assert.equal(exact.shares, staked);

    // Asking for more than the position is worth is still just "all of it", not an error: the
    // owner asked to close it, and the program will unstake what is there.
    const over = planUnstake(500_000_000n, staked, PRICE);
    assert.equal(over.full, true);
    assert.equal(over.shares, staked);
  });

  it("never re-derives a full position from its value", () => {
    // The bug this exists to prevent: converting 99999999 back into shares floors to 87497863,
    // one share short, and leaves a position the owner cannot close by asking again.
    assert.equal(sharesForAmount(99_999_999n, PRICE), staked - 1n);
    assert.equal(planUnstake(99_999_999n, staked, PRICE).shares, staked);
  });

  it("floors a partial unstake, so it is never more than was asked", () => {
    const plan = planUnstake(50_000_000n, staked, PRICE);
    assert.equal(plan.full, false);
    assert.equal(plan.shares, sharesForAmount(50_000_000n, PRICE));
    assert.ok(plan.amount <= 50_000_000n);
    assert.ok(50_000_000n - plan.amount <= 1n);
    assert.ok(plan.shares < staked);
  });

  it("refuses an amount worth less than a single share", () => {
    // At a price above the scale, one base unit buys zero shares.
    assert.throws(
      () => planUnstake(1n, staked, 2n * 1_000_000_000n),
      AmountError,
    );
  });

  it("refuses to unstake from a position that is not there", () => {
    assert.throws(() => planUnstake(1n, 0n, PRICE), AmountError);
  });

  it("refuses a zero amount rather than reading it as everything", () => {
    assert.throws(() => planUnstake(0n, staked, PRICE), AmountError);
  });
});

describe("the cooldown", () => {
  const started = 1_788_157_635n;
  const cooldown = 172_800n;

  it("ends exactly one cooldown after the unstake", () => {
    assert.equal(cooldownEndsAt(started, cooldown), started + 172_800n);
  });

  it("counts the boundary second as ready, as the program's >= does", () => {
    assert.equal(
      cooldownComplete(started, cooldown, started + cooldown - 1n),
      false,
    );
    assert.equal(cooldownComplete(started, cooldown, started + cooldown), true);
    assert.equal(
      cooldownComplete(started, cooldown, started + cooldown + 1n),
      true,
    );
  });

  it("is read from the configuration, not assumed to be 48 hours", () => {
    // A config that changed its cooldown moves the boundary with it; nothing here hardcodes 172800.
    assert.equal(cooldownComplete(started, 60n, started + 60n), true);
    assert.equal(cooldownComplete(started, 1_000_000n, started + 60n), false);
  });
});

describe("showing an amount", () => {
  it("writes base units as SKR without floating point", () => {
    assert.equal(formatSkr(0n, 6), "0");
    assert.equal(formatSkr(1n, 6), "0.000001");
    assert.equal(formatSkr(1_000_000n, 6), "1");
    assert.equal(formatSkr(1_364_411_789n, 6), "1364.411789");
    assert.equal(formatSkr(99_999_999n, 6), "99.999999");
    assert.equal(formatSkr(1_500_000n, 6), "1.5");
  });
});
