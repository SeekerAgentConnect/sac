/**
 * Building one fresh unsigned transfer (docs/protocol.md#preparation). The request stays an
 * action; this turns it into a transaction at the moment the owner asks to review it, resolving
 * everything that matters — the mint's decimals, both token accounts, the blockhash — from the
 * chain rather than from what the agent wrote.
 *
 * Nothing here signs or sends. The bytes it returns carry empty signature slots, and only the
 * owner's wallet can fill them.
 */
import { createHash } from "node:crypto";

import {
  PublicKey,
  SystemProgram,
  TransactionMessage,
  VersionedTransaction,
  type TransactionInstruction,
} from "@solana/web3.js";

import { parseBaseUnits } from "@seeker-vault/server-sdk";
import {
  Network,
  type Asset,
  type TransferAction,
} from "@seeker-vault/server-sdk/protocol";
import {
  TOKEN_2022_PROGRAM,
  TOKEN_PROGRAM,
  associatedTokenAddress,
} from "./addresses.ts";
import { GENESIS_HASHES, networkOfGenesisHash } from "./network.ts";
import type { ChainAccount, ChainReader } from "./rpc.ts";
import {
  TOKEN_ACCOUNT_BYTES,
  TokenAccountState,
  createAssociatedTokenAccount,
  decodeMint,
  decodeTokenAccount,
  transferChecked,
} from "./token.ts";

/**
 * The transfer can't be carried out as asked, and waiting won't change that: the asset isn't one
 * Stage 4 supports, an account is missing or frozen, or the RPC serves another network. It is the
 * agent's or the owner's problem to fix, not a transient failure.
 */
export class UnsupportedTransfer extends Error {
  constructor(message: string) {
    super(message);
    this.name = "UnsupportedTransfer";
  }
}

/** One built transaction and everything the owner and the phone need to judge it. */
export interface BuiltTransaction {
  /** The unsigned transaction in wire format, with empty signature slots. */
  readonly transaction: Uint8Array;
  /** SHA-256 of `transaction`: what an approval names. */
  readonly contentHash: Uint8Array;
  readonly lastValidBlockHeight: bigint;
  /** When `lastValidBlockHeight` is expected to pass, in epoch milliseconds. */
  readonly estimatedExpiryMs: number;
  readonly feeLamports: bigint;
  /** Lamports spent on giving the recipient a token account, or 0 when none is created. */
  readonly rentLamports: bigint;
}

/** A Solana slot is scheduled every 400 ms, which is how the expiry estimate is made. */
const BLOCK_MS = 400;
/** The base fee per signature, used only when the endpoint won't price the message. */
const BASE_FEE_LAMPORTS = 5000n;

/**
 * Checks that the endpoint serves the network the request names. A wrong endpoint would otherwise
 * produce a transaction that is valid — on the wrong chain.
 */
export async function assertNetwork(
  rpc: ChainReader,
  network: Network,
): Promise<void> {
  const expected = GENESIS_HASHES.get(network);
  if (expected === undefined) {
    throw new UnsupportedTransfer("the request names no network");
  }
  const hash = await rpc.genesisHash();
  if (hash === expected) return;
  const served = networkOfGenesisHash(hash);
  throw new UnsupportedTransfer(
    served === undefined
      ? "the configured Solana RPC endpoint serves a cluster this sidecar doesn't know, not " +
          `${name(network)}; point SOLANA_RPC_URL at ${name(network)}`
      : `the configured Solana RPC endpoint serves ${name(served)}, not ${name(network)}`,
  );
}

/**
 * Checks that the asset is one Stage 4 can transfer. Native SOL always is, and nothing is read for
 * it. `vault_transfer` calls this before it stores a request, so an agent hears about an
 * unsupported token at once rather than after the owner has opened it.
 */
export async function assertSupportedAsset(
  rpc: ChainReader,
  asset: Asset | undefined,
): Promise<void> {
  const mint = mintOf(asset);
  if (mint !== undefined) await mintDecimals(rpc, mint);
}

/**
 * The mint's decimals, once it is one Stage 4 can transfer. It must be a classic SPL mint:
 * Token-2022's extensions can change what a transfer does after the owner has reviewed it, and an
 * NFT isn't an amount they can review as one.
 */
