package io.github.brrenat.seekervault.push

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeekerVaultMessagingServiceTest {
    @Test
    fun exactInvalidationSchedulesAuthoritativeSyncAtDeliveredPriority() {
        val scheduled = mutableListOf<Boolean>()
        val service =
            SeekerVaultMessagingService().also { candidate ->
                candidate.enqueueSync = { _, expedited -> scheduled += expedited }
            }

        service.onMessageReceived(message(REQUEST_INVALIDATION_DATA, "high"))
        service.onMessageReceived(message(REQUEST_INVALIDATION_DATA, "normal"))

        assertEquals(listOf(true, false), scheduled)
    }

    @Test
    fun ignoresUnknownVersionsMissingFieldsAndAdditionalPayloadData() {
        var scheduled = false
        val service =
            SeekerVaultMessagingService().also { candidate ->
                candidate.enqueueSync = { _, _ -> scheduled = true }
            }

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

        assertFalse(scheduled)
        assertTrue(isRequestInvalidation(REQUEST_INVALIDATION_DATA))
        assertFalse(isRequestInvalidation(REQUEST_INVALIDATION_DATA + ("extra" to "field")))
    }

    private fun message(data: Map<String, String>, deliveredPriority: String): RemoteMessage =
        RemoteMessage(
            Bundle().apply {
                data.forEach(::putString)
                putString("google.delivered_priority", deliveredPriority)
            }
        )
}
