package io.github.brrenat.seekervault.rpc

import io.github.brrenat.seekervault.confirmations.ChainReader
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.solana.NetworkAccounts
import io.github.brrenat.seekervault.solana.SolanaAccounts

/**
 * A fake chain that serves [network] and nothing else (SEE-184). Asking it about another network is
 * a test failure, so a test that wires one can't pass with a read routed to the wrong cluster.
 */
fun SolanaAccounts.serving(network: Network = Network.NETWORK_MAINNET): NetworkAccounts =
    NetworkAccounts { asked ->
        check(asked == network) { "a read about $asked reached the $network chain" }
        this
    }

/**
 * The app's resolver over [endpoints] — what the trusted gateway's client configuration named for
 * each network (SEE-185) — and a fake chain reader.
 */
fun testRpc(
    endpoints: Map<Network, String> = emptyMap(),
    allowLocalValidator: Boolean = false,
    accounts: (String) -> SolanaAccounts = { error("no account reads in this test") },
    reader: (String) -> ChainReader,
): SolanaRpc = SolanaRpc(endpoints, allowLocalValidator, reader, accounts)
