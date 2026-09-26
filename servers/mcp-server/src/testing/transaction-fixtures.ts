/**
 * The transfer fixtures both runtimes share (docs/testing/transaction-fixtures.md).
 *
 * Every case is built here, by the same code that builds a real preparation, and decoded on the
 * phone by `TransactionFixturesTest`. That is what pins the format: the phone's parser is checked
 * against transactions this sidecar actually produces, not against a description of them.
 *
 * The adversarial cases are built with the same library, on purpose. A tampered transaction has to
 * be a real, well-formed transaction — otherwise it would only prove that the phone rejects
 * nonsense, and what matters is that it rejects a transaction that is perfectly valid and simply
 * isn't the one the owner was asked to approve.
 *
 * Run `node servers/mcp-server/src/testing/transaction-fixtures.ts` after changing a case.
 *
 * It lives in `testing/` because it writes a file, which only test-only code and `storage/` may do
 * (stage-boundary.test.ts). Nothing here ships.
 */
import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import {
  PublicKey,
  SystemProgram,
  TransactionInstruction,
  TransactionMessage,
  VersionedTransaction,
} from "@solana/web3.js";

import {
  Network,
  type TransferAction,
} from "@seeker-vault/server-sdk/protocol";
import {
  ASSOCIATED_TOKEN_PROGRAM,
  SYSTEM_PROGRAM,
  TOKEN_PROGRAM,
  associatedTokenAddress,
} from "../solana/addresses.ts";
import { buildTransfer } from "../solana/transfer.ts";
import {
  FakeChain,
  TEST_BLOCKHASH,
  mintAccount,
  tokenAccount,
  walletAccount,
} from "./chain.ts";

/** Where the committed file lives, relative to the repository root. */
export const FIXTURE_PATH = "fixtures/transactions/cases.json";

const FIXTURE_FILE = fileURLToPath(
  new URL("../../../../fixtures/transactions/cases.json", import.meta.url),
);

/** Deterministic addresses, so a rebuilt file is byte-identical. */
const WALLET = address(11);
const RECIPIENT = address(22);
const MINT = address(33);
const OTHER = address(44);
const OTHER_MINT = address(55);
/** A program the phone has no reason to know: it stands for "something unread". */
const UNKNOWN_PROGRAM = address(66);
const COMPUTE_BUDGET = new PublicKey(
  "ComputeBudget111111111111111111111111111111",
);

function address(seed: number): PublicKey {
  return new PublicKey(Uint8Array.from({ length: 32 }, () => seed));
}

/** The request as the phone sees it: the fields of the stored, immutable action. */
export interface FixtureRequest {
  readonly wallet: string;
  readonly network: "mainnet" | "devnet" | "testnet";
  readonly recipient: string;
  readonly amount: string;
  /** Absent for native SOL. */
  readonly tokenMint?: string;
  /** The agent's own words, which are never evidence about the transaction. */
  readonly note?: string;
}

export interface FixtureFacts {
  /** The wallet the bytes prove the funds reach, or null when they prove none. */
  readonly recipient: string | null;
  /** The token account the tokens go to; null for SOL. */
  readonly destinationAccount: string | null;
  readonly amount: string;
  readonly mint: string | null;
  readonly decimals: number;
  /** Whether the transaction has the chain vouch for the recipient's token account. */
  readonly ensuresRecipientAccount: boolean;
}

export interface FixtureCase {
  readonly name: string;
  readonly description: string;
  readonly request: FixtureRequest;
  /** The prepared transaction, in standard base64. */
  readonly transaction: string;
  /** The sidecar's content hash, in base64. Wrong on purpose in one case. */
  readonly contentHash: string;
  readonly version: number;
  /** What the phone must conclude: "verified", "unverified", or "invalid". */
  readonly verdict: "verified" | "unverified" | "invalid";
  /** The findings the phone must report, sorted by name. */
  readonly findings: readonly string[];
  /** What the phone must read out of the bytes, or null when it can't get that far. */
  readonly facts: FixtureFacts | null;
}

