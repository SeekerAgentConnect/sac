/**
 * Whether the message the chain holds is the one the owner approved, allowing for what a wallet
 * adds of its own before it sends (the phone's `transactions/ApprovedMessage.kt`, the same rule).
 *
 * A wallet asked to sign and send may append instructions: Seeker Wallet adds a compute-unit limit
 * and price, and Lighthouse assertions that make the transaction fail if the accounts it touches
 * changed. Neither moves value or acts for anybody. So two messages are the same approved one when
 * they are equal byte for byte, or when all of this holds:
 *
 * - the same version, the same signers in the same order with the same writability, so the same
 *   fee payer;
 * - the same recent blockhash and the same address lookup tables with the same indexes;
 * - with every compute-budget and Lighthouse instruction left out of both, the same instructions in
 *   the same order: program, data, and accounts in the same positions with the same flags.
 *
 * Anything else is not a match, and neither is a message this cannot take apart: a result is never
 * settled on a guess. It reads bytes and reaches nothing.
 */

import { encodeBase58 } from "./action.ts";

/** The programs a wallet may add instructions for without making the transaction another one. */
export const WALLET_ADDED_PROGRAMS: ReadonlySet<string> = new Set([
  "ComputeBudget111111111111111111111111111111",
  // Lighthouse: assertions on account state that abort the transaction when it differs.
  "L2TExMFKdjpN9kozasaurPirfHy9P8sbXoAN1qA3S95",
]);

export function isApprovedMessage(
  approved: Uint8Array,
  onChain: Uint8Array,
): boolean {
  if (Buffer.from(approved).equals(Buffer.from(onChain))) return true;
  const mine = parseMessage(approved);
  const theirs = parseMessage(onChain);
  if (mine === undefined || theirs === undefined) return false;
  if (mine.version !== theirs.version) return false;
  if (mine.blockhash !== theirs.blockhash) return false;
  if (mine.lookups !== theirs.lookups) return false;
  if (mine.required !== theirs.required) return false;
  for (let index = 0; index < mine.required; index += 1) {
    if (mine.keys[index] !== theirs.keys[index]) return false;
    if (writable(mine, index) !== writable(theirs, index)) return false;
  }
  const kept = mine.instructions.filter((it) => !walletAdded(mine, it));
  const sent = theirs.instructions.filter((it) => !walletAdded(theirs, it));
  if (kept.length !== sent.length) return false;
  return kept.every((it, at) => sameInstruction(mine, it, theirs, sent[at]!));
}

interface Instruction {
  readonly program: number;
  readonly accounts: readonly number[];
  readonly data: string;
}

interface Message {
  readonly version: number | undefined;
  readonly required: number;
  readonly readonlySigned: number;
  readonly readonlyUnsigned: number;
  readonly keys: readonly string[];
  readonly blockhash: string;
  readonly instructions: readonly Instruction[];
  /** The tables and their indexes, as one comparable string. */
  readonly lookups: string;
  readonly loadedWritable: number;
  readonly loaded: number;
}

function walletAdded(message: Message, it: Instruction): boolean {
  const program = message.keys[it.program];
  return program !== undefined && WALLET_ADDED_PROGRAMS.has(program);
}

function sameInstruction(
  mine: Message,
  a: Instruction,
  theirs: Message,
  b: Instruction,
): boolean {
  if (a.data !== b.data) return false;
  if (reference(mine, a.program) !== reference(theirs, b.program)) return false;
  if (a.accounts.length !== b.accounts.length) return false;
  return a.accounts.every((x, at) => {
    const y = b.accounts[at]!;
    const named = reference(mine, x);
    return (
      named !== undefined &&
      named === reference(theirs, y) &&
      x < mine.required === y < theirs.required &&
      writable(mine, x) === writable(theirs, y)
    );
  });
}

/** A written-in address, or a position among the accounts the lookup tables load. */
function reference(message: Message, index: number): string | undefined {
  const key = message.keys[index];
  if (key !== undefined) return key;
  const loaded = index - message.keys.length;
  return loaded >= 0 && loaded < message.loaded
    ? `loaded:${loaded}`
    : undefined;
}

