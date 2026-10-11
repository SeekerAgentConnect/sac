package io.github.brrenat.seekervault.rpc

import io.github.brrenat.seekervault.confirmations.ChainEndpoints
import io.github.brrenat.seekervault.confirmations.ChainReader
import io.github.brrenat.seekervault.confirmations.GENESIS_HASHES
import io.github.brrenat.seekervault.confirmations.hostOf
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.NetworkAccounts
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Solana networks the trusted gateway may configure an endpoint for, in order. */
val RPC_NETWORKS: List<Network> =
    listOf(Network.NETWORK_MAINNET, Network.NETWORK_DEVNET, Network.NETWORK_TESTNET)

/** The endpoint a network is asked at now. */
data class RpcEndpoint(
    val network: Network,
    val url: String,
    /**
     * Whether a genesis hash no known cluster has is accepted — a local test validator. Only a
     * debug build's devnet or testnet endpoint; mainnet never.
     */
    val allowUnknownGenesis: Boolean,
) {
    /** All that is ever shown or logged about it: a URL can carry an API key. */
    val host: String
        get() = hostOf(url)
}

/**
 * Where this phone asks each Solana network, and the proof that it is that network (SEE-184,
 * SEE-185).
 *
 * The one resolver every on-device chain read goes through: the account reads that verify a
 * transaction before it is signed (lookup tables, swap fee accounts, staking positions) and the
 * reads that follow a sent transaction afterwards (confirmation, history). There is no app-wide
 * network: each read names the network its operation was bound to, and two operations on two
 * networks reach two endpoints at the same moment.
 *
 * ## Which endpoint
 *
 * The trusted gateway's client configuration names at most one per network
 * ([io.github.brrenat.seekervault.config.ClientConfigRepository]); that is the only source. There
 * is no build default, no owner setting and no fallback: a network the gateway configured no
 * endpoint for has none, and its reads fail and say so rather than reach another network's.
 *
 * ## The proof
 *
 * Before anything read through an endpoint counts, its genesis hash must name the network
 * ([GENESIS_HASHES]). An endpoint that serves another cluster is refused for this network —
 * [SolanaProblem.WrongNetwork] — and is never used for any other. The one exception is a debug
 * build's devnet or testnet endpoint, which may be a local validator.
 *
 * ## Changes
 *
 * A validated configuration with different endpoints is [apply]ed at once, in this process, without
 * a restart: every cached client and every cached genesis answer is dropped, and [onChange]
 * listeners are told which networks changed. A proof that was in flight when the endpoints changed
 * is not kept.
 */
class SolanaRpc(
    initial: Map<Network, String>,
    /** Whether a devnet or testnet endpoint may be a local validator: debug builds only. */
    private val allowLocalValidator: Boolean,
    private val reader: (String) -> ChainReader,
    private val accounts: (String) -> SolanaAccounts,
) : ChainEndpoints, NetworkAccounts {
    private val lock = Any()
    private var generation = 0L
    private val readers = mutableMapOf<String, ChainReader>()
    private val accountReaders = mutableMapOf<String, SolanaAccounts>()
    private val genesis = mutableMapOf<String, String>()
    private val listeners = CopyOnWriteArrayList<(Network) -> Unit>()

    private val _endpoints = MutableStateFlow(clean(initial))

    /** The gateway's endpoint for each network that has one, as applied now. */
    val endpoints: StateFlow<Map<Network, String>> = _endpoints.asStateFlow()

    /** The endpoint [network] is asked at now, or null when the gateway configured none. */
    fun endpoint(network: Network): RpcEndpoint? =
        _endpoints.value[network]?.let {
            RpcEndpoint(
                network,
                it,
                allowUnknownGenesis = allowLocalValidator && network != Network.NETWORK_MAINNET,
            )
        }

    override fun configured(network: Network): Boolean = endpoint(network) != null

    override suspend fun readerFor(network: Network): ChainReader {
        val endpoint = endpoint(network) ?: throw SolanaException(SolanaProblem.NoEndpoint)
        prove(endpoint)
        return synchronized(lock) { readers.getOrPut(endpoint.url) { reader(endpoint.url) } }
    }

    override fun on(network: Network): SolanaAccounts =
        object : SolanaAccounts {
            override suspend fun accounts(addresses: List<String>): List<AccountSnapshot?> {
                // Resolved per read, so a new configuration applies to the next one.
                val endpoint = endpoint(network) ?: throw SolanaException(SolanaProblem.NoEndpoint)
                prove(endpoint)
                val client =
                    synchronized(lock) {
                        accountReaders.getOrPut(endpoint.url) { accounts(endpoint.url) }
                    }
                return client.accounts(addresses)
            }
        }

    /**
     * Applies the gateway's endpoints. Nothing happens when they are the ones in use; otherwise
     * every cached client and proof is dropped, and each network whose endpoint changed is told.
     */
    fun apply(endpoints: Map<Network, String>) {
        val next = clean(endpoints)
        val changed =
            synchronized(lock) {
                val previous = _endpoints.value
                if (previous == next) return
                _endpoints.value = next
                generation++
                readers.clear()
                accountReaders.clear()
                genesis.clear()
                RPC_NETWORKS.filter { previous[it] != next[it] }
            }
        changed.forEach { network -> listeners.forEach { it(network) } }
    }

    /** Called with each network whose endpoint changed, after the change is in effect. */
    fun onChange(listener: (Network) -> Unit) {
        listeners += listener
    }

    /** Throws unless [endpoint]'s genesis hash names its network (or it may be a validator). */
    private suspend fun prove(endpoint: RpcEndpoint) {
        if (!accepts(endpoint, served(endpoint))) throw SolanaException(SolanaProblem.WrongNetwork)
    }

    /** The known cluster [endpoint] serves by its genesis hash, or null for an unknown chain. */
    private suspend fun served(endpoint: RpcEndpoint): Network? {
        val (started, known) = synchronized(lock) { generation to genesis[endpoint.url] }
        val hash =
            known
                ?: synchronized(lock) { readers.getOrPut(endpoint.url) { reader(endpoint.url) } }
                    .genesisHash()
                    .also { hash ->
                        // A proof that started under another setting is not remembered.
                        synchronized(lock) {
                            if (generation == started) genesis[endpoint.url] = hash
                        }
                    }
        return networkOf(hash)
    }

    private fun accepts(endpoint: RpcEndpoint, served: Network?): Boolean =
        served == endpoint.network || (served == null && endpoint.allowUnknownGenesis)

    private fun networkOf(hash: String): Network? =
        GENESIS_HASHES.entries.firstOrNull { it.value == hash }?.key

    private fun clean(endpoints: Map<Network, String>): Map<Network, String> =
        endpoints
            .filterKeys { it in RPC_NETWORKS }
            .mapValues { it.value.trim() }
            .filterValues(String::isNotEmpty)
}
