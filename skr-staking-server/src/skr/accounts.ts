/**
 * Reading the staking program's three accounts.
 *
 * These are Borsh structs behind an eight-byte discriminator. Decoding is written out by hand, one
 * field at a time, for the same reason the addresses are derived rather than pasted: the layout is
 * a wire contract owned by somebody else, and a decoder that silently reads past the end of a
 * struct, or that trusts a length it was handed, is how a field ends up meaning the wrong thing.
 *
 * Every decoder here checks the discriminator first and the exact length second. A short account is
 * refused rather than zero-padded, because a zero in `shares` and an absent `shares` are different
 * facts and only one of them is safe to act on.
 */
import { PublicKey } from "@solana/web3.js";
import { ACCOUNT } from "./program.ts";

/** The byte length each account occupies on chain, discriminator included. */
export const ACCOUNT_SIZE = {
  stakeConfig: 193,
  userStake: 169,
  guardianDelegationPool: 188,
} as const;

/** Raised when an account cannot be read as what it was asked to be. */
export class AccountDecodeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AccountDecodeError";
  }
}

/** A cursor that refuses to read past the end of the buffer it was given. */
class Reader {
  #data: Buffer;
  #offset: number;
  readonly #what: string;

  constructor(data: Buffer, what: string) {
    this.#data = data;
    this.#offset = 0;
    this.#what = what;
  }

  discriminator(expected: Buffer): void {
    const actual = this.#take(8);
    if (!actual.equals(expected)) {
      throw new AccountDecodeError(
        `${this.#what}: discriminator ${actual.toString("hex")} is not ${expected.toString("hex")}`,
      );
    }
  }

  u8(): number {
    return this.#take(1).readUInt8(0);
  }

  bool(): boolean {
    const byte = this.u8();
    if (byte > 1) {
      throw new AccountDecodeError(`${this.#what}: ${byte} is not a boolean`);
    }
    return byte === 1;
  }

  u16(): number {
    return this.#take(2).readUInt16LE(0);
  }

  u64(): bigint {
    return this.#take(8).readBigUInt64LE(0);
  }

  i64(): bigint {
    return this.#take(8).readBigInt64LE(0);
  }

  u128(): bigint {
    const bytes = this.#take(16);
    return bytes.readBigUInt64LE(0) | (bytes.readBigUInt64LE(8) << 64n);
  }

  pubkey(): PublicKey {
    return new PublicKey(this.#take(32));
  }

  /** Asserts the struct consumed exactly the account, so a layout drift is loud. */
  end(): void {
    if (this.#offset !== this.#data.length) {
      throw new AccountDecodeError(
        `${this.#what}: read ${this.#offset} of ${this.#data.length} bytes`,
      );
    }
  }

  #take(length: number): Buffer {
    const end = this.#offset + length;
    if (end > this.#data.length) {
      throw new AccountDecodeError(
        `${this.#what}: wanted ${length} bytes at ${this.#offset}, have ${this.#data.length}`,
      );
    }
    const slice = this.#data.subarray(this.#offset, end);
    this.#offset = end;
    return slice;
  }
}

/** The program's single configuration account. */
export interface StakeConfig {
  readonly bump: number;
  readonly authority: PublicKey;
  readonly mint: PublicKey;
  readonly stakeVault: PublicKey;
  /** The smallest stake the program will accept, in SKR base units. */
  readonly minStakeAmount: bigint;
  /** How long an unstake waits before it may be withdrawn. Read from here, never assumed. */
  readonly cooldownSeconds: bigint;
  readonly totalShares: bigint;
  /** Scaled by `SHARE_PRICE_SCALE`. */
  readonly sharePrice: bigint;
  readonly commissionWeightSum: bigint;
  readonly cumulativeCommissionPerShare: bigint;
  readonly lastVaultAmount: bigint;
}

export function decodeStakeConfig(data: Buffer): StakeConfig {
  if (data.length !== ACCOUNT_SIZE.stakeConfig) {
    throw new AccountDecodeError(
      `StakeConfig: ${data.length} bytes, expected ${ACCOUNT_SIZE.stakeConfig}`,
    );
  }
  const reader = new Reader(data, "StakeConfig");
  reader.discriminator(ACCOUNT.stakeConfig);
  const config: StakeConfig = {
    bump: reader.u8(),
    authority: reader.pubkey(),
    mint: reader.pubkey(),
    stakeVault: reader.pubkey(),
    minStakeAmount: reader.u64(),
    cooldownSeconds: reader.u64(),
    totalShares: reader.u128(),
    sharePrice: reader.u128(),
    commissionWeightSum: reader.u128(),
    cumulativeCommissionPerShare: reader.u128(),
    lastVaultAmount: reader.u64(),
  };
  reader.end();
  if (config.sharePrice === 0n) {
    // The program itself refuses a zero share price (InvalidSharePrice). Refusing it here too
    // means no arithmetic in this package ever has to guard against dividing by it.
    throw new AccountDecodeError("StakeConfig: share price is zero");
  }
  return config;
}

