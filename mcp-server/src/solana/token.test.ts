/**
 * The SPL Token encodings (SAW-019). These bytes are the contract between the sidecar, the wallet,
 * and the phone that re-parses them before the owner approves, so each one is checked against the
 * program's documented layout rather than against another encoder.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { PublicKey } from "@solana/web3.js";

import {
  ASSOCIATED_TOKEN_PROGRAM,
  SYSTEM_PROGRAM,
  TOKEN_PROGRAM,
  associatedTokenAddress,
} from "./addresses.ts";
import {
  MINT_BYTES,
  TOKEN_ACCOUNT_BYTES,
  TokenAccountState,
  createAssociatedTokenAccount,
  decodeMint,
  decodeTokenAccount,
  transferChecked,
} from "./token.ts";

const OWNER = new PublicKey("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM");
const MINT = new PublicKey("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");

describe("associatedTokenAddress", () => {
  it("derives the address from the owner, the token program, and the mint", () => {
    const [expected] = PublicKey.findProgramAddressSync(
      [OWNER.toBytes(), TOKEN_PROGRAM.toBytes(), MINT.toBytes()],
      ASSOCIATED_TOKEN_PROGRAM,
    );
    assert.equal(
      associatedTokenAddress(OWNER, MINT).toBase58(),
      expected.toBase58(),
    );
  });

  it("stays the same across calls, and is frozen here", () => {
    // Freezing one derivation means a change to the seeds or their order fails loudly, instead of
    // quietly sending a token to an address nobody holds.
    assert.equal(
      associatedTokenAddress(OWNER, MINT).toBase58(),
      "FGETo8T8wMcN2wCjav8VK6eh3dLk63evNDPxzLSJra8B",
    );
  });

  it("gives each owner and each mint its own account", () => {
    const other = new PublicKey("So11111111111111111111111111111111111111112");
    assert.notEqual(
      associatedTokenAddress(OWNER, MINT).toBase58(),
      associatedTokenAddress(OWNER, other).toBase58(),
    );
    assert.notEqual(
      associatedTokenAddress(OWNER, MINT).toBase58(),
      associatedTokenAddress(other, MINT).toBase58(),
    );
  });

  it("derives an address no key can sign for", () => {
    assert.ok(!PublicKey.isOnCurve(associatedTokenAddress(OWNER, MINT)));
  });
});

describe("transferChecked", () => {
  const destination = new PublicKey(
    "So11111111111111111111111111111111111111112",
  );

  it("encodes instruction 12 with the amount and the decimals", () => {
    const instruction = transferChecked(
      { source: OWNER, mint: MINT, destination, authority: OWNER },
      123_456_789n,
      6,
    );
    assert.equal(instruction.programId.toBase58(), TOKEN_PROGRAM.toBase58());
    assert.equal(instruction.data.length, 10);
    assert.equal(instruction.data.readUInt8(0), 12);
    assert.equal(instruction.data.readBigUInt64LE(1), 123_456_789n);
    assert.equal(instruction.data.readUInt8(9), 6);
  });

  it("holds the largest u64 amount", () => {
    const instruction = transferChecked(
      { source: OWNER, mint: MINT, destination, authority: OWNER },
      18_446_744_073_709_551_615n,
      9,
    );
    assert.equal(
      instruction.data.readBigUInt64LE(1),
      18_446_744_073_709_551_615n,
    );
  });

  it("asks only the owner to sign, and writes only the two token accounts", () => {
    const instruction = transferChecked(
      { source: OWNER, mint: MINT, destination, authority: OWNER },
      1n,
      0,
    );
    assert.deepEqual(
      instruction.keys.map((key) => ({
        key: key.pubkey.toBase58(),
        signer: key.isSigner,
        writable: key.isWritable,
      })),
      [
        { key: OWNER.toBase58(), signer: false, writable: true },
        { key: MINT.toBase58(), signer: false, writable: false },
        { key: destination.toBase58(), signer: false, writable: true },
        { key: OWNER.toBase58(), signer: true, writable: false },
      ],
    );
  });
});

describe("createAssociatedTokenAccount", () => {
  it("is the idempotent instruction, with the payer as the only signer", () => {
    const address = associatedTokenAddress(OWNER, MINT);
    const instruction = createAssociatedTokenAccount(
      OWNER,
      address,
      OWNER,
      MINT,
    );
    assert.equal(
      instruction.programId.toBase58(),
      ASSOCIATED_TOKEN_PROGRAM.toBase58(),
    );
    assert.deepEqual(Array.from(instruction.data), [1]);
    assert.deepEqual(
      instruction.keys.map((key) => key.pubkey.toBase58()),
      [
        OWNER.toBase58(),
        address.toBase58(),
        OWNER.toBase58(),
        MINT.toBase58(),
        SYSTEM_PROGRAM.toBase58(),
        TOKEN_PROGRAM.toBase58(),
      ],
    );
    assert.deepEqual(
      instruction.keys.map((key) => key.isSigner),
      [true, false, false, false, false, false],
    );
  });
});

describe("decodeMint", () => {
  it("reads the supply, the decimals, and whether it is initialized", () => {
    const data = Buffer.alloc(MINT_BYTES);
    data.writeBigUInt64LE(42_000n, 36);
    data.writeUInt8(9, 44);
    data.writeUInt8(1, 45);
    assert.deepEqual(decodeMint(data), {
      supply: 42_000n,
      decimals: 9,
      initialized: true,
    });
  });

  it("refuses data that is too short to be a mint", () => {
    assert.equal(decodeMint(new Uint8Array(MINT_BYTES - 1)), undefined);
  });
});

describe("decodeTokenAccount", () => {
  it("reads the mint, the owner, the amount, and the state", () => {
    const data = Buffer.alloc(TOKEN_ACCOUNT_BYTES);
    data.set(MINT.toBytes(), 0);
    data.set(OWNER.toBytes(), 32);
    data.writeBigUInt64LE(7n, 64);
    data.writeUInt8(TokenAccountState.FROZEN, 108);
    assert.deepEqual(decodeTokenAccount(data), {
      mint: MINT.toBase58(),
      owner: OWNER.toBase58(),
      amount: 7n,
      state: TokenAccountState.FROZEN,
    });
  });

  it("refuses data that is too short to be a token account", () => {
    assert.equal(
      decodeTokenAccount(new Uint8Array(TOKEN_ACCOUNT_BYTES - 1)),
      undefined,
    );
  });
});
