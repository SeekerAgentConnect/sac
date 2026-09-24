/**
 * Starting and stopping this server.
 *
 * One listener carries three things: `/healthz`, `/mcp` for the agent, and the Direct Server SDK's
 * phone API for everything else. The SDK owns pairing, the request lifecycle, preparation, results
 * and confirmation — this file only decides which path reaches which handler and hands the SDK the
 * staking provider it should prepare with.
 */
import { createServer as createHttpServer } from "node:http";
import { createServer as createH2cServer } from "node:http2";
import {
  alreadyAnswered,
  writeJson,
  type AnyRequest,
  type AnyResponse,
} from "./http.ts";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import {
  ProviderUnavailable,
  UnsupportedPreparation,
  openDirectServer,
  type ConfirmationProvider,
  type DirectServer,
  type StakingProvider,
} from "@seeker-vault/server-sdk";
import { Network } from "@seeker-vault/server-sdk/protocol";
import type { Config } from "./config.ts";
import { UNUSED_LIVE_COMMAND_TIMEOUT_SECONDS } from "./config.ts";
import { ChainUnavailable, SolanaRpc } from "./skr/chain.ts";
import { isApprovedTransaction } from "./skr/confirmation.ts";
import { SkrStakingProvider, UnsupportedStaking } from "./skr/provider.ts";
import { mcpEndpoint, type McpEndpoint } from "./mcp-endpoint.ts";

export const VERSION = "0.1.0";

export interface StartedServer {
  /** The origin the listener actually came up on. */
  readonly url: string;
  readonly direct: DirectServer;
  readonly provider: SkrStakingProvider;
  close(): Promise<void>;
}

export interface StartOptions {
  readonly log?: (message: string) => void;
}

/**
 * Turns this package's chain errors into the two the SDK understands.
 *
 * The distinction is the whole reason it exists: `CHAIN_UNAVAILABLE` tells an agent to try again,
 * and `INVALID_PARAMETERS` tells it that trying again will not help. Collapsing them would make a
 * position that cannot be unstaked look like an endpoint having a bad minute.
 */
async function providerCall<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    if (error instanceof ChainUnavailable) {
      throw new ProviderUnavailable(error.message, { cause: error });
    }
    if (error instanceof UnsupportedStaking) {
      throw new UnsupportedPreparation(error.message);
    }
    throw error;
  }
}

