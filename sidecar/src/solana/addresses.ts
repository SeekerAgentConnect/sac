/**
 * The program addresses a transfer touches, and the associated token account that derives from a
 * wallet and a mint. Addresses only: nothing here holds a key, signs, or sends.
 */
import { PublicKey } from "@solana/web3.js";

/** The System program, which moves lamports. */
export const SYSTEM_PROGRAM = new PublicKey("11111111111111111111111111111111");
/** The classic SPL Token program: the only token program Stage 4 supports. */
export const TOKEN_PROGRAM = new PublicKey(
  "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
);
/**
 * Token-2022. Its mints can carry extensions — transfer fees, hooks, confidential balances —
 * whose effects a transfer can't state up front, so the sidecar refuses them by name rather than
 * preparing something the owner can't check.
 */
export const TOKEN_2022_PROGRAM = new PublicKey(
  "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",
);
/** The Associated Token Account program, which derives and creates a wallet's token accounts. */
export const ASSOCIATED_TOKEN_PROGRAM = new PublicKey(
  "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL",
);

/**
 * `owner`'s associated token account for `mint` under the classic Token program: the one account a
 * transfer reads or creates, so neither side has to name one.
 */
export function associatedTokenAddress(
  owner: PublicKey,
  mint: PublicKey,
): PublicKey {
  const [address] = PublicKey.findProgramAddressSync(
    [owner.toBytes(), TOKEN_PROGRAM.toBytes(), mint.toBytes()],
    ASSOCIATED_TOKEN_PROGRAM,
  );
  return address;
}
