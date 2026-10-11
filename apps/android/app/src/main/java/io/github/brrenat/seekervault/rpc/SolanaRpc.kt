package io.github.brrenat.seekervault.rpc

import io.github.brrenat.seekervault.confirmations.ChainEndpoints
import io.github.brrenat.seekervault.confirmations.ChainReader
import io.github.brrenat.seekervault.confirmations.GENESIS_HASHES
import io.github.brrenat.seekervault.confirmations.hostOf
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.rpc.storage.RpcSettingsStore
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.NetworkAccounts
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Solana networks this phone has an endpoint setting for, in the order they are shown. */
val RPC_NETWORKS: List<Network> =
    listOf(Network.NETWORK_MAINNET, Network.NETWORK_DEVNET, Network.NETWORK_TESTNET)

/**
 * What this build was configured with (SEE-165, SEE-184): the initial endpoint for each network,
 * and the legacy general one (`seekervault.solanaRpc`). All of it is extractable client
 * configuration; none of it is a secret.
 */
data class RpcDefaults(
    val perNetwork: Map<Network, String> = emptyMap(),
    /** The legacy general endpoint: used for a network only once its genesis hash names it. */
    val general: String = "",
    /**
     * Whether an explicitly configured devnet or testnet endpoint may serve a genesis hash no
     * cluster has — a local test validator. Only a debug build says yes; mainnet never does, and
     * neither does the general endpoint, which was not configured *for* any network.
     */
    val allowLocalValidator: Boolean = false,
    /** Whether an `http:` endpoint may be entered: a debug build's loopback validator only. */
    val allowCleartext: Boolean = false,
) {
    fun forNetwork(network: Network): String = perNetwork[network].orEmpty().trim()
}

/** Where the endpoint a network is asked at comes from, in order of precedence. */
enum class RpcSource {
    /** The owner set it on this phone. */
    Owner,
    /** This build's endpoint for the network. */
    Build,
    /** This build's general endpoint, which must prove the network by its genesis hash. */
    General,
}

/** The endpoint a network is asked at now, and why that one. */
data class RpcEndpoint(
    val network: Network,
    val url: String,
    val source: RpcSource,
    val allowUnknownGenesis: Boolean,
) {
    /** All that is ever shown or logged about it: a URL can carry an API key. */
    val host: String
        get() = hostOf(url)
}

/** Why a URL can't be an endpoint setting, before anything is asked of it. */
enum class RpcUrlProblem {
    Empty,
    NotAUrl,
    /** Only `https:`, or `http:` in a debug build for a local validator. */
    NotHttps,
    /** A username or password in the URL. */
    Credentials,
}

/** What asking an endpoint which network it serves found. */
sealed interface RpcCheck {
    /** Nothing is set for the network. */
    data object NotSet : RpcCheck

    data class Invalid(val problem: RpcUrlProblem) : RpcCheck

    /** It serves the network: by its genesis hash, or as an allowed local validator. */
    data class Serves(val host: String, val localValidator: Boolean = false) : RpcCheck

    /** It serves [served] — or, when null, a chain no known cluster has. */
    data class OtherNetwork(val host: String, val served: Network?) : RpcCheck

    /** It couldn't be asked, or its answer couldn't be used. */
    data class Failed(val host: String, val problem: SolanaProblem) : RpcCheck
}

/**
 * Where this phone asks each Solana network, and the proof that it is that network (SEE-184).
 *
 * The one resolver every on-device chain read goes through: the account reads that verify a
 * transaction before it is signed (lookup tables, swap fee accounts, staking positions) and the
 * reads that follow a sent transaction afterwards (confirmation, history). There is no app-wide
 * network: each read names the network its operation was bound to, and two operations on two
 * networks reach two endpoints at the same moment.
 *
 * ## Which endpoint
 *
 * For each network, the first that is set of: the owner's setting on this phone, this build's
 * endpoint for that network, and this build's general endpoint. It is a **selection, not a
 * failover**: an endpoint the owner chose is the one used, and a failure of it is reported rather
 * than quietly replaced by another.
 *
 * ## The proof
 *
 * Before anything read through an endpoint counts, its genesis hash must name the network
 * ([GENESIS_HASHES]). An endpoint that serves another cluster is refused for this network —
 * [SolanaProblem.WrongNetwork] — and is never used as a fallback for any other. The one exception
 * is a debug build's explicitly configured devnet or testnet endpoint, which may be a local
 * validator.
 *
 * ## Whose endpoint
 *
 * The application's and its owner's. Nothing a server, feed, manifest, proposal or provider sends
 * can set or override it: the only writers are the build and the settings sheet.
 *
 * ## Changes
 *
 * A change takes effect at once, in this process, without a restart: every cached client and every
 * cached genesis answer is dropped, and [onChange] listeners are told which network changed. A
 * proof that was in flight when the setting changed is not kept.
 */
