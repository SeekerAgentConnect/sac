/**
 * The sidecar's only chain client: the handful of read-only Solana JSON-RPC methods a transfer
 * preparation needs. It reads, and nothing else — there is no method here that signs, sends, or
 * simulates, so no code path can broadcast anything.
 *
 * The endpoint URL can carry an API key, so it never appears in an error message or a log line.
 */
export interface ChainAccount {
  /** The program that owns the account, as a base58 address. */
  readonly owner: string;
  readonly executable: boolean;
  readonly data: Uint8Array;
}

export interface LatestBlockhash {
  readonly blockhash: string;
  /** The transaction can land only while the chain's block height is at most this. */
  readonly lastValidBlockHeight: bigint;
}

/** How far a signature has got, as getSignatureStatuses reports it. */
export type Commitment = "processed" | "confirmed" | "finalized";

/** What the endpoint knows about one signature. */
export interface SignatureStatus {
  readonly slot: bigint;
  readonly commitment: Commitment;
  /**
   * The chain's own error, when the transaction ran and failed; undefined when it succeeded. It
   * is the JSON the endpoint returned, rendered for a person to read.
   */
  readonly chainError: string | undefined;
}

/** One confirmed transaction as the chain holds it. */
export interface ChainTransaction {
  readonly slot: bigint;
  /** The whole transaction in wire format, signatures and all. */
  readonly transaction: Uint8Array;
  /** The chain's own error, when it ran and failed. */
  readonly chainError: string | undefined;
}

/**
 * The chain couldn't be read: the endpoint didn't answer, answered an error, or answered
 * something this client doesn't understand. It says nothing about the request that needed it, so
 * the same call can be retried.
 */
export class ChainUnavailable extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "ChainUnavailable";
  }
}

/** What the sidecar reads from the chain. `SolanaRpc` is the one implementation; tests fake it. */
export interface ChainReader {
  genesisHash(): Promise<string>;
  /** The account, or undefined when the chain has none at that address. */
  account(address: string): Promise<ChainAccount | undefined>;
  latestBlockhash(): Promise<LatestBlockhash>;
  blockHeight(): Promise<bigint>;
  /** The fee for a compiled message, or undefined when the endpoint can't say. */
  feeForMessage(messageBase64: string): Promise<bigint | undefined>;
  rentExemption(bytes: number): Promise<bigint>;
  /**
   * What the endpoint knows about a signature, or undefined when it knows nothing. A recent
   * signature is answered from the status cache; `searchHistory` also searches the ledger, which
   * is the only way to tell "it never ran" from "it left the cache" (SAW-022).
   */
  signatureStatus(
    signature: string,
    searchHistory: boolean,
  ): Promise<SignatureStatus | undefined>;
  /** The transaction under a signature, or undefined when the endpoint hasn't got it. */
  confirmedTransaction(
    signature: string,
  ): Promise<ChainTransaction | undefined>;
}

/**
 * How long one chain-backed operation may take in total, however many calls it makes.
 *
 * A preparation reads the mint, both token accounts, the recipient, the rent, the blockhash, the
 * fee, and the block height, one after another, and a confirmation check reads up to three
 * things. The phone waits on one unary RPC for all of it, so the per-call timeout
 * (`SOLANA_RPC_TIMEOUT_MS`) can't be the only bound: a slow endpoint would let the sidecar keep
 * building and storing a preparation long after the phone gave up on it. This is the bound the
 * phone's deadline is set against (android ConnectConnectionGateway.CHAIN_TIMEOUT).
 */
export const CHAIN_BUDGET_MS = 20_000;

/**
 * Runs one chain-backed operation under {@link CHAIN_BUDGET_MS}. When the budget runs out the
 * operation is abandoned with a ChainUnavailable, so nothing it would have produced is stored:
 * the caller hears the same "the endpoint didn't answer" it hears for a single slow call, and the
 * request is left exactly as it was.
 */
