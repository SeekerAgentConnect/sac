package io.github.brrenat.seekervault.feeds

import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.toOutcome
import io.github.brrenat.seekervault.feeds.storage.FeedCursorStore
import io.github.brrenat.seekervault.gateway.v1.FeedEvent
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.channelFor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The live half of a feed, for as long as the app is being looked at (SEE-91).
 *
 * One listener per gateway, opened when the app comes to the foreground and closed when it leaves.
 * A shared feed has nothing to deliver to a phone nobody is holding: there is no notification to
 * raise from a proposal (the owner decides on one when they look), and a stream kept alive in the
 * background would cost a connection on someone else's server and a radio on this one for no
 * reason. What a phone needs after a while away is a snapshot, and it takes one when it returns.
 *
 * The direct path's live stream works the same way for the same reason
 * (`sync/ForegroundUpdateManager`), and this manager deliberately shares none of its state: a
 * broadcast feed and a paired sidecar have different failure modes, different transports and
 * different privacy properties, and the one thing they do share — that a document is applied
 * through a revision-ordered idempotent path — is in the repositories, not here.
 *
 * ## How the snapshot and the stream are joined
 *
 * The stream opens first, then each channel whose continuity the broker could not prove is read
 * from the gateway. There is no buffer between them, and none is needed: both apply through
 * [FeedHost], which refuses a revision that is not newer than what is held (SEE-89). So a page from
 * the middle of a walk and an event that arrives during it converge whichever order they land in,
 * and the ordering question — "could a publication fall into a gap?" — is answered by the documents
 * themselves rather than by this code being careful.
 *
 * What the sequence is for, then, is the *next* time: a completed walk's boundary is stored, and a
 * gateway that has not moved since answers it with one small "unchanged" instead of a feed.
 */
