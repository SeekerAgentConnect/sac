package io.github.brrenat.seekervault.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.plugins.OperationId
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.proposals.Proposal
import io.github.brrenat.seekervault.proposals.ProposalKey
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStatus
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the owner is shown about a feed, and what a tap on it can do (SEE-92).
 *
 * The alert is posted after the app has read the authoritative state. Its display copy uses the
 * local source name and a human operation label, while its route carries only two opaque IDs.
 * Everything that makes a proposal stop waiting takes its alert away, and the tap route opens a
 * screen: there is no action on the notification, and nothing is prepared, signed or sent by
 * arriving anywhere.
 */
@Config(sdk = [35])
@RunWith(AndroidJUnit4::class)
class FeedNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context.applicationContext as Application
    private val platform = context.getSystemService(NotificationManager::class.java)
    private val notifications = ProposalNotificationManager(context, configured = true)

    @Before
    fun reset() {
        platform.cancelAll()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun clear() = platform.cancelAll()

    @Test
    fun createsItsOwnChannelOnlyWhenConfigured() {
        ProposalNotificationManager(context, configured = false).createChannels()
        assertNull(platform.getNotificationChannel(ProposalNotificationManager.FEEDS_CHANNEL_ID))

        notifications.createChannels()

        val channel =
            checkNotNull(
                platform.getNotificationChannel(ProposalNotificationManager.FEEDS_CHANNEL_ID)
            )
        // Its own channel, so the owner can silence one kind of alert and keep the other, and
        // quieter than a request's: a proposal is an offer to everyone subscribed.
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)
        assertTrue(
            ProposalNotificationManager.FEEDS_CHANNEL_ID !=
                RequestNotificationManager.REQUESTS_CHANNEL_ID
        )
    }

    @Test
    fun deniedPermissionPostsNothing() {
        notifications.reconcile(emptySet(), setOf(ONE), listOf(FEED), listOf(ONE_RECORD))

        assertTrue(platform.activeNotifications.isEmpty())
    }

    @Test
    fun aNewlyWaitingProposalGetsBrandedTypeSourceAndSummaryWithAnExactImmutableRoute() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.reconcile(emptySet(), setOf(ONE), listOf(FEED), listOf(ONE_RECORD))
        // The same set again posts nothing new: what is shown follows what changed.
        notifications.reconcile(setOf(ONE), setOf(ONE), listOf(FEED), listOf(ONE_RECORD))

        val active = platform.activeNotifications.single()
        val posted = active.notification
        assertEquals(Notification.VISIBILITY_SECRET, posted.visibility)
        assertEquals(R.drawable.ic_notification_sac, posted.smallIcon.resId)
        assertEquals(R.mipmap.ic_launcher, posted.getLargeIcon().resId)
        assertTrue(posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("Swap signal", posted.extras[Notification.EXTRA_TITLE])
        assertEquals("From ${FEED.label}", posted.extras[Notification.EXTRA_SUB_TEXT])
        assertEquals(
            "Review the swap terms and choose your amount.",
            posted.extras[Notification.EXTRA_TEXT],
        )
        val expanded = posted.extras.getString(Notification.EXTRA_BIG_TEXT).orEmpty()
        assertTrue(FEED.label in expanded)
        // The authoritative kind informs the words, but no ID, terms, or publisher note is copied.
        assertTrue(ONE.proposalId !in expanded)
        assertTrue(ONE.connectionId !in expanded)
        assertTrue(PRIVATE_NOTE !in expanded)

        val route = shadowOf(posted.contentIntent).savedIntent
        assertEquals(
            ProposalRef(FEED.id, ONE.proposalId),
            ProposalNotificationIntent.destination(route),
        )
        assertTrue(
            "the route is explicit and app-local",
            route.component?.className?.endsWith("MainActivity") == true,
        )
    }

    @Test
    fun knownAndFutureOperationsUseHumanLabelsRatherThanRawProtocolIdentifiers() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val future = ProposalRef(FEED.id, "11111111-2222-4333-8444-555555555554")
        val futureRecord = record(FEED, future, "future_action")

        notifications.reconcile(
            emptySet(),
            setOf(TWO, future),
            listOf(FEED),
            listOf(TWO_RECORD, futureRecord),
        )

        assertEquals(
            setOf("Prediction signal", "Signal ready for review"),
            platform.activeNotifications.mapTo(mutableSetOf()) {
                it.notification.extras[Notification.EXTRA_TITLE]
            },
        )
        assertTrue(
            platform.activeNotifications.none {
                it.notification.extras.toString().contains("future_action")
            }
        )
    }

    @Test
    fun aReferenceWithoutItsAuthoritativeProposalPostsNothing() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.reconcile(emptySet(), setOf(ONE), listOf(FEED), emptyList())

        assertTrue(platform.activeNotifications.isEmpty())
    }

    /**
     * Everything that makes a proposal stop waiting takes its alert away, and the caller's set is
     * how: a withdrawal, an expiry, a dismissal on this device and an operation already begun are
     * all reasons it is no longer reviewable (`proposals/ProposalState.proposalStanding`).
     */
    @Test
    fun aProposalThatStoppedWaitingHasItsAlertRemoved() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.reconcile(
            emptySet(),
            setOf(ONE, TWO),
            listOf(FEED),
            listOf(ONE_RECORD, TWO_RECORD),
        )
        assertEquals(2, platform.activeNotifications.size)

        notifications.reconcile(
            setOf(ONE, TWO),
            setOf(TWO),
            listOf(FEED),
            listOf(TWO_RECORD),
        )

        val active = platform.activeNotifications.single()
        assertEquals(
            ProposalRef(FEED.id, TWO.proposalId),
            ProposalNotificationIntent.destination(
                shadowOf(active.notification.contentIntent).savedIntent
            ),
        )
    }

    /** A feed that is gone takes its alerts with it, and only its own. */
    @Test
    fun removingAFeedCancelsItsAlertsAndNoOthers() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.reconcile(
            emptySet(),
            setOf(ONE, OTHER),
            listOf(FEED, OTHER_FEED),
            listOf(ONE_RECORD, OTHER_RECORD),
        )
        assertEquals(2, platform.activeNotifications.size)

        notifications.cancelConnection(FEED.id)

        assertEquals(1, platform.activeNotifications.size)
    }

    /** A proposal on a connection this phone no longer holds is not announced at all. */
    @Test
    fun aProposalWithNoFeedBehindItIsNotAnnounced() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.reconcile(emptySet(), setOf(ONE), emptyList(), listOf(ONE_RECORD))

        assertTrue(platform.activeNotifications.isEmpty())
    }

    /** And neither is one whose connection is not a feed: that is the other manager's business. */
    @Test
    fun aDirectConnectionIsNotAFeed() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.reconcile(
            emptySet(),
            setOf(ProposalRef(DIRECT.id, ONE.proposalId)),
            listOf(DIRECT),
            listOf(record(DIRECT, ProposalRef(DIRECT.id, ONE.proposalId), "swap")),
        )

        assertTrue(platform.activeNotifications.isEmpty())
    }

    /** An identifier that is not one never becomes a route, however it arrived. */
    @Test
    fun aForgedRouteIsNotADestination() {
        assertNull(ProposalNotificationIntent.destination(null))
        assertNull(ProposalNotificationIntent.destination(Intent()))
        val real = ProposalNotificationIntent.intent(context, ONE)
        assertEquals(ONE, ProposalNotificationIntent.destination(real))
        // The action is what identifies the route, and the IDs are validated after it.
        assertNull(
            ProposalNotificationIntent.destination(
                Intent(real).setAction("android.intent.action.VIEW")
            )
        )
        assertNull(
            ProposalNotificationIntent.destination(
                Intent(real).putExtra("notification_proposal_id", "../../etc/passwd")
            )
        )
        // A request alert's route is not a proposal's, in either direction.
        assertNull(
            ProposalNotificationIntent.destination(
                RequestNotificationIntent.intent(
                    context,
                    io.github.brrenat.seekervault.connections.RequestKey(FEED.id, ONE.proposalId),
                )
            )
        )
        assertNull(RequestNotificationIntent.destination(real))
    }

    private companion object {
        const val GATEWAY = "https://feeds.example.com"
        const val SERVER_A = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val SERVER_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
        const val PRIVATE_NOTE = "publisher-only description"

        // A feed's connection ID is one this phone minted at the moment it was added — a UUID,
        // like every other connection's, which is what the tap route validates before using it.
        val FEED = feed(SERVER_A, "aaaaaaaa-1111-4222-8333-444444444444")
        val OTHER_FEED = feed(SERVER_B, "bbbbbbbb-1111-4222-8333-444444444444")
        val ONE = ProposalRef(FEED.id, "11111111-2222-4333-8444-555555555551")
        val TWO = ProposalRef(FEED.id, "11111111-2222-4333-8444-555555555552")
        val OTHER = ProposalRef(OTHER_FEED.id, "11111111-2222-4333-8444-555555555553")
        val ONE_RECORD = record(FEED, ONE, "swap")
        val TWO_RECORD = record(FEED, TWO, "prediction")
        val OTHER_RECORD = record(OTHER_FEED, OTHER, "swap")
        val DIRECT =
            Connection(
                id = "00000000-0000-4000-8000-00000000000d",
                label = "My sidecar",
                serverUrl = "https://sidecar.example.com",
                serverId = "00000000-0000-4000-8000-00000000000d",
                deviceName = "Seeker",
                pairedAt = Instant.parse("2026-09-17T09:00:00Z"),
            )

        fun record(connection: Connection, ref: ProposalRef, operation: String) =
            ProposalRecord(
                connectionId = ref.connectionId,
                proposal =
                    Proposal(
                        key =
                            ProposalKey(
                                connection.serverId,
                                channelFor(connection.serverId),
                                ref.proposalId,
                            ),
                        revision = 1,
                        operation = OperationId(operation),
                        plugin = PluginId("test.plugin"),
                        status = ProposalStatus.Open,
                        createdAt = Instant.parse("2026-09-17T09:00:00Z"),
                        updatedAt = Instant.parse("2026-09-17T09:00:00Z"),
                        expiresAt = Instant.parse("2026-09-18T09:00:00Z"),
                        note = PRIVATE_NOTE,
                    ),
            )

        fun feed(serverId: String, id: String) =
            Connection(
                id = id,
                label = "Copy trading",
                serverUrl = GATEWAY,
                serverId = serverId,
                deviceName = "",
                pairedAt = Instant.parse("2026-09-17T09:00:00Z"),
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server =
                    ServerRecord.Known(
                        ServerManifest(
                            serverId = serverId,
                            protocolVersion = 1,
                            settingsRevision = 1,
                            mode = ConnectionMode.GatewayFeed,
                            reference =
                                ServerReference.Feed(
                                    gatewayUrl = GATEWAY,
                                    channel = channelFor(serverId),
                                ),
                            environments = setOf(PluginEnvironment.Production),
                        )
                    ),
            )
    }
}