export async function withChainBudget<T>(
  operation: () => Promise<T>,
  budgetMs: number = CHAIN_BUDGET_MS,
): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const expired = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(
      () =>
        reject(
          new ChainUnavailable(
            `the Solana RPC endpoint didn't finish answering within ${budgetMs} ms`,
          ),
        ),
      budgetMs,
    );
    // A pending timer must not keep the process alive on its own.
    timer.unref?.();
  });
  try {
    return await Promise.race([operation(), expired]);
  } finally {
    clearTimeout(timer);
  }
}

export interface SolanaRpcOptions {
  /** How long one call may take before it's abandoned. */
  readonly timeoutMs: number;
  /** Replaced in tests; the global fetch otherwise. */
  readonly fetch?: typeof globalThis.fetch;
}

/**
 * Preparation reads at "confirmed": the freshest blockhash gives the owner the longest window in
 * which their approval can still land.
 */
const COMMITMENT = "confirmed";

/** How much of a chain error is kept: Outcome.detail and Confirmation.chain_error are display text. */
const MAX_CHAIN_ERROR_CHARS = 512;

export class SolanaRpc implements ChainReader {
  readonly #url: string;
  readonly #timeoutMs: number;
  readonly #fetch: typeof globalThis.fetch;
  #id = 0;
  /** An endpoint's genesis hash never changes, so it's read once. */
  #genesisHash: string | undefined;

  constructor(url: string, options: SolanaRpcOptions) {
    this.#url = url;
    this.#timeoutMs = options.timeoutMs;
    this.#fetch = options.fetch ?? globalThis.fetch;
  }

  async genesisHash(): Promise<string> {
    this.#genesisHash ??= expectString(
      await this.#call("getGenesisHash", []),
      "getGenesisHash",
    );
    return this.#genesisHash;
  }

  async account(address: string): Promise<ChainAccount | undefined> {
    const value = valueOf(
      await this.#call("getAccountInfo", [
        address,
        { encoding: "base64", commitment: COMMITMENT },
      ]),
      "getAccountInfo",
    );
    if (value === null || value === undefined) return undefined;
    const account = expectObject(value, "getAccountInfo");
    const data = account.data;
    if (!Array.isArray(data) || typeof data[0] !== "string") {
      throw unexpected("getAccountInfo", "account data in base64");
    }
    return {
      owner: expectString(account.owner, "getAccountInfo"),
      executable: account.executable === true,
      data: Buffer.from(data[0], "base64"),
    };
  }

  async latestBlockhash(): Promise<LatestBlockhash> {
    const value = expectObject(
      valueOf(
        await this.#call("getLatestBlockhash", [{ commitment: COMMITMENT }]),
        "getLatestBlockhash",
      ),
      "getLatestBlockhash",
    );
    return {
      blockhash: expectString(value.blockhash, "getLatestBlockhash"),
      lastValidBlockHeight: expectCount(
        value.lastValidBlockHeight,
        "getLatestBlockhash",
      ),
    };
  }

  async blockHeight(): Promise<bigint> {
    return expectCount(
      await this.#call("getBlockHeight", [{ commitment: COMMITMENT }]),
      "getBlockHeight",
    );
  }

  async feeForMessage(messageBase64: string): Promise<bigint | undefined> {
    const value = valueOf(
      await this.#call("getFeeForMessage", [
        messageBase64,
        { commitment: COMMITMENT },
      ]),
      "getFeeForMessage",
    );
    // null means the endpoint couldn't price the message, for example because its blockhash is
    // already gone. The caller falls back to the base fee rather than failing the preparation.
    return value === null || value === undefined
      ? undefined
      : expectCount(value, "getFeeForMessage");
  }

  async signatureStatus(
    signature: string,
    searchHistory: boolean,
  ): Promise<SignatureStatus | undefined> {
    const statuses = valueOf(
      await this.#call("getSignatureStatuses", [
        [signature],
        { searchTransactionHistory: searchHistory },
      ]),
      "getSignatureStatuses",
    );
    if (!Array.isArray(statuses)) {
      throw unexpected("getSignatureStatuses", "a list of statuses");
    }
    const status: unknown = statuses[0];
    if (status === null || status === undefined) return undefined;
    const value = expectObject(status, "getSignatureStatuses");
    return {
      slot: expectCount(value.slot, "getSignatureStatuses"),
      commitment: expectCommitment(value.confirmationStatus),
      chainError: chainError(value.err),
    };
  }

  async confirmedTransaction(
    signature: string,
  ): Promise<ChainTransaction | undefined> {
    const result = await this.#call("getTransaction", [
      signature,
      {
        encoding: "base64",
        commitment: COMMITMENT,
        maxSupportedTransactionVersion: 0,
      },
    ]);
    if (result === null || result === undefined) return undefined;
    const value = expectObject(result, "getTransaction");
    const encoded = value.transaction;
    if (!Array.isArray(encoded) || typeof encoded[0] !== "string") {
      throw unexpected("getTransaction", "the transaction in base64");
    }
    const meta =
      value.meta === null || value.meta === undefined
        ? undefined
        : expectObject(value.meta, "getTransaction");
    return {
      slot: expectCount(value.slot, "getTransaction"),
      transaction: Buffer.from(encoded[0], "base64"),
      chainError: chainError(meta?.err),
    };
  }

  async rentExemption(bytes: number): Promise<bigint> {
    return expectCount(
      await this.#call("getMinimumBalanceForRentExemption", [
        bytes,
        { commitment: COMMITMENT },
      ]),
      "getMinimumBalanceForRentExemption",
    );
  }

  async #call(method: string, params: unknown[]): Promise<unknown> {
    this.#id += 1;
    let response: Response;
    try {
      response = await this.#fetch(this.#url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: this.#id,
          method,
          params,
        }),
        signal: AbortSignal.timeout(this.#timeoutMs),
      });
    } catch (error) {
      throw new ChainUnavailable(
        `the Solana RPC endpoint didn't answer ${method} within ${this.#timeoutMs} ms or couldn't be reached`,
        { cause: error },
      );
    }
    if (!response.ok) {
      throw new ChainUnavailable(
        `the Solana RPC endpoint answered ${method} with HTTP ${response.status}`,
      );
    }
    let body: unknown;
    try {
      body = await response.json();
    } catch (error) {
      throw new ChainUnavailable(
        `the Solana RPC endpoint answered ${method} with something that isn't JSON`,
        { cause: error },
      );
    }
    const envelope = expectObject(body, method);
    if (envelope.error !== undefined && envelope.error !== null) {
      const error = expectObject(envelope.error, method);
      const message =
        typeof error.message === "string" ? error.message : "an error";
      throw new ChainUnavailable(
        `the Solana RPC endpoint refused ${method}: ${message}`,
      );
    }
    if (!("result" in envelope)) throw unexpected(method, "a result");
    return envelope.result;
  }
}

