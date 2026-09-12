/**
 * Telling whether the transaction the chain holds under a signature is the one the owner
 * approved (SAW-022). The approved bytes carry empty signature slots and the chain's carry the
 * wallet's real ones, so the two are compared over what the signatures cover: the message.
 *
 * This reads bytes and nothing else. It reaches no network, and a transaction it can't parse is
 * simply not a match — a confirmed result is never reported on a guess.
 */

/** The largest signature count this reads before giving up; a transfer's is 1. */
const MAX_SIGNATURES = 64;

/**
 * The message region of a wire transaction: everything the signatures cover, which is everything
 * after them. Undefined when the bytes aren't a transaction this can take apart.
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
 * messages must be equal byte for byte: a different fee payer, recipient, amount, blockhash, or
 * extra instruction all make it false.
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
