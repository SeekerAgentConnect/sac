/**
 * Telling whether the transaction the chain holds under a signature is the one the owner approved
 * (SEE-146).
 *
 * The SDK's `ConfirmationProvider` asks each host for this rather than owning it, because only the
 * host knows what its own preparations look like. The rule here is the same one the general MCP
 * server uses (mcp-server/src/solana/confirmation.ts) and for the same reason: the approved bytes
 * carry empty signature slots and the chain's copy carries the wallet's real ones, so the two are
 * compared over the region the signatures cover — the message — which is everything else.
 *
 * This reads bytes and nothing else. It reaches no network, and a transaction it cannot take apart
 * is simply not a match: a staking result is never reported as confirmed on a guess. That matters
 * more here than for a transfer, because the thing being confirmed can be an unstake, and an
 * unstake wrongly called confirmed is a cooldown the owner believes is running.
 */

/** The largest signature count this reads before giving up; a staking transaction's is 1. */
const MAX_SIGNATURES = 64;

/**
 * The message region of a wire transaction: everything the signatures cover, which is everything
 * after them. Undefined when the bytes are not a transaction this can take apart.
 */
export function transactionMessage(
  transaction: Uint8Array,
): Uint8Array | undefined {
  const count = shortVec(transaction);
  if (count === undefined || count.value < 1 || count.value > MAX_SIGNATURES) {
    return undefined;
  }
  const start = count.length + count.value * 64;
  if (start >= transaction.length) return undefined;
  return transaction.subarray(start);
}

/**
 * Whether `onChain` is the approved transaction, signatures aside. Both must parse, and their
 * messages must be equal byte for byte: a different program, pool, amount, blockhash, fee payer,
 * or one extra instruction all make it false.
 */
export function isApprovedTransaction(
  approved: Uint8Array,
  onChain: Uint8Array,
): boolean {
  const mine = transactionMessage(approved);
  const theirs = transactionMessage(onChain);
  if (mine === undefined || theirs === undefined) return false;
  return Buffer.from(mine).equals(Buffer.from(theirs));
}

/** A compact-u16 at the start of `bytes`: its value, and how many bytes it took. */
function shortVec(
  bytes: Uint8Array,
): { readonly value: number; readonly length: number } | undefined {
  let value = 0;
  for (let length = 0; length < 3; length += 1) {
    const byte = bytes[length];
    if (byte === undefined) return undefined;
    value |= (byte & 0x7f) << (length * 7);
    if ((byte & 0x80) === 0) return { value, length: length + 1 };
  }
  return undefined;
}
