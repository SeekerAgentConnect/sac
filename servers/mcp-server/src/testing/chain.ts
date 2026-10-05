/**
 * A Solana chain the tests control: the accounts, blockhash, fee, and genesis hash a preparation
 * reads. `FakeChain` is the ChainReader the builder talks to directly, and `startFakeRpc` serves
 * the same state as real JSON-RPC, so a sidecar process can be pointed at it without a network.
 */
import { once } from "node:events";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";

import { PublicKey } from "@solana/web3.js";

import { Network } from "@seekeragentconnect/server-sdk/protocol";
import {
  SYSTEM_PROGRAM,
  TOKEN_PROGRAM,
  associatedTokenAddress,
} from "../solana/addresses.ts";
import { GENESIS_HASHES } from "../solana/network.ts";
import {
  ChainUnavailable,
  type ChainAccount,
  type ChainReader,
  type ChainTransaction,
  type Commitment,
  type LatestBlockhash,
  type SignatureStatus,
} from "../solana/rpc.ts";
import {
  MINT_BYTES,
  TOKEN_ACCOUNT_BYTES,
  TokenAccountState,
} from "../solana/token.ts";

/** A recognizable blockhash: 32 bytes of 1..32, so it is valid base58 and never a real one. */
export const TEST_BLOCKHASH = new PublicKey(
  Uint8Array.from({ length: 32 }, (_, index) => index + 1),
).toBase58();

/** The rent a token account needs, as mainnet charges it. */
export const TOKEN_ACCOUNT_RENT = 2_039_280n;

/** One signature the fake chain knows about (SAW-022). */
export interface FakeSignature {
  readonly slot: bigint;
  readonly commitment: Commitment;
  /** The chain's own error, when the transaction ran and failed. */
  readonly chainError?: string;
  /**
   * The wire transaction the chain serves under this signature. Absent stands for an endpoint
   * that has a status but hasn't served the transaction yet.
   */
  readonly transaction?: Uint8Array;
  /** True for a signature the status cache has dropped: only a ledger search finds it. */
  readonly onlyInHistory?: boolean;
}

export class FakeChain implements ChainReader {
  genesisHashValue = GENESIS_HASHES.get(Network.DEVNET) ?? "";
  readonly accounts = new Map<string, ChainAccount>();
  blockhash = TEST_BLOCKHASH;
  lastValidBlockHeight = 1000n;
  height = 900n;
  /** undefined stands for an endpoint that won't price the message. */
  feeLamports: bigint | undefined = 5000n;
  rentLamports = TOKEN_ACCOUNT_RENT;
  /** Every method called, in order, so a test can show that nothing extra was read. */
  readonly calls: string[] = [];
  /** When set, every call fails with it: an endpoint that stopped answering. */
  unavailable: string | undefined;
  /** What the chain holds under each signature, by base58 signature (SAW-022). */
  readonly signatures = new Map<string, FakeSignature>();

  put(address: PublicKey | string, account: ChainAccount): void {
    this.accounts.set(
      typeof address === "string" ? address : address.toBase58(),
      account,
    );
  }

  genesisHash(): Promise<string> {
    this.#called("getGenesisHash");
    return Promise.resolve(this.genesisHashValue);
  }

  account(address: string): Promise<ChainAccount | undefined> {
    this.#called(`getAccountInfo(${address})`);
    return Promise.resolve(this.accounts.get(address));
  }

  latestBlockhash(): Promise<LatestBlockhash> {
    this.#called("getLatestBlockhash");
    return Promise.resolve({
      blockhash: this.blockhash,
      lastValidBlockHeight: this.lastValidBlockHeight,
    });
  }

  blockHeight(): Promise<bigint> {
    this.#called("getBlockHeight");
    return Promise.resolve(this.height);
  }

  feeForMessage(): Promise<bigint | undefined> {
    this.#called("getFeeForMessage");
    return Promise.resolve(this.feeLamports);
  }

