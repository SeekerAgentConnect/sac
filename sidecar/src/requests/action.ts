/**
 * Rules for a durable request's parameters (docs/protocol.md): what each action kind needs, how
 * amounts and addresses are written, and the exact bytes a message signs. Pure code, for the MCP
 * tools and the phone API that arrive from SAW-010 on.
 */
import { equals } from "@bufbuild/protobuf";

import {
  AssetSchema,
  Network,
  type Action,
  type Asset,
  type SignMessageAction,
  type SwapAction,
  type TransferAction,
} from "../gen/seekervault/request/v1/request_pb.js";
import { invalidTextReason } from "../live/command.ts";

/** The largest amount: lamports and SPL token amounts are u64 on chain. */
export const MAX_BASE_UNITS = 18_446_744_073_709_551_615n;
/** The largest message to sign, in bytes. */
export const MAX_MESSAGE_BYTES = 4096;
/** The largest agent note or result detail, in UTF-8 bytes. */
export const MAX_NOTE_BYTES = 1024;
/** The largest slippage, in basis points: 100%. */
export const MAX_SLIPPAGE_BPS = 10_000;

/** What a wallet action must be signed with: a wallet, and a network when the action has one. */
export interface ActionBinding {
  readonly wallet: string;
  /** Absent for sign_message, which isn't bound to a network. */
  readonly network?: Network;
}

const DIGITS = /^(?:0|[1-9][0-9]*)$/;
const BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
const NETWORKS: ReadonlySet<Network> = new Set([
  Network.MAINNET,
  Network.DEVNET,
  Network.TESTNET,
]);

/**
 * Parses an amount in base units: decimal digits with no sign, decimal point, exponent,
 * whitespace, or leading zeros, and at most MAX_BASE_UNITS. Returns undefined for anything else.
 * Amounts stay strings on the wire because JSON numbers and Kotlin's Long can't hold them all.
 */
export function parseBaseUnits(text: string): bigint | undefined {
  // MAX_BASE_UNITS has 20 digits; checking the length first keeps BigInt() cheap.
  if (text.length > 20 || !DIGITS.test(text)) return undefined;
  const value = BigInt(text);
  return value <= MAX_BASE_UNITS ? value : undefined;
}

/** Decodes base58 in the Bitcoin alphabet Solana uses, or returns undefined for other characters. */
export function decodeBase58(text: string): Uint8Array | undefined {
  let value = 0n;
  for (const char of text) {
    const digit = BASE58.indexOf(char);
    if (digit < 0) return undefined;
    value = value * 58n + BigInt(digit);
  }
  const bytes: number[] = [];
  for (; value > 0n; value >>= 8n) bytes.unshift(Number(value & 0xffn));
  // Each leading "1" stands for a leading zero byte.
  for (let i = 0; text[i] === "1"; i++) bytes.unshift(0);
  return Uint8Array.from(bytes);
}

/** Encodes bytes as base58, the way Solana writes addresses and signatures. */
export function encodeBase58(bytes: Uint8Array): string {
  let value = 0n;
  for (const byte of bytes) value = value * 256n + BigInt(byte);
  let text = "";
  for (; value > 0n; value /= 58n)
    text = BASE58.charAt(Number(value % 58n)) + text;
  // Each leading zero byte is written as "1".
  for (let i = 0; bytes[i] === 0; i++) text = "1" + text;
  return text;
}

/** Whether `text` is a Solana address: base58 for exactly 32 bytes. */
export function isAddress(text: string): boolean {
  return (
    text.length >= 32 && text.length <= 44 && decodeBase58(text)?.length === 32
  );
}

/** The exact bytes the wallet signs: the text's UTF-8 encoding, or the data as it is. */
export function messageBytes(action: SignMessageAction): Uint8Array {
  const { content } = action;
  if (content.case === "text") return new TextEncoder().encode(content.value);
  return content.case === "data" ? content.value : new Uint8Array();
}

/**
 * Says why `action` can't be stored as a request, or returns undefined if it can. Reasons name
 * fields the way docs/protocol.md does.
 */
export function invalidActionReason(
  action: Action | undefined,
): string | undefined {
  if (action === undefined) return "action is missing";
  const { kind } = action;
  switch (kind.case) {
    case "ack":
      return invalidTextReason(kind.value.text);
    case "signMessage":
      return invalidSignMessageReason(kind.value);
    case "transfer":
      return invalidTransferReason(kind.value);
    case "swap":
      return invalidSwapReason(kind.value);
    default:
      return "action is missing";
  }
}

