/**
 * What this server knows about an owner's staking position, and how it turns a request into bytes.
 *
 * Two jobs, kept apart on purpose:
 *
 * - **Reading.** `position` is the whole of what `get_staking_status` answers and the whole of what
 *   every precondition is checked against. It creates nothing and changes nothing.
 * - **Preparing.** `build` produces an unsigned transaction against state read at that moment. It is
 *   called when the owner opens the review, not when the agent asks, so what they see is current.
 *
 * Nothing in this file signs, sends, or approves, and there is no key anywhere in this package to
 * do it with. A prepared transaction is a proposal; the owner's wallet is the only thing that can
 * turn one into an effect.
 */
import { createHash } from "node:crypto";
import {
  PublicKey,
  TransactionMessage,
  VersionedTransaction,
  type TransactionInstruction,
} from "@solana/web3.js";
import {
  Network,
  StakingOperation,
  type StakingAction,
} from "@seekeragentconnect/server-sdk/protocol";
import {
  decodeGuardianDelegationPool,
  decodeStakeConfig,
  decodeTokenAccount,
  decodeUserStake,
  type StakeConfig,
  type UserStake,
} from "./accounts.ts";
import {
  ChainUnavailable,
  MAINNET_GENESIS_HASH,
  type ChainReader,
} from "./chain.ts";
import {
  cancelUnstakeInstruction,
  createOwnerTokenAccountInstruction,
  stakeInstruction,
  unstakeInstruction,
  withdrawInstruction,
} from "./instructions.ts";
import {
  OFFICIAL_GUARDIAN,
  SKR_DECIMALS,
  associatedTokenAddress,
  stakingAddresses,
  userStakeAddress,
  type StakingAddresses,
} from "./program.ts";
import {
  AmountError,
  amountForShares,
  cooldownComplete,
  cooldownEndsAt,
  formatSkr,
  parseBaseUnits,
  planUnstake,
  requirePositive,
} from "./shares.ts";

/**
 * The request cannot be carried out as asked, and asking again will not change that. It is the
 * owner's position or the action's own terms that say no, so it is reported to the agent as a
 * refusal rather than as an outage.
 */
export class UnsupportedStaking extends Error {
  constructor(message: string) {
    super(message);
    this.name = "UnsupportedStaking";
  }
}

/** A classic SPL token account is this many bytes, which is what its rent is priced from. */
const TOKEN_ACCOUNT_BYTES = 165;
/** A signature's base fee, used when the cluster will not price a message. */
const BASE_FEE_LAMPORTS = 5_000n;
/** Roughly how long a slot lasts, for turning a block-height window into a wall-clock estimate. */
const BLOCK_MS = 400;
/** A blockhash is valid for 150 blocks, so no honest window is longer than that. */
const MOST_BLOCKS = 150;

/** One owner's position, as the chain has it right now. */
export interface StakingPosition {
  readonly wallet: string;
  /** SKR sitting in the owner's own token account, which is what a stake can come from. */
  readonly available: bigint;
  /** Shares still earning. */
  readonly shares: bigint;
  /** What those shares are worth at the current price. */
  readonly staked: bigint;
  /** SKR waiting out a cooldown. Fixed when the unstake was made. */
  readonly unstaking: bigint;
  /** When the pending unstake started, or undefined when there is none. */
  readonly unstakeStartedAt: bigint | undefined;
  /** When the pending unstake may be withdrawn, or undefined when there is none. */
  readonly withdrawableAt: bigint | undefined;
  /** True once the cooldown has finished and the amount may be withdrawn. */
  readonly withdrawable: boolean;
  /** The cooldown this deployment keeps, read from its configuration. */
  readonly cooldownSeconds: bigint;
  /** The smallest stake the program accepts. */
  readonly minStake: bigint;
  /** The scaled share price the amounts above were computed with. */
  readonly sharePrice: bigint;
  /** The owner's SOL, which pays the fee for any of this. */
  readonly lamports: bigint;
  /** Whether the owner's SKR token account exists yet. */
  readonly tokenAccountExists: boolean;
  /** Whether the guardian pool is still taking stake. */
  readonly guardianActive: boolean;
  /** The stake account this owner's position lives in, derived rather than looked up. */
  readonly stakeAccount: string;
  /** The owner's SKR token account, which a stake spends from and a withdrawal lands in. */
  readonly tokenAccount: string;
}

