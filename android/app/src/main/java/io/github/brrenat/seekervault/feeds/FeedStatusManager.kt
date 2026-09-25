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
import kotlinx.coroutines.flow.update
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
     * Which read of a gateway is the newest, so an answer that overtook a later one is dropped
     * rather than published (SEE-155).
     *
     * A manual refresh and the periodic pass are two reads of the same gateway in flight at once,
     * and nothing makes them finish in the order they started: a refresh sent second over a warm
     * connection lands first, and the poll's older answer would then overwrite it with whatever the
     * gateway said a moment earlier. That is not a stale row for one tick — the state it overwrites
     * is the fresher one, so a publisher that just came back is shown offline again.
     *
     * [reads] is stamped when a gateway's read starts; [applied] is the newest stamp already
     * published for that gateway. Both are guarded by [lifecycleLock] because a refresh runs on a
     * coroutine of its own.
     */
    private var reads = 0L
    private val applied = mutableMapOf<String, Long>()

    /** The manual read in flight, so two taps in a row are one read. */
    private var refreshJob: Job? = null

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
        val current =
            synchronized(lifecycleLock) {
                // One refresh at a time. An owner pulling twice, or a screen that refreshes each
                // of its feeds, should cost one read of each gateway and not one per tap: the
                // answer in flight is the answer they are waiting for.
                if (refreshJob?.isActive == true) return
                session
            }
        val job = scope.launch { once(current, feedsByGateway(connections.value)) }
        synchronized(lifecycleLock) { refreshJob = job }
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
     * One pass over every gateway: each one's channels read in batches, and each one's answers
     * folded into the state as they arrive.
     *
     * A gateway that fails leaves its own feeds as they were rather than marking them offline. An
     * unreachable gateway is a thing the owner is already told about, by the row's own
     * connectivity, and claiming its publishers have stopped would be this phone inventing a
     * verdict it was never given — the same conflation from the other direction. One gateway
     * failing is also not the next one's business: the loop moves on to it either way.
     */
    private suspend fun once(current: Long, known: Map<String, Map<String, String>>) {
        for ((gatewayUrl, feeds) in known) {
            if (!isCurrent(current)) return
            val stamp = synchronized(lifecycleLock) { ++reads }
            val read = read(current, gatewayUrl, feeds.values.distinct()) ?: return
            if (!isCurrent(current)) return
            publish(gatewayUrl, stamp, feeds, read)
        }
        // A connection the owner removed stops being an answer about anything. The live list is
        // read again here rather than taken from `known`, which is a snapshot from before the
        // calls: a feed removed while this pass was in flight would otherwise stay in the state
        // until the pass after next.
        if (isCurrent(current)) {
            val live = feedsByGateway(connections.value).values.flatMap { it.keys }.toSet()
            _state.update { it.copy(feeds = it.feeds.filterKeys { id -> id in live }) }
        }
    }

    /**
     * One gateway's channels, asked for in batches of [MOST_CHANNELS] (SEE-155).
     *
     * The gateway refuses a read naming more channels than that, and a phone holding a thirty-third
     * feed used to send all of them in one request and have the whole read refused — which left
     * every feed on that gateway stale, on every poll, for ever. Splitting is the fix, and
     * truncating is not: a phone that asked about its first thirty-two feeds would report nothing
     * about the rest while looking like it had.
     *
     * A batch that fails takes only its own channels out of the answer. The others are published,
     * and the channels in the failed batch keep whatever they had, for the reason a whole failed
     * gateway does: this phone was not told those publishers had stopped, and saying so would be
     * inventing a verdict.
     *
     * Null means the pass was overtaken and nothing should be published at all.
     */
    private suspend fun read(current: Long, gatewayUrl: String, channels: List<String>): Read? {
        val answered = mutableMapOf<String, FeedAvailability>()
        val asked = mutableSetOf<String>()
        for (batch in channels.chunked(MOST_CHANNELS)) {
            if (!isCurrent(current)) return null
            val part =
                try {
                    statuses.statuses(gatewayUrl, batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: GatewayException) {
                    continue
                }
            asked += batch
            answered += part
        }
        return Read(answered = answered, asked = asked)
    }

    /**
     * Fold one gateway's answer into the state, unless a later read of the same gateway has already
     * landed.
     *
     * Two things are checked against the present rather than against the snapshot the read started
     * from: [stamp], so an overtaken answer is dropped, and the connection list, so a feed the
     * owner removed mid-read is not written back in by the answer that was already on its way.
     */
    private fun publish(
        gatewayUrl: String,
        stamp: Long,
        feeds: Map<String, String>,
        read: Read,
    ) {
        // A read whose every batch failed has nothing to say, so it does not claim the gateway
        // either: otherwise a refresh that failed fast would drop the periodic read that was
        // still out and did get an answer, and the row would stay stale for another interval.
        if (read.asked.isEmpty()) return
        synchronized(lifecycleLock) {
            if (stamp <= (applied[gatewayUrl] ?: 0L)) return
            applied[gatewayUrl] = stamp
        }
        val live = feedsByGateway(connections.value)[gatewayUrl].orEmpty()
        val fresh =
            feeds
                .filter { (id, channel) -> live[id] == channel && channel in read.asked }
                .mapValues { (_, channel) -> read.answered[channel] ?: FeedAvailability.Unknown }
        if (fresh.isEmpty()) return
        _state.update { it.copy(feeds = it.feeds + fresh) }
    }

    /**
     * One gateway's answer: what it said, and which channels it was actually asked about — which
     * are not the same set, because a channel a gateway does not host is left out of an answer and
     * a channel in a failed batch was never asked at all. Only the second set may be written.
     */
    private class Read(
        val answered: Map<String, FeedAvailability>,
        val asked: Set<String>,
    )

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

        /**
         * The most channels one read may name, which is the gateway's own bound
         * (feed-gateway/internal/gateway.MostStatusChannels). It is duplicated rather than derived
         * because there is nothing to derive it from on a phone: the number is part of the API's
         * contract, and a gateway that lowered it would refuse the batch, which is a failed batch
         * and not a failed gateway.
         */
        const val MOST_CHANNELS = 32
    }
}