  rentExemption(): Promise<bigint> {
    this.#called("getMinimumBalanceForRentExemption");
    return Promise.resolve(this.rentLamports);
  }

  /** Puts a transaction on chain under `signature`. */
  land(signature: string, entry: FakeSignature): void {
    this.signatures.set(signature, entry);
  }

  signatureStatus(
    signature: string,
    searchHistory: boolean,
  ): Promise<SignatureStatus | undefined> {
    this.#called(
      `getSignatureStatuses(${searchHistory ? "history" : "cache"})`,
    );
    const found = this.signatures.get(signature);
    if (found === undefined) return Promise.resolve(undefined);
    if (found.onlyInHistory === true && !searchHistory) {
      return Promise.resolve(undefined);
    }
    return Promise.resolve({
      slot: found.slot,
      commitment: found.commitment,
      chainError: found.chainError,
    });
  }

  confirmedTransaction(
    signature: string,
  ): Promise<ChainTransaction | undefined> {
    this.#called("getTransaction");
    const found = this.signatures.get(signature);
    if (found?.transaction === undefined) return Promise.resolve(undefined);
    return Promise.resolve({
      slot: found.slot,
      transaction: found.transaction,
      chainError: found.chainError,
    });
  }

  #called(method: string): void {
    this.calls.push(method);
    if (this.unavailable !== undefined) {
      throw new ChainUnavailable(this.unavailable);
    }
  }
}

/** A wallet: owned by the System program, with no data. */
export function walletAccount(): ChainAccount {
  return {
    owner: SYSTEM_PROGRAM.toBase58(),
    executable: false,
    data: new Uint8Array(),
  };
}

/** An executable account, which is a program and never a wallet. */
export function programAccount(): ChainAccount {
  return {
    owner: "BPFLoaderUpgradeab1e11111111111111111111111",
    executable: true,
    data: new Uint8Array(),
  };
}

export interface FakeMint {
  readonly decimals: number;
  readonly supply: bigint;
  /** The token program that owns the mint; the classic one unless a test says otherwise. */
  readonly program?: PublicKey;
  readonly initialized?: boolean;
}

/** A mint account, laid out the way the SPL Token program writes one. */
export function mintAccount(mint: FakeMint): ChainAccount {
  const data = Buffer.alloc(MINT_BYTES);
  data.writeUInt32LE(0, 0); // no mint authority
  data.writeBigUInt64LE(mint.supply, 36);
  data.writeUInt8(mint.decimals, 44);
  data.writeUInt8(mint.initialized === false ? 0 : 1, 45);
  return {
    owner: (mint.program ?? TOKEN_PROGRAM).toBase58(),
    executable: false,
    data,
  };
}

export interface FakeTokenAccount {
  readonly mint: PublicKey | string;
  readonly owner: PublicKey | string;
  readonly amount: bigint;
  readonly state?: TokenAccountState;
  readonly program?: PublicKey;
}

/** A token account, laid out the way the SPL Token program writes one. */
export function tokenAccount(account: FakeTokenAccount): ChainAccount {
  const data = Buffer.alloc(TOKEN_ACCOUNT_BYTES);
  data.set(new PublicKey(account.mint).toBytes(), 0);
  data.set(new PublicKey(account.owner).toBytes(), 32);
  data.writeBigUInt64LE(account.amount, 64);
  data.writeUInt8(account.state ?? TokenAccountState.INITIALIZED, 108);
  return {
    owner: (account.program ?? TOKEN_PROGRAM).toBase58(),
    executable: false,
    data,
  };
}

/**
 * Gives `owner` the associated token account a holder of `mint` has, and returns its address.
 * Addresses go in and out as base58, so a caller outside this package needs no chain library of
 * its own to set a token holding up.
 */
export function holdToken(
  chain: FakeChain,
  holding: {
    readonly mint: string;
    readonly owner: string;
    readonly amount: bigint;
  },
): string {
  const address = associatedTokenAddress(
    new PublicKey(holding.owner),
    new PublicKey(holding.mint),
  ).toBase58();
  chain.put(
    address,
    tokenAccount({
      mint: holding.mint,
      owner: holding.owner,
      amount: holding.amount,
    }),
  );
  return address;
}

