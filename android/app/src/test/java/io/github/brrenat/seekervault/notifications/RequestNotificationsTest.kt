package io.github.brrenat.seekervault.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.request.v1.ActionKt
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.ackAction
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.signMessageAction
import io.github.brrenat.seekervault.request.v1.swapAction
import io.github.brrenat.seekervault.request.v1.transferAction
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
        notifications.reconcile(emptySet(), setOf(KEY), listOf(CONNECTION), listOf(REQUEST))

        assertTrue(platform.activeNotifications.isEmpty())
    }

    @Test
    fun aNewRequestGetsBrandedTypeSourceAndSummaryWithAnExactImmutableTapDestination() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.reconcile(emptySet(), setOf(KEY), listOf(CONNECTION), listOf(REQUEST))
        notifications.reconcile(setOf(KEY), setOf(KEY), listOf(CONNECTION), listOf(REQUEST))

        val active = platform.activeNotifications.single()
        val posted = active.notification
        assertEquals(Notification.VISIBILITY_SECRET, posted.visibility)
        assertEquals(R.drawable.ic_notification_sac, posted.smallIcon.resId)
        assertEquals(R.mipmap.ic_launcher, posted.getLargeIcon().resId)
        assertEquals(context.getColor(R.color.notification_accent), posted.color)
        assertTrue(posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(
            "Acknowledgement requested",
            posted.extras[Notification.EXTRA_TITLE],
        )
        assertEquals(
            "From Home sidecar",
            posted.extras[Notification.EXTRA_SUB_TEXT],
        )
        assertEquals(
            "A message is ready for your review.",
            posted.extras[Notification.EXTRA_TEXT],
        )
        assertEquals(
            "From Home sidecar\n\nA message is ready for your review.",
            posted.extras[Notification.EXTRA_BIG_TEXT],
        )
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertEquals(KEY, RequestNotificationIntent.destination(intent))
        assertTrue(intent.component?.className?.endsWith("MainActivity") == true)
        assertTrue(posted.contentIntent.isImmutable)
        // Type is read from the authoritative request, but its private text is not display copy.
        assertFalse(posted.extras.toString().contains(PRIVATE_REQUEST_TEXT))
    }

    @Test
    fun everyRequestKindUsesHumanWordsAndNeverARawEnumName() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val requests =
            listOf(
                REQUEST,
                request(SIGNATURE_ID) { signMessage = signMessageAction { text = "hello" } },
                request(TRANSFER_ID) { transfer = transferAction {} },
                request(SWAP_ID) { swap = swapAction {} },
                request(UNKNOWN_ID) {},
            )
        val keys = requests.mapTo(mutableSetOf()) { RequestKey(CONNECTION_ID, it.ref.requestId) }

        notifications.reconcile(emptySet(), keys, listOf(CONNECTION), requests)

        assertEquals(
            setOf(
                "Acknowledgement requested",
                "Signature requested",
                "Transfer requested",
                "Swap requested",
                "Review requested",
            ),
            platform.activeNotifications.mapTo(mutableSetOf()) {
                it.notification.extras[Notification.EXTRA_TITLE]
            },
        )
        assertTrue(
            platform.activeNotifications.none {
                it.notification.extras.toString().contains("KIND_NOT_SET")
            }
        )
    }

    @Test
    fun nativeExpandedStyleKeepsALongSourceNameAndNightUsesTheApprovedReadableAccent() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val longName = "studio-" + "m".repeat(57)

        notifications.reconcile(
            emptySet(),
            setOf(KEY),
            listOf(CONNECTION.copy(label = longName)),
            listOf(REQUEST),
        )

        val posted = platform.activeNotifications.single().notification
        assertEquals("From $longName", posted.extras[Notification.EXTRA_SUB_TEXT])
        assertTrue(posted.extras[Notification.EXTRA_BIG_TEXT].toString().contains(longName))
        val night =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    uiMode =
                        (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            Configuration.UI_MODE_NIGHT_YES
                }
            )
        assertEquals(0xFFE7FC6E.toInt(), night.getColor(R.color.notification_accent))
    }

    @Test
    fun missingAuthoritativeDataPostsNothingAndABlankStoredNameHasAHonestFallback() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.reconcile(emptySet(), setOf(KEY), listOf(CONNECTION), emptyList())
        assertTrue(platform.activeNotifications.isEmpty())

        notifications.reconcile(
            emptySet(),
            setOf(KEY),
            listOf(CONNECTION.copy(label = "  ")),
            listOf(REQUEST),
        )
        assertEquals(
            "From Unnamed connection",
            platform.activeNotifications.single().notification.extras[Notification.EXTRA_SUB_TEXT],
        )
    }

    @Test
    fun distinctRequestsDoNotReplaceOneAnothersTapAndLeavingPendingCancelsOnlyThatAlert() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val other = RequestKey(CONNECTION_ID, OTHER_REQUEST_ID)
        val otherRequest = request(OTHER_REQUEST_ID) { ack = ackAction { text = "Other" } }

        notifications.reconcile(
            emptySet(),
            setOf(KEY, other),
            listOf(CONNECTION),
            listOf(REQUEST, otherRequest),
        )
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

        notifications.reconcile(
            setOf(KEY, other),
            setOf(other),
            listOf(CONNECTION),
            listOf(otherRequest),
        )
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
        val otherRequest =
            request(OTHER_REQUEST_ID, OTHER_CONNECTION_ID) {
                ack = ackAction { text = "Other" }
            }
        notifications.reconcile(
            emptySet(),
            setOf(KEY, otherKey),
            listOf(CONNECTION, otherConnection),
            listOf(REQUEST, otherRequest),
        )
        assertEquals(
            mapOf(
                KEY to "From Home sidecar",
                otherKey to "From Other sidecar",
            ),
            platform.activeNotifications.associate {
                checkNotNull(
                    RequestNotificationIntent.destination(
                        shadowOf(it.notification.contentIntent).savedIntent
                    )
                ) to it.notification.extras[Notification.EXTRA_SUB_TEXT]
            },
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
            listOf(REQUEST),
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
        const val SIGNATURE_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        const val TRANSFER_ID = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        const val SWAP_ID = "11111111-1111-4111-8111-111111111111"
        const val UNKNOWN_ID = "22222222-2222-4222-8222-222222222222"
        const val PRIVATE_REQUEST_TEXT = "sign these secret bytes"
        val KEY = RequestKey(CONNECTION_ID, REQUEST_ID)
        val REQUEST = request(REQUEST_ID) { ack = ackAction { text = PRIVATE_REQUEST_TEXT } }
        val CONNECTION =
            Connection(
                id = CONNECTION_ID,
                label = "Home sidecar",
                serverUrl = "https://sidecar.example",
                serverId = "server",
                deviceName = "Seeker",
                pairedAt = Instant.parse("2026-09-14T12:00:00Z"),
            )

        fun request(
            requestId: String,
            connectionId: String = CONNECTION_ID,
            build: ActionKt.Dsl.() -> Unit,
        ): ActionRequest = actionRequest {
            ref = requestRef {
                this.connectionId = connectionId
                this.requestId = requestId
            }
            action = action(build)
            state = RequestState.REQUEST_STATE_PENDING
        }
    }
}
