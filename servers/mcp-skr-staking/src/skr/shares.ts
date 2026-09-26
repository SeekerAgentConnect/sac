/**
 * Share arithmetic, in integers, with the rounding written down.
 *
 * The program holds a position as **shares** and a share is worth a scaled `sharePrice` of SKR. An
 * owner asks in SKR, because that is the thing they have; `unstake` takes shares, because that is
 * the thing the program burns. Converting between them is therefore not a formatting detail, it is
 * the step where an owner can be given less than they asked for or more than they own, so it lives
 * in one file with one rounding rule and its own tests.
 *
 * Two rules hold everywhere:
 *
 * 1. **Everything floors.** The program floors, so anything else here would disagree with the chain
 *    about what a transaction does. A `tokens -> shares -> tokens` round trip can therefore come
 *    back up to one base unit short, and that is expected rather than a defect.
 * 2. **A full position is the position, not a recomputation.** Asking to unstake everything passes
 *    `UserStake.shares` exactly. Converting the staked amount back into shares would floor a second
 *    time and leave a few shares behind, which is how an owner ends up unable to close a position
 *    they asked twice to close.
 */
import { SHARE_PRICE_SCALE } from "./program.ts";

/** The largest value a u64 field can carry. Amounts above it cannot be put in a transaction. */
export const MAX_U64 = 18_446_744_073_709_551_615n;

/** Raised when an amount or a share count cannot be represented or is not usable. */
export class AmountError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AmountError";
  }
}

/**
 * Parses an amount in base units from the decimal-integer-string form the whole repository uses on
 * the wire. A JSON number loses precision above 2^53 and Kotlin reads uint64 as a signed `Long`, so
 * amounts stay strings from the agent all the way to the instruction data.
 */
export function parseBaseUnits(value: string, field: string): bigint {
  if (!/^(?:0|[1-9][0-9]*)$/.test(value)) {
    throw new AmountError(
      `${field} must be a decimal integer in base units, with no sign, point, exponent, or leading zeros`,
    );
  }
  const amount = BigInt(value);
  if (amount > MAX_U64) {
    throw new AmountError(`${field} exceeds the largest representable amount`);
  }
  return amount;
}

/** Requires a positive amount. Zero is refused explicitly rather than treated as "all". */
export function requirePositive(amount: bigint, field: string): bigint {
  if (amount <= 0n) {
    throw new AmountError(`${field} must be greater than zero`);
  }
  return amount;
}

/**
 * The shares a stake of `amount` base units buys at `sharePrice`, floored as the program floors.
 */
export function sharesForAmount(amount: bigint, sharePrice: bigint): bigint {
  if (sharePrice <= 0n) {
    throw new AmountError("share price must be greater than zero");
  }
  return (amount * SHARE_PRICE_SCALE) / sharePrice;
}

/** The base units `shares` are worth at `sharePrice`, floored as the program floors. */
export function amountForShares(shares: bigint, sharePrice: bigint): bigint {
  if (sharePrice <= 0n) {
    throw new AmountError("share price must be greater than zero");
  }
  return (shares * sharePrice) / SHARE_PRICE_SCALE;
}

/** What an unstake of a given size resolves to, and why it is that size. */
export interface UnstakePlan {
  /** The shares the instruction will burn. */
  readonly shares: bigint;
  /** What those shares are worth right now — what the program will record as unstaking. */
  readonly amount: bigint;
  /**
   * True when the request closes the whole position, in which case `shares` is the account's own
   * share count and no conversion was involved.
   */
  readonly full: boolean;
}

/**
 * Turns "unstake this many SKR" into shares against a position.
 *
 * An amount at or above what the position is currently worth is a full unstake: the owner is asking
 * for everything, and everything is `staked` shares exactly. Below that, the shares are floored,
 * which can make the recorded amount up to one base unit less than asked — the alternative, rounding
 * up, would burn shares the owner did not offer.
 */
export function planUnstake(
  requested: bigint,
  staked: bigint,
  sharePrice: bigint,
): UnstakePlan {
  requirePositive(requested, "amount");
  if (staked <= 0n) {
    throw new AmountError("there is no active stake to unstake");
  }
  const stakedAmount = amountForShares(staked, sharePrice);
  if (requested >= stakedAmount) {
    return { shares: staked, amount: stakedAmount, full: true };
  }
  const shares = sharesForAmount(requested, sharePrice);
  if (shares <= 0n) {
    // Possible when the requested amount is smaller than a single share is worth. Refusing it is
    // honest: an instruction burning zero shares would record nothing as unstaking and still look
    // to the owner like it had worked.
    throw new AmountError(
      "amount is too small to unstake: it is worth less than one share",
    );
  }
  if (shares > staked) {
    // Not reachable through `requested < stakedAmount` with floored arithmetic, but a position that
    // moved between the two reads would reach it, and burning more shares than are held must never
    // be the thing that gets built.
    throw new AmountError("amount is more than the active stake");
  }
  return { shares, amount: amountForShares(shares, sharePrice), full: false };
}

/** When a pending unstake becomes withdrawable, as a Unix second. */
export function cooldownEndsAt(
  unstakeTimestamp: bigint,
  cooldownSeconds: bigint,
): bigint {
  return unstakeTimestamp + cooldownSeconds;
}

/**
 * Whether a pending unstake has finished its cooldown at `now`.
 *
 * The program's own check is `now >= unstake_timestamp + cooldown_seconds`, so the boundary second
 * counts as ready. Anything here that used `>` would report a withdrawal as unavailable in the one
 * second the chain would have accepted it.
 */
export function cooldownComplete(
  unstakeTimestamp: bigint,
  cooldownSeconds: bigint,
  now: bigint,
): boolean {
  return now >= cooldownEndsAt(unstakeTimestamp, cooldownSeconds);
}

/** Formats base units as SKR for display only. Never used to decide anything. */
export function formatSkr(amount: bigint, decimals: number): string {
  const scale = 10n ** BigInt(decimals);
  const whole = amount / scale;
  const fraction = amount % scale;
  if (fraction === 0n) return whole.toString();
  return `${whole}.${fraction.toString().padStart(decimals, "0").replace(/0+$/, "")}`;
}