class SolanaRpc(
    private val store: RpcSettingsStore,
    private val defaults: RpcDefaults,
    private val reader: (String) -> ChainReader,
    private val accounts: (String) -> SolanaAccounts,
) : ChainEndpoints, NetworkAccounts {
    private val lock = Any()
    private var generation = 0L
    private val readers = mutableMapOf<String, ChainReader>()
    private val accountReaders = mutableMapOf<String, SolanaAccounts>()
    private val genesis = mutableMapOf<String, String>()
    private val listeners = CopyOnWriteArrayList<(Network) -> Unit>()

    private val _settings = MutableStateFlow(store.read())

    /** The owner's own endpoint for each network that has one. */
    val settings: StateFlow<Map<Network, String>> = _settings.asStateFlow()

    val buildDefaults: RpcDefaults
        get() = defaults

    /** The endpoint [network] is asked at now, or null when nothing is set for it. */
    fun endpoint(network: Network): RpcEndpoint? {
        val local = defaults.allowLocalValidator && network != Network.NETWORK_MAINNET
        _settings.value[network]?.let {
            return RpcEndpoint(network, it, RpcSource.Owner, local)
        }
        defaults.forNetwork(network).takeIf(String::isNotEmpty)?.let {
            return RpcEndpoint(network, it, RpcSource.Build, local)
        }
        defaults.general.trim().takeIf(String::isNotEmpty)?.let {
            return RpcEndpoint(network, it, RpcSource.General, allowUnknownGenesis = false)
        }
        return null
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
                // Resolved per read, so a change of setting applies to the next one.
                val endpoint = endpoint(network) ?: throw SolanaException(SolanaProblem.NoEndpoint)
                prove(endpoint)
                val client =
                    synchronized(lock) {
                        accountReaders.getOrPut(endpoint.url) { accounts(endpoint.url) }
                    }
                return client.accounts(addresses)
            }
        }

    /** Asks the endpoint [network] is set to now which network it serves. */
    suspend fun check(network: Network): RpcCheck {
        val endpoint = endpoint(network) ?: return RpcCheck.NotSet
        val served =
            try {
                served(endpoint)
            } catch (e: SolanaException) {
                return RpcCheck.Failed(endpoint.host, e.problem)
            }
        return if (accepts(endpoint, served)) {
            RpcCheck.Serves(endpoint.host, localValidator = served == null)
        } else {
            RpcCheck.OtherNetwork(endpoint.host, served)
        }
    }

    /**
     * Checks [url] for [network] and, only when it serves that network, makes it the owner's
     * setting. Returns what the check found; nothing is changed unless it is [RpcCheck.Serves].
     */
    suspend fun save(network: Network, url: String): RpcCheck {
        val text = url.trim()
        problemOf(text)?.let {
            return RpcCheck.Invalid(it)
        }
        val local = defaults.allowLocalValidator && network != Network.NETWORK_MAINNET
        val candidate = RpcEndpoint(network, text, RpcSource.Owner, local)
        val hash =
            try {
                // A fresh client, and nothing cached: it is not the setting until it is proven.
                reader(text).genesisHash()
            } catch (e: SolanaException) {
                return RpcCheck.Failed(candidate.host, e.problem)
            }
        val served = networkOf(hash)
        if (!accepts(candidate, served)) return RpcCheck.OtherNetwork(candidate.host, served)
        change(network) { it + (network to text) }
        return RpcCheck.Serves(candidate.host, localValidator = served == null)
    }

    /** Drops the owner's setting for [network], so this build's own applies again. */
    fun reset(network: Network) {
        if (network !in _settings.value) return
        change(network) { it - network }
    }

    /** Called with the network whose endpoint changed, after the change is in effect. */
    fun onChange(listener: (Network) -> Unit) {
        listeners += listener
    }

    /** Whether [text] could be an endpoint setting, or why not. Nothing is asked of it. */
    fun problemOf(text: String): RpcUrlProblem? {
        if (text.isBlank()) return RpcUrlProblem.Empty
        val uri =
            try {
                URI(text.trim())
            } catch (_: Exception) {
                return RpcUrlProblem.NotAUrl
            }
        if (uri.host.isNullOrEmpty()) return RpcUrlProblem.NotAUrl
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && !(scheme == "http" && defaults.allowCleartext)) {
            return RpcUrlProblem.NotHttps
        }
        if (uri.rawUserInfo != null) return RpcUrlProblem.Credentials
        return null
    }

    private fun change(network: Network, update: (Map<Network, String>) -> Map<Network, String>) {
        synchronized(lock) {
            val next = update(_settings.value)
            store.write(next)
            _settings.value = next
            generation++
            readers.clear()
            accountReaders.clear()
            genesis.clear()
        }
        listeners.forEach { it(network) }
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
}
