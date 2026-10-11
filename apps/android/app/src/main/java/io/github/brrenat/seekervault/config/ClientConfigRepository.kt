package io.github.brrenat.seekervault.config

import io.github.brrenat.seekervault.config.storage.ClientConfigStore
import io.github.brrenat.seekervault.gateway.v1.ClientConfig as WireClientConfig
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why there is no configuration a dependent action may use. */
sealed interface ConfigProblem {
    /** This build names no gateway, so there is nowhere to ask. */
    data object NoGateway : ConfigProblem

    /** The gateway could not be asked, or did not answer. Never read as "no fee". */
    data class Unreachable(val detail: String? = null) : ConfigProblem

    /** The gateway answered something this phone refuses whole ([clientConfigFrom]). */
    data class Invalid(val problem: ConfigInvalid) : ConfigProblem
}

/** What the phone holds now, and how the last attempt to refresh it went. */
data class ClientConfigState(
    /** The last configuration validated from the gateway, cached across restarts. */
    val config: ClientConfig? = null,
    /** When [config] was fetched. */
    val fetchedAt: Instant? = null,
    /** Why the last refresh failed; null after one that succeeded. */
    val problem: ConfigProblem? = null,
)

/** Whether something that depends on the configuration may go ahead, and with which. */
sealed interface ConfigAvailability {
    /** [config] is fresh from the gateway, or a cached one still inside [MAX_CONFIG_AGE]. */
    data class Usable(val config: ClientConfig, val cached: Boolean) : ConfigAvailability

    /** Nothing usable: the action stops and says why. */
    data class Unavailable(val problem: ConfigProblem) : ConfigAvailability
}

/**
 * What an operation needs from the client configuration: a refreshed answer before it is prepared,
 * and the held one when an approval re-reads the terms it was prepared under (SEE-185).
 */
interface OperationConfig {
    val state: StateFlow<ClientConfigState>

    suspend fun forOperation(): ConfigAvailability

    fun held(): ConfigAvailability
}

/**
 * The trusted gateway's client configuration on this phone (SEE-185, docs/wiki/client-config.md).
 *
 * ## The policy
 *
 * - **Where from.** Only [gatewayUrl], the one gateway this build names. Nothing else can supply or
 *   redirect it.
 * - **When.** Read from the cache when the process starts; refreshed at first use, whenever the app
 *   comes back to the foreground or a feed stream (re)connects, and before every new operation is
 *   prepared ([forOperation]).
 * - **The cache.** The last validated answer is kept on disk with the time it was fetched. A new
 *   operation may be prepared with it, when the gateway cannot be reached, for [MAX_CONFIG_AGE]
 *   after that time and no longer. Reads about what was already sent — confirmations, positions —
 *   keep using the cached endpoints whatever their age: they only read, and every endpoint is still
 *   proven by its genesis hash before anything read through it counts.
 * - **Failure.** No usable configuration is [ConfigAvailability.Unavailable] with its reason, and
 *   the dependent action says so. It is never a zero fee and never a guessed network or endpoint.
 * - **Updates.** A validated answer that differs from the one held replaces it at once, without a
 *   restart, and [onChange] listeners hear about it (the RPC resolver drops every cached client and
 *   genesis proof). A preparation made under other fee terms is not silently re-used: the operation
 *   binds the terms it was prepared with and is prepared again when they move.
 */