export interface FixtureFile {
  readonly note: string;
  readonly cases: readonly FixtureCase[];
}

function transferAction(
  overrides: Partial<{
    wallet: PublicKey;
    recipient: PublicKey;
    amount: string;
    mint: PublicKey;
  }> = {},
): TransferAction {
  const mint = overrides.mint;
  return {
    $typeName: "seekervault.request.v1.TransferAction",
    wallet: (overrides.wallet ?? WALLET).toBase58(),
    network: Network.DEVNET,
    recipient: (overrides.recipient ?? RECIPIENT).toBase58(),
    amount: overrides.amount ?? "1500000",
    asset: {
      $typeName: "seekervault.request.v1.Asset",
      kind:
        mint === undefined
          ? {
              case: "nativeSol",
              value: { $typeName: "seekervault.request.v1.Asset.NativeSol" },
            }
          : { case: "tokenMint", value: mint.toBase58() },
    },
  };
}

function requestOf(action: TransferAction, note?: string): FixtureRequest {
  const mint =
    action.asset?.kind.case === "tokenMint"
      ? action.asset.kind.value
      : undefined;
  return {
    wallet: action.wallet,
    network: "devnet",
    recipient: action.recipient,
    amount: action.amount,
    ...(mint === undefined ? {} : { tokenMint: mint }),
    ...(note === undefined ? {} : { note }),
  };
}

/** A chain where the owner holds the token and the recipient is a plain wallet. */
function chainWith(options: { destination?: boolean; decimals?: number } = {}) {
  const chain = new FakeChain();
  chain.put(
    MINT,
    mintAccount({ decimals: options.decimals ?? 6, supply: 1_000_000_000n }),
  );
  chain.put(
    associatedTokenAddress(WALLET, MINT),
    tokenAccount({
      mint: MINT,
      owner: WALLET,
      amount: 18_446_744_073_709_551_615n,
    }),
  );
  chain.put(RECIPIENT, walletAccount());
  chain.put(OTHER, walletAccount());
  if (options.destination === true) {
    chain.put(
      associatedTokenAddress(RECIPIENT, MINT),
      tokenAccount({ mint: MINT, owner: RECIPIENT, amount: 0n }),
    );
  }
  return chain;
}

const NOW = Date.UTC(2026, 8, 12, 12, 0, 0);

/** A case built by the real builder: exactly what a phone would be handed. */
async function built(
  name: string,
  description: string,
  action: TransferAction,
  expectation: Omit<
    FixtureCase,
    | "name"
    | "description"
    | "request"
    | "transaction"
    | "contentHash"
    | "version"
  >,
  options: { chain?: FakeChain; request?: TransferAction; note?: string } = {},
): Promise<FixtureCase> {
  const chain = options.chain ?? chainWith();
  const prepared = await buildTransfer(chain, action, NOW);
  return {
    name,
    description,
    // The request the owner was shown can differ from the action the transaction was built for:
    // that is exactly what a tampered preparation looks like.
    request: requestOf(options.request ?? action, options.note),
    transaction: Buffer.from(prepared.transaction).toString("base64"),
    contentHash: Buffer.from(prepared.contentHash).toString("base64"),
    version: 1,
    ...expectation,
  };
}

/** A case assembled here, for shapes the sidecar would never build. */
function crafted(
  name: string,
  description: string,
  request: FixtureRequest,
  instructions: TransactionInstruction[],
  expectation: Omit<
    FixtureCase,
    | "name"
    | "description"
    | "request"
    | "transaction"
    | "contentHash"
    | "version"
  >,
  options: { payer?: PublicKey; signed?: boolean; legacy?: boolean } = {},
): FixtureCase {
  const message = new TransactionMessage({
    payerKey: options.payer ?? WALLET,
    recentBlockhash: TEST_BLOCKHASH,
    instructions,
  });
  const compiled =
    options.legacy === true
      ? message.compileToLegacyMessage()
      : message.compileToV0Message();
  const transaction = new VersionedTransaction(compiled);
  if (options.signed === true) {
    // A signature slot that isn't empty: the wallet must never be handed one of these.
    transaction.signatures[0] = new Uint8Array(64).fill(7);
  }
  const bytes = transaction.serialize();
  return {
    name,
    description,
    request,
    transaction: Buffer.from(bytes).toString("base64"),
    contentHash: createHash("sha256").update(bytes).digest("base64"),
    version: 1,
    ...expectation,
  };
}