/** Unsigned bytes and the facts a review needs about them. */
export interface BuiltStaking {
  readonly transaction: Uint8Array;
  readonly contentHash: Uint8Array;
  readonly lastValidBlockHeight: bigint;
  readonly estimatedExpiryMs: number;
  readonly feeLamports: bigint;
  readonly rentLamports: bigint;
}

export interface StakingProviderOptions {
  /** Which guardian's pool to stake into. Defaults to the official one. */
  readonly guardian?: PublicKey;
}

/**
 * The SKR-specific half of this server. Everything that knows what a stake account looks like, what
 * the program will refuse, and which bytes to build lives behind this one class, so the MCP tools
 * above it deal in requests and amounts and nothing else.
 */
export class SkrStakingProvider {
  readonly #chain: ChainReader;
  readonly #addresses: StakingAddresses;

  constructor(chain: ChainReader, options: StakingProviderOptions = {}) {
    this.#chain = chain;
    this.#addresses = stakingAddresses(options.guardian ?? OFFICIAL_GUARDIAN);
  }

  /** The deployment this server acts on, for a capability report and for the documentation. */
  get addresses(): StakingAddresses {
    return this.#addresses;
  }

  /**
   * Refuses an endpoint that is not serving mainnet-beta.
   *
   * The SKR staking program is deployed there and nowhere else — checked, not assumed — so an
   * endpoint pointed at another cluster would read every account as absent, and "you have no
   * position" is exactly what an empty devnet looks like. Comparing genesis hashes turns that into
   * a refusal instead of a plausible answer.
   */
  async assertNetwork(network: Network): Promise<void> {
    if (network !== Network.MAINNET) {
      throw new UnsupportedStaking(
        "the SKR staking program is deployed on mainnet only, so network must be mainnet",
      );
    }
    const genesis = await this.#chain.genesisHash();
    if (genesis !== MAINNET_GENESIS_HASH) {
      throw new UnsupportedStaking(
        "the configured Solana endpoint is not serving mainnet-beta, so it cannot reach the SKR staking program",
      );
    }
  }

