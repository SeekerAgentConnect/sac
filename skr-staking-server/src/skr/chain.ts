/**
 * The chain reads this server makes, and the only ones it makes.
 *
 * Everything here is a read. This server holds no key, signs nothing, and sends nothing; the whole
 * of its relationship with a cluster is asking what is true right now so that it can build bytes
 * the owner's own wallet may later choose to sign.
 *
 * The interface is small and named so a test can stand in for it without a network. A test in this
 * package that reached a cluster would be a test that fails when somebody else stakes.
 */
import { Connection, PublicKey, type VersionedMessage } from "@solana/web3.js";

/** The endpoint could not answer. Retryable, and proof of nothing about the request. */
export class ChainUnavailable extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "ChainUnavailable";
  }
}

/** One account as the cluster returned it. */
export interface ChainAccount {
  readonly owner: PublicKey;
  readonly data: Buffer;
  readonly lamports: bigint;
}

/** A blockhash and the height past which a transaction built on it can no longer land. */
export interface LatestBlockhash {
  readonly blockhash: string;
  readonly lastValidBlockHeight: bigint;
}

/** What the provider needs from a cluster, and nothing more. */
export interface ChainReader {
  /** The cluster's genesis hash, which is how this server knows which cluster it is talking to. */
  genesisHash(): Promise<string>;
  /** Several accounts in one round trip, in the order asked for; `undefined` where none exists. */
  accounts(
    addresses: readonly PublicKey[],
  ): Promise<readonly (ChainAccount | undefined)[]>;
  latestBlockhash(): Promise<LatestBlockhash>;
  /** The chain's current block height, for saying how much of a blockhash window is left. */
  blockHeight(): Promise<bigint>;
  /** The lamports an account of this size must hold to stay alive. */
  rentExemption(bytes: number): Promise<bigint>;
  /** What the cluster says one compiled message would cost, when it will say. */
  feeForMessage(message: VersionedMessage): Promise<bigint | undefined>;
}

/** Mainnet-beta's genesis hash, as `solana genesis-hash` reports it. */
export const MAINNET_GENESIS_HASH =
  "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d";

export interface SolanaRpcOptions {
  readonly timeoutMs?: number;
}

/** A `ChainReader` over one JSON-RPC endpoint. */
export class SolanaRpc implements ChainReader {
  readonly #connection: Connection;
  readonly #timeoutMs: number;
  #genesisHash: string | undefined;

  constructor(endpoint: string, options: SolanaRpcOptions = {}) {
    this.#timeoutMs = options.timeoutMs ?? 10_000;
    this.#connection = new Connection(endpoint, {
      commitment: "confirmed",
      disableRetryOnRateLimit: true,
    });
  }

  /**
   * Read once and remembered. A cluster's genesis hash does not change, and re-reading it on every
   * call would make the check this server's most frequent request rather than its cheapest.
   */
  async genesisHash(): Promise<string> {
    this.#genesisHash ??= await this.#call("getGenesisHash", () =>
      this.#connection.getGenesisHash(),
    );
    return this.#genesisHash;
  }

  async accounts(
    addresses: readonly PublicKey[],
  ): Promise<readonly (ChainAccount | undefined)[]> {
    if (addresses.length === 0) return [];
    const infos = await this.#call("getMultipleAccounts", () =>
      this.#connection.getMultipleAccountsInfo([...addresses]),
    );
    return infos.map((info) =>
      info === null
        ? undefined
        : {
            owner: info.owner,
            data: Buffer.from(info.data),
            lamports: BigInt(info.lamports),
          },
    );
  }

  async latestBlockhash(): Promise<LatestBlockhash> {
    const latest = await this.#call("getLatestBlockhash", () =>
      this.#connection.getLatestBlockhash("confirmed"),
    );
    return {
      blockhash: latest.blockhash,
      lastValidBlockHeight: BigInt(latest.lastValidBlockHeight),
    };
  }

  async blockHeight(): Promise<bigint> {
    return BigInt(
      await this.#call("getBlockHeight", () =>
        this.#connection.getBlockHeight("confirmed"),
      ),
    );
  }

  async rentExemption(bytes: number): Promise<bigint> {
    const lamports = await this.#call("getMinimumBalanceForRentExemption", () =>
      this.#connection.getMinimumBalanceForRentExemption(bytes),
    );
    return BigInt(lamports);
  }

  async feeForMessage(message: VersionedMessage): Promise<bigint | undefined> {
    // A cluster that will not price a message has not failed: the estimate is for display, and a
    // missing one is reported as missing rather than guessed at.
    try {
      const response = await this.#call("getFeeForMessage", () =>
        this.#connection.getFeeForMessage(message, "confirmed"),
      );
      return response.value === null ? undefined : BigInt(response.value);
    } catch {
      return undefined;
    }
  }

  async #call<T>(what: string, operation: () => Promise<T>): Promise<T> {
    const timer = AbortSignal.timeout(this.#timeoutMs);
    try {
      return await Promise.race([
        operation(),
        new Promise<never>((_, reject) => {
          timer.addEventListener("abort", () => {
            reject(
              new ChainUnavailable(
                `the Solana endpoint didn't answer ${what} within ${this.#timeoutMs} ms`,
              ),
            );
          });
        }),
      ]);
    } catch (error) {
      if (error instanceof ChainUnavailable) throw error;
      // The endpoint's own message can carry the configured URL, and the URL can carry an API key.
      throw new ChainUnavailable(
        `the Solana endpoint failed to answer ${what}`,
        { cause: error },
      );
    }
  }
}
