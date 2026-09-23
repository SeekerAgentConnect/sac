/**
 * Building the four staking instructions.
 *
 * Account order is part of the instruction, not a detail of it: the program reads its accounts
 * positionally, so a pair swapped here is a different instruction that happens to deserialize. Each
 * builder therefore lists its accounts in the order the published IDL lists them, with the same
 * writable and signer flags, and `instructions.test.ts` pins both.
 *
 * Nothing in this module reads the chain or signs anything. It turns already-validated values into
 * bytes, and a caller that has not checked the state is a caller that will have its transaction
 * refused by the program rather than one that gets away with something.
 */
import {
  PublicKey,
  SystemProgram,
  TransactionInstruction,
} from "@solana/web3.js";
import {
  ASSOCIATED_TOKEN_PROGRAM_ID,
  INSTRUCTION,
  TOKEN_PROGRAM_ID,
  type StakingAddresses,
} from "./program.ts";
import { MAX_U64 } from "./shares.ts";

/** The Associated Token Account program's `CreateIdempotent`, which is instruction 1. */
const CREATE_IDEMPOTENT = 1;

function u64(value: bigint): Buffer {
  if (value < 0n || value > MAX_U64) {
    throw new RangeError(`${value} does not fit in a u64`);
  }
  const buffer = Buffer.alloc(8);
  buffer.writeBigUInt64LE(value);
  return buffer;
}

function u128(value: bigint): Buffer {
  if (value < 0n || value >= 1n << 128n) {
    throw new RangeError(`${value} does not fit in a u128`);
  }
  const buffer = Buffer.alloc(16);
  buffer.writeBigUInt64LE(value & 0xffff_ffff_ffff_ffffn, 0);
  buffer.writeBigUInt64LE(value >> 64n, 8);
  return buffer;
}

/**
 * `stake`: moves SKR from the owner's token account into the vault and mints shares at the current
 * price. The owner is both the payer and the user; the program allows a third-party payer, and this
 * server deliberately never builds one, so that the only signer is the wallet being reviewed.
 */
