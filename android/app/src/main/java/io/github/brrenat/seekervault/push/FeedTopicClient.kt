package io.github.brrenat.seekervault.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Joining and leaving a feed's public topic (SEE-92).
 *
 * It is one interface with two methods so that everything above it can be tested without Firebase,
 * and so that an installation built without a Firebase project remains an ordinary app — the same
 * reason [FcmRegistrationClient] exists beside it.
 *
 * **Membership is Firebase's, not ours.** Nothing on this phone keeps a list of subscriptions on
 * disk, and no server of ours is told about one: the broadcast gateway names a topic when it is
 * asked ([io.github.brrenat.seekervault.feeds.FeedTopics]) and is never told whether anybody
 * joined. That is also why the manager above reconciles rather than remembers — see
 * [FeedTopicManager].
 */
interface FeedTopicClient {
    suspend fun subscribe(topic: String)

    suspend fun unsubscribe(topic: String)
}

/** Uses Firebase's own topic membership, and only when Google Services initialized Firebase. */
class FirebaseFeedTopicClient(context: Context) : FeedTopicClient {
    private val applicationContext = context.applicationContext

    override suspend fun subscribe(topic: String) {
        messaging()?.subscribeToTopic(topic)?.await()
    }

    override suspend fun unsubscribe(topic: String) {
        messaging()?.unsubscribeFromTopic(topic)?.await()
    }

    private fun messaging(): FirebaseMessaging? =
        if (FirebaseApp.getApps(applicationContext).isEmpty()) null
        else FirebaseMessaging.getInstance()
}

/**
 * The shape a topic name is held to before it reaches Firebase, or a hint is read as one.
 *
 * Firebase's own rule is `[a-zA-Z0-9-_.~%]+`; this is that, bounded, and with the prefix the
 * gateway's relay uses. It is applied to what the gateway said and to what arrived in a message,
 * for the same reason every other identifier on this phone is validated at its boundary: a name
 * that reached `subscribeToTopic` unchecked would be an argument built somewhere else.
 */
internal fun isFeedTopic(topic: String): Boolean =
    topic.length in 6..900 &&
        topic.startsWith("feed.") &&
        topic.all { (it.isLetterOrDigit() && it.code < 0x80) || it in "-_.~%" }