export async function startStakingServer(
  config: Config,
  options: StartOptions = {},
): Promise<StartedServer> {
  const log =
    options.log ?? ((message) => console.log(`[skr-staking] ${message}`));

  mkdirSync(dirname(config.databasePath), { recursive: true });

  const chain = new SolanaRpc(config.rpcUrl, {
    timeoutMs: config.rpcTimeoutMs,
  });
  const provider = new SkrStakingProvider(chain, {
    ...(config.guardian === undefined ? {} : { guardian: config.guardian }),
  });

  // Refuse to come up against the wrong cluster. A staking server pointed at devnet would read
  // every account as absent and report "you have nothing staked", which is indistinguishable from
  // the truth and is the one wrong answer worth refusing to start over.
  await providerCall(() => provider.assertNetwork(Network.MAINNET));
  log(
    `reading mainnet-beta; staking with guardian pool ${provider.addresses.guardianPool.toBase58()}`,
  );

  const stakingProvider: StakingProvider = {
    checkStaking: (action) => providerCall(() => provider.check(action)),
    buildStaking: (action, at) =>
      providerCall(() => provider.build(action, at)),
  };

  /**
   * How a submitted staking transaction stops being submitted.
   *
   * Without this the SDK builds no `ConfirmationTracker`, and a request the wallet has sent stays
   * SUBMITTED for good: the agent polls and reads the same unchanged request, and the owner's own
   * `CheckStatus` on the phone is answered `CHAIN_UNAVAILABLE` because there is nothing to ask.
   * For staking that is worse than an unfinished transfer — an unstake nobody can confirm is a
   * 48-hour cooldown the owner cannot tell has started.
   *
   * It reads the same endpoint everything else here reads, and it can do nothing but read: there
   * is no submit call in this interface, and a transaction that does not match the approved bytes
   * settles nothing rather than being reported either way.
   */
  const confirmationProvider: ConfirmationProvider = {
    endpointUrl: config.rpcUrl,
    assertNetwork: (network) =>
      providerCall(() => provider.assertNetwork(network)),
    signatureStatus: (signature, searchHistory) =>
      providerCall(() => chain.signatureStatus(signature, searchHistory)),
    confirmedTransaction: (signature) =>
      providerCall(() => chain.confirmedTransaction(signature)),
    blockHeight: () => providerCall(() => chain.blockHeight()),
    matchesApprovedTransaction: isApprovedTransaction,
  };

  let listeningUrl: string | undefined;
  const direct = openDirectServer({
    databasePath: config.databasePath,
    publicOrigin: () => config.publicUrl ?? listeningUrl ?? "",
    requestTtlSeconds: config.requestTtlSeconds,
    pendingLimit: config.pendingLimit,
    pairingTokenTtlSeconds: config.pairingTokenTtlSeconds,
    liveCommandTimeoutSeconds: UNUSED_LIVE_COMMAND_TIMEOUT_SECONDS,
    log,
    stakingProvider,
    confirmationProvider,
  });

  let mcp: McpEndpoint | undefined;
  try {
    mcp = mcpEndpoint({
      core: direct.requests,
      provider,
      mcpToken: config.mcpToken,
      allowedHosts: config.allowedHosts,
      version: VERSION,
      log,
    });
    // No `legacyPhoneToken`: that credential exists for the Stage 1 live diagnostic, which this
    // server does not serve. A phone here authenticates with the credential it was issued at
    // pairing, and nothing else opens the phone API.
    const phone = direct.phoneHandler({ includeUpdates: true });

    const handler = (request: AnyRequest, response: AnyResponse): void => {
      const path = (request.url ?? "/").split("?")[0] ?? "/";
      if (path === "/healthz") {
        writeJson(response, 200, { status: "ok" });
        return;
      }
      if (path === "/mcp") {
        void mcp?.handle(request, response).catch((error: unknown) => {
          log(`/mcp failed: ${messageOf(error)}`);
          if (!alreadyAnswered(response)) {
            writeJson(response, 500, { error: "internal" });
          }
        });
        return;
      }
      phone(request, response);
    };

    const server = config.h2c
      ? createH2cServer(handler as never)
      : createHttpServer(handler as never);

    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.listen(config.port, config.host, () => {
        server.removeListener("error", reject);
        resolve();
      });
    });
    const address = server.address();
    const port =
      address !== null && typeof address === "object"
        ? address.port
        : config.port;
    // Cleartext either way: h2c is still http, and TLS is terminated in front of this server.
    listeningUrl =
      config.publicUrl ?? `http://${displayHost(config.host)}:${port}`;
    log(
      `listening on ${listeningUrl}; MCP at /mcp, phone API on the same listener`,
    );

    const endpoint = mcp;
    return {
      url: listeningUrl,
      direct,
      provider,
      close: async () => {
        direct.beginShutdown();
        await endpoint.close();
        await new Promise<void>((resolve) => server.close(() => resolve()));
        await direct.close();
      },
    };
  } catch (error) {
    // Whatever failed, the database must not be left locked by a server that never came up.
    await mcp?.close();
    direct.beginShutdown();
    await direct.close();
    throw error;
  }
}

/** A wildcard bind has no address worth printing, so say the loopback one a person can use. */
function displayHost(host: string): string {
  if (host === "0.0.0.0" || host === "::") return "127.0.0.1";
  return host === "::1" ? "[::1]" : host;
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