function solFacts(amount: string, recipient = RECIPIENT): FixtureFacts {
  return {
    recipient: recipient.toBase58(),
    destinationAccount: null,
    amount,
    mint: null,
    decimals: 9,
    ensuresRecipientAccount: false,
  };
}

function tokenFacts(
  amount: string,
  carriesAccountInstruction: boolean,
  decimals = 6,
  mint: PublicKey = MINT,
): FixtureFacts {
  const destination = associatedTokenAddress(RECIPIENT, mint);
  // The associated-account instruction vouches for the recipient's account for the mint it names,
  // and for no other. A mint the request doesn't name derives a different account, so carrying the
  // instruction there vouches for somebody else's: the transaction establishes nothing about the
  // account this request's transfer was meant to reach, and so it names no wallet and ensures
  // nothing.
  const ensuresRecipientAccount = carriesAccountInstruction && mint === MINT;
  return {
    recipient: ensuresRecipientAccount ? RECIPIENT.toBase58() : null,
    destinationAccount: destination.toBase58(),
    amount,
    mint: mint.toBase58(),
    decimals,
    ensuresRecipientAccount,
  };
}

/** Every shared case, in a fixed order. */
export async function transactionFixtures(): Promise<FixtureFile> {
  const solTransfer = transferAction({ amount: "2500000000" });
  const tokenTransfer = transferAction({ mint: MINT, amount: "1500000" });

  const cases: FixtureCase[] = [
    await built(
      "sol_transfer",
      "A plain SOL transfer: one System instruction, the owner's wallet as the only signer.",
      solTransfer,
      { verdict: "verified", findings: [], facts: solFacts("2500000000") },
    ),
    await built(
      "token_transfer_existing_account",
      "An SPL token transfer to a recipient who already has a token account. The idempotent " +
        "associated-account instruction is there all the same: it costs nothing, and it is what " +
        "has the chain confirm the account is still the recipient's.",
      tokenTransfer,
      {
        verdict: "verified",
        findings: [],
        facts: tokenFacts("1500000", true),
      },
      { chain: chainWith({ destination: true }) },
    ),
    await built(
      "token_transfer_creates_account",
      "The recipient has no token account yet, so the transaction also creates one.",
      tokenTransfer,
      { verdict: "verified", findings: [], facts: tokenFacts("1500000", true) },
    ),
    await built(
      "token_transfer_max_amount",
      "The largest amount a u64 holds, which no floating-point path could carry.",
      transferAction({ mint: MINT, amount: "18446744073709551615" }),
      {
        verdict: "verified",
        findings: [],
        facts: tokenFacts("18446744073709551615", true),
      },
    ),
    await built(
      "token_transfer_zero_decimals",
      "A mint with no decimals: the amount reads the same in base units and whole tokens.",
      transferAction({ mint: MINT, amount: "7" }),
      { verdict: "verified", findings: [], facts: tokenFacts("7", true, 0) },
      { chain: chainWith({ decimals: 0 }) },
    ),
    await built(
      "fake_ticker_in_the_note",
      "The agent's note names a well-known ticker; the transfer is of an unrelated mint. The " +
        "phone shows the mint address and base units, and never a name, so there is nothing for " +
        "the note to contradict.",
      tokenTransfer,
      { verdict: "verified", findings: [], facts: tokenFacts("1500000", true) },
      { note: "Sending 1.5 USDC to the treasury" },
    ),
    await built(
      "note_disagrees_with_the_amount",
      "The agent's note says a tenth of what the instruction carries. The request and the " +
        "transaction agree, so the transfer is sound; the prose is not evidence about either, " +
        "and the base units the phone shows — and a threshold compares — are the instruction's " +
        "own.",
      solTransfer,
      { verdict: "verified", findings: [], facts: solFacts("2500000000") },
      { note: "Sending 0.25 SOL for the test run" },
    ),
    await built(
      "changed_recipient",
      "A transaction that pays somebody else, presented against the original request.",
      transferAction({ recipient: OTHER, amount: "2500000000" }),
      {
        verdict: "invalid",
        findings: ["RecipientMismatch"],
        facts: solFacts("2500000000", OTHER),
      },
      { request: solTransfer },
    ),
    await built(
      "changed_amount",
      "The same recipient, a larger amount than the owner was asked to approve.",
      transferAction({ amount: "9500000000" }),
      {
        verdict: "invalid",
        findings: ["AmountMismatch"],
        facts: solFacts("9500000000"),
      },
      { request: solTransfer },
    ),
    await built(
      "changed_mint",
      "A different token from the one the request names, to the right recipient.",
      transferAction({ mint: OTHER_MINT, amount: "1500000" }),
      {
        verdict: "invalid",
        findings: [
          "AccountCreationForSomeoneElse",
          "DestinationNotRecipientsAccount",
          "MintMismatch",
          "SourceNotOwnersAccount",
        ],
        facts: tokenFacts("1500000", true, 6, OTHER_MINT),
      },
      {
        chain: (() => {
          const chain = chainWith();
          chain.put(
            OTHER_MINT,
            mintAccount({ decimals: 6, supply: 1_000_000n }),
          );
          chain.put(
            associatedTokenAddress(WALLET, OTHER_MINT),
            tokenAccount({
              mint: OTHER_MINT,
              owner: WALLET,
              amount: 9_000_000n,
            }),
          );
          return chain;
        })(),
        request: tokenTransfer,
      },
    ),
  ];

  const solRequest = requestOf(solTransfer);
  cases.push(
    crafted(
      "extra_transfer",
      "The transfer the owner approved, and a second one to somebody else alongside it.",
      solRequest,
      [
        SystemProgram.transfer({
          fromPubkey: WALLET,
          toPubkey: RECIPIENT,
          lamports: 2_500_000_000n,
        }),
        SystemProgram.transfer({
          fromPubkey: WALLET,
          toPubkey: OTHER,
          lamports: 1n,
        }),
      ],
      {
        verdict: "invalid",
        findings: ["ExtraTransfer"],
        facts: solFacts("2500000000"),
      },
    ),
    crafted(
      "extra_signer",
      "Another account would have to sign as well, so the owner's approval would carry " +
        "somebody else's.",
      solRequest,
      [
        SystemProgram.transfer({
          fromPubkey: WALLET,
          toPubkey: RECIPIENT,
          lamports: 2_500_000_000n,
        }),
        new TransactionInstruction({
          programId: UNKNOWN_PROGRAM,
          keys: [{ pubkey: OTHER, isSigner: true, isWritable: false }],
          data: Buffer.alloc(0),
        }),
      ],
      {
        verdict: "invalid",
        findings: ["ExtraSigner", "UnrecognizedInstruction"],
        facts: solFacts("2500000000"),
      },
    ),
    crafted(
      "wrong_fee_payer",
      "Someone else pays and signs, which is not the wallet the request is bound to.",
      solRequest,
      [
        SystemProgram.transfer({
          fromPubkey: WALLET,
          toPubkey: RECIPIENT,
          lamports: 2_500_000_000n,
        }),
      ],
      {
        verdict: "invalid",
        findings: ["ExtraSigner", "FeePayerNotTheWallet"],
        facts: solFacts("2500000000"),
      },
      { payer: OTHER },
    ),
    crafted(
      "token_delegate_alongside_the_transfer",
      "An SPL Token Approve rides along with the transfer. The token program is one a transfer " +
        "may call, which is exactly why a program's name is not permission for every instruction " +
        "it offers: this one hands the account to a delegate.",
      requestOf(tokenTransfer),
      [
        createIdempotentInstruction(),
        transferCheckedInstruction(),
        new TransactionInstruction({
          programId: TOKEN_PROGRAM,
          keys: [
            {
              pubkey: new PublicKey(associatedTokenAddress(WALLET, MINT)),
              isSigner: false,
              isWritable: true,
            },
            { pubkey: OTHER, isSigner: false, isWritable: false },
            { pubkey: WALLET, isSigner: true, isWritable: false },
          ],
          // Instruction 4 is Approve.
          data: Buffer.concat([Buffer.from([4]), u64(1_000_000n)]),
        }),
      ],
      {
        verdict: "invalid",
        findings: ["UnreadableValueInstruction"],
        facts: tokenFacts("1500000", true),
      },
    ),
    crafted(
      "token_destination_authority_changed",
      "The destination is exactly the address the recipient's associated token account derives " +
        "to, and the transaction does nothing to establish that it is still theirs. A classic " +
        "SPL token account's authority can be handed to somebody else after the address was " +
        "derived, so the phone can name the account and nobody behind it.",
      requestOf(tokenTransfer),
      [transferCheckedInstruction()],
      {
        verdict: "invalid",
        findings: ["DestinationOwnerUnchecked"],
        facts: tokenFacts("1500000", false),
      },
    ),
    crafted(
      "unknown_program_alongside_the_transfer",
      "A memo-like instruction from a program the phone doesn't read. The transfer itself " +
        "matches, but the review doesn't cover the whole transaction.",
      solRequest,
      [
        SystemProgram.transfer({
          fromPubkey: WALLET,
          toPubkey: RECIPIENT,
          lamports: 2_500_000_000n,
        }),
        new TransactionInstruction({
          programId: UNKNOWN_PROGRAM,
          keys: [],
          data: Buffer.from("hello", "utf8"),
        }),
      ],
      {
        verdict: "unverified",
        findings: ["UnrecognizedInstruction"],
        facts: solFacts("2500000000"),
      },
    ),
    crafted(
      "compute_budget_priority_fee",
      "A compute-budget price the owner would also pay. It is read, not merely tolerated.",
      solRequest,
      [
        new TransactionInstruction({
          programId: COMPUTE_BUDGET,
          keys: [],
          data: Buffer.concat([Buffer.from([3]), u64(25_000n)]),
        }),
        SystemProgram.transfer({
          fromPubkey: WALLET,
          toPubkey: RECIPIENT,
          lamports: 2_500_000_000n,
        }),
      ],
      { verdict: "verified", findings: [], facts: solFacts("2500000000") },
    ),
    crafted(
      "already_signed",
      "A signature slot is already filled, so this is not an unsigned transaction to review.",
      solRequest,
      [
        SystemProgram.transfer({
          fromPubkey: WALLET,
          toPubkey: RECIPIENT,
          lamports: 2_500_000_000n,
        }),
      ],
      {
        verdict: "invalid",
        findings: ["AlreadySigned"],
        facts: solFacts("2500000000"),
      },
      { signed: true },
    ),
    crafted(
      "legacy_message",
      "A legacy message, which has no version byte. It reads the same way and is judged the same.",
      solRequest,
      [
        SystemProgram.transfer({
          fromPubkey: WALLET,
          toPubkey: RECIPIENT,
          lamports: 2_500_000_000n,
        }),
      ],
      { verdict: "verified", findings: [], facts: solFacts("2500000000") },
      { legacy: true },
    ),
  );

  // Bytes that aren't a transaction at all, and one whose hash doesn't match its own bytes.
  const sound = cases[0];
  if (sound === undefined) throw new Error("the first case is built above");
  const soundBytes = Buffer.from(sound.transaction, "base64");
  cases.push(
    {
      name: "truncated",
      description:
        "The last byte is missing, so a length runs past the end of the input.",
      request: solRequest,
      transaction: soundBytes
        .subarray(0, soundBytes.length - 1)
        .toString("base64"),
      contentHash: createHash("sha256")
        .update(soundBytes.subarray(0, soundBytes.length - 1))
        .digest("base64"),
      version: 1,
      verdict: "invalid",
      findings: ["Malformed"],
      facts: null,
    },
    {
      name: "trailing_bytes",
      description:
        "A well-formed transaction with something appended. Content nobody read is exactly what " +
        "must not be approved.",
      request: solRequest,
      transaction: Buffer.concat([soundBytes, Buffer.from([0])]).toString(
        "base64",
      ),
      contentHash: createHash("sha256")
        .update(Buffer.concat([soundBytes, Buffer.from([0])]))
        .digest("base64"),
      version: 1,
      verdict: "invalid",
      findings: ["Malformed"],
      facts: null,
    },
    {
      name: "content_hash_mismatch",
      description:
        "The bytes are sound, but the sidecar's own hash isn't theirs: the preparation doesn't " +
        "even agree with itself.",
      request: solRequest,
      transaction: sound.transaction,
      contentHash: Buffer.alloc(32).toString("base64"),
      version: 1,
      verdict: "invalid",
      findings: ["HashMismatch"],
      facts: solFacts("2500000000"),
    },
  );

  return {
    note:
      "Built by servers/mcp-server/src/testing/transaction-fixtures.ts and decoded by the phone's " +
      "TransactionFixturesTest. Run `node servers/mcp-server/src/testing/transaction-fixtures.ts` to rebuild.",
    cases,
  };
}