export function stakeInstruction(options: {
  readonly addresses: StakingAddresses;
  readonly owner: PublicKey;
  readonly userStake: PublicKey;
  readonly ownerTokenAccount: PublicKey;
  readonly amount: bigint;
}): TransactionInstruction {
  const { addresses, owner } = options;
  return new TransactionInstruction({
    programId: addresses.programId,
    keys: [
      { pubkey: options.userStake, isSigner: false, isWritable: true },
      { pubkey: addresses.stakeConfig, isSigner: false, isWritable: true },
      { pubkey: addresses.guardianPool, isSigner: false, isWritable: true },
      // payer
      { pubkey: owner, isSigner: true, isWritable: true },
      // user — the same wallet, read only, and the seed of the stake account
      { pubkey: owner, isSigner: false, isWritable: false },
      {
        pubkey: options.ownerTokenAccount,
        isSigner: false,
        isWritable: true,
      },
      { pubkey: addresses.stakeVault, isSigner: false, isWritable: true },
      { pubkey: addresses.mint, isSigner: false, isWritable: false },
      { pubkey: TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
      {
        pubkey: SystemProgram.programId,
        isSigner: false,
        isWritable: false,
      },
      {
        pubkey: addresses.eventAuthority,
        isSigner: false,
        isWritable: false,
      },
      { pubkey: addresses.programId, isSigner: false, isWritable: false },
    ],
    data: Buffer.concat([INSTRUCTION.stake, u64(options.amount)]),
  });
}

/**
 * `unstake`: burns shares and records what they are worth now as waiting out the cooldown. The
 * argument is shares, not SKR — see `shares.ts` for why that conversion is its own decision.
 */
export function unstakeInstruction(options: {
  readonly addresses: StakingAddresses;
  readonly owner: PublicKey;
  readonly userStake: PublicKey;
  readonly shares: bigint;
}): TransactionInstruction {
  const { addresses, owner } = options;
  return new TransactionInstruction({
    programId: addresses.programId,
    keys: [
      { pubkey: options.userStake, isSigner: false, isWritable: true },
      { pubkey: addresses.stakeConfig, isSigner: false, isWritable: true },
      { pubkey: addresses.guardianPool, isSigner: false, isWritable: true },
      { pubkey: owner, isSigner: true, isWritable: false },
      { pubkey: addresses.stakeVault, isSigner: false, isWritable: false },
      { pubkey: addresses.mint, isSigner: false, isWritable: false },
      {
        pubkey: addresses.eventAuthority,
        isSigner: false,
        isWritable: false,
      },
      { pubkey: addresses.programId, isSigner: false, isWritable: false },
    ],
    data: Buffer.concat([INSTRUCTION.unstake, u128(options.shares)]),
  });
}

/**
 * `cancel_unstake`: puts the pending amount back to work as shares and clears the cooldown. It takes
 * no argument, so it always acts on the whole pending unstake — there is no partial cancellation,
 * and a review must say so rather than implying an amount was chosen.
 */
export function cancelUnstakeInstruction(options: {
  readonly addresses: StakingAddresses;
  readonly owner: PublicKey;
  readonly userStake: PublicKey;
}): TransactionInstruction {
  const { addresses, owner } = options;
  return new TransactionInstruction({
    programId: addresses.programId,
    keys: [
      { pubkey: options.userStake, isSigner: false, isWritable: true },
      { pubkey: addresses.stakeConfig, isSigner: false, isWritable: true },
      { pubkey: addresses.guardianPool, isSigner: false, isWritable: true },
      { pubkey: owner, isSigner: true, isWritable: false },
      { pubkey: addresses.stakeVault, isSigner: false, isWritable: false },
      {
        pubkey: addresses.eventAuthority,
        isSigner: false,
        isWritable: false,
      },
      { pubkey: addresses.programId, isSigner: false, isWritable: false },
    ],
    data: Buffer.from(INSTRUCTION.cancelUnstake),
  });
}

/**
 * `withdraw`: moves the cooled-down amount out of the vault into the owner's token account. It takes
 * no argument either — the program pays out whatever it recorded — so a withdrawal's amount is a
 * fact about chain state and never a number the server chose.
 *
 * The program does not require the owner's signature here: a withdrawal is a crank anybody may turn,
 * and the tokens can only go to the owner's own account. This server still builds it with the owner
 * as the fee payer, so the transaction the owner reviews is one only they can send.
 */
export function withdrawInstruction(options: {
  readonly addresses: StakingAddresses;
  readonly owner: PublicKey;
  readonly userStake: PublicKey;
  readonly ownerTokenAccount: PublicKey;
}): TransactionInstruction {
  const { addresses, owner } = options;
  return new TransactionInstruction({
    programId: addresses.programId,
    keys: [
      { pubkey: options.userStake, isSigner: false, isWritable: true },
      { pubkey: addresses.stakeConfig, isSigner: false, isWritable: true },
      // The program declares `user` writable and not a signer. The fee payer signs anyway, and this
      // server always makes that the owner.
      { pubkey: owner, isSigner: false, isWritable: true },
      { pubkey: addresses.stakeVault, isSigner: false, isWritable: true },
      {
        pubkey: options.ownerTokenAccount,
        isSigner: false,
        isWritable: true,
      },
      { pubkey: TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
      {
        pubkey: addresses.eventAuthority,
        isSigner: false,
        isWritable: false,
      },
      { pubkey: addresses.programId, isSigner: false, isWritable: false },
    ],
    data: Buffer.from(INSTRUCTION.withdraw),
  });
}

/**
 * The Associated Token Account program's idempotent create, for the account a withdrawal pays into.
 *
 * It is idempotent so that a withdrawal does not depend on a read of the owner's token account
 * staying true between preparation and signing. The phone knows this instruction is allowed to
 * appear in a withdrawal, checks that it creates exactly the owner's own SKR account, and refuses it
 * anywhere else.
 */
export function createOwnerTokenAccountInstruction(options: {
  readonly owner: PublicKey;
  readonly mint: PublicKey;
  readonly ownerTokenAccount: PublicKey;
}): TransactionInstruction {
  return new TransactionInstruction({
    programId: ASSOCIATED_TOKEN_PROGRAM_ID,
    keys: [
      // funding account
      { pubkey: options.owner, isSigner: true, isWritable: true },
      {
        pubkey: options.ownerTokenAccount,
        isSigner: false,
        isWritable: true,
      },
      // the owner the account belongs to
      { pubkey: options.owner, isSigner: false, isWritable: false },
      { pubkey: options.mint, isSigner: false, isWritable: false },
      {
        pubkey: SystemProgram.programId,
        isSigner: false,
        isWritable: false,
      },
      { pubkey: TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
    ],
    data: Buffer.from([CREATE_IDEMPOTENT]),
  });
}