export interface FakeRpc {
  readonly url: string;
  close(): Promise<void>;
}

/** Serves `chain` as Solana JSON-RPC on loopback, for a sidecar that reads it over HTTP. */
export async function startFakeRpc(chain: FakeChain): Promise<FakeRpc> {
  const server: Server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk: Buffer) => chunks.push(chunk));
    req.on("end", () => {
      void (async () => {
        let body: { id?: unknown; method?: string; params?: unknown[] };
        try {
          body = JSON.parse(
            Buffer.concat(chunks).toString("utf8"),
          ) as typeof body;
        } catch {
          res.writeHead(400).end();
          return;
        }
        const id = body.id ?? null;
        try {
          const result = await answer(
            chain,
            body.method ?? "",
            body.params ?? [],
          );
          send(res, { jsonrpc: "2.0", id, result });
        } catch (error) {
          send(res, {
            jsonrpc: "2.0",
            id,
            error: {
              code: -32000,
              message: error instanceof Error ? error.message : "failed",
            },
          });
        }
      })();
    });
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${port}`,
    close: async () => {
      const closed = new Promise<void>((resolve) =>
        server.close(() => resolve()),
      );
      server.closeAllConnections();
      await closed;
    },
  };
}

async function answer(
  chain: FakeChain,
  method: string,
  params: unknown[],
): Promise<unknown> {
  const context = { context: { slot: Number(chain.height) } };
  switch (method) {
    case "getGenesisHash":
      return chain.genesisHash();
    case "getAccountInfo": {
      const account = await chain.account(String(params[0]));
      return {
        ...context,
        value:
          account === undefined
            ? null
            : {
                owner: account.owner,
                executable: account.executable,
                lamports: 1,
                rentEpoch: 0,
                space: account.data.length,
                data: [Buffer.from(account.data).toString("base64"), "base64"],
              },
      };
    }
    case "getLatestBlockhash": {
      const latest = await chain.latestBlockhash();
      return {
        ...context,
        value: {
          blockhash: latest.blockhash,
          lastValidBlockHeight: Number(latest.lastValidBlockHeight),
        },
      };
    }
    case "getBlockHeight":
      return Number(await chain.blockHeight());
    case "getFeeForMessage": {
      const fee = await chain.feeForMessage();
      return { ...context, value: fee === undefined ? null : Number(fee) };
    }
    case "getMinimumBalanceForRentExemption":
      return Number(await chain.rentExemption());
    case "getSignatureStatuses": {
      const options = params[1];
      const searchHistory =
        typeof options === "object" &&
        options !== null &&
        (options as { searchTransactionHistory?: unknown })
          .searchTransactionHistory === true;
      const wanted = Array.isArray(params[0]) ? params[0] : [];
      const statuses = await Promise.all(
        wanted.map(async (signature) => {
          const status = await chain.signatureStatus(
            String(signature),
            searchHistory,
          );
          return status === undefined
            ? null
            : {
                slot: Number(status.slot),
                confirmations: null,
                err: status.chainError === undefined ? null : status.chainError,
                confirmationStatus: status.commitment,
              };
        }),
      );
      return { ...context, value: statuses };
    }
    case "getTransaction": {
      const found = await chain.confirmedTransaction(String(params[0]));
      return found === undefined
        ? null
        : {
            slot: Number(found.slot),
            transaction: [
              Buffer.from(found.transaction).toString("base64"),
              "base64",
            ],
            meta: {
              err: found.chainError === undefined ? null : found.chainError,
            },
          };
    }
    default:
      throw new Error(`the fake chain doesn't serve ${method}`);
  }
}

function send(res: import("node:http").ServerResponse, body: unknown): void {
  const text = JSON.stringify(body);
  res.writeHead(200, {
    "Content-Type": "application/json",
    "Content-Length": String(Buffer.byteLength(text)),
  });
  res.end(text);
}
