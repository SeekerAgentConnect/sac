package io.github.brrenat.seekervault.sync

import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.sync.storage.SyncStore
import io.github.brrenat.seekervault.update.v1.RemovalReason
import io.github.brrenat.seekervault.update.v1.SubscribeResponse
import io.github.brrenat.seekervault.update.v1.SyncResponse
import io.github.brrenat.seekervault.update.v1.knownRequest
import io.github.brrenat.seekervault.update.v1.syncRequest
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Transient access for one call. Its credential is never printable or persisted. */
class SyncConnection(val serverUrl: String, val credential: String) {
    override fun toString(): String = "SyncConnection(serverUrl=$serverUrl, credential=<redacted>)"
}

/** A locally authoritative request status that server cache updates may advance but never undo. */
data class LocalRequestState(val key: RequestKey, val state: RequestState)

/**
 * The narrow bridge to phone-owned state. It offers no wallet operation, preparation call, policy,
 * or way to create an answer. Synchronization can retry an already-recorded result and reconcile an
 * already-recorded Activity row, and nothing else.
 */
interface SynchronizationHost {
    suspend fun connectionIds(): Set<String>

    suspend fun access(connectionId: String): SyncConnection?

    suspend fun retryRecordedResults(connectionId: String)

    suspend fun nonterminalActivity(connectionId: String): List<LocalRequestState>

    suspend fun authoritativeRequests(connectionId: String): Map<String, ActionRequest>

    suspend fun applyCache(state: ConnectionSyncState)

    suspend fun recordFailure(connectionId: String, failure: CheckOutcome)

    suspend fun revoke(connectionId: String)
}

/**
 * The application-scoped convergence point shared by manual Refresh and the later foreground and
 * WorkManager owners. It needs no Activity or ViewModel. Calls for one connection coalesce, while
 * separate connections never wait on one another.
 */
