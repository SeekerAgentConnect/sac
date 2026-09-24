/**
 * What `get_staking_status` will and will not answer.
 *
 * A read looks harmless, which is exactly why it is worth a test: it is the one tool that returns
 * chain state without going anywhere near a request, a preparation, or the owner, so nothing else
 * on its path would catch a wallet this server cannot act on.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { create } from "@bufbuild/protobuf";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import type { AgentRequests } from "@seeker-vault/server-sdk";
import {
  Network,
  WalletBindingSchema,
} from "@seeker-vault/server-sdk/protocol";
import { ChainUnavailable, MAINNET_GENESIS_HASH } from "../skr/chain.ts";
import type {
  ChainAccount,
  ChainReader,
  ChainSignatureStatus,
  ChainTransaction,
  LatestBlockhash,
} from "../skr/chain.ts";
import { SkrStakingProvider } from "../skr/provider.ts";
import { STATUS_TOOL, registerStakingTools } from "./tools.ts";

/** That staker from the fixtures; the address itself never matters to these tests. */
const WALLET = "8BEEMWvZorsLCFedYhTrc8k6giigzrhWYuR4eTG1CZDW";

type ToolHandler = (input: never) => Promise<CallToolResult>;

/** An `McpServer` that only remembers what was registered on it. */
function recordingServer(): {
  server: McpServer;
  handlers: Map<string, ToolHandler>;
} {
  const handlers = new Map<string, ToolHandler>();
  const server = {
    registerTool(name: string, _config: unknown, handler: ToolHandler) {
      handlers.set(name, handler);
    },
  } as unknown as McpServer;
  return { server, handlers };
}

/**
 * A chain that answers the genesis question and refuses every other one.
 *
 * Reading an account is what a status answer is made of, so a chain that throws on `accounts`
 * proves the difference between "refused before reading" and "read, then labelled wrongly".
 */
function genesisOnlyChain(genesis: string): {
  chain: ChainReader;
  reads: () => number;
} {
  let reads = 0;
  const refuse = (): never => {
    reads += 1;
    throw new ChainUnavailable("no test reaches a cluster");
  };
  const chain: ChainReader = {
    genesisHash: () => Promise.resolve(genesis),
    accounts: (): Promise<readonly (ChainAccount | undefined)[]> => refuse(),
    latestBlockhash: (): Promise<LatestBlockhash> => refuse(),
    blockHeight: (): Promise<bigint> => refuse(),
    rentExemption: (): Promise<bigint> => refuse(),
    feeForMessage: (): Promise<bigint | undefined> => refuse(),
    signatureStatus: (): Promise<ChainSignatureStatus | undefined> => refuse(),
    confirmedTransaction: (): Promise<ChainTransaction | undefined> => refuse(),
  };
  return { chain, reads: () => reads };
}

/** The only part of `AgentRequests` the status tool touches. */
function coreBoundTo(network: Network): AgentRequests {
  return {
    activeWallet: () =>
      create(WalletBindingSchema, { wallet: WALLET, network }),
  } as unknown as AgentRequests;
}

function statusHandler(
  network: Network,
  genesis = MAINNET_GENESIS_HASH,
): { call: () => Promise<CallToolResult>; reads: () => number } {
  const { chain, reads } = genesisOnlyChain(genesis);
  const { server, handlers } = recordingServer();
  registerStakingTools(
    server,
    coreBoundTo(network),
    new SkrStakingProvider(chain),
    () => undefined,
  );
  const handler = handlers.get(STATUS_TOOL);
  assert.ok(handler !== undefined, "the status tool was not registered");
  return { call: () => handler({} as never), reads };
}

function textOf(result: CallToolResult): string {
  const first = result.content?.[0];
  return first !== undefined && first.type === "text" ? first.text : "";
}

describe("get_staking_status and the connection's network", () => {
  // The regression: creating a request asserts the network (the SDK calls `checkStaking`), but a
  // status read never did. A phone that published a devnet binding got this mainnet-only
  // provider's answer for that address back, labelled "devnet" — a position read off one cluster
  // and presented as another's, which is indistinguishable from an ordinary answer.
  it("refuses a devnet binding instead of reading mainnet and calling it devnet", async () => {
    const { call, reads } = statusHandler(Network.DEVNET);
    const result = await call();
    assert.equal(result.isError, true);
    assert.equal(result.structuredContent, undefined);
    assert.match(textOf(result), /mainnet/i);
    assert.equal(reads(), 0, "the position must not be read at all");
  });

  it("refuses an unspecified binding the same way", async () => {
    const { call, reads } = statusHandler(Network.UNSPECIFIED);
    const result = await call();
    assert.equal(result.isError, true);
    assert.equal(reads(), 0);
  });

  it("refuses a mainnet binding when the endpoint serves another cluster", async () => {
    const { call, reads } = statusHandler(
      Network.MAINNET,
      "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY",
    );
    const result = await call();
    assert.equal(result.isError, true);
    assert.match(textOf(result), /mainnet-beta/);
    assert.equal(reads(), 0);
  });

  it("reads the position for a mainnet binding on a mainnet endpoint", async () => {
    // The chain refuses the account read, so this gets as far as the read and no further — which
    // is what it is here to show: the network check passed and the position was asked for.
    const { call, reads } = statusHandler(Network.MAINNET);
    const result = await call();
    assert.equal(result.isError, true);
    assert.match(textOf(result), /CHAIN_UNAVAILABLE|11/);
    assert.equal(reads(), 1);
  });
});
