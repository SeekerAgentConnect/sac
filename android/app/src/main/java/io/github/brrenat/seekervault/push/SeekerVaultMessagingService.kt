package io.github.brrenat.seekervault.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.brrenat.seekervault.SeekerVaultApplication
import io.github.brrenat.seekervault.sync.PushSyncScheduler

/**
 * Accepts only SAW-056's fixed, content-free invalidation and schedules authoritative Sync. It
 * never opens a request, notification, wallet, approval, signature, or transaction from payload.
 */
class SeekerVaultMessagingService : FirebaseMessagingService() {
    internal var enqueueSync: (Context, Boolean) -> Unit = PushSyncScheduler::enqueue

    override fun onRegistered(token: String) {
        (application as SeekerVaultApplication).fcmRegistrations.onRegistered(token)
    }

    override fun onUnregistered(token: String) {
        (application as SeekerVaultApplication).fcmRegistrations.onUnregistered(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!isRequestInvalidation(message.data)) return
        enqueueSync(this, message.priority == RemoteMessage.PRIORITY_HIGH)
    }
}

internal val REQUEST_INVALIDATION_DATA =
    mapOf(
        "kind" to "request_invalidation",
        "version" to "1",
    )

/** Unknown versions and messages with any additional field are ignored, not partly trusted. */
internal fun isRequestInvalidation(data: Map<String, String>): Boolean =
    data == REQUEST_INVALIDATION_DATA
