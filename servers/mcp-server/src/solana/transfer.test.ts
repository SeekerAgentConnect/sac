/**
 * Building a transfer (SAW-019). Every case deserializes the bytes that would go to the wallet and
 * checks them: the fee payer, the signer set, and each instruction. Nothing here reaches a network
 * — the chain is `FakeChain` — and nothing signs.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { PublicKey, VersionedTransaction } from "@solana/web3.js";

import { create } from "@bufbuild/protobuf";

import {
  Network,
  TransferActionSchema,
  type TransferAction,
} from "@seeker_agent_connect/server-sdk/protocol";
import {
  FakeChain,
  TOKEN_ACCOUNT_RENT,
  TEST_BLOCKHASH,
  mintAccount,
  programAccount,
  tokenAccount,
  walletAccount,
} from "../testing/chain.ts";
import {
  ASSOCIATED_TOKEN_PROGRAM,
  SYSTEM_PROGRAM,
  TOKEN_2022_PROGRAM,
  TOKEN_PROGRAM,
  associatedTokenAddress,
} from "./addresses.ts";
import { GENESIS_HASHES } from "./network.ts";
import { ChainUnavailable } from "./rpc.ts";
import { TokenAccountState } from "./token.ts";
import { UnsupportedTransfer, buildTransfer } from "./transfer.ts";

const NOW = Date.UTC(2026, 8, 12, 12, 0, 0);
const WALLET = key(11);
const RECIPIENT = key(22);
const MINT = key(33);

/** A distinct, valid 32-byte address for each number. */
function key(seed: number): PublicKey {
  return new PublicKey(Uint8Array.from({ length: 32 }, () => seed));
}

/** What a case varies about the standard transfer; `mint` makes it a token transfer. */
interface TransferOverrides {
  readonly wallet?: string;
  readonly network?: Network;
  readonly recipient?: string;
  readonly amount?: string;
  readonly mint?: string;
}

function transfer(overrides: TransferOverrides = {}): TransferAction {
  return create(TransferActionSchema, {
    wallet: overrides.wallet ?? WALLET.toBase58(),
    network: overrides.network ?? Network.DEVNET,
    recipient: overrides.recipient ?? RECIPIENT.toBase58(),
    amount: overrides.amount ?? "1000000",
    asset: {
      kind:
        overrides.mint === undefined
          ? { case: "nativeSol", value: {} }
          : { case: "tokenMint", value: overrides.mint },
    },
  });
}

function tokenTransferAction(amount = "1000000"): TransferAction {
  return transfer({ amount, mint: MINT.toBase58() });
}

/** A chain with a funded owner and a recipient who is a plain wallet. */
function fundedChain(decimals = 6, held = 5_000_000n): FakeChain {
  const chain = new FakeChain();
  chain.put(MINT, mintAccount({ decimals, supply: 1_000_000_000n }));
  chain.put(
    associatedTokenAddress(WALLET, MINT),
    tokenAccount({ mint: MINT, owner: WALLET, amount: held }),
  );
  chain.put(RECIPIENT, walletAccount());
  return chain;
}

function parse(bytes: Uint8Array): VersionedTransaction {
  return VersionedTransaction.deserialize(bytes);
}