  /**
   * Everything about one owner's position, in a single round trip.
   *
   * The accounts are fetched together rather than one at a time because they have to describe the
   * same moment: a share price from one slot and a share count from another would produce a staked
   * balance that was never true.
   */
  async position(wallet: string): Promise<StakingPosition> {
    const owner = parseWallet(wallet);
    const addresses = this.#addresses;
    const userStake = userStakeAddress(
      addresses.stakeConfig,
      owner,
      addresses.guardianPool,
    );
    const tokenAccount = associatedTokenAddress(owner, addresses.mint);
    const [config, pool, stake, ata, walletAccount] =
      await this.#chain.accounts([
        addresses.stakeConfig,
        addresses.guardianPool,
        userStake,
        tokenAccount,
        owner,
      ]);

    if (config === undefined) {
      throw new ChainUnavailable(
        "the staking configuration account was not found; the endpoint may not be serving mainnet-beta",
      );
    }
    this.#requireOwnedByProgram(config.owner, "the staking configuration");
    const configuration = decodeStakeConfig(config.data);
    this.#requireDeployment(configuration);

    if (pool === undefined) {
      throw new ChainUnavailable("the guardian pool account was not found");
    }
    this.#requireOwnedByProgram(pool.owner, "the guardian pool");
    const guardian = decodeGuardianDelegationPool(pool.data);
    if (!guardian.stakeConfig.equals(addresses.stakeConfig)) {
      throw new UnsupportedStaking(
        "the guardian pool belongs to another staking configuration",
      );
    }

    const position =
      stake === undefined
        ? undefined
        : this.#readStake(stake.owner, stake.data, owner);
    const shares = position?.shares ?? 0n;
    const unstaking = position?.unstakingAmount ?? 0n;
    const startedAt =
      position === undefined || unstaking === 0n
        ? undefined
        : position.unstakeTimestamp;
    const readyAt =
      startedAt === undefined
        ? undefined
        : cooldownEndsAt(startedAt, configuration.cooldownSeconds);

    return {
      wallet: owner.toBase58(),
      available: this.#readOwnBalance(ata?.data, ata?.owner, owner),
      shares,
      staked: amountForShares(shares, configuration.sharePrice),
      unstaking,
      unstakeStartedAt: startedAt,
      withdrawableAt: readyAt,
      withdrawable:
        startedAt !== undefined &&
        cooldownComplete(
          startedAt,
          configuration.cooldownSeconds,
          nowSeconds(),
        ),
      cooldownSeconds: configuration.cooldownSeconds,
      minStake: configuration.minStakeAmount,
      sharePrice: configuration.sharePrice,
      lamports: walletAccount?.lamports ?? 0n,
      tokenAccountExists: ata !== undefined,
      guardianActive: guardian.active,
      stakeAccount: userStake.toBase58(),
      tokenAccount: tokenAccount.toBase58(),
    };
  }

  /**
   * Whether the action could be carried out against the position as it stands.
   *
   * This runs before a request is stored, so an agent is told at once that it is asking to withdraw
   * a cooldown that has not finished, rather than the owner finding out when they open the review.
   * It is not a promise: the position can move between here and preparation, and `build` checks
   * every one of these again against the state it actually builds from.
   */
  async check(action: StakingAction): Promise<void> {
    await this.assertNetwork(action.network);
    const position = await this.position(action.wallet);
    this.#plan(action, position);
  }

  /**
   * Builds the unsigned transaction for the action, against state read now.
   *
   * Everything the review will show is decided here and re-checked here. The owner's wallet is the
   * fee payer and the only signer, so what comes back is a transaction nobody but them can send.
   */
  async build(action: StakingAction, nowMs: number): Promise<BuiltStaking> {
    await this.assertNetwork(action.network);
    const owner = parseWallet(action.wallet);
    const position = await this.position(action.wallet);
    const plan = this.#plan(action, position);
    const addresses = this.#addresses;
    const userStake = userStakeAddress(
      addresses.stakeConfig,
      owner,
      addresses.guardianPool,
    );
    const ownerTokenAccount = associatedTokenAddress(owner, addresses.mint);

    const instructions: TransactionInstruction[] = [];
    let rentLamports = 0n;
    switch (action.operation) {
      case StakingOperation.STAKE:
        instructions.push(
          stakeInstruction({
            addresses,
            owner,
            userStake,
            ownerTokenAccount,
            amount: plan.amount,
          }),
        );
        if (!position.tokenAccountExists) {
          // Unreachable: staking needs SKR, and SKR needs the account. Refused rather than
          // patched, because a stake built here would transfer from an account with nothing in it.
          throw new UnsupportedStaking(
            "the wallet has no SKR token account, so it holds no SKR to stake",
          );
        }
        break;
      case StakingOperation.UNSTAKE:
        instructions.push(
          unstakeInstruction({
            addresses,
            owner,
            userStake,
            shares: plan.shares,
          }),
        );
        break;
      case StakingOperation.CANCEL_UNSTAKE:
        instructions.push(
          cancelUnstakeInstruction({ addresses, owner, userStake }),
        );
        break;
      case StakingOperation.WITHDRAW:
        if (!position.tokenAccountExists) {
          // The withdrawal needs somewhere to land. The create is idempotent, so it is correct
          // even if the account appears between this read and the wallet.
          instructions.push(
            createOwnerTokenAccountInstruction({
              owner,
              mint: addresses.mint,
              ownerTokenAccount,
            }),
          );
          rentLamports = await this.#chain.rentExemption(TOKEN_ACCOUNT_BYTES);
        }
        instructions.push(
          withdrawInstruction({
            addresses,
            owner,
            userStake,
            ownerTokenAccount,
          }),
        );
        break;
      default:
        throw new UnsupportedStaking(
          "the operation is not one this server serves",
        );
    }

    const { blockhash, lastValidBlockHeight } =
      await this.#chain.latestBlockhash();
    const message = new TransactionMessage({
      payerKey: owner,
      recentBlockhash: blockhash,
      instructions,
    }).compileToV0Message();
    const transaction = new VersionedTransaction(message).serialize();
    const fee =
      (await this.#chain.feeForMessage(message)) ??
      BASE_FEE_LAMPORTS * BigInt(message.header.numRequiredSignatures);
    const height = await this.#chain.blockHeight();

    if (position.lamports < fee + rentLamports) {
      throw new UnsupportedStaking(
        `the wallet holds ${position.lamports} lamports, which does not cover the ${fee + rentLamports} lamports this transaction needs for fees${rentLamports > 0n ? " and rent" : ""}`,
      );
    }

    return {
      transaction,
      contentHash: createHash("sha256").update(transaction).digest(),
      lastValidBlockHeight,
      estimatedExpiryMs:
        nowMs + blocksLeft(lastValidBlockHeight, height) * BLOCK_MS,
      feeLamports: fee,
      rentLamports,
    };
  }

  /**
   * The one place an operation is checked against a position, used by both `check` and `build` so
   * that the answer an agent was given and the transaction the owner reviews cannot disagree about
   * the rules — only about the moment.
   */
  #plan(
    action: StakingAction,
    position: StakingPosition,
  ): { readonly amount: bigint; readonly shares: bigint } {
    switch (action.operation) {
      case StakingOperation.STAKE: {
        const amount = requirePositive(
          parseBaseUnits(action.amount, "amount"),
          "amount",
        );
        if (!position.guardianActive) {
          throw new UnsupportedStaking(
            "the guardian pool is no longer active, so it takes no new stake",
          );
        }
        if (amount < position.minStake) {
          throw new UnsupportedStaking(
            `the smallest stake this program accepts is ${formatSkr(position.minStake, SKR_DECIMALS)} SKR`,
          );
        }
        if (amount > position.available) {
          throw new UnsupportedStaking(
            `the wallet holds ${formatSkr(position.available, SKR_DECIMALS)} SKR, which is less than the ${formatSkr(amount, SKR_DECIMALS)} SKR asked for`,
          );
        }
        return { amount, shares: 0n };
      }
      case StakingOperation.UNSTAKE: {
        const requested = requirePositive(
          parseBaseUnits(action.amount, "amount"),
          "amount",
        );
        if (position.shares === 0n) {
          throw new UnsupportedStaking("there is no active stake to unstake");
        }
        if (position.withdrawable) {
          // The program's own rule (WithdrawRequired): a finished cooldown has to be taken before
          // another unstake may start. Saying so here is the difference between a clear refusal
          // and a transaction that fails on chain after the owner approved it.
          throw new UnsupportedStaking(
            `${formatSkr(position.unstaking, SKR_DECIMALS)} SKR has finished its cooldown and must be withdrawn before unstaking more`,
          );
        }
        try {
          const plan = planUnstake(
            requested,
            position.shares,
            position.sharePrice,
          );
          return { amount: plan.amount, shares: plan.shares };
        } catch (error) {
          throw error instanceof AmountError
            ? new UnsupportedStaking(error.message)
            : error;
        }
      }
      case StakingOperation.CANCEL_UNSTAKE: {
        if (position.unstaking === 0n) {
          throw new UnsupportedStaking("there is no pending unstake to cancel");
        }
        return { amount: position.unstaking, shares: 0n };
      }
      case StakingOperation.WITHDRAW: {
        if (position.unstaking === 0n) {
          throw new UnsupportedStaking("there is nothing waiting to withdraw");
        }
        if (!position.withdrawable) {
          const readyAt = position.withdrawableAt;
          throw new UnsupportedStaking(
            `the cooldown has not finished${readyAt === undefined ? "" : `; it ends at ${new Date(Number(readyAt) * 1000).toISOString()}`}`,
          );
        }
        return { amount: position.unstaking, shares: 0n };
      }
      default:
        throw new UnsupportedStaking(
          "operation must be stake, unstake, cancel_unstake, or withdraw",
        );
    }
  }

  #requireOwnedByProgram(owner: PublicKey, what: string): void {
    if (!owner.equals(this.#addresses.programId)) {
      throw new UnsupportedStaking(
        `${what} account is not owned by the SKR staking program`,
      );
    }
  }

  /**
   * The configuration has to describe the deployment this server derived its addresses for. It is
   * the one read that can catch a program that was upgraded to point somewhere else.
   */
  #requireDeployment(configuration: StakeConfig): void {
    if (!configuration.mint.equals(this.#addresses.mint)) {
      throw new UnsupportedStaking(
        "the staking configuration names another mint than SKR",
      );
    }
    if (!configuration.stakeVault.equals(this.#addresses.stakeVault)) {
      throw new UnsupportedStaking(
        "the staking configuration names another vault than the derived one",
      );
    }
  }

  #readStake(owner: PublicKey, data: Buffer, wallet: PublicKey): UserStake {
    this.#requireOwnedByProgram(owner, "the stake");
    const stake = decodeUserStake(data);
    // The account was found at the address derived for this wallet and guardian, so these can only
    // disagree if the program's seeds changed under us. Checking is cheap and the alternative is
    // reporting somebody else's position as the owner's.
    if (!stake.user.equals(wallet)) {
      throw new UnsupportedStaking(
        "the stake account at the derived address belongs to another wallet",
      );
    }
    if (!stake.guardianPool.equals(this.#addresses.guardianPool)) {
      throw new UnsupportedStaking(
        "the stake account at the derived address belongs to another guardian pool",
      );
    }
    return stake;
  }

  /** The owner's SKR balance, or zero when they have no token account yet. */
  #readOwnBalance(
    data: Buffer | undefined,
    owner: PublicKey | undefined,
    wallet: PublicKey,
  ): bigint {
    if (data === undefined) return 0n;
    if (owner === undefined || !owner.equals(TOKEN_PROGRAM)) {
      throw new UnsupportedStaking(
        "the wallet's SKR address is held by something other than the token program",
      );
    }
    const account = decodeTokenAccount(data);
    if (!account.mint.equals(this.#addresses.mint)) {
      throw new UnsupportedStaking(
        "the wallet's associated account is for another mint",
      );
    }
    if (!account.owner.equals(wallet)) {
      throw new UnsupportedStaking(
        "the wallet's associated token account is owned by somebody else",
      );
    }
    return account.amount;
  }
}

const TOKEN_PROGRAM = new PublicKey(
  "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
);

function parseWallet(wallet: string): PublicKey {
  try {
    return new PublicKey(wallet);
  } catch {
    throw new UnsupportedStaking(
      "wallet must be a base58 address of exactly 32 bytes",
    );
  }
}

/** The current second, as the program's own clock counts. */
function nowSeconds(): bigint {
  return BigInt(Math.floor(Date.now() / 1000));
}

/**
 * How many blocks a preparation still has to land in.
 *
 * Clamped at both ends. A cluster whose height has already passed the window gives a negative
 * number, which would put the estimated expiry in the past and make a fresh preparation look
 * stale; and one whose height lags behind gives an implausibly large one, which would tell the
 * owner they have longer than they do. Both are reported as the bound rather than as the number.
 */
function blocksLeft(lastValidBlockHeight: bigint, height: bigint): number {
  const remaining = lastValidBlockHeight - height;
  if (remaining <= 0n) return 0;
  return remaining > BigInt(MOST_BLOCKS) ? MOST_BLOCKS : Number(remaining);
}