/** One owner's stake with one guardian. */
export interface UserStake {
  readonly bump: number;
  readonly stakeConfig: PublicKey;
  readonly user: PublicKey;
  readonly guardianPool: PublicKey;
  /** Shares still staked and earning. Zero after a full unstake. */
  readonly shares: bigint;
  readonly costBasis: bigint;
  readonly cumulativeCommissionBeforeStaking: bigint;
  /**
   * SKR base units waiting out the cooldown. Fixed at unstake time from the share price then, so a
   * later price rise does not raise it.
   */
  readonly unstakingAmount: bigint;
  /** When the pending unstake started; 0 when there is none. */
  readonly unstakeTimestamp: bigint;
}

export function decodeUserStake(data: Buffer): UserStake {
  if (data.length !== ACCOUNT_SIZE.userStake) {
    throw new AccountDecodeError(
      `UserStake: ${data.length} bytes, expected ${ACCOUNT_SIZE.userStake}`,
    );
  }
  const reader = new Reader(data, "UserStake");
  reader.discriminator(ACCOUNT.userStake);
  const stake: UserStake = {
    bump: reader.u8(),
    stakeConfig: reader.pubkey(),
    user: reader.pubkey(),
    guardianPool: reader.pubkey(),
    shares: reader.u128(),
    costBasis: reader.u128(),
    cumulativeCommissionBeforeStaking: reader.u128(),
    unstakingAmount: reader.u64(),
    unstakeTimestamp: reader.i64(),
  };
  reader.end();
  return stake;
}

/** A guardian's pool, which a stake is delegated to. */
export interface GuardianDelegationPool {
  readonly stakeConfig: PublicKey;
  readonly guardian: PublicKey;
  readonly authority: PublicKey;
  readonly totalShares: bigint;
  readonly cumulativeCommissionPerShare: bigint;
  readonly lastSharePrice: bigint;
  readonly accruedCommission: bigint;
  readonly commissionBps: number;
  readonly bump: number;
  /** A deregistered pool takes no new stake; the program refuses it with GuardianPoolInactive. */
  readonly active: boolean;
  readonly deregisteredSharePrice: bigint;
}

export function decodeGuardianDelegationPool(
  data: Buffer,
): GuardianDelegationPool {
  if (data.length !== ACCOUNT_SIZE.guardianDelegationPool) {
    throw new AccountDecodeError(
      `GuardianDelegationPool: ${data.length} bytes, expected ${ACCOUNT_SIZE.guardianDelegationPool}`,
    );
  }
  const reader = new Reader(data, "GuardianDelegationPool");
  reader.discriminator(ACCOUNT.guardianDelegationPool);
  const pool: GuardianDelegationPool = {
    stakeConfig: reader.pubkey(),
    guardian: reader.pubkey(),
    authority: reader.pubkey(),
    totalShares: reader.u128(),
    cumulativeCommissionPerShare: reader.u128(),
    lastSharePrice: reader.u128(),
    accruedCommission: reader.u128(),
    commissionBps: reader.u16(),
    bump: reader.u8(),
    active: reader.bool(),
    deregisteredSharePrice: reader.u128(),
  };
  reader.end();
  return pool;
}

/** An SPL token account, read for the owner's SKR balance. */
export interface TokenAccount {
  readonly mint: PublicKey;
  readonly owner: PublicKey;
  readonly amount: bigint;
  /** 1 is initialized, 2 is a native-SOL wrapper. 0 is uninitialized and never usable. */
  readonly state: number;
}

/** The fixed 165-byte classic SPL token account. */
export const TOKEN_ACCOUNT_SIZE = 165;

export function decodeTokenAccount(data: Buffer): TokenAccount {
  // A Token-2022 account, or one with extensions, is longer and is deliberately not read here: the
  // staking program names the classic token program, so anything else is not an account it would
  // accept either.
  if (data.length !== TOKEN_ACCOUNT_SIZE) {
    throw new AccountDecodeError(
      `token account: ${data.length} bytes, expected ${TOKEN_ACCOUNT_SIZE}`,
    );
  }
  const state = data.readUInt8(108);
  if (state === 0) {
    throw new AccountDecodeError("token account: uninitialized");
  }
  return {
    mint: new PublicKey(data.subarray(0, 32)),
    owner: new PublicKey(data.subarray(32, 64)),
    amount: data.readBigUInt64LE(64),
    state,
  };
}
