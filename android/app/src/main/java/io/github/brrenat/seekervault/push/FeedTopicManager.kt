package io.github.brrenat.seekervault.push

import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.feeds.FeedTopics
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.channelFor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Which feeds' hints this phone has asked Firebase to deliver (SEE-92).
 *
 * The owner adds a feed; this asks that feed's gateway what its topic is called and subscribes to
 * it. The owner removes a feed; this unsubscribes. Nothing else happens here — a topic subscription
 * is the whole of it, and what arrives on one is handled elsewhere ([SeekerVaultMessagingService],
 * `sync/FeedSynchronization`).
 *
 * ## Why it reconciles instead of remembering
 *
 * Membership belongs to Firebase, and this phone deliberately keeps no list of it on disk. So the
 * intent is derived, every time, from the connections the owner has: a feed that is there should be
 * subscribed, and a topic that is subscribed and belongs to no feed should not be. That leaves one
 * gap, and it is the honest one to leave — a feed removed while the process was dead leaves a
 * subscription nothing here remembers. The cure is the hint itself: one that arrives for a topic no
 * feed wants is unsubscribed from ([onHint]), so the stale subscription removes itself the first
 * time it costs anything. A registry on disk would be a second place the truth lived, and the truth
 * is the connection list.
 *
 * ## What it is not
 *
 * It is not a registration: nothing about this device is sent anywhere. The gateway is asked the
 * name of a public topic and is never told whether this phone joined it; Firebase is told to join
 * and is not told why. And it is not a delivery guarantee — a topic message is a hint, the
 * foreground stream and the periodic read are what the app actually relies on (SEE-91).
 *
 * Callbacks and connection changes enter one serialized channel, exactly as
 * [FcmRegistrationManager] does it, so a subscription and the unsubscription that overtakes it
 * cannot cross.
 */
