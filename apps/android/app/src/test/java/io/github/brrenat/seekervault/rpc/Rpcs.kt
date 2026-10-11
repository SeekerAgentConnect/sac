package io.github.brrenat.seekervault.rpc

import io.github.brrenat.seekervault.confirmations.ChainReader
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.rpc.storage.RpcSettingsStore
import io.github.brrenat.seekervault.solana.NetworkAccounts
import io.github.brrenat.seekervault.solana.SolanaAccounts
import java.io.File

/**
 * A fake chain that serves [network] and nothing else (SEE-184). Asking it about another network is
 * a test failure, so a test that wires one can't pass with a read routed to the wrong cluster.
 */
fun SolanaAccounts.serving(network: Network = Network.NETWORK_MAINNET): NetworkAccounts =
    NetworkAccounts { asked ->
        check(asked == network) { "a read about $asked reached the $network chain" }
        this
    }

/** The app's resolver over [defaults] and a fake chain reader, with its settings under [dir]. */
fun testRpc(
    dir: File,
    defaults: RpcDefaults = RpcDefaults(),
    accounts: (String) -> SolanaAccounts = { error("no account reads in this test") },
    reader: (String) -> ChainReader,
): SolanaRpc = SolanaRpc(RpcSettingsStore(dir), defaults, reader, accounts)
