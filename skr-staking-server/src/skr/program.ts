/**
 * The SKR staking program's identity, its addresses, and the derivations that produce them.
 *
 * Every constant here was read back off mainnet-beta before it was written down, and every derived
 * address is re-derived by `program.test.ts` and compared against the live account. That matters
 * because an address is the only thing standing between "stake with the official pool" and "send
 * tokens to somebody who asked nicely": a wrong constant is not a bug that shows up in a test run,
 * it is a bug that shows up as somebody else's balance.
 *
 * Only two of these are roots of trust. The program ID and the SKR mint have to be known; the
 * config, the vault and a user's stake account are all program-derived, so they are computed rather
 * than believed, and the phone computes them again for itself when it reads the bytes back.
 */
import { PublicKey } from "@solana/web3.js";
import { createHash } from "node:crypto";

/**
 * The staking program, deployed on mainnet-beta and nowhere else. Its own Anchor IDL is published
 * on chain at `4aAEUKCcju9iAEAgdeaNz4RC7sCPv63q5g714nw4QY68`, and this module is written from that
 * document rather than from any copy of it.
 */
export const STAKING_PROGRAM_ID = new PublicKey(
  "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ",
);

/** The SKR mint. A classic SPL token with 6 decimals — not Token-2022. */
export const SKR_MINT = new PublicKey(
  "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3",
);

/** SKR's decimal places, as the mint itself reports them. */
export const SKR_DECIMALS = 6;

/**
 * The guardian whose delegation pool this server stakes into. A stake is delegated to a guardian,
 * and the user's stake account is derived per guardian, so this is part of the account's identity
 * rather than a preference: staking into another pool would produce a different stake account.
 *
 * Serving more than one guardian is deliberately out of scope here, and the phone pins the same
 * pool, so a request naming another one is refused rather than quietly executed.
 */
export const OFFICIAL_GUARDIAN = new PublicKey(
  "SKRGdBwzb1AtFW2chhBnZpGFnFLj6Mi7HM7iwjXALvw",
);

/** The classic SPL Token program. The staking program names it explicitly in its IDL. */
export const TOKEN_PROGRAM_ID = new PublicKey(
  "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
);

/** The Associated Token Account program, used to give a withdrawal somewhere to land. */
export const ASSOCIATED_TOKEN_PROGRAM_ID = new PublicKey(
  "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL",
);

/** The System program, which `stake` names because it may create the stake account. */
export const SYSTEM_PROGRAM_ID = new PublicKey(
  "11111111111111111111111111111111",
);

/**
 * Share prices are integers scaled by 1e9. Nothing here ever divides in floating point: a share
 * price is a `bigint`, an amount is a `bigint`, and the rounding is stated where it happens.
 */
export const SHARE_PRICE_SCALE = 1_000_000_000n;

/**
 * An Anchor discriminator is the first eight bytes of a SHA-256 over a namespaced name. Deriving
 * them rather than pasting them means the names in this file are the names in the IDL, and
 * `program.test.ts` asserts each one against the value the published IDL carries.
 */
function discriminator(namespace: string, name: string): Buffer {
  return createHash("sha256")
    .update(`${namespace}:${name}`)
    .digest()
    .subarray(0, 8);
}

/** The four instructions this server builds, and nothing else the program serves. */
export const INSTRUCTION = {
  stake: discriminator("global", "stake"),
  unstake: discriminator("global", "unstake"),
  cancelUnstake: discriminator("global", "cancel_unstake"),
  withdraw: discriminator("global", "withdraw"),
} as const;

/** The three accounts this server reads. */
export const ACCOUNT = {
  stakeConfig: discriminator("account", "StakeConfig"),
  userStake: discriminator("account", "UserStake"),
  guardianDelegationPool: discriminator("account", "GuardianDelegationPool"),
} as const;

/** The singleton configuration account: `PDA(["stake_config"])`. */
export function stakeConfigAddress(): PublicKey {
  return PublicKey.findProgramAddressSync(
    [Buffer.from("stake_config")],
    STAKING_PROGRAM_ID,
  )[0];
}

/** The vault that holds every staker's principal: `PDA(["stake_vault"])`. */
export function stakeVaultAddress(): PublicKey {
  return PublicKey.findProgramAddressSync(
    [Buffer.from("stake_vault")],
    STAKING_PROGRAM_ID,
  )[0];
}

/** A guardian's delegation pool: `PDA(["guardian_pool", stakeConfig, guardian])`. */
export function guardianPoolAddress(
  stakeConfig: PublicKey,
  guardian: PublicKey,
): PublicKey {
  return PublicKey.findProgramAddressSync(
    [Buffer.from("guardian_pool"), stakeConfig.toBuffer(), guardian.toBuffer()],
    STAKING_PROGRAM_ID,
  )[0];
}

/**
 * One owner's stake account with one guardian:
 * `PDA(["user_stake", stakeConfig, user, guardianPool])`.
 *
 * The guardian pool is part of the seed, so the same owner staking with two guardians has two
 * accounts and neither is "their" stake account on its own.
 */
export function userStakeAddress(
  stakeConfig: PublicKey,
  user: PublicKey,
  guardianPool: PublicKey,
): PublicKey {
  return PublicKey.findProgramAddressSync(
    [
      Buffer.from("user_stake"),
      stakeConfig.toBuffer(),
      user.toBuffer(),
      guardianPool.toBuffer(),
    ],
    STAKING_PROGRAM_ID,
  )[0];
}

/** Anchor's CPI event authority, which every one of these instructions names. */
export function eventAuthorityAddress(): PublicKey {
  return PublicKey.findProgramAddressSync(
    [Buffer.from("__event_authority")],
    STAKING_PROGRAM_ID,
  )[0];
}

/** The owner's associated token account for a mint. */
export function associatedTokenAddress(
  owner: PublicKey,
  mint: PublicKey,
): PublicKey {
  return PublicKey.findProgramAddressSync(
    [owner.toBuffer(), TOKEN_PROGRAM_ID.toBuffer(), mint.toBuffer()],
    ASSOCIATED_TOKEN_PROGRAM_ID,
  )[0];
}

/**
 * Every address this server uses for one guardian, derived once. Passing this around instead of
 * five separate arguments is what keeps a mismatched pair from being constructible.
 */
export interface StakingAddresses {
  readonly programId: PublicKey;
  readonly mint: PublicKey;
  readonly stakeConfig: PublicKey;
  readonly stakeVault: PublicKey;
  readonly guardian: PublicKey;
  readonly guardianPool: PublicKey;
  readonly eventAuthority: PublicKey;
}

/** Derives the whole set for one guardian, defaulting to the official pool. */
export function stakingAddresses(
  guardian: PublicKey = OFFICIAL_GUARDIAN,
): StakingAddresses {
  const stakeConfig = stakeConfigAddress();
  return {
    programId: STAKING_PROGRAM_ID,
    mint: SKR_MINT,
    stakeConfig,
    stakeVault: stakeVaultAddress(),
    guardian,
    guardianPool: guardianPoolAddress(stakeConfig, guardian),
    eventAuthority: eventAuthorityAddress(),
  };
}