class FeedTopicManager(
    private val loaded: StateFlow<Boolean>,
    private val connections: StateFlow<List<Connection>>,
    private val gateway: FeedTopics,
    private val client: FeedTopicClient,
    private val loadConnections: suspend () -> Unit,
    dispatcher: CoroutineDispatcher,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private sealed interface Event {
        /** The feeds the owner has, by the gateway that serves each. */
        class Feeds(val wanted: Map<String, Set<String>>) : Event

        /** A hint arrived on this topic. */
        class Hint(val topic: String) : Event

        /** Firebase registered or re-registered this installation. */
        data object Registered : Event

        /** Try one gateway's feeds again, after a failure. */
        class Retry(val origin: String, val attempt: Int) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private val _state = MutableStateFlow(FeedTopicsState())

    /** What this phone believes it is subscribed to, for the tests and for a future screen. */
    val state: StateFlow<FeedTopicsState> = _state.asStateFlow()

    /** The feeds each gateway serves, as the owner's connections say. */
    private var wanted = emptyMap<String, Set<String>>()

    /** Topic by channel, for the channels this process has subscribed. */
    private val subscribed = mutableMapOf<String, String>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(loaded, connections) { ready, values ->
                    if (!ready) emptyMap()
                    else
                        values
                            .filter { it.mode == ConnectionMode.GatewayFeed }
                            .groupBy { it.serverUrl }
                            .mapValues { (_, feeds) -> feeds.map { it.serverId }.toSortedSet() }
                }
                .distinctUntilChanged()
                .collect { events.send(Event.Feeds(it)) }
        }
        scope.launch {
            for (event in events) handle(event)
        }
        scope.launch { bestEffort { loadConnections() } }
    }

    /**
     * Called by [SeekerVaultMessagingService] when a hint arrives, with the topic it arrived on.
     *
     * The topic is not trusted for anything and is not stored: it is compared with what this phone
     * wants, and its only effect is to unsubscribe from a topic that nothing wants any more. A hint
     * for a feed the owner still has changes nothing here — the read it triggers is the worker's
     * job.
     */
    fun onHint(topic: String) {
        if (isFeedTopic(topic)) events.trySend(Event.Hint(topic))
    }

    /**
     * Called when Firebase registers this installation, initially or again.
     *
     * Topic membership is attached to the installation, so a re-registration is a moment when it is
     * worth saying again what this phone wants. Subscribing to a topic it is already subscribed to
     * costs one call and changes nothing, which is the right trade against a phone that quietly
     * stops receiving hints.
     */
    fun onRegistered() {
        events.trySend(Event.Registered)
    }

    internal fun close() {
        events.close()
        scope.cancel()
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Feeds -> {
                wanted = event.wanted
                // A gateway the owner no longer has any feed on is no longer a gateway whose
                // answers this phone is waiting for.
                publish {
                    it.copy(
                        failed = it.failed.filterTo(mutableSetOf()) { o -> o in wanted },
                        unsupported = it.unsupported.filterTo(mutableSetOf()) { o -> o in wanted },
                    )
                }
                reconcile(wanted.keys, attempt = 0)
            }
            is Event.Registered -> {
                // Everything again, from scratch: what this phone is subscribed to is what it asked
                // for, and after a re-registration it has not asked for anything yet.
                subscribed.clear()
                publish { FeedTopicsState() }
                reconcile(wanted.keys, attempt = 0)
            }
            is Event.Hint -> {
                // A hint is also a moment this app is awake, so it says again what it wants: a
                // subscription that could not be made earlier may succeed now, and one that is
                // already in place costs no call at all.
                reconcile(wanted.keys, attempt = 0)
                when {
                    // A feed the owner has. Reading it is the worker's job, not this one's.
                    event.topic in subscribed.values -> Unit
                    // A gateway could not be asked, so this phone does not know what it wants:
                    // unsubscribing now could drop the hints for a feed the owner still has.
                    _state.value.failed.isNotEmpty() -> Unit
                    // Nothing here wants it: a feed removed while the app was not running, or one
                    // this installation inherited. It costs the owner a wake-up for nothing, and
                    // leaving is the answer they would give.
                    else -> leave(event.topic)
                }
            }
            is Event.Retry -> {
                if (event.origin !in wanted) return
                reconcile(setOf(event.origin), event.attempt)
            }
        }
    }

    /**
     * Brings one or more gateways' subscriptions in line with what the owner has.
     *
     * The leaving happens first and for every gateway, because it is local: a channel no connection
     * owns any more is unsubscribed whether or not its gateway can be reached. Joining needs the
     * gateway to name the topics, so it is per origin and it is what a failure retries.
     */
    private suspend fun reconcile(origins: Set<String>, attempt: Int) {
        val channels = wanted.flatMap { (_, servers) -> servers.map(::channelFor) }.toSet()
        for ((channel, topic) in subscribed.toList()) {
            if (channel in channels) continue
            subscribed.remove(channel)
            leave(topic)
        }
        for (origin in origins) {
            val servers = wanted[origin] ?: continue
            val missing = servers.map(::channelFor).filterNot { it in subscribed }
            if (missing.isEmpty()) continue
            join(origin, missing.take(MOST_TOPICS), attempt)
        }
    }

    /** Asks one gateway where its feeds' hints arrive, and subscribes to what it names. */
    private suspend fun join(origin: String, channels: List<String>, attempt: Int) {
        val named =
            try {
                gateway.topics(origin, channels)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GatewayException) {
                if (e.kind == GatewayException.Kind.Unimplemented) {
                    // This gateway relays nothing, which is a working deployment: there is nothing
                    // to subscribe to and nothing to retry until the feed set changes.
                    publish {
                        it.copy(unsupported = it.unsupported + origin, failed = it.failed - origin)
                    }
                    return
                }
                later(origin, attempt)
                return
            } catch (_: Exception) {
                later(origin, attempt)
                return
            }
        // A gateway that hosts a feed but relays no hints for it names nothing for that channel.
        // That is not a failure and not something to keep asking about on a timer: the feed is
        // still read on every glance, and the next change to the feed set asks again.
        publish { it.copy(failed = it.failed - origin, unsupported = it.unsupported - origin) }
        for (one in named) {
            if (!isFeedTopic(one.topic)) continue
            if (!bestEffortResult { client.subscribe(one.topic) }) {
                // Firebase refused or could not be reached. Nothing is recorded as subscribed —
                // what the owner asked for is still in the connection list — and the attempt is
                // made again with a bounded delay.
                later(origin, attempt)
                return
            }
            subscribed[one.channel] = one.topic
            publish { it.copy(topics = it.topics + (one.channel to one.topic)) }
        }
    }

    /** Unsubscribes from one topic, as far as Firebase will let this phone. */
    private suspend fun leave(topic: String) {
        publish { held -> held.copy(topics = held.topics.filterValues { it != topic }) }
        // A failure here is not retried on a timer. The subscription costs the owner a wake-up
        // they do not need, not a document they should not see — and the next hint on it comes
        // back through [onHint], which tries again for exactly that reason.
        bestEffortResult { client.unsubscribe(topic) }
    }

    private fun later(origin: String, attempt: Int) {
        publish { it.copy(failed = it.failed + origin) }
        if (attempt >= MOST_RETRIES) return
        val next = attempt + 1
        scope.launch {
            sleep(RETRY_BASE_MILLIS * (1L shl (next - 1)))
            events.trySend(Event.Retry(origin, next))
        }
    }

    private fun publish(change: (FeedTopicsState) -> FeedTopicsState) {
        _state.value = change(_state.value)
    }

    private suspend fun bestEffortResult(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Subscribing is an optional hint path, and the app's own reads are the recovery.
        }
    }

    internal companion object {
        /**
         * How many feeds on one gateway are asked about at once, matching the gateway's own bound
         * and the stream's (SEE-91). A phone with more feeds than this on one gateway subscribes to
         * the first of them and reads the rest when the owner looks, rather than being refused the
         * lot.
         */
        internal const val MOST_TOPICS = 32

        /** A bounded retry, as registration has: 5, 10, then 20 seconds. */
        internal const val MOST_RETRIES = 3
        internal const val RETRY_BASE_MILLIS = 5_000L
    }
}

/**
 * What this phone believes about its feeds' hints.
 *
 * [topics] is topic by channel — what was asked for and accepted. [failed] and [unsupported] are
 * about gateways rather than feeds, because that is the level the answer comes at: one could not be
 * asked, and the other relays nothing at all.
 */
data class FeedTopicsState(
    val topics: Map<String, String> = emptyMap(),
    val failed: Set<String> = emptySet(),
    val unsupported: Set<String> = emptySet(),
)
