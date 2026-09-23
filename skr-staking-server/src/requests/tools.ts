/**
 * The five tools this server offers an agent (SEE-146).
 *
 * One of them reads and four of them ask. The four that ask create an approval request and stop:
 * there is no key in this package, no way to reach a wallet from it, and no code path that
 * approves anything. What an agent gets back is a request ID and PENDING, which is a question put
 * to the owner rather than an answer.
 *
 * **The wallet is never a parameter.** It comes from the connection's own binding — the wallet the
 * owner published from their phone — so an agent cannot name somebody else's, and a request that
 * outlives a wallet change is cancelled by the SDK rather than prepared against the wrong position.
 *
 * **Reading a result is the same call again.** Every creating tool takes an `idempotency_key`, and
 * repeating a call with the same key and the same parameters returns the original request as it
 * stands now, whatever state it has reached. That is how an agent follows a request to its end
 * without a sixth tool, and it is also what makes a retry after a lost response safe: the second
 * call finds the first request instead of creating a second one.
 */
import { create } from "@bufbuild/protobuf";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import {
  MAX_NOTE_BYTES,
  MAX_EXPIRES_IN_SECONDS,
  MIN_EXPIRES_IN_SECONDS,
  RequestFailure,
  encodeBase58,
  invalidActionReason,
  isTerminal,
  networkName,
  privateRequest,
  stakingOperationName,
  type AgentRequests,
} from "@seeker-vault/server-sdk";
import {
  ActionSchema,
  RequestError,
  RequestState,
  StakingOperation,
  type ActionRequest,
} from "@seeker-vault/server-sdk/protocol";
import { z } from "zod";
import { SKR_DECIMALS } from "../skr/program.ts";
import { formatSkr } from "../skr/shares.ts";
import type { SkrStakingProvider, StakingPosition } from "../skr/provider.ts";

export const STATUS_TOOL = "get_staking_status";
export const STAKE_TOOL = "request_stake";
export const UNSTAKE_TOOL = "request_unstake";
export const CANCEL_UNSTAKE_TOOL = "request_cancel_unstake";
export const WITHDRAW_TOOL = "request_withdraw";

/** Every tool this server serves, in the order the documentation lists them. */
export const TOOLS = [
  STATUS_TOOL,
  STAKE_TOOL,
  UNSTAKE_TOOL,
  CANCEL_UNSTAKE_TOOL,
  WITHDRAW_TOOL,
] as const;

const AMOUNT_DESCRIPTION =
  'The amount in SKR base units, as a decimal integer string: SKR has 6 decimals, so 1 SKR is "1000000". No sign, decimal point, exponent, or leading zeros.';

const IDEMPOTENCY_DESCRIPTION =
  "1 to 128 characters from A-Z, a-z, 0-9, '.', '_', ':', and '-'. Reuse it to retry, or to read the request you already created; use a new one for a new request.";

const NOTE_DESCRIPTION = `Optional: why you're asking, up to ${MAX_NOTE_BYTES} UTF-8 bytes. The owner sees it apart from the verified parameters, and it is never treated as a fact about the transaction.`;

const EXPIRES_DESCRIPTION = `Optional: how long the owner has to decide, ${MIN_EXPIRES_IN_SECONDS} to ${MAX_EXPIRES_IN_SECONDS} seconds. The server's default applies otherwise.`;

const STATUSES = [
  "PENDING",
  "PROCESSING",
  "SUBMITTED",
  "CONFIRMED",
  "COMPLETED",
  "REJECTED",
  "CANCELLED",
  "EXPIRED",
  "FAILED",
  "UNKNOWN",
] as const;