/**
 * Says why an agent note or a result detail can't be stored. It may be empty, but it must be
 * valid Unicode of at most MAX_NOTE_BYTES.
 */
export function invalidNoteReason(
  field: string,
  text: string,
): string | undefined {
  if (!text.isWellFormed()) {
    return `${field} is not valid Unicode (it contains an unpaired surrogate)`;
  }
  const bytes = Buffer.byteLength(text, "utf8");
  return bytes > MAX_NOTE_BYTES
    ? `${field} is ${bytes} UTF-8 bytes; the limit is ${MAX_NOTE_BYTES}`
    : undefined;
}

function invalidSignMessageReason(
  action: SignMessageAction,
): string | undefined {
  const walletReason = invalidAddressReason("wallet", action.wallet);
  if (walletReason !== undefined) return walletReason;
  const { content } = action;
  if (content.case === undefined) return "message is missing";
  if (content.case === "text" && !content.value.isWellFormed()) {
    return "message is not valid Unicode (it contains an unpaired surrogate)";
  }
  const bytes = messageBytes(action).length;
  if (bytes === 0) return "message is empty";
  return bytes > MAX_MESSAGE_BYTES
    ? `message is ${bytes} bytes; the limit is ${MAX_MESSAGE_BYTES}`
    : undefined;
}

function invalidTransferReason(action: TransferAction): string | undefined {
  return (
    invalidBindingReason(action.wallet, action.network) ??
    invalidAddressReason("recipient", action.recipient) ??
    invalidAssetReason("asset", action.asset) ??
    invalidAmountReason("amount", action.amount)
  );
}

function invalidSwapReason(action: SwapAction): string | undefined {
  const reason =
    invalidBindingReason(action.wallet, action.network) ??
    invalidAssetReason("input_asset", action.inputAsset) ??
    invalidAssetReason("output_asset", action.outputAsset) ??
    invalidAmountReason("input_amount", action.inputAmount);
  if (reason !== undefined) return reason;
  const { inputAsset, outputAsset } = action;
  if (
    inputAsset &&
    outputAsset &&
    equals(AssetSchema, inputAsset, outputAsset)
  ) {
    return "output_asset must differ from input_asset";
  }
  if (action.slippageBps < 1 || action.slippageBps > MAX_SLIPPAGE_BPS) {
    return `slippage_bps must be 1 to ${MAX_SLIPPAGE_BPS}`;
  }
  return undefined;
}

/**
 * The wallet and network a wallet action must be carried out with, or undefined for an action
 * that needs no wallet. sign_message names only a wallet: a signature over bytes doesn't depend
 * on a network.
 */
export function actionBinding(action: Action): ActionBinding | undefined {
  const { kind } = action;
  switch (kind.case) {
    case "signMessage":
      return { wallet: kind.value.wallet };
    case "transfer":
    case "swap":
      return { wallet: kind.value.wallet, network: kind.value.network };
    default:
      return undefined;
  }
}

/**
 * Says why a wallet and network can't be used together, or returns undefined if they can. A
 * WalletBinding the phone publishes goes through the same check as an action's own binding.
 */
export function invalidBindingReason(
  wallet: string,
  network: Network,
): string | undefined {
  const reason = invalidAddressReason("wallet", wallet);
  if (reason !== undefined) return reason;
  if (network === Network.UNSPECIFIED) return "network is missing";
  return NETWORKS.has(network)
    ? undefined
    : "network must be mainnet, devnet, or testnet";
}

function invalidAddressReason(
  field: string,
  value: string,
): string | undefined {
  if (value === "") return `${field} is missing`;
  return isAddress(value)
    ? undefined
    : `${field} is not a base58 Solana address`;
}

function invalidAssetReason(
  field: string,
  asset: Asset | undefined,
): string | undefined {
  if (asset === undefined) return `${field} is missing`;
  const { kind } = asset;
  switch (kind.case) {
    case "nativeSol":
      return undefined;
    case "tokenMint":
      return invalidAddressReason(`${field}.token_mint`, kind.value);
    default:
      return `${field} is missing`;
  }
}

function invalidAmountReason(
  field: string,
  amount: string,
): string | undefined {
  if (amount === "") return `${field} is missing`;
  if (!DIGITS.test(amount)) {
    return `${field} must be a whole number of base units in decimal digits, with no sign, decimal point, exponent, or leading zeros`;
  }
  const value = parseBaseUnits(amount);
  if (value === undefined) {
    return `${field} is larger than ${MAX_BASE_UNITS.toString()}, the u64 maximum`;
  }
  return value === 0n ? `${field} must be at least 1` : undefined;
}
