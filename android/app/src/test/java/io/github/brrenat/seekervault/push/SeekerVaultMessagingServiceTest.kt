package io.github.brrenat.seekervault.push

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeekerVaultMessagingServiceTest {
    /** A service with both handoffs replaced, which is the whole of what this callback may do. */
    private class Received {
        val synchronized = mutableListOf<Boolean>()
        var reads = 0
        val topics = mutableListOf<String>()

        fun service(): SeekerVaultMessagingService =
            SeekerVaultMessagingService().also { service ->
                service.enqueueSync = { _, expedited -> synchronized += expedited }
                service.enqueueFeedRead = { reads++ }
                service.noticeTopic = { topics += it }
            }
    }

    @Test
    fun exactInvalidationSchedulesAuthoritativeSyncAtDeliveredPriority() {
        val received = Received()
        val service = received.service()

        service.onMessageReceived(message(REQUEST_INVALIDATION_DATA, "high"))
        service.onMessageReceived(message(REQUEST_INVALIDATION_DATA, "normal"))

        assertEquals(listOf(true, false), received.synchronized)
    }

    @Test
    fun ignoresUnknownVersionsMissingFieldsAndAdditionalPayloadData() {
        val received = Received()
        val service = received.service()

        for (data in
            listOf(
                emptyMap(),
                mapOf("kind" to "request_invalidation"),
                mapOf("kind" to "request_invalidation", "version" to "2"),
                REQUEST_INVALIDATION_DATA + ("request_id" to "must-not-be-accepted"),
                REQUEST_INVALIDATION_DATA + ("credential" to "must-not-be-accepted"),
            )) {
            service.onMessageReceived(message(data, "high"))
        }

        assertTrue(received.synchronized.isEmpty())
        assertTrue(isRequestInvalidation(REQUEST_INVALIDATION_DATA))
        assertFalse(isRequestInvalidation(REQUEST_INVALIDATION_DATA + ("extra" to "field")))
    }

    /**
     * A feed hint schedules one authoritative read, whatever priority it arrived at, and hands the
     * topic it arrived on to the one component that has any use for it (SEE-92).
     *
     * The read is not expedited and carries no input: two hints are one read, and the read covers
     * every feed this phone holds — which is what makes a coalesced or dropped hint cost nothing.
     */
    @Test
    fun aFeedHintSchedulesOneReadAndPassesOnOnlyItsTopic() {
        val received = Received()
        val service = received.service()

        service.onMessageReceived(feedMessage("high"))
        service.onMessageReceived(feedMessage("normal"))

        assertEquals(2, received.reads)
        assertEquals(listOf(TOPIC, TOPIC), received.topics)
        // The two kinds do not cross: a feed hint never schedules the private path's Sync.
        assertTrue(received.synchronized.isEmpty())
    }

    /** The two kinds are separate, and each is matched whole. */
    @Test
    fun theTwoKindsAreMatchedWholeAndNeitherIsTheOther() {
        val received = Received()
        val service = received.service()

        service.onMessageReceived(message(REQUEST_INVALIDATION_DATA, "high"))
        service.onMessageReceived(feedMessage("high"))

        assertEquals(listOf(true), received.synchronized)
        assertEquals(1, received.reads)

        for (data in
            listOf(
                mapOf("kind" to "feed_invalidation"),
                mapOf("kind" to "feed_invalidation", "version" to "2"),
                FEED_INVALIDATION_DATA + ("channel" to "must-not-be-accepted"),
                FEED_INVALIDATION_DATA + ("proposal_id" to "must-not-be-accepted"),
            )) {
            service.onMessageReceived(message(data, "high"))
        }
        assertEquals(1, received.reads)
        assertTrue(isFeedInvalidation(FEED_INVALIDATION_DATA))
        assertFalse(isFeedInvalidation(FEED_INVALIDATION_DATA + ("extra" to "field")))
        assertFalse(isFeedInvalidation(REQUEST_INVALIDATION_DATA))
        assertFalse(isRequestInvalidation(FEED_INVALIDATION_DATA))
    }

    /**
     * A hint that arrived with no topic, or one that is not a feed's, still reads: the topic only
     * ever decides whether to stop subscribing to something, never what is read.
     */
    @Test
    fun aHintWithNoUsableTopicStillReadsAndPassesOnNothing() {
        val received = Received()
        val service = received.service()

        for (from in listOf(null, "", "/topics/", "not-a-topic", "/topics/feed.production.a b")) {
            service.onMessageReceived(feedMessage("high", from))
        }

        assertEquals(5, received.reads)
        assertTrue(received.topics.isEmpty())
        // And the one shape Firebase actually uses.
        assertEquals(TOPIC, topicOf("/topics/$TOPIC"))
        assertNull(topicOf(TOPIC))
        assertNull(topicOf(null))
    }

    private fun feedMessage(
        deliveredPriority: String,
        from: String? = "/topics/$TOPIC",
    ): RemoteMessage =
        RemoteMessage(
            Bundle().apply {
                FEED_INVALIDATION_DATA.forEach(::putString)
                putString("google.delivered_priority", deliveredPriority)
                from?.let { putString("from", it) }
            }
        )

    private fun message(data: Map<String, String>, deliveredPriority: String): RemoteMessage =
        RemoteMessage(
            Bundle().apply {
                data.forEach(::putString)
                putString("google.delivered_priority", deliveredPriority)
            }
        )

    private companion object {
        const val TOPIC = "feed.production.3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    }
}
