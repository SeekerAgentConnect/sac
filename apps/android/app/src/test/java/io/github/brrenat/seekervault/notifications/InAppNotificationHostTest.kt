package io.github.brrenat.seekervault.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.designsystem.InAppNotificationTag
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.PendingItem
import io.github.brrenat.seekervault.plugins.ExecutionProviderId
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.actionOf
import io.github.brrenat.seekervault.proposals.Proposal
import io.github.brrenat.seekervault.proposals.ProposalKey
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStatus
import io.github.brrenat.seekervault.request.v1.ActionKt
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class InAppNotificationHostTest {
    @get:Rule val compose = createComposeRule()

    private val owner = FakeLifecycleOwner()
    private var ready by mutableStateOf(true)
    private var waiting by mutableStateOf(emptyList<PendingItem>())
    private var connections by mutableStateOf(listOf(DIRECT, FEED))
    private var reviewOpen by mutableStateOf(emptySet<ReviewIdentity>())
    private var opened: InAppNotificationTarget? = null
    private var underneathClicked = false
    private var root: android.view.View? = null

    private fun host() {
        compose.setContent {
            root = LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SeekerTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.fillMaxSize().testTag(UNDERNEATH).clickable {
                                underneathClicked = true
                            }
                        )
                        InAppNotifications(
                            ready = ready,
                            connections = connections,
                            waiting = waiting,
                            reviewOpen = { it in reviewOpen },
                            onOpen = { opened = it },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a request that arrives says what its system notification would and opens its review`() {
        host()
        compose.onNodeWithTag(InAppNotificationTag).assertDoesNotExist()

        waiting = listOf(PendingItem.Private(TRANSFER))
        compose.waitForIdle()

        compose
            .onNodeWithTag(InAppNotificationTag)
            .assertContentDescriptionEquals("Transfer requested, open request")
        compose.onNodeWithText("Transfer requested", useUnmergedTree = true).assertExists()
        compose
            .onNodeWithText(
                "New request · Home sidecar · Review the amount and recipient before deciding " +
                    "whether to send.",
                useUnmergedTree = true,
            )
            .assertExists()

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { click() }
        compose.waitForIdle()

        assertEquals(
            InAppNotificationTarget.Review(ReviewIdentity.Private(DIRECT.id, TRANSFER_ID)),
            opened,
        )
    }

    @Test
    fun `the banner clears the status bar and the cutout, and floats a gap below them`() {
        host()
        waiting = listOf(PendingItem.Private(TRANSFER))
        compose.waitForIdle()

        // A 24dp status bar with a deeper 30dp cutout, at this configuration's 3x density: the
        // banner has to clear whichever reaches further, then leave its gap (SEE-150).
        //
        // Dispatched through the platform view, and only once the banner is on screen. Compose
        // installs its insets listener when something first *reads* insets, so a dispatch made
        // while the queue was empty would reach nothing and leave the banner measuring against
        // zero — which is what the other test asserts, so it would have passed for the wrong
        // reason.
        compose.runOnUiThread {
            checkNotNull(root)
                .dispatchApplyWindowInsets(
                    android.view.WindowInsets.Builder()
                        .setInsets(
                            android.view.WindowInsets.Type.statusBars(),
                            android.graphics.Insets.of(0, 72, 0, 0),
                        )
                        .setInsets(
                            android.view.WindowInsets.Type.displayCutout(),
                            android.graphics.Insets.of(0, 90, 0, 0),
                        )
                        .build()
                )
        }
        compose.waitForIdle()

        compose.onNodeWithTag(InAppNotificationTag).assertTopPositionInRootIsEqualTo(42.dp)
        compose.onNodeWithTag(InAppNotificationTag).assertLeftPositionInRootIsEqualTo(8.dp)
    }

    @Test
    fun `without any system bar the banner still keeps its gap from the top edge`() {
        host()

        waiting = listOf(PendingItem.Private(TRANSFER))
        compose.waitForIdle()

        compose.onNodeWithTag(InAppNotificationTag).assertTopPositionInRootIsEqualTo(12.dp)
    }

    @Test
    fun `a signal gets the signal words and opens the signal review`() {
        host()

        waiting = listOf(PendingItem.Signal(PREDICTION))
        compose.waitForIdle()

        compose
            .onNodeWithTag(InAppNotificationTag)
            .assertContentDescriptionEquals("Prediction signal, open signal")
        compose
            .onNodeWithText(
                "New signal · Copy trading · Review the market before choosing a side and amount.",
                useUnmergedTree = true,
            )
            .assertExists()

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { click() }
        compose.waitForIdle()

        assertEquals(
            InAppNotificationTarget.Review(ReviewIdentity.Signal(FEED.id, PROPOSAL_ID)),
            opened,
        )
    }

    @Test
    fun `a server that ended the pairing opens Add connection`() {
        host()

        connections = listOf(DIRECT.copy(revokedAt = Instant.parse("2026-09-20T10:00:00Z")), FEED)
        compose.waitForIdle()

        compose
            .onNodeWithTag(InAppNotificationTag)
            .assertContentDescriptionEquals("Home sidecar disconnected, open")
        compose.onNodeWithText("Home sidecar disconnected", useUnmergedTree = true).assertExists()

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { click() }
        compose.waitForIdle()

        assertEquals(InAppNotificationTarget.PairAgain(DIRECT.id), opened)
    }

    @Test
    fun `a request whose review is already open raises no banner`() {
        reviewOpen = setOf(ReviewIdentity.Private(DIRECT.id, TRANSFER_ID))
        host()

        waiting = listOf(PendingItem.Private(TRANSFER))
        compose.waitForIdle()

        compose.onNodeWithTag(InAppNotificationTag).assertDoesNotExist()
    }

    @Test
    fun `a touch on the banner is the banner's and never reaches what is underneath`() {
        host()
        waiting = listOf(PendingItem.Private(TRANSFER))
        compose.waitForIdle()

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { click() }
        compose.waitForIdle()

        assertFalse(underneathClicked)

        // And the content is still reachable everywhere the banner is not.
        compose.onNodeWithTag(UNDERNEATH).performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(true, underneathClicked)
    }

    @Test
    fun `TalkBack announces the arrival politely and offers Dismiss instead of the swipe`() {
        host()
        waiting = listOf(PendingItem.Private(TRANSFER))
        compose.waitForIdle()

        val node = compose.onNodeWithTag(InAppNotificationTag).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
        val dismiss = node.config[SemanticsActions.CustomActions].single { it.label == "Dismiss" }

        compose.runOnUiThread { dismiss.action() }
        compose.mainClock.advanceTimeBy(EXIT_AND_A_FRAME)
        compose.waitForIdle()

        compose.onNodeWithTag(InAppNotificationTag).assertDoesNotExist()
        assertNull(opened)
    }

    @Test
    fun `what arrived while the app was away is not replayed as banners when it returns`() {
        host()
        waiting = listOf(PendingItem.Private(TRANSFER))
        compose.waitForIdle()
        compose.onNodeWithTag(InAppNotificationTag).assertExists()

        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        // A banner belongs to an app being looked at; leaving takes it with it.
        compose.onNodeWithTag(InAppNotificationTag).assertDoesNotExist()

        // Two more arrive while the app is away. The system notification's job, not a banner's.
        waiting = listOf(PendingItem.Private(TRANSFER), PendingItem.Signal(PREDICTION))
        compose.waitForIdle()

        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithTag(InAppNotificationTag).assertDoesNotExist()

        // What arrives after the owner is back is a banner again.
        waiting =
            listOf(
                PendingItem.Private(TRANSFER),
                PendingItem.Signal(PREDICTION),
                PendingItem.Private(SIGNATURE),
            )
        compose.waitForIdle()
        compose.onNodeWithTag(InAppNotificationTag).assertExists()
    }

    @Test
    fun `what the opening fetch and the stored proposals bring back is the baseline, not arrivals`() {
        // A cold start: the stored connections have been read, but the fetch that follows them
        // has not settled and the stored proposals have not been read, so nothing a banner reads
        // is an answer yet.
        ready = false
        host()

        // The fetch comes back with the inbox the owner has been carrying, and the proposal store
        // is read. Neither is news; both belong to the baseline.
        waiting = listOf(PendingItem.Private(TRANSFER), PendingItem.Signal(PREDICTION))
        compose.waitForIdle()
        compose.onNodeWithTag(InAppNotificationTag).assertDoesNotExist()

        ready = true
        compose.waitForIdle()
        compose.onNodeWithTag(InAppNotificationTag).assertDoesNotExist()

        // What arrives after the opening is a banner, as it always was.
        waiting =
            listOf(
                PendingItem.Private(TRANSFER),
                PendingItem.Signal(PREDICTION),
                PendingItem.Private(SIGNATURE),
            )
        compose.waitForIdle()
        compose
            .onNodeWithTag(InAppNotificationTag)
            .assertContentDescriptionEquals("Transfer requested, open request")
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle
            get() = registry
    }

    private companion object {
        const val UNDERNEATH = "underneath"
        const val EXIT_AND_A_FRAME = 400L
        const val CONNECTION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val TRANSFER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SIGNATURE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val FEED_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val SERVER_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        const val PROPOSAL_ID = "11111111-2222-4333-8444-555555555551"
        const val GATEWAY = "https://feeds.example.com"

        val DIRECT =
            Connection(
                id = CONNECTION_ID,
                label = "Home sidecar",
                serverUrl = "https://sidecar.example",
                serverId = "server",
                deviceName = "Seeker",
                pairedAt = Instant.parse("2026-09-20T09:00:00Z"),
            )

        val FEED =
            Connection(
                id = FEED_ID,
                label = "Copy trading",
                serverUrl = GATEWAY,
                serverId = SERVER_ID,
                deviceName = "",
                pairedAt = Instant.parse("2026-09-20T09:00:00Z"),
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server =
                    ServerRecord.Known(
                        ServerManifest(
                            serverId = SERVER_ID,
                            protocolVersion = 1,
                            settingsRevision = 1,
                            mode = ConnectionMode.GatewayFeed,
                            reference =
                                ServerReference.Feed(
                                    gatewayUrl = GATEWAY,
                                    channel = channelFor(SERVER_ID),
                                ),
                            environments = setOf(PluginEnvironment.Production),
                        )
                    ),
            )

        val TRANSFER = request(TRANSFER_ID) { transfer = transferAction {} }
        val SIGNATURE = request(SIGNATURE_ID) { transfer = transferAction {} }

        val PREDICTION =
            ProposalRecord(
                connectionId = FEED_ID,
                proposal =
                    Proposal(
                        key = ProposalKey(SERVER_ID, channelFor(SERVER_ID), PROPOSAL_ID),
                        revision = 1,
                        // Through the same normalization production uses, so the copy
                        // asserted here is the copy the notifier would be handed (SEE-145).
                        action = checkNotNull(actionOf("prediction")),
                        provider = ExecutionProviderId("test"),
                        plugin = PluginId("test.plugin"),
                        status = ProposalStatus.Open,
                        createdAt = Instant.parse("2026-09-20T09:00:00Z"),
                        updatedAt = Instant.parse("2026-09-20T09:00:00Z"),
                        expiresAt = Instant.parse("2026-09-21T09:00:00Z"),
                        note = "publisher-only description",
                    ),
            )

        fun request(requestId: String, build: ActionKt.Dsl.() -> Unit): ActionRequest =
            actionRequest {
                ref = requestRef {
                    this.connectionId = CONNECTION_ID
                    this.requestId = requestId
                }
                action = action(build)
                state = RequestState.REQUEST_STATE_PENDING
            }
    }
}