/** The associated-account CreateIdempotent the sidecar puts in front of every token transfer. */
function createIdempotentInstruction(): TransactionInstruction {
  const destination = new PublicKey(associatedTokenAddress(RECIPIENT, MINT));
  return new TransactionInstruction({
    programId: ASSOCIATED_TOKEN_PROGRAM,
    keys: [
      { pubkey: WALLET, isSigner: true, isWritable: true },
      { pubkey: destination, isSigner: false, isWritable: true },
      { pubkey: RECIPIENT, isSigner: false, isWritable: false },
      { pubkey: MINT, isSigner: false, isWritable: false },
      { pubkey: SYSTEM_PROGRAM, isSigner: false, isWritable: false },
      { pubkey: TOKEN_PROGRAM, isSigner: false, isWritable: false },
    ],
    data: Buffer.from([1]),
  });
}

/** SPL Token TransferChecked, as the sidecar builds it, for the crafted cases. */
function transferCheckedInstruction(): TransactionInstruction {
  const source = new PublicKey(associatedTokenAddress(WALLET, MINT));
  const destination = new PublicKey(associatedTokenAddress(RECIPIENT, MINT));
  return new TransactionInstruction({
    programId: TOKEN_PROGRAM,
    keys: [
      { pubkey: source, isSigner: false, isWritable: true },
      { pubkey: MINT, isSigner: false, isWritable: false },
      { pubkey: destination, isSigner: false, isWritable: true },
      { pubkey: WALLET, isSigner: true, isWritable: false },
    ],
    data: Buffer.concat([Buffer.from([12]), u64(1_500_000n), Buffer.from([6])]),
  });
}

function u64(value: bigint): Buffer {
  const buffer = Buffer.alloc(8);
  buffer.writeBigUInt64LE(value);
  return buffer;
}

/** The file as it is committed: pretty-printed, with a trailing newline. */
export function serializeFixtures(file: FixtureFile): string {
  return `${JSON.stringify(file, null, 2)}\n`;
}

if (import.meta.main) {
  writeFileSync(FIXTURE_FILE, serializeFixtures(await transactionFixtures()));
  console.log(`wrote ${FIXTURE_PATH}`);
}
