package io.github.brrenat.seekervault.push

import com.google.firebase.messaging.FirebaseMessagingService
import io.github.brrenat.seekervault.SeekerVaultApplication

/**
 * Receives registration lifecycle callbacks only (SAW-055). Message receipt belongs to SAW-057:
 * this service neither handles a payload nor starts sync, a notification, a wallet, or an action.
 */
class SeekerVaultMessagingService : FirebaseMessagingService() {
    override fun onRegistered(token: String) {
        (application as SeekerVaultApplication).fcmRegistrations.onRegistered(token)
    }

    override fun onUnregistered(token: String) {
        (application as SeekerVaultApplication).fcmRegistrations.onUnregistered(token)
    }
}