const REQUEST_SCHEMA = {
  request_id: z.string(),
  operation: z.enum(["stake", "unstake", "cancel_unstake", "withdraw"]),
  status: z.enum(STATUSES),
  terminal: z.boolean().describe("True once the request can no longer change."),
  wallet: z.string(),
  network: z.string(),
  amount: z
    .string()
    .optional()
    .describe("The SKR base units the request names, when it names any."),
  created_at: z.string(),
  expires_at: z.string(),
  updated_at: z.string(),
  signature: z
    .string()
    .optional()
    .describe("The transaction's ID on chain, once the wallet has sent it."),
  detail: z
    .string()
    .optional()
    .describe("Why a request ended as it did, as display text."),
  confirmation: z.string().optional(),
  slot: z.number().optional(),
  chain_error: z.string().optional(),
  checked_at: z.string().optional(),
  checked_with: z
    .string()
    .optional()
    .describe("The host of the one endpoint a confirmed result rests on."),
};

const STATUS_SCHEMA = {
  wallet: z.string(),
  network: z.string(),
  available_skr: z
    .string()
    .describe(
      "SKR in the wallet, in base units, which is what a stake can use.",
    ),
  staked_skr: z
    .string()
    .describe("What the active shares are worth now, in base units."),
  shares: z.string().describe("The active shares themselves."),
  unstaking_skr: z
    .string()
    .describe("SKR waiting out the cooldown, fixed when the unstake was made."),
  withdrawable: z
    .boolean()
    .describe("Whether the pending unstake's cooldown has finished."),
  withdrawable_at: z
    .string()
    .optional()
    .describe("When the pending unstake may be withdrawn."),
  cooldown_seconds: z
    .number()
    .describe(
      "The cooldown this deployment keeps, read from its configuration.",
    ),
  minimum_stake_skr: z.string(),
  share_price: z
    .string()
    .describe("The scaled share price the amounts were computed with."),
  sol_lamports: z
    .string()
    .describe("The wallet's SOL, which pays the network fee for any of this."),
  program: z.string(),
  mint: z.string(),
  stake_account: z
    .string()
    .describe("The program-derived account this owner's position lives in."),
  token_account: z
    .string()
    .describe("The owner's SKR account, which a withdrawal lands in."),
  display: z
    .string()
    .describe("The same position in SKR, for showing a person."),
};

/** Registers the five tools on one MCP server instance. */
export function registerStakingTools(
  server: McpServer,
  core: AgentRequests,
  provider: SkrStakingProvider,
  log: (message: string) => void,
): void {
  registerStatus(server, core, provider);
  registerAmountTool(server, core, {
    tool: STAKE_TOOL,
    operation: StakingOperation.STAKE,
    title: "Ask the owner to stake SKR",
    description:
      "Creates an approval request to stake an amount of SKR. It creates the request and nothing else: no transaction is built when you call this, nothing is signed, and nothing is sent. The owner sees it on their phone, reviews the exact transaction there, and decides. Call again with the same idempotency_key to read how it ended.",
    amount:
      "How much SKR to stake, in base units. It must be at least the program's minimum and at most what the wallet holds.",
    log,
  });
  registerAmountTool(server, core, {
    tool: UNSTAKE_TOOL,
    operation: StakingOperation.UNSTAKE,
    title: "Ask the owner to start unstaking SKR",
    description:
      "Creates an approval request to start unstaking an amount of SKR. Unstaking is not withdrawing: it burns shares, stops them earning, and starts a cooldown, after which request_withdraw moves the tokens. Unstaking again while a cooldown is running adds to the pending amount and restarts the cooldown. An amount at or above the whole position unstakes all of it.",
    amount:
      "How much SKR to start unstaking, in base units. At or above the position's current value, the whole position is unstaked.",
    log,
  });
  registerBareTool(server, core, {
    tool: CANCEL_UNSTAKE_TOOL,
    operation: StakingOperation.CANCEL_UNSTAKE,
    title: "Ask the owner to cancel a pending unstake",
    description:
      "Creates an approval request to cancel the pending unstake, putting the whole pending amount back to work as stake and clearing the cooldown. It takes no amount: the program cancels all of it or none of it.",
    log,
  });
  registerBareTool(server, core, {
    tool: WITHDRAW_TOOL,
    operation: StakingOperation.WITHDRAW,
    title: "Ask the owner to withdraw unstaked SKR",
    description:
      "Creates an approval request to withdraw the amount a finished cooldown released, moving it from the vault back into the wallet. It takes no amount: the program pays out exactly what it recorded when the unstake was made. It fails while the cooldown is still running.",
    log,
  });
}

