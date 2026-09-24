package io.github.brrenat.seekervault.feeds

import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.GatewayException
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
import kotlinx.coroutines.launch

/** What the phone last heard about each feed's own publisher, by connection id (SEE-150). */
data class FeedStatusState(
    val foreground: Boolean = false,
    val feeds: Map<String, FeedAvailability> = emptyMap(),
) {
    /** A connection nothing has been heard about is unknown, which is never treated as online. */
    fun availabilityOf(connectionId: String): FeedAvailability =
        feeds[connectionId] ?: FeedAvailability.Unknown
}

/**
 * Asks each gateway whether the publishers behind this phone's feeds are running, while the app is
 * being looked at (SEE-150).
 *
 * ## Why it is its own manager
 *
 * [ForegroundFeedManager] owns whether *this phone* is connected to a gateway; this owns whether
 * the *publisher* behind each channel is. Conflating the two is the bug: a gateway that answers
 * says nothing about a publisher's own server, because the gateway keeps serving what that
 * publisher last published after its process is gone. Two questions, two states, and nothing here
 * ever reads one as evidence for the other.
 *
 * Keeping them apart also keeps the failure modes apart. A gateway whose stream is refused still
 * answers this read; a gateway that has never heard of presence (a build older than SEE-150)
 * answers `unimplemented` and leaves every feed unknown, which is a working deployment and not a
 * problem to show the owner.
 *
 * ## Why it is a poll, and only in the foreground
 *
 * There is nothing to push. Presence is a fact the gateway holds and revises by not being told
 * anything; it has no event, and inventing one would mean a second stream for a line of text. A
 * read every [INTERVAL_MILLIS] while somebody is looking is cheap — one small unauthenticated call
 * per gateway, no matter how many feeds it hosts — and a phone nobody is holding has no use for it:
 * what it needs on return is the current answer, which is the first thing this asks for.
 *
 * State is keyed by connection id rather than by channel, because that is what the UI holds and
 * because two connections may name the same channel on different gateways. Each feed's answer is
 * its own: one publisher being offline says nothing about the next.
 */
class FeedStatusManager(
    private val connections: StateFlow<List<Connection>>,
    private val statuses: FeedStatuses,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { (it * Random.nextDouble(0.75, 1.25)).toLong() },
    ownerScope: CoroutineScope? = null,
) {
    private val scope = ownerScope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(FeedStatusState())
    val state: StateFlow<FeedStatusState> = _state.asStateFlow()

    private val lifecycleLock = Any()
    private var session = 0L
    private var foregroundJob: Job? = null
    private var closingJob: Job? = null

    /**
     * Start asking. Idempotent on the same terms [ForegroundFeedManager.onForeground] is: a
     * configuration change that recreates the activity must not start a second poll, and a call
     * while the last one is stopping waits for it.
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
            // The answers are kept across the gap rather than cleared: what the gateway said a
            // moment
            // ago is the best thing known until the first read of this session lands, and blanking
            // them would show a running feed as unknown every time the app is reopened.
            _state.value = _state.value.copy(foreground = true)
            poll(current)
        }
        synchronized(lifecycleLock) { if (session == current) foregroundJob = job }
    }

    /** Stop asking. */
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
            _state.value = _state.value.copy(foreground = false)
        }
        synchronized(lifecycleLock) { closingJob = closing }
    }

    /**
     * Ask now, outside the interval: what a manual refresh does, so the owner is never made to
     * wait.
     */
    fun refresh() {
        val current = synchronized(lifecycleLock) { session }
        scope.launch { once(current, feedsByGateway(connections.value)) }
    }

    private fun isCurrent(expected: Long) = synchronized(lifecycleLock) { session == expected }

    /**
     * One pass per interval, over whatever feeds the owner has at that moment.
     *
     * A change to the feed list does not restart anything — unlike the stream, this read carries
     * the channels in the request, so the next pass simply asks about the new set. What a new feed
     * gets is an answer on the next tick, and a removed one stops being asked about and is dropped
     * from the state.
     */
    private suspend fun poll(current: Long) {
        // The feed list is read at the top of each pass rather than collected into a variable
        // beside it: a collector on another coroutine writing what this loop reads is a race for no
        // benefit, since a pass that starts a moment later reads the same thing anyway.
        while (isCurrent(current)) {
            once(current, feedsByGateway(connections.value))
            if (!isCurrent(current)) return
            sleep(jitter(INTERVAL_MILLIS))
        }
    }

    /**
     * One read per gateway, and the answers folded into the state together.
     *
     * A gateway that fails leaves its own feeds as they were rather than marking them offline. An
     * unreachable gateway is a thing the owner is already told about, by the row's own
     * connectivity, and claiming its publishers have stopped would be this phone inventing a
     * verdict it was never given — the same conflation from the other direction.
     */
    private suspend fun once(current: Long, known: Map<String, Map<String, String>>) {
        for ((gatewayUrl, feeds) in known) {
            if (!isCurrent(current)) return
            val answered =
                try {
                    statuses.statuses(gatewayUrl, feeds.values.distinct())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: GatewayException) {
                    continue
                }
            if (!isCurrent(current)) return
            _state.value =
                _state.value.copy(
                    feeds =
                        _state.value.feeds +
                            feeds.mapValues { (_, channel) ->
                                answered[channel] ?: FeedAvailability.Unknown
                            }
                )
        }
        // A connection the owner removed stops being an answer about anything.
        if (isCurrent(current)) {
            val live = known.values.flatMap { it.keys }.toSet()
            _state.value = _state.value.copy(feeds = _state.value.feeds.filterKeys { it in live })
        }
    }

    /** The feeds this phone holds, as gateway origin to (connection id to channel). */
    private fun feedsByGateway(all: List<Connection>): Map<String, Map<String, String>> =
        all.filter { it.mode == ConnectionMode.GatewayFeed && it.revokedAt == null }
            .groupBy { it.serverUrl }
            .mapValues { (_, feeds) -> feeds.associate { it.id to channelFor(it.serverId) } }

    private companion object {
        /**
         * Half a minute, which is the interval the gateway asks its publishers to check in on.
         * Asking faster would read the same answer twice; asking much slower would mean an owner
         * watching a feed die and being told about it a minute later than the gateway knew.
         */
        const val INTERVAL_MILLIS = 30_000L
    }
}
