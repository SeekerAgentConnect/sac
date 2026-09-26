/**
 * The SPL Token account layouts and the two instructions a token transfer needs, encoded here
 * rather than taken from a library: these are exactly the bytes the owner's phone parses before
 * they approve (SAW-020), and the tests freeze them against published vectors.
 *
 * Layouts: https://github.com/solana-program/token — Mint is 82 bytes and Account is 165.
 */
import { PublicKey, TransactionInstruction } from "@solana/web3.js";

import {
  ASSOCIATED_TOKEN_PROGRAM,
  SYSTEM_PROGRAM,
  TOKEN_PROGRAM,
} from "./addresses.ts";

/** The size of an SPL Token mint account, and of a token account. */
export const MINT_BYTES = 82;
export const TOKEN_ACCOUNT_BYTES = 165;

/** SPL Token instruction 12, TransferChecked: the only transfer that states the decimals. */
const TRANSFER_CHECKED = 12;
/** Associated Token Account instruction 1, CreateIdempotent: creating an account that exists is fine. */
const CREATE_IDEMPOTENT = 1;

/** A token account's state byte. */
export const TokenAccountState = {
  UNINITIALIZED: 0,
  INITIALIZED: 1,
  FROZEN: 2,
} as const;
export type TokenAccountState =
  (typeof TokenAccountState)[keyof typeof TokenAccountState];

export interface Mint {
  readonly decimals: number;
  readonly supply: bigint;
  readonly initialized: boolean;
}

export interface TokenAccount {
  readonly mint: string;
  readonly owner: string;
  readonly amount: bigint;
  readonly state: TokenAccountState;
}

/** Reads a mint account's data, or returns undefined when it isn't one. */
export function decodeMint(data: Uint8Array): Mint | undefined {
  if (data.length < MINT_BYTES) return undefined;
  const view = Buffer.from(data.buffer, data.byteOffset, data.byteLength);
  return {
    supply: view.readBigUInt64LE(36),
    decimals: view.readUInt8(44),
    initialized: view.readUInt8(45) === 1,
  };
}

/** Reads a token account's data, or returns undefined when it isn't one. */
export function decodeTokenAccount(data: Uint8Array): TokenAccount | undefined {
  if (data.length < TOKEN_ACCOUNT_BYTES) return undefined;
  const view = Buffer.from(data.buffer, data.byteOffset, data.byteLength);
  return {
    mint: new PublicKey(view.subarray(0, 32)).toBase58(),
    owner: new PublicKey(view.subarray(32, 64)).toBase58(),
    amount: view.readBigUInt64LE(64),
    state: view.readUInt8(108) as TokenAccountState,
  };
}

export interface TransferCheckedAccounts {
  readonly source: PublicKey;
  readonly mint: PublicKey;
  readonly destination: PublicKey;
  /** The account that owns `source`, and the only signer the instruction needs. */
  readonly authority: PublicKey;
}

/**
 * TransferChecked. It carries the mint and its decimals, so the program refuses the transfer if
 * either differs from what was prepared: a mint that changed under the owner can't move an amount
 * they didn't review.
 */
export function transferChecked(
  accounts: TransferCheckedAccounts,
  amount: bigint,
  decimals: number,
): TransactionInstruction {
  const data = Buffer.alloc(10);
  data.writeUInt8(TRANSFER_CHECKED, 0);
  data.writeBigUInt64LE(amount, 1);
  data.writeUInt8(decimals, 9);
  return new TransactionInstruction({
    programId: TOKEN_PROGRAM,
    keys: [
      { pubkey: accounts.source, isSigner: false, isWritable: true },
      { pubkey: accounts.mint, isSigner: false, isWritable: false },
      { pubkey: accounts.destination, isSigner: false, isWritable: true },
      { pubkey: accounts.authority, isSigner: true, isWritable: false },
    ],
    data,
  });
}

/**
 * CreateIdempotent on the Associated Token Account program: gives `owner` its associated account
 * for `mint`, and succeeds even if someone else created it between preparation and signing.
 * `payer` pays its rent, which the preparation discloses.
 */
export function createAssociatedTokenAccount(
  payer: PublicKey,
  address: PublicKey,
  owner: PublicKey,
  mint: PublicKey,
): TransactionInstruction {
  return new TransactionInstruction({
    programId: ASSOCIATED_TOKEN_PROGRAM,
    keys: [
      { pubkey: payer, isSigner: true, isWritable: true },
      { pubkey: address, isSigner: false, isWritable: true },
      { pubkey: owner, isSigner: false, isWritable: false },
      { pubkey: mint, isSigner: false, isWritable: false },
      { pubkey: SYSTEM_PROGRAM, isSigner: false, isWritable: false },
      { pubkey: TOKEN_PROGRAM, isSigner: false, isWritable: false },
    ],
    data: Buffer.from([CREATE_IDEMPOTENT]),
  });
}