class ForegroundFeedManager(
    private val connections: StateFlow<List<Connection>>,
    private val host: FeedHost,
    private val tickets: FeedTickets,
    private val stream: FeedStream,
    private val cursors: FeedCursorStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { (it * Random.nextDouble(0.75, 1.25)).toLong() },
    ownerScope: CoroutineScope? = null,
) {
    private val scope = ownerScope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(ForegroundFeedsState())
    val state: StateFlow<ForegroundFeedsState> = _state.asStateFlow()

    private val lifecycleLock = Any()
    private var session = 0L
    private var foregroundJob: Job? = null
    private var closingJob: Job? = null

    /**
     * Start listening, if there is anything to listen to. Idempotent: a configuration change that
     * recreates the activity must not open a second stream, so a call while one is running is
     * ignored and a call while the last one is closing waits for it.
     */
    fun onForeground() {
        val (current, closing) =
            synchronized(lifecycleLock) {
                if (foregroundJob?.isActive == true) return
                session += 1
                session to closingJob
            }
        val job = scope.launch {
            closing?.join()
            _state.value = ForegroundFeedsState(foreground = true)
            own(current)
        }
        synchronized(lifecycleLock) { if (session == current) foregroundJob = job }
    }

    /** Stop listening. What was applied stays applied; what was not is read again on return. */
    fun onBackground() {
        val job =
            synchronized(lifecycleLock) {
                session += 1
                val running = foregroundJob ?: return
                foregroundJob = null
                running
            }
        val closing = scope.launch {
            job.cancel()
            job.join()
            _state.value = ForegroundFeedsState(foreground = false)
        }
        synchronized(lifecycleLock) { closingJob = closing }
    }

    private fun isCurrent(expected: Long) = synchronized(lifecycleLock) { session == expected }

    /**
     * Keeps one listener per gateway for as long as this foreground session lasts, following the
     * feeds the owner has.
     *
     * Adding or removing a feed reopens that gateway's stream, because the transport has no way to
     * add a subscription to one that is open — the channels are fixed by the ticket it was opened
     * with. The change is therefore debounced: adding three feeds from a list is one reconnect, not
     * three.
     */
    private suspend fun own(current: Long) {
        val listeners = mutableMapOf<String, Job>()
        val wanted = mutableMapOf<String, Set<String>>()
        var reconciled = false
        try {
            connections
                .map { all ->
                    all.filter { it.mode == ConnectionMode.GatewayFeed }
                        .groupBy { it.serverUrl }
                        .mapValues { (_, feeds) -> feeds.map { it.serverId }.toSet() }
                }
                .distinctUntilChanged()
                .collect { grouped ->
                    // A gateway whose feeds changed, or which has none left, loses its listener.
                    for ((origin, job) in listeners.toList()) {
                        if (grouped[origin] == wanted[origin]) continue
                        job.cancel()
                        listeners.remove(origin)
                        wanted.remove(origin)
                        if (origin !in grouped) publish(current) { it - origin }
                    }
                    for ((origin, servers) in grouped) {
                        if (origin in listeners) continue
                        wanted[origin] = servers
                        val settle = reconciled
                        listeners[origin] = scope.launch {
                            if (settle) sleep(SETTLE_MILLIS)
                            maintain(origin, current)
                        }
                    }
                    reconciled = true
                }
        } finally {
            listeners.values.forEach { it.cancel() }
        }
    }

    /**
     * Keeps one gateway's listener open, with backoff between attempts.
     *
     * The ticket is held across reconnects and replaced only when the broker says the grant itself
     * is the problem. A ticket is cheap, but asking for one on every retry would turn a broker that
     * is down into a poll against the gateway beside it.
     */
    private suspend fun maintain(origin: String, current: Long) {
        var attempt = 0
        var grant: FeedGrant? = null
        while (isCurrent(current)) {
            val feeds = feedsOf(origin)
            if (feeds.isEmpty()) {
                publish(current) { it - origin }
                return
            }
            try {
                if (grant == null) {
                    publish(current) { it + (origin to FeedListenerState.Connecting) }
                    grant = tickets.ticket(origin, feeds.keys.map(::channelFor).take(MOST_CHANNELS))
                }
                val ended = listen(origin, grant, feeds, current)
                attempt = 0
                when (val next = ended?.let(::afterClose)) {
                    is AfterClose.Stop -> {
                        publish(current) { it + (origin to FeedListenerState.Refused(next.code)) }
                        return
                    }
                    is AfterClose.Reconnect -> if (next.reticket) grant = null
                    // The stream ended without the broker saying why: the connection went away, or
                    // one channel was dropped and the only way back is a new stream.
                    null -> Unit
                }
                // A clean end is not a problem to report, so what is shown is the activity. After a
                // failure it is the reason instead, which the branches below publish: a listener
                // that says "reconnecting" when the certificate was refused is telling the owner
                // the wrong thing to wait for.
                publish(current) { it + (origin to FeedListenerState.Reconnecting(1)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GatewayException) {
                if (e.kind == GatewayException.Kind.Unimplemented) {
                    // No stream at this gateway, which is a working deployment: the feeds on it are
                    // read over unary calls, and this listener stops until the feed set changes.
                    publish(current) { it + (origin to FeedListenerState.NoStream) }
                    return
                }
                grant = null
                attempt += 1
                publish(current) {
                    it + (origin to FeedListenerState.Unreachable(e.kind.toOutcome()))
                }
            } catch (e: FeedStreamException) {
                if (e.kind == FeedStreamException.Kind.Unsupported) {
                    publish(current) { it + (origin to FeedListenerState.NoStream) }
                    return
                }
                if (e.kind == FeedStreamException.Kind.Unauthenticated) grant = null
                attempt += 1
                publish(current) { it + (origin to FeedListenerState.Unreachable(outcomeOf(e))) }
            }
            if (!isCurrent(current)) return
            // Even after a clean end — a node shutting down, a ticket expiring — there is a pause
            // before the next attempt. A gateway restarting is restarting for every phone
            // subscribed to it, and they must not all come back in the same instant.
            sleep(backoff(attempt.coerceAtLeast(1), jitter))
        }
    }

    /**
     * Listens until the stream ends, and returns the code the broker ended it with, if it said one.
     *
     * Everything that arrives is applied as it arrives. The cursor moves only after a document has
     * been applied, so a listener that dies mid-event asks for that document again rather than
     * skipping it — the apply path makes the repeat free.
     */
    private suspend fun listen(
        origin: String,
        grant: FeedGrant,
        feeds: Map<String, Connection>,
        current: Long,
    ): Int? {
        val owners = mutableMapOf<String, Connection>()
        val resume = mutableMapOf<String, FeedCursor>()
        for (granted in grant.channels) {
            val connection =
                feeds.values.firstOrNull { channelFor(it.serverId) == granted.channel } ?: continue
            owners[granted.streamChannel] = connection
            cursors.get(connection.serverId)?.cursor?.let { resume[granted.streamChannel] = it }
        }
        if (owners.isEmpty()) return null
        val epochs = mutableMapOf<String, String>()
        var closed: Int? = null
        try {
            stream.listen(origin, grant.ticket, resume).collect { event ->
                when (event) {
                    is FeedStreamEvent.Opened -> {
                        publish(current) {
                            it + (origin to FeedListenerState.Live(owners.size))
                        }
                        for ((channel, connection) in owners) {
                            val subscription = event.subscriptions[channel]
                            subscription?.let { epochs[channel] = it.epoch }
                            open(connection, channel, subscription)
                        }
                    }
                    is FeedStreamEvent.Published -> {
                        val connection = owners[event.streamChannel] ?: return@collect
                        apply(connection, event.event)
                        epochs[event.streamChannel]?.let {
                            remember(connection.serverId, FeedCursor(it, event.offset))
                        }
                    }
                    is FeedStreamEvent.Unreadable -> {
                        // Something changed that this version cannot read. The snapshot is the
                        // answer: whatever the event said, the gateway can say it in a document
                        // this app does have a validator for.
                        owners[event.streamChannel]?.let { snapshot(it) }
                    }
                    is FeedStreamEvent.Dropped -> {
                        // One channel lost its subscription, and this transport cannot ask for it
                        // back. Ending the stream reopens every channel on it, and the one that was
                        // dropped fails its continuity check and reads a snapshot.
                        closed = null
                        throw StreamEnded()
                    }
                    is FeedStreamEvent.Closed -> {
                        closed = event.code
                        throw StreamEnded()
                    }
                    FeedStreamEvent.Alive -> Unit
                }
            }
        } catch (e: StreamEnded) {
            return closed
        }
        return closed
    }

    /**
     * What to do about one channel when the stream opens: nothing, or read the snapshot.
     *
     * Nothing is the good case, and it is the broker saying it replayed everything this listener
     * missed — the documents it replayed arrive as ordinary events right after this. Otherwise the
     * position it reports is where the channel is now, so it is stored before the snapshot is read:
     * anything published while the walk runs arrives on the stream and is applied there.
     */
    private suspend fun open(
        connection: Connection,
        channel: String,
        subscription: FeedSubscription?,
    ) {
        val held = cursors.get(connection.serverId)
        when (continuity(held?.cursor, subscription)) {
            is Continuity.Recovered -> Unit
            is Continuity.Snapshot -> {
                if (subscription != null && subscription.epoch.isNotEmpty()) {
                    remember(
                        connection.serverId,
                        FeedCursor(subscription.epoch, subscription.offset),
                    )
                }
                snapshot(connection)
            }
        }
    }

    /** Reads the authoritative feed, and remembers the boundary it was read at. */
    private suspend fun snapshot(connection: Connection) {
        val held = cursors.get(connection.serverId)
        val sequence = host.readFeed(connection.id, held?.sequence ?: 0L) ?: return
        remember(connection.serverId, held?.cursor, sequence)
    }

    /** Applies one event's document through the phone's own path for that kind of document. */
    private suspend fun apply(connection: Connection, event: FeedEvent) {
        when (event.documentCase) {
            FeedEvent.DocumentCase.PROPOSAL -> host.applyProposal(connection.id, event.proposal)
            FeedEvent.DocumentCase.MANIFEST -> host.applySettings(connection.id, event.manifest)
            // An envelope from a later protocol. It is not read as an empty document: something
            // changed, and the snapshot is how this version finds out what.
            else -> snapshot(connection)
        }
    }

    private fun remember(serverId: String, cursor: FeedCursor?, sequence: Long? = null) {
        val held = cursors.get(serverId)
        cursors.put(
            FeedCursorStore.Progress(
                serverId = serverId,
                cursor = cursor ?: held?.cursor,
                sequence = sequence ?: held?.sequence ?: 0L,
            )
        )
    }

    private fun feedsOf(origin: String): Map<String, Connection> =
        connections.value
            .filter { it.mode == ConnectionMode.GatewayFeed && it.serverUrl == origin }
            .associateBy { it.serverId }

    private fun publish(
        current: Long,
        change: (Map<String, FeedListenerState>) -> Map<String, FeedListenerState>,
    ) {
        if (!isCurrent(current)) return
        _state.value = _state.value.copy(gateways = change(_state.value.gateways))
    }

    private companion object {
        /**
         * How many channels one listener carries. It is the gateway's own bound on a ticket, said
         * here as well so that a phone with more feeds on one gateway keeps a working stream for
         * the first of them rather than being refused a ticket for all of them.
         */
        const val MOST_CHANNELS = 32

        /**
         * How long a change to the feed set settles before the stream is reopened. Adding three
         * feeds in a row is one reconnect.
         */
        const val SETTLE_MILLIS = 1_000L

        fun outcomeOf(failure: FeedStreamException): CheckOutcome =
            when (failure.kind) {
                FeedStreamException.Kind.CertificateRejected -> CheckOutcome.CertificateRejected
                FeedStreamException.Kind.CleartextBlocked -> CheckOutcome.CleartextBlocked
                FeedStreamException.Kind.Unreachable -> CheckOutcome.Unreachable
                else -> CheckOutcome.Failed
            }
    }
}

/** What one gateway's listener is doing. */
sealed interface FeedListenerState {
    /** Opening: asking for a ticket, or connecting with one. */
    data object Connecting : FeedListenerState

    /** Listening, to this many channels. */
    data class Live(val channels: Int) : FeedListenerState

    /** Not listening; trying again. */
    data class Reconnecting(val attempt: Int) : FeedListenerState

    /** Not reachable, or refusing to be trusted. The feeds on it are still read on demand. */
    data class Unreachable(val outcome: CheckOutcome) : FeedListenerState

    /**
     * This gateway serves no stream. Not a failure: it holds the documents and answers reads, and
     * the phone uses them.
     */
    data object NoStream : FeedListenerState

    /**
     * The broker will not take this listener back as it is — a limit, or a protocol it refused.
     * Retrying in a loop would be a phone talking to itself, so it stops until something changes.
     */
    data class Refused(val code: Int) : FeedListenerState
}

/** What every gateway's listener is doing, for the UI and for the tests. */
data class ForegroundFeedsState(
    val foreground: Boolean = false,
    val gateways: Map<String, FeedListenerState> = emptyMap(),
)

/**
 * Ends a collection deliberately. The code the broker sent is kept by the collector, because a
 * disconnect is not a failure — it is the broker telling a listener when to come back.
 *
 * No stack trace and no suppression: it is control flow, and one is recorded a few times a session.
 */
private class StreamEnded : Exception(null, null, false, false)
