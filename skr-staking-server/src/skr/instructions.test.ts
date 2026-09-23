import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { PublicKey } from "@solana/web3.js";
import {
  associatedTokenAddress,
  stakingAddresses,
  userStakeAddress,
} from "./program.ts";
import {
  cancelUnstakeInstruction,
  createOwnerTokenAccountInstruction,
  stakeInstruction,
  unstakeInstruction,
  withdrawInstruction,
} from "./instructions.ts";

const ADDRESSES = stakingAddresses();

/**
 * A `stake` that really happened, on mainnet-beta:
 * `4aFwJrtYRM1bvWEPp2cdqhaaQTfY7LjsRUiZHm8SeWk2DtYAG2wMqveS4DtHrtodAsb7L6Z4QUT6a3cpdsCrsgUs`.
 *
 * Everything below — the account order, the flags, the sixteen data bytes — is what that transaction
 * carried. Rebuilding it from this package's own inputs and getting the same bytes is the only
 * evidence that matters that these builders agree with the deployed program, because the program is
 * the one thing here that cannot be asked to explain itself.
 */
const REAL_STAKE = {
  owner: "HY5JrHkeALiQxMczdMcbVwzfZFatpnyoBbUKAy8mehBm",
  userStake: "CXrGnMD86sVCczL5LdxQQRZovUwfzLhThxvjd5F1pNph",
  ownerTokenAccount: "7ZmX32DQfh2vzPUuJvhpQLYkmMzmdZdGJhwCup6kSUuo",
  amount: 1_000_000n,
  data: "ceb0ca12c8d1b36c40420f0000000000",
  accounts: [
    "CXrGnMD86sVCczL5LdxQQRZovUwfzLhThxvjd5F1pNph",
    "4HQy82s9CHTv1GsYKnANHMiHfhcqesYkK6sB3RDSYyqw",
    "DPJ58trLsF9yPrBa2pk6UaRkvqW8hWUYjawe788WBuqr",
    "HY5JrHkeALiQxMczdMcbVwzfZFatpnyoBbUKAy8mehBm",
    "HY5JrHkeALiQxMczdMcbVwzfZFatpnyoBbUKAy8mehBm",
    "7ZmX32DQfh2vzPUuJvhpQLYkmMzmdZdGJhwCup6kSUuo",
    "8isViKbwhuhFhsv2t8vaFL74pKCqaFPQXo1KkeQwZbB8",
    "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3",
    "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
    "11111111111111111111111111111111",
    "8rUTGg1XoyuvK9G64S7d37m3HtLZH24oPeMmXkpJH8ir",
    "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ",
  ],
} as const;

const OWNER = new PublicKey(REAL_STAKE.owner);

function accountsOf(instruction: {
  keys: readonly { pubkey: PublicKey }[];
}): string[] {
  return instruction.keys.map((key) => key.pubkey.toBase58());
}

describe("stake", () => {
  const built = stakeInstruction({
    addresses: ADDRESSES,
    owner: OWNER,
    userStake: new PublicKey(REAL_STAKE.userStake),
    ownerTokenAccount: new PublicKey(REAL_STAKE.ownerTokenAccount),
    amount: REAL_STAKE.amount,
  });

  it("rebuilds a real mainnet stake, account for account", () => {
    assert.deepEqual(accountsOf(built), [...REAL_STAKE.accounts]);
  });

  it("rebuilds its sixteen data bytes exactly", () => {
    assert.equal(built.data.toString("hex"), REAL_STAKE.data);
  });

  it("names the staking program", () => {
    assert.equal(
      built.programId.toBase58(),
      "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ",
    );
  });

  it("derives the same stake account and token account the real one used", () => {
    // The transaction did not tell us these; the program's seeds did, and they agree.
    assert.equal(
      userStakeAddress(
        ADDRESSES.stakeConfig,
        OWNER,
        ADDRESSES.guardianPool,
      ).toBase58(),
      REAL_STAKE.userStake,
    );
    assert.equal(
      associatedTokenAddress(OWNER, ADDRESSES.mint).toBase58(),
      REAL_STAKE.ownerTokenAccount,
    );
  });

  it("asks exactly one signature, from the owner, who also pays", () => {
    const signers = built.keys.filter((key) => key.isSigner);
    assert.equal(signers.length, 1);
    assert.equal(signers[0]?.pubkey.toBase58(), REAL_STAKE.owner);
    assert.equal(signers[0]?.isWritable, true);
  });

  it("writes only what the program writes", () => {
    const writable = built.keys
      .filter((key) => key.isWritable)
      .map((key) => key.pubkey.toBase58());
    assert.deepEqual(writable, [
      REAL_STAKE.userStake,
      ADDRESSES.stakeConfig.toBase58(),
      ADDRESSES.guardianPool.toBase58(),
      REAL_STAKE.owner,
      REAL_STAKE.ownerTokenAccount,
      ADDRESSES.stakeVault.toBase58(),
    ]);
  });

  it("refuses an amount that does not fit the program's u64", () => {
    assert.throws(
      () =>
        stakeInstruction({
          addresses: ADDRESSES,
          owner: OWNER,
          userStake: new PublicKey(REAL_STAKE.userStake),
          ownerTokenAccount: new PublicKey(REAL_STAKE.ownerTokenAccount),
          amount: 1n << 64n,
        }),
      RangeError,
    );
  });
});