export async function mintDecimals(
  rpc: ChainReader,
  mint: string,
): Promise<number> {
  const account = await rpc.account(mint);
  if (account === undefined) {
    throw new UnsupportedTransfer(`there is no account at the mint ${mint}`);
  }
  if (account.owner === TOKEN_2022_PROGRAM.toBase58()) {
    throw new UnsupportedTransfer(
      `${mint} is a Token-2022 mint; this sidecar transfers classic SPL tokens only, because a ` +
        "Token-2022 extension can change what a transfer does after the owner has reviewed it",
    );
  }
  if (account.owner !== TOKEN_PROGRAM.toBase58()) {
    throw new UnsupportedTransfer(`${mint} is not an SPL token mint`);
  }
  const decoded = decodeMint(account.data);
  if (decoded === undefined || !decoded.initialized) {
    throw new UnsupportedTransfer(`${mint} is not an initialized SPL mint`);
  }
  if (decoded.decimals === 0 && decoded.supply === 1n) {
    throw new UnsupportedTransfer(
      `${mint} looks like an NFT (no decimals and a supply of one); this sidecar transfers ` +
        "fungible SPL tokens only",
    );
  }
  return decoded.decimals;
}

/**
 * Builds a fresh unsigned transaction for `action`. Every call reads the chain again, so each
 * preparation is a new version with its own blockhash: an approval of an earlier one can't be
 * used for this one.
 */
export async function buildTransfer(
  rpc: ChainReader,
  action: TransferAction,
  nowMs: number,
): Promise<BuiltTransaction> {
  await assertNetwork(rpc, action.network);
  const amount = parseBaseUnits(action.amount);
  if (amount === undefined || amount === 0n) {
    throw new UnsupportedTransfer(
      "the request's amount isn't a whole number of base units from 1 to the u64 maximum",
    );
  }
  const wallet = address(action.wallet, "wallet");
  const recipient = address(action.recipient, "recipient");
  const mint = mintOf(action.asset);

  const built =
    mint === undefined
      ? await solTransfer(rpc, wallet, recipient, amount)
      : await tokenTransfer(
          rpc,
          wallet,
          recipient,
          address(mint, "mint"),
          amount,
        );

  const { blockhash, lastValidBlockHeight } = await rpc.latestBlockhash();
  const message = new TransactionMessage({
    payerKey: wallet,
    recentBlockhash: blockhash,
    instructions: built.instructions,
  }).compileToV0Message();
  const transaction = new VersionedTransaction(message).serialize();
  const messageBase64 = Buffer.from(message.serialize()).toString("base64");
  const fee = await rpc.feeForMessage(messageBase64);
  const height = await rpc.blockHeight();
  const remaining =
    lastValidBlockHeight > height ? lastValidBlockHeight - height : 0n;

  return {
    transaction,
    contentHash: createHash("sha256").update(transaction).digest(),
    lastValidBlockHeight,
    estimatedExpiryMs: nowMs + Number(remaining) * BLOCK_MS,
    feeLamports:
      fee ?? BASE_FEE_LAMPORTS * BigInt(message.header.numRequiredSignatures),
    rentLamports: built.rentLamports,
  };
}

interface Instructions {
  readonly instructions: TransactionInstruction[];
  readonly rentLamports: bigint;
}

/** SOL moves with one System program instruction, and creates nothing. */
async function solTransfer(
  rpc: ChainReader,
  wallet: PublicKey,
  recipient: PublicKey,
  lamports: bigint,
): Promise<Instructions> {
  const account = await rpc.account(recipient.toBase58());
  if (account !== undefined) {
    if (isTokenProgram(account.owner)) {
      throw new UnsupportedTransfer(
        `${recipient.toBase58()} is a token account, not a wallet; SOL sent to it could not be ` +
          "spent. Give the owner's own address instead",
      );
    }
    if (account.executable) {
      throw new UnsupportedTransfer(
        `${recipient.toBase58()} is an executable program, not a wallet`,
      );
    }
  }
  return {
    instructions: [
      SystemProgram.transfer({
        fromPubkey: wallet,
        toPubkey: recipient,
        lamports,
      }),
    ],
    rentLamports: 0n,
  };
}

/**
 * A token moves between associated token accounts: the owner's own, which must already hold
 * enough, and the recipient's, which the transaction creates when it doesn't exist yet and has
 * the chain vouch for when it does.
 */