describe("buildTransfer", () => {
  it("builds a SOL transfer the owner's wallet alone can sign", async () => {
    const chain = new FakeChain();
    const built = await buildTransfer(chain, transfer(), NOW);

    const tx = parse(built.transaction);
    const { message } = tx;
    assert.equal(message.header.numRequiredSignatures, 1);
    assert.equal(tx.signatures.length, 1);
    assert.ok(
      tx.signatures.every((signature) => signature.every((byte) => byte === 0)),
      "the sidecar leaves every signature slot empty",
    );
    assert.equal(
      message.staticAccountKeys[0]?.toBase58(),
      WALLET.toBase58(),
      "the owner's wallet is the fee payer",
    );
    assert.equal(message.addressTableLookups.length, 0);
    assert.equal(message.recentBlockhash, TEST_BLOCKHASH);

    assert.equal(message.compiledInstructions.length, 1);
    const [instruction] = message.compiledInstructions;
    assert.equal(
      message.staticAccountKeys[instruction?.programIdIndex ?? -1]?.toBase58(),
      SYSTEM_PROGRAM.toBase58(),
    );
    const data = Buffer.from(instruction?.data ?? new Uint8Array());
    assert.equal(data.readUInt32LE(0), 2, "System instruction 2 is Transfer");
    assert.equal(data.readBigUInt64LE(4), 1_000_000n);
    const accounts = (instruction?.accountKeyIndexes ?? []).map((index) =>
      message.staticAccountKeys[index]?.toBase58(),
    );
    assert.deepEqual(accounts, [WALLET.toBase58(), RECIPIENT.toBase58()]);
  });

  it("reports the fee, the blockhash window, and no rent for a SOL transfer", async () => {
    const chain = new FakeChain();
    chain.feeLamports = 5000n;
    chain.lastValidBlockHeight = 1000n;
    chain.height = 900n;

    const built = await buildTransfer(chain, transfer(), NOW);

    assert.equal(built.feeLamports, 5000n);
    assert.equal(built.rentLamports, 0n);
    assert.equal(built.lastValidBlockHeight, 1000n);
    // 100 blocks left, at 400 ms each.
    assert.equal(built.estimatedExpiryMs, NOW + 100 * 400);
    assert.equal(built.contentHash.length, 32);
  });

  it("falls back to the base fee when the endpoint won't price the message", async () => {
    const chain = new FakeChain();
    chain.feeLamports = undefined;
    const built = await buildTransfer(chain, transfer(), NOW);
    assert.equal(built.feeLamports, 5000n);
  });

  it("estimates no window left once the blockhash's height has passed", async () => {
    const chain = new FakeChain();
    chain.height = 2000n;
    chain.lastValidBlockHeight = 1000n;
    const built = await buildTransfer(chain, transfer(), NOW);
    assert.equal(built.estimatedExpiryMs, NOW);
  });

  it("moves a token between associated accounts, with the mint's own decimals", async () => {
    const chain = fundedChain(6);
    const source = associatedTokenAddress(WALLET, MINT);
    const destination = associatedTokenAddress(RECIPIENT, MINT);
    chain.put(
      destination,
      tokenAccount({ mint: MINT, owner: RECIPIENT, amount: 0n }),
    );

    const built = await buildTransfer(
      chain,
      tokenTransferAction("250000"),
      NOW,
    );

    const { message } = parse(built.transaction);
    // The account exists, so nothing is created and no rent is charged; the idempotent create is
    // still there, because it is what makes the chain check whose account the destination is.
    assert.equal(message.compiledInstructions.length, 2);
    const [, instruction] = message.compiledInstructions;
    assert.equal(
      message.staticAccountKeys[instruction?.programIdIndex ?? -1]?.toBase58(),
      TOKEN_PROGRAM.toBase58(),
    );
    const data = Buffer.from(instruction?.data ?? new Uint8Array());
    assert.equal(
      data.readUInt8(0),
      12,
      "SPL Token instruction 12 is TransferChecked",
    );
    assert.equal(data.readBigUInt64LE(1), 250_000n);
    assert.equal(data.readUInt8(9), 6, "the decimals come from the mint");
    assert.deepEqual(
      (instruction?.accountKeyIndexes ?? []).map((index) =>
        message.staticAccountKeys[index]?.toBase58(),
      ),
      [
        source.toBase58(),
        MINT.toBase58(),
        destination.toBase58(),
        WALLET.toBase58(),
      ],
    );
    assert.equal(built.rentLamports, 0n, "no account had to be created");
  });

  it("creates the recipient's token account when they have none, and discloses the rent", async () => {
    const chain = fundedChain();
    const destination = associatedTokenAddress(RECIPIENT, MINT);

    const built = await buildTransfer(chain, tokenTransferAction(), NOW);

    assert.equal(built.rentLamports, TOKEN_ACCOUNT_RENT);
    const { message } = parse(built.transaction);
    assert.equal(message.compiledInstructions.length, 2);
    const [create, send] = message.compiledInstructions;
    assert.equal(
      message.staticAccountKeys[create?.programIdIndex ?? -1]?.toBase58(),
      ASSOCIATED_TOKEN_PROGRAM.toBase58(),
    );
    assert.deepEqual(
      Array.from(create?.data ?? new Uint8Array()),
      [1],
      "instruction 1 is CreateIdempotent, so a race doesn't fail the transfer",
    );
    assert.deepEqual(
      (create?.accountKeyIndexes ?? [])
        .slice(0, 4)
        .map((index) => message.staticAccountKeys[index]?.toBase58()),
      [
        WALLET.toBase58(),
        destination.toBase58(),
        RECIPIENT.toBase58(),
        MINT.toBase58(),
      ],
    );
    assert.equal(
      message.staticAccountKeys[send?.programIdIndex ?? -1]?.toBase58(),
      TOKEN_PROGRAM.toBase58(),
    );
  });

  it("uses the mint's decimals and never a ticker's", async () => {
    const chain = fundedChain(0, 10_000n);
    chain.put(
      associatedTokenAddress(RECIPIENT, MINT),
      tokenAccount({ mint: MINT, owner: RECIPIENT, amount: 0n }),
    );
    const built = await buildTransfer(chain, tokenTransferAction("7"), NOW);
    const { message } = parse(built.transaction);
    const data = Buffer.from(
      message.compiledInstructions[1]?.data ?? new Uint8Array(),
    );
    assert.equal(data.readBigUInt64LE(1), 7n);
    assert.equal(data.readUInt8(9), 0);
  });

  it("carries the largest amount a u64 holds", async () => {
    const chain = new FakeChain();
    const built = await buildTransfer(
      chain,
      transfer({ amount: "18446744073709551615" }),
      NOW,
    );
    const { message } = parse(built.transaction);
    const data = Buffer.from(
      message.compiledInstructions[0]?.data ?? new Uint8Array(),
    );
    assert.equal(data.readBigUInt64LE(4), 18_446_744_073_709_551_615n);
  });

  describe("refuses what it can't send", () => {
    it("an RPC endpoint serving another network", async () => {
      const chain = new FakeChain();
      chain.genesisHashValue = GENESIS_HASHES.get(Network.MAINNET) ?? "";
      await assert.rejects(
        buildTransfer(chain, transfer(), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("serves mainnet, not devnet"),
      );
    });

    it("an RPC endpoint serving a cluster it doesn't know", async () => {
      const chain = new FakeChain();
      chain.genesisHashValue = key(99).toBase58();
      await assert.rejects(
        buildTransfer(chain, transfer(), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("doesn't know"),
      );
    });

    it("an amount of zero", async () => {
      await assert.rejects(
        buildTransfer(new FakeChain(), transfer({ amount: "0" }), NOW),
        UnsupportedTransfer,
      );
    });

    it("an amount past the u64 maximum", async () => {
      await assert.rejects(
        buildTransfer(
          new FakeChain(),
          transfer({ amount: "18446744073709551616" }),
          NOW,
        ),
        UnsupportedTransfer,
      );
    });

    it("an address that isn't one", async () => {
      await assert.rejects(
        buildTransfer(
          new FakeChain(),
          transfer({ recipient: "not-an-address" }),
          NOW,
        ),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("recipient"),
      );
    });

    it("SOL sent to a token account", async () => {
      const chain = new FakeChain();
      chain.put(
        RECIPIENT,
        tokenAccount({ mint: MINT, owner: WALLET, amount: 1n }),
      );
      await assert.rejects(
        buildTransfer(chain, transfer(), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("is a token account, not a wallet"),
      );
    });

    it("SOL sent to a program", async () => {
      const chain = new FakeChain();
      chain.put(RECIPIENT, programAccount());
      await assert.rejects(
        buildTransfer(chain, transfer(), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("executable program"),
      );
    });

    it("a Token-2022 mint, by name", async () => {
      const chain = fundedChain();
      chain.put(
        MINT,
        mintAccount({
          decimals: 6,
          supply: 1n,
          program: TOKEN_2022_PROGRAM,
        }),
      );
      await assert.rejects(
        buildTransfer(chain, tokenTransferAction(), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("Token-2022"),
      );
    });

    it("an NFT", async () => {
      const chain = fundedChain();
      chain.put(MINT, mintAccount({ decimals: 0, supply: 1n }));
      await assert.rejects(
        buildTransfer(chain, tokenTransferAction("1"), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer && error.message.includes("NFT"),
      );
    });

    it("a mint that doesn't exist", async () => {
      const chain = new FakeChain();
      await assert.rejects(
        buildTransfer(chain, tokenTransferAction(), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("no account at the mint"),
      );
    });

    it("a token the owner has no account for", async () => {
      const chain = fundedChain();
      chain.accounts.delete(associatedTokenAddress(WALLET, MINT).toBase58());
      await assert.rejects(
        buildTransfer(chain, tokenTransferAction(), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("has no token account for"),
      );
    });

    it("more of a token than the owner holds", async () => {
      const chain = fundedChain(6, 100n);
      await assert.rejects(
        buildTransfer(chain, tokenTransferAction("101"), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("holds 100 base units"),
      );
    });

    it("a frozen token account", async () => {
      const chain = fundedChain();
      chain.put(
        associatedTokenAddress(WALLET, MINT),
        tokenAccount({
          mint: MINT,
          owner: WALLET,
          amount: 5_000_000n,
          state: TokenAccountState.FROZEN,
        }),
      );
      await assert.rejects(
        buildTransfer(chain, tokenTransferAction(), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("frozen"),
      );
    });

    it("a token sent to a token account instead of its owner", async () => {
      const chain = fundedChain();
      chain.put(
        RECIPIENT,
        tokenAccount({ mint: MINT, owner: key(44), amount: 0n }),
      );
      await assert.rejects(
        buildTransfer(chain, tokenTransferAction(), NOW),
        (error: Error) =>
          error instanceof UnsupportedTransfer &&
          error.message.includes("is a token account, not a wallet"),
      );
    });

    it("an endpoint that stopped answering", async () => {
      const chain = new FakeChain();
      chain.unavailable = "the endpoint didn't answer";
      await assert.rejects(
        buildTransfer(chain, transfer(), NOW),
        ChainUnavailable,
      );
    });
  });

  it("reads nothing beyond what it needs for SOL", async () => {
    const chain = new FakeChain();
    await buildTransfer(chain, transfer(), NOW);
    assert.deepEqual(chain.calls, [
      "getGenesisHash",
      `getAccountInfo(${RECIPIENT.toBase58()})`,
      "getLatestBlockhash",
      "getFeeForMessage",
      "getBlockHeight",
    ]);
  });
});