class SynchronizationRepository(
    private val store: SyncStore,
    private val transport: UpdateTransport,
    private val host: SynchronizationHost,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class ActiveSync(
        val barrier: String,
        val epoch: Long,
        val job: Job?,
        val result: CompletableDeferred<SynchronizeOutcome>,
    )

    private class Coordinator {
        val control = Mutex()
        val apply = Mutex()
        var epoch = 0L
        var removed = false
        var active: ActiveSync? = null
        var buffering = false
        var streamGeneration = 0L
        val buffered = ArrayDeque<Pair<Long, SubscribeResponse>>()
        var overflowed = false
    }

    private val coordinators = ConcurrentHashMap<String, Coordinator>()
    private val loading = Mutex()
    private val _state = MutableStateFlow(SynchronizationState())
    val state: StateFlow<SynchronizationState> = _state.asStateFlow()

    /** A transport stream whose generation is the only one allowed to mutate this connection. */
    data class RegisteredStream(
        val generation: Long,
        val subscription: UpdateSubscription,
    )

    /** Restores disk state and immediately publishes it to Inbox and Activity without any UI. */
    suspend fun load() = loading.withLock {
        if (_state.value.loaded) return@withLock
        val active = host.connectionIds()
        val loaded =
            withContext(io) {
                store.connectionIds().filter { it !in active }.forEach(store::delete)
                store.list().filter { it.connectionId in active }.associateBy { it.connectionId }
            }
        for (id in active) {
            val coordinator = coordinators.getOrPut(id) { Coordinator() }
            coordinator.control.withLock { coordinator.removed = false }
        }
        _state.value = SynchronizationState(loaded = true, connections = loaded)
        for (connection in loaded.values) host.applyCache(connection)
    }

    /** Headless entry point used by a process started only for synchronization. */
    suspend fun synchronizeAll(): Map<String, SynchronizeOutcome> {
        if (!_state.value.loaded) load()
        return synchronizeConnections(host.connectionIds())
    }

    /**
     * Synchronizes only the requested active connections. Recovery workers use this to leave a
     * healthy foreground stream as the active path while still bounding independent sidecars.
     */
    suspend fun synchronizeConnections(
        connectionIds: Set<String>
    ): Map<String, SynchronizeOutcome> {
        if (!_state.value.loaded) load()
        val ids = connectionIds.intersect(host.connectionIds())
        val permits = Semaphore(MAX_HEADLESS_CONCURRENCY)
        return coroutineScope {
            ids.associateWith { id ->
                    async {
                        permits.withPermit {
                            val outcome =
                                withTimeoutOrNull(PER_CONNECTION_TIMEOUT_MILLIS) {
                                    synchronize(id)
                                }
                            if (outcome != null) {
                                outcome
                            } else {
                                host.recordFailure(id, CheckOutcome.Unreachable)
                                SynchronizeOutcome.Failed(CheckOutcome.Unreachable)
                            }
                        }
                    }
                }
                .mapValues { it.value.await() }
        }
    }

    /**
     * Obtains and atomically applies one complete frozen snapshot. Concurrent callers for the same
     * connection await the same run; a different connection has its own coordinator.
     */
    suspend fun synchronize(
        connectionId: String,
        subscriptionCursor: String = "",
    ): SynchronizeOutcome {
        if (!isConnectionId(connectionId)) return SynchronizeOutcome.Removed
        if (!_state.value.loaded) load()
        val coordinator = coordinators.getOrPut(connectionId) { Coordinator() }
        var leader = false
        val active =
            coordinator.control.withLock {
                if (coordinator.removed) return SynchronizeOutcome.Removed
                coordinator.active
                    ?: ActiveSync(
                            subscriptionCursor,
                            coordinator.epoch,
                            currentCoroutineContext()[Job],
                            CompletableDeferred(),
                        )
                        .also {
                            coordinator.active = it
                            coordinator.buffering = true
                            leader = true
                            publishRuntime(connectionId, syncing = true, failure = null)
                        }
            }
        if (!leader) {
            val outcome =
                try {
                    active.result.await()
                } catch (e: CancellationException) {
                    // The run this call joined was abandoned by its own caller — a Retry whose
                    // screen closed, a worker whose job ended. That cancels the shared result, and
                    // inheriting it would end a caller that is still perfectly alive. The
                    // foreground owner is one such caller, and nothing starts another one while the
                    // connection stays paired, so it would stop watching for good (SEE-152).
                    currentCoroutineContext().ensureActive()
                    coordinator.control.withLock {
                        if (coordinator.active === active) coordinator.active = null
                    }
                    return synchronize(connectionId, subscriptionCursor)
                }
            if (
                subscriptionCursor.isEmpty() ||
                    active.barrier == subscriptionCursor ||
                    outcome == SynchronizeOutcome.Removed
            ) {
                return outcome
            }
            // A snapshot without this stream's barrier cannot close its registration race. Start
            // one more run after the coalesced manual/worker call rather than pretending it did.
            coordinator.control.withLock {
                if (coordinator.active === active && active.result.isCompleted) {
                    coordinator.active = null
                }
            }
            return synchronize(connectionId, subscriptionCursor)
        }

        try {
            val outcome =
                try {
                    runConvergingSync(connectionId, active, coordinator)
                } catch (e: UpdateTransportException) {
                    when (e.kind) {
                        UpdateTransportException.Kind.Unauthenticated -> {
                            remove(connectionId)
                            host.revoke(connectionId)
                            SynchronizeOutcome.Removed
                        }
                        UpdateTransportException.Kind.SnapshotInvalid -> {
                            requireFullSync(connectionId, coordinator, active.epoch)
                            try {
                                runConvergingSync(
                                    connectionId,
                                    active,
                                    coordinator,
                                    barrier = "",
                                )
                            } catch (retry: UpdateTransportException) {
                                if (retry.kind == UpdateTransportException.Kind.Unauthenticated) {
                                    remove(connectionId)
                                    host.revoke(connectionId)
                                    SynchronizeOutcome.Removed
                                } else if (
                                    retry.kind == UpdateTransportException.Kind.UpgradeRequired
                                ) {
                                    upgradeRequired(
                                        connectionId,
                                        coordinator,
                                        active.epoch,
                                    )
                                } else {
                                    failed(
                                        connectionId,
                                        retry.toOutcome(),
                                        coordinator,
                                        active.epoch,
                                    )
                                }
                            } catch (retry: IOException) {
                                failed(
                                    connectionId,
                                    CheckOutcome.Failed,
                                    coordinator,
                                    active.epoch,
                                )
                            } catch (retry: SecurityException) {
                                failed(
                                    connectionId,
                                    CheckOutcome.Failed,
                                    coordinator,
                                    active.epoch,
                                )
                            } catch (retry: IllegalArgumentException) {
                                failed(
                                    connectionId,
                                    CheckOutcome.Failed,
                                    coordinator,
                                    active.epoch,
                                )
                            }
                        }
                        UpdateTransportException.Kind.UpgradeRequired ->
                            upgradeRequired(connectionId, coordinator, active.epoch)
                        else -> failed(connectionId, e.toOutcome(), coordinator, active.epoch)
                    }
                } catch (e: IOException) {
                    failed(connectionId, CheckOutcome.Failed, coordinator, active.epoch)
                } catch (e: SecurityException) {
                    failed(connectionId, CheckOutcome.Failed, coordinator, active.epoch)
                } catch (e: IllegalArgumentException) {
                    requireFullSync(connectionId, coordinator, active.epoch)
                    failed(connectionId, CheckOutcome.Failed, coordinator, active.epoch)
                }
            if (!active.result.isCompleted) active.result.complete(outcome)
            return outcome
        } catch (e: CancellationException) {
            if (!active.result.isCompleted) active.result.cancel(e)
            throw e
        } catch (e: Throwable) {
            if (!active.result.isCompleted) active.result.completeExceptionally(e)
            throw e
        } finally {
            withContext(NonCancellable) {
                coordinator.apply.withLock {
                    coordinator.control.withLock {
                        if (coordinator.active === active) {
                            coordinator.active = null
                            coordinator.buffering = false
                            coordinator.buffered.clear()
                            coordinator.overflowed = false
                        }
                        if (!coordinator.removed && coordinator.active == null) {
                            publishRuntime(connectionId, syncing = false)
                        }
                    }
                }
            }
        }
    }

    /**
     * A bounded stream buffer can overflow, or a buffered event can expose an ordering conflict,
     * while a snapshot is in flight. The first complete snapshot remains useful, but it cannot
     * claim convergence: immediately replace it with a full snapshot while the stream buffers
     * again. A second conflict is surfaced to the caller instead of looping without a bound.
     */
    private suspend fun runConvergingSync(
        connectionId: String,
        active: ActiveSync,
        coordinator: Coordinator,
        barrier: String = active.barrier,
    ): SynchronizeOutcome {
        val first = runSync(connectionId, active, coordinator, barrier)
        if (first !is SynchronizeOutcome.Updated || !first.state.fullSyncRequired) return first
        val recovered = runSync(connectionId, active, coordinator, barrier = "")
        if (recovered is SynchronizeOutcome.Updated && recovered.state.fullSyncRequired) {
            throw UpdateTransportException(
                UpdateTransportException.Kind.SnapshotInvalid,
                "stream changed beyond the bounded reconciliation buffer",
            )
        }
        return recovered
    }

    /** Allocates ownership for one future foreground stream. Older generations become inert. */
    suspend fun beginStream(connectionId: String): Long {
        val coordinator = coordinators.getOrPut(connectionId) { Coordinator() }
        return coordinator.control.withLock {
            if (coordinator.removed) return@withLock 0
            coordinator.streamGeneration++
            coordinator.buffered.clear()
            coordinator.overflowed = false
            coordinator.streamGeneration
        }
    }

    /**
     * Opens a stream without exposing a credential to the lifecycle owner. The persisted cursor and
     * sidecar instance are read at the last possible moment, and removal wins every race.
     */
    suspend fun openStream(connectionId: String): RegisteredStream? {
        if (!isConnectionId(connectionId)) return null
        if (!_state.value.loaded) load()
        val current = _state.value.connections[connectionId] ?: return null
        val endpoint =
            current.endpoint?.takeIf { current.availability == UpdateAvailability.Available }
                ?: return null
        val access = host.access(connectionId) ?: return null
        val safeEndpoint = validateEndpoint(access.serverUrl, endpoint) ?: return null
        val generation = beginStream(connectionId)
        if (generation == 0L) return null
        val cursor = current.cursor.takeUnless { current.fullSyncRequired }.orEmpty()
        val instance = current.serverInstanceId.takeIf { cursor.isNotEmpty() }.orEmpty()
        val subscription =
            try {
                transport.subscribe(
                    safeEndpoint,
                    access.credential,
                    connectionId,
                    cursor,
                    instance,
                )
            } catch (e: Throwable) {
                endStream(connectionId, generation)
                throw e
            }
        val coordinator = coordinators.getValue(connectionId)
        val stillCurrent =
            coordinator.control.withLock {
                !coordinator.removed && coordinator.streamGeneration == generation
            }
        if (!stillCurrent) {
            subscription.close()
            return null
        }
        return RegisteredStream(generation, subscription)
    }

    /** Makes every response from a closed or replaced stream inert. */
    suspend fun endStream(connectionId: String, generation: Long) {
        val coordinator = coordinators[connectionId] ?: return
        coordinator.control.withLock {
            if (generation == 0L || generation != coordinator.streamGeneration) return
            coordinator.streamGeneration++
            coordinator.buffered.clear()
            coordinator.overflowed = false
        }
    }

    /** The cursor already made durable, for an authenticated client heartbeat. */
    fun appliedCursor(connectionId: String): String =
        _state.value.connections[connectionId]?.cursor.orEmpty()

    /**
     * Applies one mutation and its cursor together. During Sync it is boundedly buffered, so a
     * post-snapshot event cannot be erased by snapshot absence. Unknown/conflicting order requests
     * another complete snapshot instead of guessing.
     */
    suspend fun applyEvent(
        connectionId: String,
        generation: Long,
        response: SubscribeResponse,
    ): EventApplyOutcome {
        val coordinator = coordinators.getOrPut(connectionId) { Coordinator() }
        coordinator.control.withLock {
            if (coordinator.removed) return EventApplyOutcome.Removed
            if (generation == 0L || generation != coordinator.streamGeneration) {
                return EventApplyOutcome.Ignored
            }
            if (coordinator.buffering) {
                if (coordinator.buffered.size == MAX_BUFFERED_EVENTS) {
                    coordinator.buffered.clear()
                    coordinator.overflowed = true
                } else if (!coordinator.overflowed) {
                    coordinator.buffered.addLast(generation to response)
                }
                return if (coordinator.overflowed) EventApplyOutcome.FullSyncRequired
                else EventApplyOutcome.Buffered
            }
        }
        val applied =
            coordinator.apply.withLock {
                applyOne(connectionId, generation, response, coordinator, persist = true).outcome
            }
        if (applied == EventApplyOutcome.Removed) {
            remove(connectionId)
            host.revoke(connectionId)
        }
        return applied
    }

    /** Cancels network work, removes durable cache, and makes every late response inert. */
    suspend fun remove(connectionId: String) = loading.withLock {
        val coordinator = coordinators.getOrPut(connectionId) { Coordinator() }
        val active =
            coordinator.control.withLock {
                coordinator.removed = true
                coordinator.epoch++
                coordinator.streamGeneration++
                coordinator.buffered.clear()
                coordinator.overflowed = false
                coordinator.buffering = false
                coordinator.active.also {
                    coordinator.active = null
                    it?.result?.complete(SynchronizeOutcome.Removed)
                }
            }
        if (active?.job !== currentCoroutineContext()[Job]) active?.job?.cancel()
        coordinator.apply.withLock {
            withContext(io) { store.delete(connectionId) }
            _state.update { it.copy(connections = it.connections - connectionId) }
        }
    }

    /** Authentication failure from either unary sync or its foreground stream has one path. */
    suspend fun revoke(connectionId: String) {
        remove(connectionId)
        host.revoke(connectionId)
    }

    private suspend fun runSync(
        connectionId: String,
        active: ActiveSync,
        coordinator: Coordinator,
        barrier: String,
    ): SynchronizeOutcome {
        var access = host.access(connectionId) ?: return SynchronizeOutcome.Removed
        host.retryRecordedResults(connectionId)
        access = host.access(connectionId) ?: return SynchronizeOutcome.Removed
        var previous = _state.value.connections[connectionId] ?: emptyState(connectionId)
        val endpoint =
            previous.endpoint
                ?.takeIf { previous.availability == UpdateAvailability.Available }
                ?.let { validateEndpoint(access.serverUrl, it) }
                ?: discover(connectionId, access, previous, coordinator, active.epoch)
                ?: return if (coordinator.removed) SynchronizeOutcome.Removed
                else legacy(connectionId)
        previous = _state.value.connections[connectionId] ?: previous

        val allKnown = host.nonterminalActivity(connectionId).distinctBy { it.key.requestId }
        val (known, nextKnownIndex) = knownPage(allKnown, previous.nextKnownIndex)
        val pages = mutableListOf<SyncResponse>()
        val tokens = mutableSetOf<String>()
        var pageToken = ""
        do {
            val request = syncRequest {
                this.connectionId = connectionId
                protocolVersion = PROTOCOL_VERSION
                pageSize = PAGE_SIZE
                this.pageToken = pageToken
                if (pageToken.isEmpty()) {
                    this.subscriptionCursor = barrier
                    knownNonterminal.addAll(
                        known.map { local ->
                            val cached = previous.requests[local.key.requestId]
                            knownRequest {
                                ref = requestRef {
                                    this.connectionId = local.key.connectionId
                                    requestId = local.key.requestId
                                }
                                revision = cached?.revision ?: 0
                                state = cached?.state ?: local.state
                            }
                        }
                    )
                }
            }
            val response = transport.sync(endpoint, access.credential, request)
            validatePage(connectionId, response, pages.firstOrNull())
            pages += response
            pageToken = response.nextPageToken
            if (pageToken.isNotEmpty() && !tokens.add(pageToken)) badResponse("repeated page token")
            if (pages.size > MAX_PAGES) badResponse("too many snapshot pages")
        } while (pageToken.isNotEmpty())

        val authoritative = host.authoritativeRequests(connectionId)
        var revokedByBuffer = false
        val applied =
            coordinator.apply.withLock {
                var merged = mergeSnapshot(previous, pages, authoritative, nextKnownIndex)
                coordinator.control.withLock {
                    if (
                        coordinator.removed ||
                            coordinator.epoch != active.epoch ||
                            coordinator.active !== active
                    ) {
                        return SynchronizeOutcome.Removed
                    }
                    run {
                        val copy = coordinator.buffered.toList()
                        coordinator.buffered.clear()
                        val overflowed = coordinator.overflowed
                        coordinator.overflowed = false
                        if (overflowed) {
                            merged =
                                merged.copy(
                                    cursor = "",
                                    serverInstanceId = "",
                                    fullSyncRequired = true,
                                )
                        } else {
                            for ((eventGeneration, event) in copy) {
                                val one =
                                    mergeEvent(
                                        merged,
                                        connectionId,
                                        eventGeneration,
                                        event,
                                        coordinator,
                                    )
                                if (one.revoked) {
                                    revokedByBuffer = true
                                    break
                                }
                                if (one.outcome == EventApplyOutcome.FullSyncRequired) {
                                    merged =
                                        merged.copy(
                                            cursor = "",
                                            serverInstanceId = "",
                                            fullSyncRequired = true,
                                        )
                                    break
                                }
                                merged = one.state ?: merged
                            }
                        }
                    }
                    // Closing the buffering window under the same control lock as the final drain
                    // makes a later event observe no buffering sync and wait for the apply lock. A
                    // conflicted first pass keeps buffering so the recovery snapshot is covered.
                    if (!revokedByBuffer && !merged.fullSyncRequired) {
                        coordinator.buffering = false
                    }
                }
                if (!revokedByBuffer) {
                    withContext(io) { store.put(merged.persistable()) }
                    publish(merged)
                    host.applyCache(merged)
                }
                merged
            }
        if (revokedByBuffer) {
            remove(connectionId)
            host.revoke(connectionId)
            return SynchronizeOutcome.Removed
        }
        return SynchronizeOutcome.Updated(applied)
    }

    private suspend fun discover(
        connectionId: String,
        access: SyncConnection,
        previous: ConnectionSyncState,
        coordinator: Coordinator,
        epoch: Long,
    ): UpdateEndpoint? {
        val discovered =
            try {
                transport.discover(access.serverUrl, access.credential, connectionId)
            } catch (e: UpdateTransportException) {
                when (e.kind) {
                    UpdateTransportException.Kind.Unauthenticated -> {
                        remove(connectionId)
                        host.revoke(connectionId)
                        return null
                    }
                    UpdateTransportException.Kind.UpgradeRequired -> {
                        if (
                            !persist(
                                previous.copy(
                                    availability = UpdateAvailability.UpgradeRequired,
                                    endpoint = null,
                                ),
                                coordinator,
                                epoch,
                            )
                        ) {
                            return null
                        }
                        return null
                    }
                    else -> throw e
                }
            }
        if (discovered == null) {
            if (
                !persist(
                    previous.copy(
                        availability = UpdateAvailability.NotConfigured,
                        endpoint = null,
                    ),
                    coordinator,
                    epoch,
                )
            ) {
                return null
            }
            return null
        }
        val safe = validateEndpoint(access.serverUrl, discovered)
        if (safe == null) {
            if (
                !persist(
                    previous.copy(
                        availability = UpdateAvailability.Incompatible,
                        endpoint = null,
                    ),
                    coordinator,
                    epoch,
                )
            ) {
                return null
            }
            return null
        }
        if (
            !persist(
                previous.copy(availability = UpdateAvailability.Available, endpoint = safe),
                coordinator,
                epoch,
            )
        ) {
            return null
        }
        return safe
    }

    private suspend fun legacy(connectionId: String): SynchronizeOutcome {
        val availability =
            _state.value.connections[connectionId]?.availability ?: UpdateAvailability.Unknown
        return SynchronizeOutcome.Legacy(availability)
    }

    private suspend fun upgradeRequired(
        connectionId: String,
        coordinator: Coordinator,
        epoch: Long,
    ): SynchronizeOutcome {
        val current = _state.value.connections[connectionId] ?: emptyState(connectionId)
        return if (
            persist(
                current.copy(
                    availability = UpdateAvailability.UpgradeRequired,
                    endpoint = null,
                    cursor = "",
                    serverInstanceId = "",
                    fullSyncRequired = true,
                ),
                coordinator,
                epoch,
            )
        ) {
            SynchronizeOutcome.Legacy(UpdateAvailability.UpgradeRequired)
        } else {
            SynchronizeOutcome.Removed
        }
    }

    private suspend fun failed(
        connectionId: String,
        outcome: CheckOutcome,
        coordinator: Coordinator,
        epoch: Long,
    ): SynchronizeOutcome {
        // A failed snapshot cannot account for stream events buffered behind it. Force the next
        // caller through a complete snapshot, even when the failure itself was only transient.
        requireFullSync(connectionId, coordinator, epoch)
        coordinator.apply.withLock {
            coordinator.control.withLock {
                if (coordinator.removed || coordinator.epoch != epoch) {
                    return SynchronizeOutcome.Removed
                }
                publishRuntime(connectionId, syncing = false, failure = outcome)
            }
        }
        host.recordFailure(connectionId, outcome)
        return SynchronizeOutcome.Failed(outcome)
    }

    private suspend fun persist(
        state: ConnectionSyncState,
        coordinator: Coordinator,
        epoch: Long,
    ): Boolean {
        coordinator.apply.withLock {
            coordinator.control.withLock {
                if (coordinator.removed || coordinator.epoch != epoch) return false
            }
            withContext(io) { store.put(state.persistable()) }
            publish(state)
        }
        return true
    }

    private fun mergeSnapshot(
        previous: ConnectionSyncState,
        pages: List<SyncResponse>,
        authoritative: Map<String, ActionRequest>,
        nextKnownIndex: Int,
    ): ConnectionSyncState {
        val entries = linkedMapOf<String, ServerRequest>()
        pages.forEach { page ->
            page.requestsList.forEach { item ->
                val request = item.request
                val requestId = request.ref.requestId
                val incoming =
                    ServerRequest(
                        RequestKey(previous.connectionId, requestId),
                        item.revision,
                        request = request,
                    )
                require(entries.put(requestId, incoming) == null) { "duplicate snapshot request" }
                previous.requests[requestId]?.let { old -> requireCanAdvance(old, incoming, false) }
                authoritative[requestId]?.let { local ->
                    requireSameIdentity(local, request)
                    require(!stateRegression(local.state, request.state)) {
                        "snapshot regressed locally authoritative state"
                    }
                }
            }
            page.removedList.forEach { item ->
                val requestId = item.ref.requestId
                val incoming =
                    ServerRequest(
                        RequestKey(previous.connectionId, requestId),
                        item.revision,
                        removed = item.reason,
                    )
                require(entries.put(requestId, incoming) == null) { "duplicate snapshot request" }
                previous.requests[requestId]?.let { old -> requireCanAdvance(old, incoming, false) }
            }
        }
        val last = pages.last()
        return previous.copy(
            serverInstanceId = last.serverInstanceId,
            cursor = last.snapshotCursor,
            lastSuccessfulSync = now(),
            nextKnownIndex = nextKnownIndex,
            requests = trimTombstones(entries),
            syncing = true,
            failure = null,
            fullSyncRequired = false,
        )
    }

    private data class AppliedEvent(
        val state: ConnectionSyncState?,
        val outcome: EventApplyOutcome,
        val revoked: Boolean = false,
    )

    private suspend fun applyOne(
        connectionId: String,
        generation: Long,
        response: SubscribeResponse,
        coordinator: Coordinator,
        persist: Boolean,
    ): AppliedEvent {
        val current = _state.value.connections[connectionId] ?: emptyState(connectionId)
        val merged = mergeEvent(current, connectionId, generation, response, coordinator)
        val next = merged.state
        if (next != null && persist) {
            withContext(io) { store.put(next.persistable()) }
            publish(next)
            host.applyCache(next)
        }
        return merged
    }

    private fun mergeEvent(
        current: ConnectionSyncState,
        connectionId: String,
        generation: Long,
        response: SubscribeResponse,
        coordinator: Coordinator,
    ): AppliedEvent {
        if (current.fullSyncRequired) return conflict(current)
        if (generation != coordinator.streamGeneration) {
            return AppliedEvent(null, EventApplyOutcome.Ignored)
        }
        if (
            response.connectionId != connectionId ||
                !opaque(response.serverInstanceId) ||
                !opaque(response.cursor) ||
                (current.serverInstanceId.isNotEmpty() &&
                    response.serverInstanceId != current.serverInstanceId)
        ) {
            return conflict(current)
        }
        val entries = current.requests.toMutableMap()
        val incoming =
            when (response.eventCase) {
                SubscribeResponse.EventCase.REQUEST_CHANGED -> {
                    val event = response.requestChanged
                    val request = event.request
                    if (!validRequest(connectionId, request) || event.revision <= 0) {
                        return conflict(current)
                    }
                    ServerRequest(
                        RequestKey(connectionId, request.ref.requestId),
                        event.revision,
                        request = request,
                    )
                }
                SubscribeResponse.EventCase.REQUEST_REMOVED -> {
                    val event = response.requestRemoved
                    if (
                        event.ref.connectionId != connectionId ||
                            !isConnectionId(event.ref.requestId) ||
                            event.revision <= 0 ||
                            event.reason == RemovalReason.REMOVAL_REASON_UNSPECIFIED ||
                            event.reason == RemovalReason.UNRECOGNIZED
                    ) {
                        return conflict(current)
                    }
                    ServerRequest(
                        RequestKey(connectionId, event.ref.requestId),
                        event.revision,
                        removed = event.reason,
                    )
                }
                SubscribeResponse.EventCase.REVOKED ->
                    return AppliedEvent(null, EventApplyOutcome.Removed, revoked = true)
                SubscribeResponse.EventCase.SYNC_REQUIRED,
                SubscribeResponse.EventCase.EVENT_NOT_SET -> return conflict(current)
                else -> return AppliedEvent(null, EventApplyOutcome.Ignored)
            }
        val old = entries[incoming.key.requestId]
        if (old != null) {
            if (incoming.revision < old.revision) {
                return AppliedEvent(current, EventApplyOutcome.Ignored)
            }
            if (incoming.revision == old.revision) {
                if (incoming != old) return conflict(current)
                return AppliedEvent(current, EventApplyOutcome.Ignored)
            }
            if (incoming.revision != old.revision + 1) return conflict(current)
            if (!canAdvance(old, incoming)) return conflict(current)
        }
        entries[incoming.key.requestId] = incoming
        return AppliedEvent(
            current.copy(
                serverInstanceId = response.serverInstanceId,
                cursor = response.cursor,
                requests = trimTombstones(entries),
                fullSyncRequired = false,
            ),
            EventApplyOutcome.Applied,
        )
    }

    private fun conflict(current: ConnectionSyncState): AppliedEvent =
        AppliedEvent(
            current.copy(cursor = "", serverInstanceId = "", fullSyncRequired = true),
            EventApplyOutcome.FullSyncRequired,
        )

    private suspend fun requireFullSync(
        connectionId: String,
        coordinator: Coordinator,
        epoch: Long,
    ) {
        val current = _state.value.connections[connectionId] ?: return
        val cleared = current.copy(cursor = "", serverInstanceId = "", fullSyncRequired = true)
        coordinator.apply.withLock {
            coordinator.control.withLock {
                if (coordinator.removed || coordinator.epoch != epoch) return
                coordinator.buffered.clear()
                coordinator.overflowed = false
            }
            try {
                withContext(io) { store.put(cleared.persistable()) }
            } catch (_: IOException) {
                // The preceding complete disk document remains usable after restart. In this
                // process the empty cursor still prevents later events from skipping the gap.
            } catch (_: SecurityException) {
                // Treat an unavailable app-private directory like any other interrupted write.
            }
            publish(cleared)
        }
    }

    private fun validatePage(
        connectionId: String,
        response: SyncResponse,
        first: SyncResponse?,
    ) {
        if (response.serializedSize > MAX_MESSAGE_BYTES) badResponse("oversized Sync response")
        if (
            response.connectionId != connectionId ||
                !opaque(response.serverInstanceId) ||
                !opaque(response.snapshotCursor) ||
                !opaque(response.nextPageToken, emptyAllowed = true)
        ) {
            badResponse("invalid Sync envelope")
        }
        if (
            first != null &&
                (response.serverInstanceId != first.serverInstanceId ||
                    response.snapshotCursor != first.snapshotCursor)
        ) {
            badResponse("snapshot changed between pages")
        }
        response.requestsList.forEach {
            if (!validRequest(connectionId, it.request) || it.revision <= 0) {
                badResponse("invalid synchronized request")
            }
        }
        response.removedList.forEach {
            if (
                it.ref.connectionId != connectionId ||
                    !isConnectionId(it.ref.requestId) ||
                    it.revision <= 0 ||
                    it.reason == RemovalReason.REMOVAL_REASON_UNSPECIFIED ||
                    it.reason == RemovalReason.UNRECOGNIZED
            ) {
                badResponse("invalid synchronized removal")
            }
        }
        response.confirmationDeferredList.forEach {
            if (it.connectionId != connectionId || !isConnectionId(it.requestId)) {
                badResponse("invalid deferred confirmation")
            }
        }
    }

    private fun knownPage(
        all: List<LocalRequestState>,
        savedIndex: Int,
    ): Pair<List<LocalRequestState>, Int> {
        if (all.isEmpty()) return emptyList<LocalRequestState>() to 0
        val start = savedIndex.mod(all.size)
        val rotated = all.drop(start) + all.take(start)
        val page = rotated.take(MAX_KNOWN)
        return page to ((start + page.size) % all.size)
    }

    private fun validateEndpoint(serverUrl: String, endpoint: UpdateEndpoint): UpdateEndpoint? {
        if (endpoint.protocolVersion != PROTOCOL_VERSION) return null
        val paired = runCatching { URI(serverUrl) }.getOrNull() ?: return null
        val update = runCatching { URI(endpoint.grpcUrl) }.getOrNull() ?: return null
        if (
            !update.isAbsolute ||
                update.userInfo != null ||
                update.query != null ||
                update.fragment != null ||
                update.rawPath !in setOf("", "/") ||
                update.host == null
        ) {
            return null
        }
        val pairedHost = paired.host ?: return null
        val loopback = isLoopback(pairedHost) && isLoopback(update.host)
        if (update.scheme == "http" && !loopback) return null
        if (update.scheme !in setOf("http", "https")) return null
        if (!loopback && !pairedHost.equals(update.host, ignoreCase = true)) return null
        return endpoint.copy(grpcUrl = endpoint.grpcUrl.removeSuffix("/"))
    }

    private fun publish(state: ConnectionSyncState) {
        _state.update { it.copy(connections = it.connections + (state.connectionId to state)) }
    }

    private fun publishRuntime(
        connectionId: String,
        syncing: Boolean,
        failure: CheckOutcome? = _state.value.connections[connectionId]?.failure,
    ) {
        val current = _state.value.connections[connectionId] ?: emptyState(connectionId)
        publish(current.copy(syncing = syncing, failure = failure))
    }

    private fun emptyState(connectionId: String) = ConnectionSyncState(connectionId)

    private fun ConnectionSyncState.persistable() = copy(syncing = false, failure = null)

    private fun trimTombstones(entries: Map<String, ServerRequest>): Map<String, ServerRequest> {
        val live = entries.values.filter { it.request != null }
        val removed =
            entries.values
                .filter { it.removed != null }
                .sortedByDescending { it.revision }
                .take(MAX_TOMBSTONES)
        return (live + removed).associateBy { it.key.requestId }
    }

    private fun requireCanAdvance(
        old: ServerRequest,
        incoming: ServerRequest,
        contiguous: Boolean,
    ) {
        require(incoming.revision >= old.revision) { "snapshot revision regressed" }
        if (incoming.revision == old.revision) {
            require(incoming == old) { "one revision has conflicting content" }
            return
        }
        if (contiguous) require(incoming.revision == old.revision + 1) { "revision gap" }
        require(canAdvance(old, incoming)) { "request state regressed" }
    }

    private fun canAdvance(old: ServerRequest, incoming: ServerRequest): Boolean {
        if (old.removed != null) return incoming.removed != null
        if (incoming.removed != null) return true
        val before = checkNotNull(old.request)
        val after = checkNotNull(incoming.request)
        return sameIdentity(before, after) && !stateRegression(before.state, after.state)
    }

    private fun requireSameIdentity(before: ActionRequest, after: ActionRequest) {
        require(sameIdentity(before, after)) { "request identity changed" }
    }

    private fun sameIdentity(before: ActionRequest, after: ActionRequest): Boolean =
        before.ref == after.ref &&
            before.action == after.action &&
            before.agentNote == after.agentNote &&
            before.createdAt == after.createdAt &&
            before.expiresAt == after.expiresAt

    private fun stateRegression(before: RequestState, after: RequestState): Boolean {
        if (before == after) return false
        if (before in TERMINAL) return true
        return stateRank(after) < stateRank(before)
    }

    private fun stateRank(state: RequestState): Int =
        when (state) {
            RequestState.REQUEST_STATE_UNSPECIFIED -> -1
            RequestState.REQUEST_STATE_PENDING -> 0
            RequestState.REQUEST_STATE_PROCESSING -> 1
            RequestState.REQUEST_STATE_SUBMITTED,
            RequestState.REQUEST_STATE_UNKNOWN -> 2
            else -> 3
        }

    private fun validRequest(connectionId: String, request: ActionRequest): Boolean =
        request.hasRef() &&
            request.ref.connectionId == connectionId &&
            isConnectionId(request.ref.requestId) &&
            request.hasAction() &&
            request.state != RequestState.REQUEST_STATE_UNSPECIFIED &&
            request.state != RequestState.UNRECOGNIZED

    private fun opaque(value: String, emptyAllowed: Boolean = false): Boolean =
        (emptyAllowed || value.isNotEmpty()) && value.toByteArray(Charsets.UTF_8).size <= 256

    private fun isLoopback(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
        return normalized == "localhost" || normalized == "::1" || normalized.startsWith("127.")
    }

    private fun badResponse(message: String): Nothing =
        throw UpdateTransportException(UpdateTransportException.Kind.BadResponse, message)

    private fun UpdateTransportException.toOutcome(): CheckOutcome =
        when (kind) {
            UpdateTransportException.Kind.Unreachable -> CheckOutcome.Unreachable
            UpdateTransportException.Kind.CertificateRejected -> CheckOutcome.CertificateRejected
            UpdateTransportException.Kind.CleartextBlocked -> CheckOutcome.CleartextBlocked
            else -> CheckOutcome.Failed
        }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val PAGE_SIZE = 100
        // A configured sidecar can hold 10,000 pending requests and Sync can add the 100 named
        // nonterminal Activity records. Message-size pagination may put only one item on a page.
        const val MAX_PAGES = 10_100
        const val MAX_KNOWN = 100
        const val MAX_MESSAGE_BYTES = 65_536
        const val MAX_BUFFERED_EVENTS = 512
        const val MAX_TOMBSTONES = 512
        const val MAX_HEADLESS_CONCURRENCY = 4
        // A large valid queue can require hundreds of size-trimmed pages. Keep the whole run
        // bounded while allowing active pagination substantially longer than one RPC deadline.
        const val PER_CONNECTION_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        val TERMINAL =
            setOf(
                RequestState.REQUEST_STATE_CONFIRMED,
                RequestState.REQUEST_STATE_COMPLETED,
                RequestState.REQUEST_STATE_REJECTED,
                RequestState.REQUEST_STATE_CANCELLED,
                RequestState.REQUEST_STATE_EXPIRED,
                RequestState.REQUEST_STATE_FAILED,
            )
    }
}