async function tokenTransfer(
  rpc: ChainReader,
  wallet: PublicKey,
  recipient: PublicKey,
  mint: PublicKey,
  amount: bigint,
): Promise<Instructions> {
  const decimals = await mintDecimals(rpc, mint.toBase58());

  const source = associatedTokenAddress(wallet, mint);
  const sourceAccount = await rpc.account(source.toBase58());
  if (sourceAccount === undefined) {
    throw new UnsupportedTransfer(
      `${wallet.toBase58()} has no token account for ${mint.toBase58()}, so it holds none of it`,
    );
  }
  const held = tokenAccount(sourceAccount, source, mint, wallet, "the owner's");
  if (held.amount < amount) {
    throw new UnsupportedTransfer(
      `${wallet.toBase58()} holds ${held.amount.toString()} base units of ${mint.toBase58()}, ` +
        `and the request sends ${amount.toString()}`,
    );
  }

  const recipientAccount = await rpc.account(recipient.toBase58());
  if (
    recipientAccount !== undefined &&
    isTokenProgram(recipientAccount.owner)
  ) {
    throw new UnsupportedTransfer(
      `${recipient.toBase58()} is a token account, not a wallet; give the account owner's own ` +
        "address, and the transfer finds or creates their token account itself",
    );
  }

  const destination = associatedTokenAddress(recipient, mint);
  const destinationAccount = await rpc.account(destination.toBase58());
  let rentLamports = 0n;
  if (destinationAccount === undefined) {
    rentLamports = await rpc.rentExemption(TOKEN_ACCOUNT_BYTES);
  } else {
    tokenAccount(
      destinationAccount,
      destination,
      mint,
      recipient,
      "the recipient's",
    );
  }
  // CreateIdempotent goes in whether or not the account exists yet, and costs nothing when it
  // does. It is what makes the destination's owner a fact the chain checks: the
  // associated-token-account program re-derives the address, reads the account, and fails the
  // whole transaction unless it is the recipient's for this mint. The phone cannot read a chain,
  // and a classic SPL account's authority can be handed to somebody else after its address was
  // derived, so without this instruction an address is only an address
  // (docs/security.md#inspecting-a-transfer).
  const instructions: TransactionInstruction[] = [
    createAssociatedTokenAccount(wallet, destination, recipient, mint),
    transferChecked(
      { source, mint, destination, authority: wallet },
      amount,
      decimals,
    ),
  ];
  return { instructions, rentLamports };
}

/** Reads a token account and checks it is the initialized, unfrozen one this transfer expects. */
function tokenAccount(
  account: ChainAccount,
  address: PublicKey,
  mint: PublicKey,
  owner: PublicKey,
  whose: string,
) {
  if (account.owner !== TOKEN_PROGRAM.toBase58()) {
    throw new UnsupportedTransfer(
      `${address.toBase58()} is not a classic SPL token account`,
    );
  }
  const decoded = decodeTokenAccount(account.data);
  if (
    decoded === undefined ||
    decoded.state === TokenAccountState.UNINITIALIZED ||
    decoded.mint !== mint.toBase58() ||
    decoded.owner !== owner.toBase58()
  ) {
    throw new UnsupportedTransfer(
      `${address.toBase58()} is not ${whose} token account for ${mint.toBase58()}`,
    );
  }
  if (decoded.state === TokenAccountState.FROZEN) {
    throw new UnsupportedTransfer(
      `${whose} token account for ${mint.toBase58()} is frozen, so the transfer would fail`,
    );
  }
  return decoded;
}

function isTokenProgram(owner: string): boolean {
  return (
    owner === TOKEN_PROGRAM.toBase58() ||
    owner === TOKEN_2022_PROGRAM.toBase58()
  );
}

/** The mint a token transfer names, or undefined for native SOL. */
function mintOf(asset: Asset | undefined): string | undefined {
  return asset?.kind.case === "tokenMint" ? asset.kind.value : undefined;
}

/** A stored address, re-parsed here: the request's rules already refused anything else. */
function address(value: string, field: string): PublicKey {
  try {
    return new PublicKey(value);
  } catch {
    throw new UnsupportedTransfer(`${field} is not a Solana address`);
  }
}

function name(network: Network): string {
  return (Network[network] ?? "unspecified").toLowerCase();
}
