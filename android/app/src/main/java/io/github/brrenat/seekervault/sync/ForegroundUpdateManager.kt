package io.github.brrenat.seekervault.sync

import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.update.v1.ResumeDisposition
import io.github.brrenat.seekervault.update.v1.SubscribeResponse
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Live transport state. It is separate from the last successful durable synchronization. */
sealed interface ForegroundConnectionState {
    data object Background : ForegroundConnectionState

    data object Connecting : ForegroundConnectionState

    data object Live : ForegroundConnectionState

    data class Reconnecting(val attempt: Int) : ForegroundConnectionState

    data class Unreachable(val failure: CheckOutcome) : ForegroundConnectionState

    data object Revoked : ForegroundConnectionState

    data class Unsupported(val availability: UpdateAvailability) : ForegroundConnectionState
}

data class ForegroundUpdatesState(
    val foreground: Boolean = false,
    val connections: Map<String, ForegroundConnectionState> = emptyMap(),
)

/**
 * Process-wide owner of foreground subscriptions. Activities only announce visibility; navigation
 * and rotation cannot create another stream. Every mutation still goes through
 * [SynchronizationRepository], the same convergence point used by manual refresh.
 */
class ForegroundUpdateManager(
    private val connections: StateFlow<List<Connection>>,
    private val synchronization: SynchronizationRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { millis ->
        (millis * Random.nextDouble(MIN_JITTER, MAX_JITTER)).toLong()
    },
    ownerScope: CoroutineScope? = null,
) {
    private val scope = ownerScope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(ForegroundUpdatesState())
    val state: StateFlow<ForegroundUpdatesState> = _state.asStateFlow()

    private val lifecycleLock = Any()
    private var session = 0L
    private var foregroundJob: Job? = null
    private var closingJob: Job? = null

    /** Idempotently starts one application-scoped session. */
    fun onForeground() {
        synchronized(lifecycleLock) {
            if (foregroundJob?.isActive == true) return
            val currentSession = ++session
            _state.update { previous ->
                previous.copy(
                    foreground = true,
                    connections =
                        connections.value.associate { connection ->
                            connection.id to
                                if (connection.usable) ForegroundConnectionState.Connecting
                                else ForegroundConnectionState.Revoked
                        },
                )
            }
            val predecessor = closingJob
            foregroundJob = scope.launch {
                predecessor?.join()
                synchronized(lifecycleLock) {
                    if (closingJob === predecessor) closingJob = null
                }
                if (isCurrent(currentSession)) ownConnections(currentSession)
            }
        }
    }

    /** Stops every RPC without calling a stream failure an outage. */
    fun onBackground() {
        synchronized(lifecycleLock) {
            if (foregroundJob == null && !_state.value.foreground) return
            ++session
            foregroundJob?.cancel()
            closingJob = foregroundJob
            foregroundJob = null
            _state.value =
                ForegroundUpdatesState(
                    foreground = false,
                    connections =
                        connections.value.associate { connection ->
                            connection.id to
                                if (connection.usable) ForegroundConnectionState.Background
                                else ForegroundConnectionState.Revoked
                        },
                )
        }
    }

    private suspend fun ownConnections(currentSession: Long): Unit = coroutineScope {
        val jobs = mutableMapOf<String, Job>()
        try {
            connections.collect { current ->
                val byId = current.associateBy(Connection::id)
                val wanted =
                    current.filter(Connection::usable).mapTo(mutableSetOf(), Connection::id)
                val removed = jobs.keys - wanted
                removed.forEach { id -> jobs.remove(id)?.cancelAndJoin() }
                if (!isCurrent(currentSession)) return@collect
                _state.update { previous ->
                    previous.copy(
                        connections =
                            byId.mapValues { (_, connection) ->
                                when {
                                    !connection.usable -> ForegroundConnectionState.Revoked
                                    jobs[connection.id]?.isActive == true ->
                                        previous.connections[connection.id]
                                            ?: ForegroundConnectionState.Connecting
                                    else -> ForegroundConnectionState.Connecting
                                }
                            }
                    )
                }
                current.filter(Connection::usable).forEach { connection ->
                    if (jobs[connection.id]?.isActive != true) {
                        jobs[connection.id] = launch {
                            maintainConnection(connection.id, currentSession)
                        }
                    }
                }
            }
        } finally {
            jobs.values.forEach(Job::cancel)
        }
    }

    private suspend fun maintainConnection(connectionId: String, currentSession: Long) {
        var attempt = 0
        while (
            isCurrent(currentSession) &&
                connections.value.any { it.id == connectionId && it.usable }
        ) {
            setStatus(
                connectionId,
                if (attempt == 0) ForegroundConnectionState.Connecting
                else ForegroundConnectionState.Reconnecting(attempt),
                currentSession,
            )
            val outcome =
                try {
                    synchronization.synchronize(connectionId)
                } catch (e: Throwable) {
                    rethrowOwnCancellation(e)
                    SynchronizeOutcome.Failed(failureOf(e))
                }
            when (outcome) {
                is SynchronizeOutcome.Legacy -> {
                    setStatus(
                        connectionId,
                        ForegroundConnectionState.Unsupported(outcome.availability),
                        currentSession,
                    )
                    return
                }
                is SynchronizeOutcome.Failed -> {
                    setStatus(
                        connectionId,
                        ForegroundConnectionState.Unreachable(outcome.failure),
                        currentSession,
                    )
                    attempt++
                    sleep(backoff(attempt))
                    continue
                }
                SynchronizeOutcome.Removed -> {
                    setStatus(connectionId, ForegroundConnectionState.Revoked, currentSession)
                    return
                }
                is SynchronizeOutcome.Updated -> Unit
            }

            var openingFailure: Throwable? = null
            val registered =
                try {
                    synchronization.openStream(connectionId)
                } catch (e: Throwable) {
                    rethrowOwnCancellation(e)
                    if (stopOnActionable(connectionId, e, currentSession)) return
                    openingFailure = e
                    null
                }
            if (registered == null) {
                if (connections.value.none { it.id == connectionId && it.usable }) return
                setStatus(
                    connectionId,
                    ForegroundConnectionState.Unreachable(
                        openingFailure?.let(::failureOf) ?: CheckOutcome.Unreachable
                    ),
                    currentSession,
                )
                attempt++
                sleep(backoff(attempt))
                continue
            }

            var retry = false
            try {
                runStream(connectionId, registered, currentSession)
                attempt = 0
            } catch (e: StreamRevokedException) {
                setStatus(connectionId, ForegroundConnectionState.Revoked, currentSession)
                return
            } catch (e: StreamIncompatibleException) {
                setStatus(
                    connectionId,
                    ForegroundConnectionState.Unsupported(UpdateAvailability.Incompatible),
                    currentSession,
                )
                return
            } catch (e: Throwable) {
                rethrowOwnCancellation(e)
                if (stopOnActionable(connectionId, e, currentSession)) return
                setStatus(
                    connectionId,
                    ForegroundConnectionState.Unreachable(failureOf(e)),
                    currentSession,
                )
                attempt++
                retry = true
            } finally {
                withContext(NonCancellable) {
                    registered.subscription.close()
                    synchronization.endStream(connectionId, registered.generation)
                }
            }
            if (retry) sleep(backoff(attempt))
        }
    }

    private suspend fun runStream(
        connectionId: String,
        registered: SynchronizationRepository.RegisteredStream,
        currentSession: Long,
    ): Unit = coroutineScope {
        val subscription = registered.subscription
        // A bounded wait, not a cancellation: a sidecar that accepts the stream and then says
        // nothing is an ordinary outage — the shape a connection coming back through a router that
        // has forgotten it takes — and the owner has to come back from it like any other (SEE-152).
        val first =
            withTimeoutOrNull(HANDSHAKE_TIMEOUT_MILLIS) {
                val received = subscription.responses.receiveCatching()
                if (received.isClosed) {
                    throw received.exceptionOrNull()
                        ?: IOException("update stream ended before ready")
                }
                received.getOrThrow()
            } ?: throw IOException("the update stream never became ready")
        validateEnvelope(connectionId, first)
        require(first.eventCase == SubscribeResponse.EventCase.READY) {
            "the first update response was not ready"
        }
        val ready = first.ready
        if (
            ready.protocolVersion != PROTOCOL_VERSION ||
                ready.heartbeatIntervalSeconds !in MIN_HEARTBEAT_SECONDS..MAX_HEARTBEAT_SECONDS ||
                ready.maxMessageBytes != MAX_MESSAGE_BYTES ||
                ready.maxPageSize !in 1..MAX_PAGE_SIZE
        ) {
            throw StreamIncompatibleException()
        }

        val signals = Channel<StreamSignal>(Channel.UNLIMITED)
        val missedHeartbeats = AtomicInteger(0)
        val reader = launch {
            while (isActive) {
                val received = subscription.responses.receiveCatching()
                if (received.isClosed) {
                    signals.send(StreamSignal.Closed(received.exceptionOrNull()))
                    return@launch
                }
                missedHeartbeats.set(0)
                signals.send(StreamSignal.Response(received.getOrThrow()))
            }
        }
        var replaying = ready.resume == ResumeDisposition.RESUME_DISPOSITION_REPLAYING
        var reconciliation: Job? = null

        fun reconcile(barrier: String) {
            if (reconciliation?.isActive == true) return
            setStatus(
                connectionId,
                ForegroundConnectionState.Reconnecting(0),
                currentSession,
            )
            reconciliation =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    signals.send(
                        StreamSignal.Reconciled(synchronization.synchronize(connectionId, barrier))
                    )
                }
        }

        when (ready.resume) {
            ResumeDisposition.RESUME_DISPOSITION_REPLAYING -> Unit
            ResumeDisposition.RESUME_DISPOSITION_FULL_SYNC_REQUIRED -> reconcile(first.cursor)
            else -> throw IOException("the sidecar sent no resume disposition")
        }

        val intervalMillis = ready.heartbeatIntervalSeconds * 1_000L
        val heartbeatTimer = launch {
            var sequence = 0L
            while (isActive) {
                delay(intervalMillis)
                val missed = missedHeartbeats.incrementAndGet()
                sequence++
                try {
                    subscription.heartbeat(
                        sequence,
                        synchronization.appliedCursor(connectionId),
                        now(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    signals.send(StreamSignal.Closed(e))
                    return@launch
                }
                if (missed >= MISSED_HEARTBEAT_LIMIT) {
                    signals.send(StreamSignal.LivenessExpired)
                    return@launch
                }
            }
        }
        try {
            while (isCurrent(currentSession)) {
                when (val signal = signals.receive()) {
                    StreamSignal.LivenessExpired ->
                        throw IOException("update stream missed its liveness deadline")
                    is StreamSignal.Closed ->
                        throw signal.cause ?: IOException("update stream ended")
                    is StreamSignal.Reconciled ->
                        when (signal.outcome) {
                            is SynchronizeOutcome.Updated -> {
                                reconciliation = null
                                if (!replaying) {
                                    setStatus(
                                        connectionId,
                                        ForegroundConnectionState.Live,
                                        currentSession,
                                    )
                                }
                            }
                            is SynchronizeOutcome.Legacy ->
                                throw IOException("update snapshot became unavailable")
                            is SynchronizeOutcome.Failed ->
                                throw IOException("update snapshot failed")
                            SynchronizeOutcome.Removed -> throw StreamRevokedException()
                        }
                    is StreamSignal.Response -> {
                        val response = signal.response
                        validateEnvelope(connectionId, response)
                        when (response.eventCase) {
                            SubscribeResponse.EventCase.REQUEST_CHANGED,
                            SubscribeResponse.EventCase.REQUEST_REMOVED,
                            SubscribeResponse.EventCase.REVOKED ->
                                when (
                                    synchronization.applyEvent(
                                        connectionId,
                                        registered.generation,
                                        response,
                                    )
                                ) {
                                    EventApplyOutcome.FullSyncRequired -> reconcile(response.cursor)
                                    EventApplyOutcome.Removed -> throw StreamRevokedException()
                                    else -> Unit
                                }
                            SubscribeResponse.EventCase.SYNC_REQUIRED -> reconcile(response.cursor)
                            SubscribeResponse.EventCase.REPLAY_COMPLETE -> {
                                require(
                                    replaying &&
                                        response.replayComplete.throughCursor == response.cursor
                                ) {
                                    "invalid replay completion"
                                }
                                replaying = false
                                if (reconciliation?.isActive != true) {
                                    setStatus(
                                        connectionId,
                                        ForegroundConnectionState.Live,
                                        currentSession,
                                    )
                                }
                            }
                            SubscribeResponse.EventCase.HEARTBEAT -> Unit
                            SubscribeResponse.EventCase.READY,
                            SubscribeResponse.EventCase.EVENT_NOT_SET ->
                                throw IOException("unexpected update response")
                        }
                    }
                }
            }
        } finally {
            heartbeatTimer.cancel()
            reader.cancel()
            reconciliation?.cancel()
            signals.close()
        }
    }

    private fun validateEnvelope(connectionId: String, response: SubscribeResponse) {
        require(response.serializedSize <= MAX_MESSAGE_BYTES) { "update response was too large" }
        require(response.connectionId == connectionId) { "update response used another connection" }
        require(
            response.serverInstanceId.isNotEmpty() && response.serverInstanceId.utf8Size() <= 256
        ) {
            "invalid update server instance"
        }
        require(response.cursor.isEmpty() || response.cursor.utf8Size() <= 256) {
            "invalid update cursor"
        }
    }

    private fun setStatus(
        connectionId: String,
        status: ForegroundConnectionState,
        currentSession: Long,
    ) {
        if (!isCurrent(currentSession)) return
        _state.update { current ->
            if (!current.foreground) current
            else current.copy(connections = current.connections + (connectionId to status))
        }
    }

    private fun isCurrent(expected: Long): Boolean =
        synchronized(lifecycleLock) {
            session == expected && _state.value.foreground
        }

    private fun backoff(attempt: Int): Long {
        val exponent = min(attempt - 1, MAX_BACKOFF_EXPONENT)
        val base = min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS * (1L shl exponent))
        return jitter(base).coerceIn(0L, MAX_BACKOFF_MILLIS)
    }

    /**
     * Rethrows only this owner's own cancellation.
     *
     * Everything else arriving as a [CancellationException] came from inside — a bounded wait
     * expiring, or a coalesced synchronization whose own caller went away and cancelled the result
     * this owner was waiting on. Letting one of those out ends the owner, and [ownConnections]
     * starts owners only when the connection *list* changes, so nothing would ever start another
     * one: the row keeps rendering whatever was published last and only a new pairing recovers it
     * (SEE-152).
     */
    private suspend fun rethrowOwnCancellation(error: Throwable) {
        if (error is CancellationException) currentCoroutineContext().ensureActive()
    }

    /**
     * Publishes the answer a failure calls for when it is one the owner cannot retry its way out
     * of, and says whether it did. Everything else is an outage and belongs in the retry loop.
     */
    private suspend fun stopOnActionable(
        connectionId: String,
        error: Throwable,
        currentSession: Long,
    ): Boolean =
        when (actionableKind(error)) {
            UpdateTransportException.Kind.Unauthenticated -> {
                synchronization.revoke(connectionId)
                setStatus(connectionId, ForegroundConnectionState.Revoked, currentSession)
                true
            }
            UpdateTransportException.Kind.UpgradeRequired -> {
                setStatus(
                    connectionId,
                    ForegroundConnectionState.Unsupported(UpdateAvailability.UpgradeRequired),
                    currentSession,
                )
                true
            }
            else -> false
        }

    /**
     * The one kind a failure has to be acted on rather than retried, or null when it is an outage.
     *
     * The transport's own classification wins, because a status code alone cannot say what
     * `FAILED_PRECONDITION` meant: the sidecar answers it both for a protocol it will not speak and
     * for a snapshot or cursor this phone has to replace, and reading the second as the first
     * retired a healthy connection for the rest of the session (SEE-152). A bare code reaches here
     * only from a throwable the transport never classified, where `UNIMPLEMENTED` is unambiguous.
     */
    private fun actionableKind(error: Throwable): UpdateTransportException.Kind? =
        (error as? UpdateTransportException)?.kind
            ?: when (connectCode(error)) {
                Code.UNAUTHENTICATED -> UpdateTransportException.Kind.Unauthenticated
                Code.UNIMPLEMENTED -> UpdateTransportException.Kind.UpgradeRequired
                else -> null
            }

    private fun failureOf(error: Throwable): CheckOutcome =
        when ((error as? UpdateTransportException)?.kind) {
            UpdateTransportException.Kind.CertificateRejected -> CheckOutcome.CertificateRejected
            UpdateTransportException.Kind.CleartextBlocked -> CheckOutcome.CleartextBlocked
            UpdateTransportException.Kind.Unreachable -> CheckOutcome.Unreachable
            else ->
                when (connectCode(error)) {
                    Code.UNAVAILABLE -> CheckOutcome.Unreachable
                    else -> CheckOutcome.Failed
                }
        }

    private fun connectCode(error: Throwable): Code? {
        val pending = ArrayDeque(listOf(error))
        val seen = mutableSetOf<Throwable>()
        while (pending.isNotEmpty() && seen.size < 32) {
            val next = pending.removeFirst()
            if (!seen.add(next)) continue
            (next as? ConnectException)?.let {
                return it.code
            }
            next.cause?.let(pending::addLast)
            next.suppressed.forEach(pending::addLast)
        }
        return null
    }

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size

    private sealed interface StreamSignal {
        data object LivenessExpired : StreamSignal

        data class Response(val response: SubscribeResponse) : StreamSignal

        data class Reconciled(val outcome: SynchronizeOutcome) : StreamSignal

        data class Closed(val cause: Throwable?) : StreamSignal
    }

    private class StreamRevokedException : IOException()

    private class StreamIncompatibleException : IOException()

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_MESSAGE_BYTES = 65_536
        const val MAX_PAGE_SIZE = 100
        const val MIN_HEARTBEAT_SECONDS = 15
        const val MAX_HEARTBEAT_SECONDS = 60
        const val MISSED_HEARTBEAT_LIMIT = 3
        const val HANDSHAKE_TIMEOUT_MILLIS = 30_000L
        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val MAX_BACKOFF_EXPONENT = 5
        const val MIN_JITTER = 0.75
        const val MAX_JITTER = 1.25
    }
}
