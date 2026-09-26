package io.github.brrenat.seekervault.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.brrenat.seekervault.SeekerVaultApplication
import io.github.brrenat.seekervault.sync.FeedSyncScheduler
import io.github.brrenat.seekervault.sync.PushSyncScheduler

/**
 * Accepts two fixed, content-free invalidations and nothing else: SAW-056's, about the owner's own
 * paired sidecar, and SEE-92's, about a publisher's public feed. Both do the same thing — schedule
 * an authoritative read — and neither opens a request, notification, wallet, approval, signature or
 * transaction from anything in the payload.
 *
 * SEE-144 adds a third sender of the first one and no third kind: a gateway relaying on behalf of a
 * server that holds no Firebase credential sends exactly SAW-056's message, so a phone cannot tell
 * the two apart and does not have to. What arrives is still "something changed", and the reading is
 * still the part with authority.
 *
 * The two are separate kinds rather than one, because they are about different servers, reach
 * different state and are bounded differently. They are matched whole, so a message with an extra
 * field is ignored rather than partly trusted.
 */
class SeekerVaultMessagingService : FirebaseMessagingService() {
    internal var enqueueSync: (Context, Boolean) -> Unit = PushSyncScheduler::enqueue
    internal var enqueueFeedRead: (Context) -> Unit = FeedSyncScheduler::enqueue

    /**
     * Where a hint's topic goes: to the one component that has any use for it. Replaced in a test,
     * which is the only way to exercise this callback without an application around it.
     */
    internal var noticeTopic: (String) -> Unit = { topic ->
        (application as? SeekerVaultApplication)?.feedTopics?.onHint(topic)
    }

    override fun onRegistered(token: String) {
        val application = application as SeekerVaultApplication
        application.fcmRegistrations.onRegistered(token)
        // Topic membership is attached to the installation, so a registration is the moment to say
        // again which feeds this phone wants hints about (SEE-92). It carries no token and nothing
        // about this device anywhere.
        application.feedTopics.onRegistered()
        // And the gateway relay, which holds this registration on behalf of servers that have no
        // Firebase credential of their own (SEE-144). It is told the same target the paired
        // sidecars are told, and only ever the relay this app was built to trust.
        application.relayRegistrations.onRegistered(token)
        // A restricted feed has no public topic to join, so each approved device names its own
        // target under the session its grant was issued with (SEE-156). A registration is the
        // moment to say it again, for every feed this phone is currently connected to.
        application.feedAccessManager.onRegistered(token)
    }

    override fun onUnregistered(token: String) {
        (application as SeekerVaultApplication).fcmRegistrations.onUnregistered(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when {
            isRequestInvalidation(message.data) ->
                enqueueSync(this, message.priority == RemoteMessage.PRIORITY_HIGH)
            isFeedInvalidation(message.data) -> {
                // Which feed changed is the topic this arrived on, which is a routing field rather
                // than payload — the same line SAW-056 drew for a target. It decides nothing about
                // what is read: it is handed to the topic manager, whose only use for it is to stop
                // subscribing to a feed the owner removed.
                topicOf(message.from)?.let(noticeTopic)
                // Empty input, and one job however many hints arrive: the read covers every feed
                // this phone holds, so a coalesced or dropped hint costs nothing (SEE-92).
                enqueueFeedRead(this)
            }
        }
    }
}

internal val REQUEST_INVALIDATION_DATA =
    mapOf(
        "kind" to "request_invalidation",
        "version" to "1",
    )

/**
 * The feed hint, as the gateway's relay sends it (services/gateway/internal/relay/relay.go). The
 * two sides agree by being pinned on both: a test on this side reads that file and fails if the
 * kind or the version drifts apart, because a mismatch would be silence rather than an error.
 */
internal val FEED_INVALIDATION_DATA =
    mapOf(
        "kind" to "feed_invalidation",
        "version" to "1",
    )

/** Unknown versions and messages with any additional field are ignored, not partly trusted. */
internal fun isRequestInvalidation(data: Map<String, String>): Boolean =
    data == REQUEST_INVALIDATION_DATA

internal fun isFeedInvalidation(data: Map<String, String>): Boolean = data == FEED_INVALIDATION_DATA

/**
 * The topic a message arrived on, from Firebase's own `from` field, or null when it is not one of
 * ours.
 *
 * Firebase spells a topic sender as `/topics/<name>`, and the name is validated before it is used
 * for anything ([isFeedTopic]) — an unrecognised one is simply not a topic this phone acts on.
 */
internal fun topicOf(from: String?): String? =
    from?.removePrefix("/topics/")?.takeIf { it != from && isFeedTopic(it) }
