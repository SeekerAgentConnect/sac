import type {
  Asset,
  Network,
  StakingAction,
  TransferAction,
} from "./gen/seekervault/request/v1/request_pb.js";

/** A host integration could not answer. The failure is retryable and proves nothing. */
export class ProviderUnavailable extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "ProviderUnavailable";
  }
}

/** A preparation cannot be built as requested; retrying the same input will not fix it. */
export class UnsupportedPreparation extends Error {
  constructor(message: string) {
    super(message);
    this.name = "UnsupportedPreparation";
  }
}

/** Unsigned bytes and review facts produced by an optional host integration. */
export interface BuiltPreparation {
  readonly transaction: Uint8Array;
  readonly contentHash: Uint8Array;
  readonly lastValidBlockHeight: bigint;
  readonly estimatedExpiryMs: number;
  readonly feeLamports: bigint;
  readonly rentLamports: bigint;
}

/** Optional host-supplied preparation behavior. It cannot sign or submit a transaction. */
export interface TransferProvider {
  checkAsset(asset: Asset | undefined): Promise<void>;
  buildTransfer(
    action: TransferAction,
    nowMs: number,
  ): Promise<BuiltPreparation>;
}

/**
 * Optional host-supplied staking behaviour, supplied by a host that serves staking actions and by
 * no other (SEE-146). Like a transfer provider it cannot sign or submit anything; it reads the
 * chain and returns unsigned bytes.
 *
 * It is a second interface rather than more methods on `TransferProvider` because the two are
 * separate promises: a host that can send SPL tokens has said nothing about whether it knows a
 * staking program, and a host that serves staking need not serve transfers at all. Which one a
 * request needs follows from its action, so neither host can be asked for work it never offered.
 */
export interface StakingProvider {
  /**
   * Whether this host can act on the action at all, checked before a request is stored, so an
   * agent hears about an unusable wallet, an unserved network or an impossible amount at once
   * rather than when the owner opens the review. It reads the chain and stores nothing.
   */
  checkStaking(action: StakingAction): Promise<void>;
  buildStaking(action: StakingAction, nowMs: number): Promise<BuiltPreparation>;
}

export type ConfirmationCommitment = "processed" | "confirmed" | "finalized";

export interface ProviderSignatureStatus {
  readonly slot: bigint;
  readonly commitment: ConfirmationCommitment;
  readonly chainError: string | undefined;
}

export interface ProviderTransaction {
  readonly slot: bigint;
  readonly transaction: Uint8Array;
  readonly chainError: string | undefined;
}

/**
 * Optional host-supplied status reads. The SDK owns when a finding is sufficient to settle a
 * request and how the finding is bound and stored; the host owns the concrete network client and
 * transaction parser.
 */
export interface ConfirmationProvider {
  /** The configured endpoint URL. Only its host is ever exposed in a result. */
  readonly endpointUrl: string;
  assertNetwork(network: Network): Promise<void>;
  signatureStatus(
    signature: string,
    searchHistory: boolean,
  ): Promise<ProviderSignatureStatus | undefined>;
  confirmedTransaction(
    signature: string,
  ): Promise<ProviderTransaction | undefined>;
  blockHeight(): Promise<bigint>;
  matchesApprovedTransaction(
    approved: Uint8Array,
    onChain: Uint8Array,
  ): boolean;
}

export const PROVIDER_BUDGET_MS = 20_000;

/** Bounds one optional-provider operation without starting a persistent timer or loop. */
export async function withProviderBudget<T>(
  operation: () => Promise<T>,
  budgetMs: number = PROVIDER_BUDGET_MS,
): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const expired = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(
      () =>
        reject(
          new ProviderUnavailable(
            `the configured provider didn't finish answering within ${budgetMs} ms`,
          ),
        ),
      budgetMs,
    );
    timer.unref?.();
  });
  try {
    return await Promise.race([operation(), expired]);
  } finally {
    clearTimeout(timer);
  }
}
