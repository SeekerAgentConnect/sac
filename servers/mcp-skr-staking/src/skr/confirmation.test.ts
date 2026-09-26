/**
 * Whether the transaction on chain is the one the owner approved.
 *
 * The transactions here are built with the same library that builds this server's preparations, so
 * what is compared is real wire encoding rather than a shape invented to satisfy the comparison.
 */
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { describe, it } from "node:test";
import {
  Keypair,
  PublicKey,
  SystemProgram,
  TransactionMessage,
  VersionedTransaction,
} from "@solana/web3.js";
import { isApprovedTransaction, transactionMessage } from "./confirmation.ts";

const PAYER = Keypair.generate().publicKey;
const OTHER = Keypair.generate().publicKey;
const BLOCKHASH = new PublicKey(randomBytes(32)).toBase58();

/** A transaction with the empty signature slot a preparation carries. */
function unsigned(to: PublicKey, lamports: number): Uint8Array {
  const message = new TransactionMessage({
    payerKey: PAYER,
    recentBlockhash: BLOCKHASH,
    instructions: [
      SystemProgram.transfer({ fromPubkey: PAYER, toPubkey: to, lamports }),
    ],
  }).compileToV0Message();
  return new VersionedTransaction(message).serialize();
}

/** The same transaction as the chain holds it: the wallet's real signature in the slot. */
function signed(transaction: Uint8Array): Uint8Array {
  const copy = Uint8Array.from(transaction);
  // One signature, so the slot is the 64 bytes after the compact-u16 count.
  copy.set(randomBytes(64), 1);
  return copy;
}

describe("the message region", () => {
  it("is everything the signatures cover", () => {
    const transaction = unsigned(OTHER, 1);
    const message = transactionMessage(transaction);
    assert.ok(message !== undefined);
    assert.equal(message.length, transaction.length - 1 - 64);
  });

  it("is undefined for bytes that are not a transaction", () => {
    assert.equal(transactionMessage(new Uint8Array()), undefined);
    // A count with no room for the signatures it promises.
    assert.equal(transactionMessage(Uint8Array.from([1, 2, 3])), undefined);
    // A count of zero: nothing signed it, so there is no transaction here.
    assert.equal(transactionMessage(Uint8Array.from([0, 1, 2])), undefined);
  });
});

describe("matching the approved transaction", () => {
  it("accepts the approved transaction once the wallet has signed it", () => {
    const approved = unsigned(OTHER, 1_000);
    assert.equal(isApprovedTransaction(approved, signed(approved)), true);
  });

  it("refuses one that differs anywhere the signature covers", () => {
    const approved = unsigned(OTHER, 1_000);
    const elsewhere = unsigned(Keypair.generate().publicKey, 1_000);
    const forMore = unsigned(OTHER, 1_001);
    assert.equal(isApprovedTransaction(approved, signed(elsewhere)), false);
    assert.equal(isApprovedTransaction(approved, signed(forMore)), false);
  });

  it("refuses bytes it cannot take apart rather than guessing", () => {
    const approved = unsigned(OTHER, 1_000);
    assert.equal(isApprovedTransaction(approved, new Uint8Array()), false);
    assert.equal(isApprovedTransaction(new Uint8Array([0]), approved), false);
  });
});