describe("unstake", () => {
  const shares = 87_497_864n;
  const built = unstakeInstruction({
    addresses: ADDRESSES,
    owner: OWNER,
    userStake: new PublicKey(REAL_STAKE.userStake),
    shares,
  });

  it("lists the accounts the IDL lists, in order", () => {
    assert.deepEqual(accountsOf(built), [
      REAL_STAKE.userStake,
      ADDRESSES.stakeConfig.toBase58(),
      ADDRESSES.guardianPool.toBase58(),
      REAL_STAKE.owner,
      ADDRESSES.stakeVault.toBase58(),
      ADDRESSES.mint.toBase58(),
      ADDRESSES.eventAuthority.toBase58(),
      ADDRESSES.programId.toBase58(),
    ]);
  });

  it("carries the discriminator and a little-endian u128 of shares", () => {
    assert.equal(built.data.length, 24);
    assert.deepEqual(
      [...built.data.subarray(0, 8)],
      [90, 95, 107, 42, 205, 124, 50, 225],
    );
    const low = built.data.readBigUInt64LE(8);
    const high = built.data.readBigUInt64LE(16);
    assert.equal(low | (high << 64n), shares);
    assert.equal(high, 0n);
  });

  it("carries a share count above 2^64 without losing the high half", () => {
    const large = (1n << 70n) + 12345n;
    const wide = unstakeInstruction({
      addresses: ADDRESSES,
      owner: OWNER,
      userStake: new PublicKey(REAL_STAKE.userStake),
      shares: large,
    });
    assert.equal(
      wide.data.readBigUInt64LE(8) | (wide.data.readBigUInt64LE(16) << 64n),
      large,
    );
  });

  it("asks the owner to sign, and does not make them writable", () => {
    const signers = built.keys.filter((key) => key.isSigner);
    assert.equal(signers.length, 1);
    assert.equal(signers[0]?.pubkey.toBase58(), REAL_STAKE.owner);
    assert.equal(signers[0]?.isWritable, false);
  });

  it("leaves the vault and the mint read only", () => {
    for (const address of [ADDRESSES.stakeVault, ADDRESSES.mint]) {
      const key = built.keys.find((candidate) =>
        candidate.pubkey.equals(address),
      );
      assert.equal(
        key?.isWritable,
        false,
        `${address.toBase58()} is read only`,
      );
    }
  });
});

describe("cancel unstake", () => {
  const built = cancelUnstakeInstruction({
    addresses: ADDRESSES,
    owner: OWNER,
    userStake: new PublicKey(REAL_STAKE.userStake),
  });

  it("lists the accounts the IDL lists, in order, with no mint", () => {
    assert.deepEqual(accountsOf(built), [
      REAL_STAKE.userStake,
      ADDRESSES.stakeConfig.toBase58(),
      ADDRESSES.guardianPool.toBase58(),
      REAL_STAKE.owner,
      ADDRESSES.stakeVault.toBase58(),
      ADDRESSES.eventAuthority.toBase58(),
      ADDRESSES.programId.toBase58(),
    ]);
  });

  it("carries its discriminator and nothing else, because it takes no amount", () => {
    assert.equal(built.data.length, 8);
    assert.deepEqual([...built.data], [64, 65, 53, 227, 125, 153, 3, 167]);
  });
});

describe("withdraw", () => {
  const built = withdrawInstruction({
    addresses: ADDRESSES,
    owner: OWNER,
    userStake: new PublicKey(REAL_STAKE.userStake),
    ownerTokenAccount: new PublicKey(REAL_STAKE.ownerTokenAccount),
  });

  it("lists the accounts the IDL lists, in order", () => {
    assert.deepEqual(accountsOf(built), [
      REAL_STAKE.userStake,
      ADDRESSES.stakeConfig.toBase58(),
      REAL_STAKE.owner,
      ADDRESSES.stakeVault.toBase58(),
      REAL_STAKE.ownerTokenAccount,
      "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
      ADDRESSES.eventAuthority.toBase58(),
      ADDRESSES.programId.toBase58(),
    ]);
  });

  it("carries its discriminator and nothing else: the chain decides the amount", () => {
    assert.equal(built.data.length, 8);
    assert.deepEqual([...built.data], [183, 18, 70, 156, 148, 109, 161, 34]);
  });

  it("asks for no signature of its own, as the program declares", () => {
    // A withdrawal is a crank; the fee payer's signature is what actually signs this transaction,
    // and the owner is always the fee payer here.
    assert.deepEqual(
      built.keys
        .filter((key) => key.isSigner)
        .map((key) => key.pubkey.toBase58()),
      [],
    );
  });

  it("makes the owner writable, because tokens land in their account", () => {
    const owner = built.keys.find((key) => key.pubkey.equals(OWNER));
    assert.equal(owner?.isWritable, true);
  });
});

describe("the supporting token-account create", () => {
  const ata = associatedTokenAddress(OWNER, ADDRESSES.mint);
  const built = createOwnerTokenAccountInstruction({
    owner: OWNER,
    mint: ADDRESSES.mint,
    ownerTokenAccount: ata,
  });

  it("is the associated token program's idempotent create", () => {
    assert.equal(
      built.programId.toBase58(),
      "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL",
    );
    assert.deepEqual([...built.data], [1]);
  });

  it("creates the owner's own SKR account and nobody else's", () => {
    assert.deepEqual(accountsOf(built), [
      REAL_STAKE.owner,
      ata.toBase58(),
      REAL_STAKE.owner,
      ADDRESSES.mint.toBase58(),
      "11111111111111111111111111111111",
      "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
    ]);
    assert.equal(ata.toBase58(), REAL_STAKE.ownerTokenAccount);
  });

  it("has the owner fund it, and only the owner sign", () => {
    const signers = built.keys.filter((key) => key.isSigner);
    assert.equal(signers.length, 1);
    assert.equal(signers[0]?.pubkey.toBase58(), REAL_STAKE.owner);
  });
});