/**
 * A commitment the endpoint named. An unrecognized one is read as the weakest, `processed`,
 * because a result is only ever reported on a level this client actually understands.
 */
function expectCommitment(value: unknown): Commitment {
  return value === "finalized" || value === "confirmed" ? value : "processed";
}

/**
 * The chain's own error for a person to read, or undefined when there was none. It is the
 * endpoint's JSON as it came, capped, and never parsed into a promise about what went wrong.
 */
function chainError(value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  // Whatever shape the endpoint sent, kept as JSON. It is display text and nothing parses it.
  const rendered =
    typeof value === "string" ? value : (JSON.stringify(value) ?? "an error");
  return rendered.slice(0, MAX_CHAIN_ERROR_CHARS);
}

/** The `value` of a response that wraps its result in a context, such as getAccountInfo. */
function valueOf(result: unknown, method: string): unknown {
  return expectObject(result, method).value;
}

function expectObject(value: unknown, method: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw unexpected(method, "an object");
  }
  return value as Record<string, unknown>;
}

function expectString(value: unknown, method: string): string {
  if (typeof value !== "string") throw unexpected(method, "a string");
  return value;
}

/** A whole, non-negative number: lamports, a block height, or an account size. */
function expectCount(value: unknown, method: string): bigint {
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw unexpected(method, "a whole number");
  }
  return BigInt(value as number);
}

function unexpected(method: string, expected: string): ChainUnavailable {
  return new ChainUnavailable(
    `the Solana RPC endpoint's answer to ${method} didn't hold ${expected}`,
  );
}
