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

/** How far a transaction has got, as the cluster reports it. */
export type ChainCommitment = "processed" | "confirmed" | "finalized";

/** What a cluster says about one signature. */
export interface ChainSignatureStatus {
  readonly slot: bigint;
  readonly commitment: ChainCommitment;
  /** The chain's own error, as display text, or undefined when the transaction succeeded. */
  readonly chainError: string | undefined;
}

/** The transaction a cluster holds under a signature, as the wire bytes it was sent as. */
export interface ChainTransaction {
  readonly slot: bigint;
  readonly transaction: Uint8Array;
  readonly chainError: string | undefined;
}

/** A chain error is display text; a long one is cut rather than carried whole. */
const MAX_CHAIN_ERROR_CHARS = 500;

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
  /**
   * What the cluster says about a signature, or undefined when it has no record of it. Absence is
   * never a result on its own: a signature drops out of the status cache after a while, which is
   * why `searchHistory` exists and why the caller only concludes anything from a searched miss.
   */
  signatureStatus(
    signature: string,
    searchHistory: boolean,
  ): Promise<ChainSignatureStatus | undefined>;
  /** The transaction under a signature, or undefined when the endpoint hasn't served it yet. */
  confirmedTransaction(
    signature: string,
  ): Promise<ChainTransaction | undefined>;
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
  readonly #endpoint: string;
  readonly #timeoutMs: number;
  #genesisHash: string | undefined;
  #rpcId = 0;

  constructor(endpoint: string, options: SolanaRpcOptions = {}) {
    this.#endpoint = endpoint;
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

  async signatureStatus(
    signature: string,
    searchHistory: boolean,
  ): Promise<ChainSignatureStatus | undefined> {
    const response = await this.#call("getSignatureStatuses", () =>
      this.#connection.getSignatureStatuses([signature], {
        searchTransactionHistory: searchHistory,
      }),
    );
    const status = response.value[0];
    if (status === null || status === undefined) return undefined;
    return {
      slot: BigInt(status.slot),
      // An unrecognised commitment is read as the weakest one there is, which settles nothing.
      // Guessing upward would turn an endpoint's oddity into a confirmed result.
      commitment:
        status.confirmationStatus === "finalized" ||
        status.confirmationStatus === "confirmed"
          ? status.confirmationStatus
          : "processed",
      chainError: chainErrorText(status.err),
    };
  }

  /**
   * The transaction as the cluster holds it, in the bytes it was submitted as.
   *
   * This is the one call that does not go through `Connection`: its `getTransaction` decodes the
   * message and hands back a parsed object, and re-encoding that would produce bytes this server
   * composed rather than bytes the chain has. The whole use of this call is comparing what ran
   * against what the owner approved, and a comparison of a re-encoding proves the encoder agrees
   * with itself. So the request is made directly, asking for base64.
   */
  async confirmedTransaction(
    signature: string,
  ): Promise<ChainTransaction | undefined> {
    const result = await this.#call("getTransaction", () =>
      this.#rpc("getTransaction", [
        signature,
        {
          encoding: "base64",
          commitment: "confirmed",
          maxSupportedTransactionVersion: 0,
        },
      ]),
    );
    if (result === null || result === undefined) return undefined;
    if (typeof result !== "object" || Array.isArray(result)) {
      throw new ChainUnavailable(
        "the Solana endpoint answered getTransaction with something that is not a transaction",
      );
    }
    const value = result as Record<string, unknown>;
    const encoded = value.transaction;
    if (!Array.isArray(encoded) || typeof encoded[0] !== "string") {
      throw new ChainUnavailable(
        "the Solana endpoint answered getTransaction without the transaction in base64",
      );
    }
    const meta =
      typeof value.meta === "object" && value.meta !== null
        ? (value.meta as Record<string, unknown>)
        : undefined;
    return {
      slot: BigInt(Number(value.slot ?? 0)),
      transaction: Buffer.from(encoded[0], "base64"),
      chainError: chainErrorText(meta?.err),
    };
  }

  /** One JSON-RPC call over the configured endpoint, for what `Connection` cannot express. */
  async #rpc(method: string, params: readonly unknown[]): Promise<unknown> {
    this.#rpcId += 1;
    const response = await fetch(this.#endpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: this.#rpcId,
        method,
        params,
      }),
      signal: AbortSignal.timeout(this.#timeoutMs),
    });
    if (!response.ok) {
      // Never the endpoint's own body or URL: either can carry the configured API key.
      throw new ChainUnavailable(
        `the Solana endpoint answered ${method} with HTTP ${response.status}`,
      );
    }
    const body: unknown = await response.json();
    if (typeof body !== "object" || body === null) {
      throw new ChainUnavailable(
        `the Solana endpoint answered ${method} with something that is not JSON-RPC`,
      );
    }
    const envelope = body as { result?: unknown; error?: { message?: string } };
    if (envelope.error !== undefined) {
      throw new ChainUnavailable(`the Solana endpoint refused ${method}`);
    }
    return envelope.result;
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

/**
 * The chain's own error as display text, or undefined when there was none.
 *
 * It is kept as the endpoint sent it, capped, and nothing parses it into a claim about what went
 * wrong: an owner reading "custom program error: 0x1771" is being shown the chain's answer, not
 * this server's interpretation of it.
 */
function chainErrorText(value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  const rendered =
    typeof value === "string" ? value : (JSON.stringify(value) ?? "an error");
  return rendered.slice(0, MAX_CHAIN_ERROR_CHARS);
}
