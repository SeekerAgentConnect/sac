/**
 * Which network an RPC endpoint actually serves. A Solana cluster's genesis hash is fixed and
 * unique, so comparing it with the request's network catches the mistake that matters most here:
 * a mainnet request prepared against devnet, or a devnet test prepared against mainnet funds.
 */
import { Network } from "@seeker-vault/server-sdk/protocol";

/** Each network's genesis hash, as `solana genesis-hash` reports it. */
export const GENESIS_HASHES: ReadonlyMap<Network, string> = new Map([
  [Network.MAINNET, "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"],
  [Network.DEVNET, "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG"],
  [Network.TESTNET, "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY"],
]);

/** The network with this genesis hash, or undefined for a cluster the sidecar doesn't know. */
export function networkOfGenesisHash(hash: string): Network | undefined {
  for (const [network, known] of GENESIS_HASHES) {
    if (known === hash) return network;
  }
  return undefined;
}