function writable(message: Message, index: number): boolean {
  const written = message.keys.length;
  if (index < message.required) {
    return index < message.required - message.readonlySigned;
  }
  if (index < written) return index < written - message.readonlyUnsigned;
  return index - written < message.loadedWritable;
}

function parseMessage(bytes: Uint8Array): Message | undefined {
  const read = new Reader(bytes);
  const first = read.byte();
  if (first === undefined) return undefined;
  let version: number | undefined;
  let required: number | undefined;
  if (first & 0x80) {
    version = first & 0x7f;
    if (version !== 0) return undefined;
    required = read.byte();
  } else {
    required = first;
  }
  const readonlySigned = read.byte();
  const readonlyUnsigned = read.byte();
  const keyCount = read.shortVec();
  if (
    required === undefined ||
    readonlySigned === undefined ||
    readonlyUnsigned === undefined ||
    keyCount === undefined
  ) {
    return undefined;
  }
  const keys: string[] = [];
  for (let i = 0; i < keyCount; i += 1) {
    const key = read.take(32);
    if (key === undefined) return undefined;
    keys.push(encodeBase58(key));
  }
  const hash = read.take(32);
  const instructionCount = read.shortVec();
  if (hash === undefined || instructionCount === undefined) return undefined;
  const instructions: Instruction[] = [];
  for (let i = 0; i < instructionCount; i += 1) {
    const program = read.byte();
    const accountCount = read.shortVec();
    if (program === undefined || accountCount === undefined) return undefined;
    const accounts = read.take(accountCount);
    const dataLength = read.shortVec();
    if (accounts === undefined || dataLength === undefined) return undefined;
    const data = read.take(dataLength);
    if (data === undefined) return undefined;
    instructions.push({
      program,
      accounts: [...accounts],
      data: Buffer.from(data).toString("hex"),
    });
  }
  const tables: string[] = [];
  let loadedWritable = 0;
  let loaded = 0;
  if (version === 0) {
    const tableCount = read.shortVec();
    if (tableCount === undefined) return undefined;
    for (let i = 0; i < tableCount; i += 1) {
      const table = read.take(32);
      const w = read.shortVec();
      const writableIndexes = w === undefined ? undefined : read.take(w);
      const r = read.shortVec();
      const readonlyIndexes = r === undefined ? undefined : read.take(r);
      if (!table || !writableIndexes || !readonlyIndexes) return undefined;
      tables.push(
        `${encodeBase58(table)}:${[...writableIndexes].join(",")}:${[...readonlyIndexes].join(",")}`,
      );
      loadedWritable += writableIndexes.length;
      loaded += writableIndexes.length + readonlyIndexes.length;
    }
  }
  if (!read.done || required === 0 || keys.length < required) return undefined;
  return {
    version,
    required,
    readonlySigned,
    readonlyUnsigned,
    keys,
    blockhash: encodeBase58(hash),
    instructions,
    lookups: tables.join("|"),
    loadedWritable,
    loaded,
  };
}

class Reader {
  private at = 0;
  private readonly bytes: Uint8Array;

  constructor(bytes: Uint8Array) {
    this.bytes = bytes;
  }

  get done(): boolean {
    return this.at === this.bytes.length;
  }

  byte(): number | undefined {
    const value = this.bytes[this.at];
    if (value !== undefined) this.at += 1;
    return value;
  }

  take(length: number): Uint8Array | undefined {
    if (this.at + length > this.bytes.length) return undefined;
    const part = this.bytes.subarray(this.at, this.at + length);
    this.at += length;
    return part;
  }

  shortVec(): number | undefined {
    let value = 0;
    for (let shift = 0; shift < 21; shift += 7) {
      const byte = this.byte();
      if (byte === undefined) return undefined;
      value |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return value;
    }
    return undefined;
  }
}