function registerStatus(
  server: McpServer,
  core: AgentRequests,
  provider: SkrStakingProvider,
): void {
  server.registerTool(
    STATUS_TOOL,
    {
      title: "Read the owner's SKR staking position",
      description:
        "Reads the wallet's SKR, its active stake, any pending unstake and whether it can be withdrawn yet, from the chain. It is a read: it creates no request, asks the owner nothing, and builds no transaction. The wallet is the one the owner connected, so it takes no parameters.",
      inputSchema: {},
      outputSchema: STATUS_SCHEMA,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        // It reads a public chain through a configured endpoint.
        openWorldHint: true,
      },
    },
    async (): Promise<CallToolResult> =>
      answerAsync(async () => {
        const binding = core.activeWallet();
        const position = await provider.position(binding.wallet);
        return statusView(position, provider, networkName(binding.network));
      }),
  );
}

interface AmountToolOptions {
  readonly tool: string;
  readonly operation: StakingOperation;
  readonly title: string;
  readonly description: string;
  readonly amount: string;
  readonly log: (message: string) => void;
}

function registerAmountTool(
  server: McpServer,
  core: AgentRequests,
  options: AmountToolOptions,
): void {
  server.registerTool(
    options.tool,
    {
      title: options.title,
      description: options.description,
      inputSchema: {
        amount: z.string().describe(`${options.amount} ${AMOUNT_DESCRIPTION}`),
        idempotency_key: z.string().describe(IDEMPOTENCY_DESCRIPTION),
        note: z.string().optional().describe(NOTE_DESCRIPTION),
        expires_in_seconds: z.number().optional().describe(EXPIRES_DESCRIPTION),
      },
      outputSchema: REQUEST_SCHEMA,
      annotations: {
        readOnlyHint: false,
        // It asks. Whether anything happens is the owner's decision, and the request itself can be
        // cancelled without effect until they make it.
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async (input): Promise<CallToolResult> =>
      answerAsync(() =>
        createStakingRequest(core, {
          operation: options.operation,
          amount: input.amount,
          idempotencyKey: input.idempotency_key,
          note: input.note,
          expiresInSeconds: input.expires_in_seconds,
          log: options.log,
        }),
      ),
  );
}

interface BareToolOptions {
  readonly tool: string;
  readonly operation: StakingOperation;
  readonly title: string;
  readonly description: string;
  readonly log: (message: string) => void;
}

function registerBareTool(
  server: McpServer,
  core: AgentRequests,
  options: BareToolOptions,
): void {
  server.registerTool(
    options.tool,
    {
      title: options.title,
      description: options.description,
      inputSchema: {
        idempotency_key: z.string().describe(IDEMPOTENCY_DESCRIPTION),
        note: z.string().optional().describe(NOTE_DESCRIPTION),
        expires_in_seconds: z.number().optional().describe(EXPIRES_DESCRIPTION),
      },
      outputSchema: REQUEST_SCHEMA,
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async (input): Promise<CallToolResult> =>
      answerAsync(() =>
        createStakingRequest(core, {
          operation: options.operation,
          // The program takes no amount for these, so neither does the action.
          amount: "",
          idempotencyKey: input.idempotency_key,
          note: input.note,
          expiresInSeconds: input.expires_in_seconds,
          log: options.log,
        }),
      ),
  );
}

interface NewStakingRequest {
  readonly operation: StakingOperation;
  readonly amount: string;
  readonly idempotencyKey: string;
  readonly note: string | undefined;
  readonly expiresInSeconds: number | undefined;
  readonly log: (message: string) => void;
}

/**
 * The one path that stores a staking request, shared by all four tools so they cannot drift apart
 * about the order the checks happen in. That order is the point:
 *
 * 1. The connection's wallet binding, so the action is bound to the owner's own wallet.
 * 2. The action's own rules, so a malformed amount never reaches a chain.
 * 3. The idempotency replay, **before** any chain read — a retry has to give back the original
 *    request whether or not an endpoint is reachable, and whether or not the position has moved
 *    since. A key that already stands for a request is answered with that request, as it is now.
 * 4. Only then, what the chain says about whether this is possible at all.
 */
async function createStakingRequest(
  core: AgentRequests,
  request: NewStakingRequest,
): Promise<RequestView> {
  const binding = core.activeWallet();
  const action = create(ActionSchema, {
    kind: {
      case: "staking",
      value: {
        wallet: binding.wallet,
        network: binding.network,
        operation: request.operation,
        amount: request.amount,
      },
    },
  });
  const reason = invalidActionReason(action);
  if (reason !== undefined) {
    throw new RequestFailure(RequestError.INVALID_PARAMETERS, reason);
  }
  const replay = core.replayOf(request.idempotencyKey, action);
  if (replay !== undefined) {
    request.log(
      `request ${idOf(replay)} returned again for its idempotency key`,
    );
    return requestView(await settled(core, replay));
  }
  const staking = core.staking;
  if (staking === undefined) {
    throw new RequestFailure(
      RequestError.CHAIN_UNAVAILABLE,
      "this server has no Solana endpoint configured, so it can act on no staking position",
    );
  }
  await staking.checkStaking(action.kind.value as never);
  const { request: stored } = core.createRequest(
    privateRequest(
      action,
      request.note ?? "",
      request.idempotencyKey,
      request.expiresInSeconds,
    ),
  );
  request.log(
    `request ${idOf(stored)} stored (${stakingOperationName(request.operation)})`,
  );
  return requestView(stored);
}

/**
 * The request with whatever the chain has since said about it.
 *
 * Reading is what makes the server look: there is no background worker here, so a SUBMITTED
 * request becomes CONFIRMED or FAILED because somebody asked about it.
 */
async function settled(
  core: AgentRequests,
  request: ActionRequest,
): Promise<ActionRequest> {
  const confirmations = core.confirmations;
  if (confirmations === undefined) return request;
  return confirmations.settle(request);
}

type RequestView = {
  request_id: string;
  operation: "stake" | "unstake" | "cancel_unstake" | "withdraw";
  status: (typeof STATUSES)[number];
  terminal: boolean;
  wallet: string;
  network: string;
  amount?: string;
  created_at: string;
  expires_at: string;
  updated_at: string;
  signature?: string;
  detail?: string;
  confirmation?: string;
  slot?: number;
  chain_error?: string;
  checked_at?: string;
  checked_with?: string;
};

type StatusView = {
  wallet: string;
  network: string;
  available_skr: string;
  staked_skr: string;
  shares: string;
  unstaking_skr: string;
  withdrawable: boolean;
  withdrawable_at?: string;
  cooldown_seconds: number;
  minimum_stake_skr: string;
  share_price: string;
  sol_lamports: string;
  program: string;
  mint: string;
  stake_account: string;
  token_account: string;
  display: string;
};

function statusView(
  position: StakingPosition,
  provider: SkrStakingProvider,
  network: string,
): StatusView {
  const addresses = provider.addresses;
  return {
    wallet: position.wallet,
    network,
    available_skr: position.available.toString(),
    staked_skr: position.staked.toString(),
    shares: position.shares.toString(),
    unstaking_skr: position.unstaking.toString(),
    withdrawable: position.withdrawable,
    ...(position.withdrawableAt === undefined
      ? {}
      : {
          withdrawable_at: new Date(
            Number(position.withdrawableAt) * 1000,
          ).toISOString(),
        }),
    cooldown_seconds: Number(position.cooldownSeconds),
    minimum_stake_skr: position.minStake.toString(),
    share_price: position.sharePrice.toString(),
    sol_lamports: position.lamports.toString(),
    program: addresses.programId.toBase58(),
    mint: addresses.mint.toBase58(),
    stake_account: position.stakeAccount,
    token_account: position.tokenAccount,
    display: describe(position),
  };
}

/** The position in words, so an agent reporting it to a person does not have to do the arithmetic. */
function describe(position: StakingPosition): string {
  const parts = [
    `${formatSkr(position.available, SKR_DECIMALS)} SKR in the wallet`,
    `${formatSkr(position.staked, SKR_DECIMALS)} SKR staked`,
  ];
  if (position.unstaking > 0n) {
    parts.push(
      position.withdrawable
        ? `${formatSkr(position.unstaking, SKR_DECIMALS)} SKR unstaked and ready to withdraw`
        : `${formatSkr(position.unstaking, SKR_DECIMALS)} SKR unstaking, withdrawable from ${
            position.withdrawableAt === undefined
              ? "an unknown time"
              : new Date(Number(position.withdrawableAt) * 1000).toISOString()
          }`,
    );
  }
  return `${parts.join(", ")}.`;
}

function requestView(request: ActionRequest): RequestView {
  const action = request.action;
  if (action?.kind.case !== "staking") {
    // Every request this server creates is a staking one, so this cannot happen through the tools.
    throw new RequestFailure(
      RequestError.NOT_FOUND,
      "that request is not one of this server's",
    );
  }
  const staking = action.kind.value;
  const outcome = request.outcome;
  const confirmation = outcome?.confirmation;
  return {
    request_id: idOf(request),
    operation: stakingOperationName(
      staking.operation,
    ) as RequestView["operation"],
    status: RequestState[request.state] as RequestView["status"],
    terminal: isTerminal(request.state),
    wallet: staking.wallet,
    network: networkName(staking.network),
    ...(staking.amount === "" ? {} : { amount: staking.amount }),
    created_at: iso(request.createdAt),
    expires_at: iso(request.expiresAt),
    updated_at: iso(request.updatedAt),
    ...(outcome !== undefined && outcome.signature.length > 0
      ? { signature: encodeBase58(outcome.signature) }
      : {}),
    ...(outcome?.detail ? { detail: outcome.detail } : {}),
    ...(confirmation === undefined
      ? {}
      : {
          confirmation:
            CONFIRMATION_LEVELS[confirmation.level] ?? "UNSPECIFIED",
          slot: Number(confirmation.slot),
          ...(confirmation.chainError
            ? { chain_error: confirmation.chainError }
            : {}),
          ...(confirmation.checkedAt === undefined
            ? {}
            : { checked_at: iso(confirmation.checkedAt) }),
          ...(confirmation.endpoint
            ? { checked_with: confirmation.endpoint }
            : {}),
        }),
  };
}

const CONFIRMATION_LEVELS: Readonly<Record<number, string>> = {
  0: "UNSPECIFIED",
  1: "NOT_FOUND",
  2: "PROCESSED",
  3: "CONFIRMED",
  4: "FINALIZED",
};

function idOf(request: ActionRequest): string {
  return request.ref?.requestId ?? "";
}

function iso(
  timestamp: { seconds: bigint; nanos: number } | undefined,
): string {
  if (timestamp === undefined) return "";
  const ms =
    Number(timestamp.seconds) * 1000 + Math.floor(timestamp.nanos / 1_000_000);
  return new Date(ms).toISOString();
}

/** A tool result: the view as text and as structured content, or the refusal an agent can read. */
async function answerAsync(
  operation: () => Promise<RequestView | StatusView>,
): Promise<CallToolResult> {
  try {
    const view = await operation();
    return {
      content: [{ type: "text", text: JSON.stringify(view) }],
      structuredContent: { ...view },
    };
  } catch (error) {
    if (!(error instanceof RequestFailure)) throw error;
    // No structuredContent: clients validate it against the success schema.
    return {
      isError: true,
      content: [{ type: "text", text: `${error.code}: ${error.message}` }],
    };
  }
}
