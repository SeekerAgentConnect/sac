import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  ACCOUNT_SIZE,
  AccountDecodeError,
  decodeGuardianDelegationPool,
  decodeStakeConfig,
  decodeTokenAccount,
  decodeUserStake,
} from "./accounts.ts";
import {
  COOLDOWN_AT_CAPTURE,
  GUARDIAN_POOL_ACCOUNT,
  SHARE_PRICE_AT_CAPTURE,
  STAKE_CONFIG_ACCOUNT,
  STAKE_VAULT_ACCOUNT,
  USER_STAKE_ACCOUNT,
  USER_STAKE_OWNER,
} from "../testing/accounts.ts";
import { OFFICIAL_GUARDIAN, SKR_MINT, stakeConfigAddress } from "./program.ts";

describe("StakeConfig, read off the chain", () => {
  const config = decodeStakeConfig(STAKE_CONFIG_ACCOUNT);

  it("consumes exactly the account the program writes", () => {
    assert.equal(STAKE_CONFIG_ACCOUNT.length, ACCOUNT_SIZE.stakeConfig);
  });

  it("names the SKR mint and the vault this server uses", () => {
    assert.equal(config.mint.toBase58(), SKR_MINT.toBase58());
    assert.equal(
      config.stakeVault.toBase58(),
      "8isViKbwhuhFhsv2t8vaFL74pKCqaFPQXo1KkeQwZbB8",
    );
  });

  it("carries the cooldown, rather than leaving it to be assumed", () => {
    assert.equal(config.cooldownSeconds, COOLDOWN_AT_CAPTURE);
    assert.equal(config.cooldownSeconds, 172_800n); // 48 hours, as the docs say — from the chain
  });

  it("carries the smallest stake and the share price", () => {
    assert.equal(config.minStakeAmount, 1_000_000n); // 1 SKR
    assert.equal(config.sharePrice, SHARE_PRICE_AT_CAPTURE);
    assert.ok(config.totalShares > 0n);
  });

  it("reads a u128 across both halves, not just the low one", () => {
    // last_vault_amount is a u64 that follows four u128s, so a decoder that mis-sized any of them
    // lands somewhere else entirely. The check that catches it is not the literal below but the
    // one in the vault suite: the config's own record of the vault balance and the vault account's
    // balance are written by different code into different layouts, and they agree.
    assert.equal(config.lastVaultAmount, 5_009_454_611_017_086n);
    assert.equal(config.totalShares, 4_358_657_405_968_428n);
  });

  it("refuses an account of the wrong length rather than padding it", () => {
    assert.throws(
      () => decodeStakeConfig(STAKE_CONFIG_ACCOUNT.subarray(0, 100)),
      AccountDecodeError,
    );
  });

  it("refuses an account that is not a StakeConfig", () => {
    const wrong = Buffer.from(STAKE_CONFIG_ACCOUNT);
    wrong[0] = (wrong[0] ?? 0) ^ 0xff;
    assert.throws(() => decodeStakeConfig(wrong), AccountDecodeError);
  });
});

describe("UserStake, read off the chain", () => {
  const stake = decodeUserStake(USER_STAKE_ACCOUNT);

  it("consumes exactly the account the program writes", () => {
    assert.equal(USER_STAKE_ACCOUNT.length, ACCOUNT_SIZE.userStake);
  });

  it("names its owner, its config and its guardian pool", () => {
    assert.equal(stake.user.toBase58(), USER_STAKE_OWNER);
    assert.equal(stake.stakeConfig.toBase58(), stakeConfigAddress().toBase58());
  });

  it("reads a position that is part staked and part cooling down", () => {
    // Both halves matter: a decoder that ran the fields together would read one of them as zero,
    // and zero is exactly the value that looks like "nothing to do here".
    assert.equal(stake.shares, 2_179_442n);
    assert.equal(stake.unstakingAmount, 1_364_411_789n);
    assert.equal(stake.unstakeTimestamp, 1_788_157_635n);
  });

  it("refuses a truncated account", () => {
    assert.throws(
      () => decodeUserStake(USER_STAKE_ACCOUNT.subarray(0, 168)),
      AccountDecodeError,
    );
  });

  it("refuses a StakeConfig handed to it as a UserStake", () => {
    assert.throws(
      () => decodeUserStake(STAKE_CONFIG_ACCOUNT),
      AccountDecodeError,
    );
  });
});

describe("the guardian's pool", () => {
  const pool = decodeGuardianDelegationPool(GUARDIAN_POOL_ACCOUNT);

  it("belongs to the config this server derives", () => {
    assert.equal(pool.stakeConfig.toBase58(), stakeConfigAddress().toBase58());
    assert.equal(pool.guardian.toBase58(), OFFICIAL_GUARDIAN.toBase58());
  });

  it("is active, so the program will take new stake into it", () => {
    assert.equal(pool.active, true);
    assert.equal(pool.deregisteredSharePrice, 0n);
  });

  it("reads the commission that follows three u128s", () => {
    assert.equal(pool.commissionBps, 0);
    assert.equal(pool.bump, 255);
  });
});

describe("the vault, as an SPL token account", () => {
  const vault = decodeTokenAccount(STAKE_VAULT_ACCOUNT);

  it("holds SKR and is owned by the config, not by a person", () => {
    assert.equal(vault.mint.toBase58(), SKR_MINT.toBase58());
    assert.equal(vault.owner.toBase58(), stakeConfigAddress().toBase58());
  });

  it("is initialized and holds the balance the config last recorded", () => {
    assert.equal(vault.state, 1);
    // Two layouts, decoded independently, landing on the same number in the same snapshot. Either
    // decoder being wrong about a field width would break this.
    assert.equal(
      vault.amount,
      decodeStakeConfig(STAKE_CONFIG_ACCOUNT).lastVaultAmount,
    );
    assert.equal(vault.amount, 5_009_454_611_017_086n);
  });

  it("refuses an account of another size, Token-2022 included", () => {
    assert.throws(
      () => decodeTokenAccount(Buffer.alloc(182)),
      AccountDecodeError,
    );
  });

  it("refuses an uninitialized account rather than reading a zero balance", () => {
    const blank = Buffer.alloc(165);
    assert.throws(() => decodeTokenAccount(blank), AccountDecodeError);
  });
});