class ClientConfigRepository(
    private val gatewayUrl: String,
    private val store: ClientConfigStore,
    private val fetch: suspend (gatewayUrl: String) -> WireClientConfig,
    private val allowCleartext: Boolean,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : OperationConfig {
    private val refreshing = Mutex()
    private val listeners = CopyOnWriteArrayList<(ClientConfig?, ClientConfig) -> Unit>()

    private val _state =
        MutableStateFlow(
            store.read()?.let { ClientConfigState(config = it.config, fetchedAt = it.fetchedAt) }
                ?: ClientConfigState(
                    problem = if (gatewayUrl.isEmpty()) ConfigProblem.NoGateway else null
                )
        )

    override val state: StateFlow<ClientConfigState> = _state.asStateFlow()

    /** The configuration held now, of any age, for reads about what was already sent. */
    val current: ClientConfig?
        get() = _state.value.config

    /** Called with the previous and the new configuration, after the new one is in effect. */
    fun onChange(listener: (previous: ClientConfig?, current: ClientConfig) -> Unit) {
        listeners += listener
    }

    /**
     * Asks the gateway now. Concurrent callers share one request; the answer is the state after it.
     */
    suspend fun refresh(): ClientConfigState {
        if (gatewayUrl.isEmpty()) {
            _state.update { it.copy(problem = ConfigProblem.NoGateway) }
            return _state.value
        }
        val started = now()
        refreshing.withLock {
            // Somebody else's refresh finished while this one waited: theirs is the answer.
            val held = _state.value
            if (
                held.fetchedAt != null && !held.fetchedAt.isBefore(started) && held.problem == null
            ) {
                return held
            }
            val answer =
                try {
                    fetch(gatewayUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(problem = ConfigProblem.Unreachable(e.message)) }
                    return _state.value
                }
            when (val read = clientConfigFrom(answer, allowCleartext)) {
                is ConfigRead.Invalid ->
                    _state.update { it.copy(problem = ConfigProblem.Invalid(read.problem)) }
                is ConfigRead.Valid -> {
                    val fetchedAt = now()
                    val previous = _state.value.config
                    try {
                        withContext(io) {
                            store.write(ClientConfigStore.Cached(read.config, fetchedAt))
                        }
                    } catch (_: IOException) {
                        // Not cached across a restart this time; still the answer for this process.
                    }
                    _state.value = ClientConfigState(config = read.config, fetchedAt = fetchedAt)
                    if (previous != read.config) listeners.forEach { it(previous, read.config) }
                }
            }
        }
        return _state.value
    }

    /**
     * The configuration a new operation may be prepared with: refreshed now, or — when the gateway
     * cannot answer — the cached one while it is inside [MAX_CONFIG_AGE].
     */
    override suspend fun forOperation(): ConfigAvailability {
        val after = refresh()
        val config = after.config
        val fetchedAt = after.fetchedAt
        if (after.problem == null && config != null) {
            return ConfigAvailability.Usable(config, cached = false)
        }
        if (config != null && fetchedAt != null && fresh(fetchedAt)) {
            return ConfigAvailability.Usable(config, cached = true)
        }
        return ConfigAvailability.Unavailable(after.problem ?: ConfigProblem.Unreachable())
    }

    /**
     * What a check made without asking the gateway may rely on: the held configuration while it is
     * inside [MAX_CONFIG_AGE]. Used where an approval re-reads the terms it was prepared under.
     */
    override fun held(): ConfigAvailability {
        val held = _state.value
        val config = held.config
        val fetchedAt = held.fetchedAt
        return if (config != null && fetchedAt != null && fresh(fetchedAt)) {
            ConfigAvailability.Usable(config, cached = true)
        } else {
            ConfigAvailability.Unavailable(held.problem ?: ConfigProblem.Unreachable())
        }
    }

    private fun fresh(fetchedAt: Instant): Boolean =
        !now().isAfter(fetchedAt.plus(MAX_CONFIG_AGE)) && !fetchedAt.isAfter(now().plus(SKEW))
}

/**
 * How long a cached configuration may stand in for the gateway when a new operation is prepared and
 * the gateway cannot be reached. Long enough to ride out an outage; short enough that a fee or
 * endpoint the operator changed is not served from a phone's memory for days.
 */
val MAX_CONFIG_AGE: Duration = Duration.ofHours(24)

/** A fetch time this far in the future is a clock that moved, not a fresh cache. */
private val SKEW: Duration = Duration.ofMinutes(5)
