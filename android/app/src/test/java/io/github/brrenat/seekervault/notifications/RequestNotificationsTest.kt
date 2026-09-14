package io.github.brrenat.seekervault.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.RequestKey
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [35])
@RunWith(AndroidJUnit4::class)
class RequestNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context.applicationContext as Application
    private val platform = context.getSystemService(NotificationManager::class.java)
    private val notifications = RequestNotificationManager(context, configured = true)

    @Before
    fun reset() {
        platform.cancelAll()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun clear() = platform.cancelAll()

    @Test
    fun createsAHighImportancePrivateRequestChannelOnlyWhenConfigured() {
        RequestNotificationManager(context, configured = false).createChannels()
        assertNull(platform.getNotificationChannel(RequestNotificationManager.REQUESTS_CHANNEL_ID))

        notifications.createChannels()

        val channel =
            checkNotNull(
                platform.getNotificationChannel(RequestNotificationManager.REQUESTS_CHANNEL_ID)
            )
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)
    }

    @Test
    fun deniedPermissionPostsNothingAndDoesNotChangeReconciliation() {
        notifications.reconcile(emptySet(), setOf(KEY), listOf(CONNECTION))

        assertTrue(platform.activeNotifications.isEmpty())
    }

    @Test
    fun aNewRequestGetsOneGenericAlertWithAnExactImmutableTapDestination() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.reconcile(emptySet(), setOf(KEY), listOf(CONNECTION))
        notifications.reconcile(setOf(KEY), setOf(KEY), listOf(CONNECTION))

        val active = platform.activeNotifications.single()
        assertEquals(Notification.VISIBILITY_SECRET, active.notification.visibility)
        assertEquals(
            "Request waiting for review",
            active.notification.extras[Notification.EXTRA_TITLE],
        )
        assertEquals(
            "Open the current request from Home sidecar.",
            active.notification.extras[Notification.EXTRA_TEXT],
        )
        val intent = shadowOf(active.notification.contentIntent).savedIntent
        assertEquals(KEY, RequestNotificationIntent.destination(intent))
        assertTrue(intent.component?.className?.endsWith("MainActivity") == true)
        assertTrue(active.notification.contentIntent.isImmutable)
        // Request content was never an input to this component and is absent from the alert.
        assertFalse(active.notification.toString().contains("sign these secret bytes"))
    }

    @Test
    fun distinctRequestsDoNotReplaceOneAnothersTapAndLeavingPendingCancelsOnlyThatAlert() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val other = RequestKey(CONNECTION_ID, OTHER_REQUEST_ID)

        notifications.reconcile(emptySet(), setOf(KEY, other), listOf(CONNECTION))
        assertEquals(
            setOf(KEY, other),
            platform.activeNotifications.mapTo(mutableSetOf()) {
                checkNotNull(
                    RequestNotificationIntent.destination(
                        shadowOf(it.notification.contentIntent).savedIntent
                    )
                )
            },
        )

        notifications.reconcile(setOf(KEY, other), setOf(other), listOf(CONNECTION))
        assertEquals(
            other,
            RequestNotificationIntent.destination(
                shadowOf(platform.activeNotifications.single().notification.contentIntent)
                    .savedIntent
            ),
        )
    }

    @Test
    fun removingAConnectionCancelsOnlyItsPostedRequestAlerts() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val otherKey = RequestKey(OTHER_CONNECTION_ID, OTHER_REQUEST_ID)
        val otherConnection = CONNECTION.copy(id = OTHER_CONNECTION_ID, label = "Other sidecar")
        notifications.reconcile(
            emptySet(),
            setOf(KEY, otherKey),
            listOf(CONNECTION, otherConnection),
        )

        notifications.cancelConnection(CONNECTION_ID)

        assertEquals(
            otherKey,
            RequestNotificationIntent.destination(
                shadowOf(platform.activeNotifications.single().notification.contentIntent)
                    .savedIntent
            ),
        )
    }

    @Test
    fun anUnusableConnectionCannotReceiveANewAlert() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.reconcile(
            emptySet(),
            setOf(KEY),
            listOf(CONNECTION.copy(revokedAt = Instant.parse("2026-09-14T12:01:00Z"))),
        )

        assertTrue(platform.activeNotifications.isEmpty())
    }

    @Test
    fun rejectsAnyNonNotificationActionOrMalformedOpaqueId() {
        assertNull(RequestNotificationIntent.destination(null))
        assertNull(RequestNotificationIntent.destination(Intent()))
        assertNull(
            RequestNotificationIntent.destination(
                RequestNotificationIntent.intent(context, KEY)
                    .putExtra(
                        "notification_request_id",
                        "not-a-request-id",
                    )
            )
        )
    }

    @Test
    fun permissionIsAskedOnlyForAConfiguredUsableInstallationBeforeTheOwnerDecides() {
        assertTrue(shouldRequestNotificationPermission(true, false, false, false))
        assertFalse(shouldRequestNotificationPermission(false, false, false, false))
        assertFalse(shouldRequestNotificationPermission(true, true, false, false))
        assertFalse(shouldRequestNotificationPermission(true, false, true, false))
        assertFalse(shouldRequestNotificationPermission(true, false, false, true))
    }

    private companion object {
        const val CONNECTION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val REQUEST_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val OTHER_REQUEST_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val OTHER_CONNECTION_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val KEY = RequestKey(CONNECTION_ID, REQUEST_ID)
        val CONNECTION =
            Connection(
                id = CONNECTION_ID,
                label = "Home sidecar",
                serverUrl = "https://sidecar.example",
                serverId = "server",
                deviceName = "Seeker",
                pairedAt = Instant.parse("2026-09-14T12:00:00Z"),
            )
    }
}
