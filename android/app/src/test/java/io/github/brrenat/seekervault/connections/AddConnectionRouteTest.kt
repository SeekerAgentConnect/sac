package io.github.brrenat.seekervault.connections

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerVaultTheme
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The Add connection screen with its ViewModel, a fake sidecar, and a fake camera: permission
 * refusal and grant through the activity result registry, malformed codes, and confirmation.
 */
@RunWith(AndroidJUnit4::class)
class AddConnectionRouteTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val key = softwareKey()
    private val repository by lazy {
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "connections")),
            vault = CredentialVault(File(folder.root, "credentials")) { key },
            results = ResultStore(File(folder.root, "results")),
            gateway = gateway,
            deviceName = "Seeker",
            io = Dispatchers.Unconfined,
        )
    }
    private var feedCalls = 0
    private var feedOutcome: FeedOutcome = FeedOutcome.NoGateway
    private val viewModel by lazy {
        ConnectionsViewModel(
            repository,
            addFeed = {
                feedCalls++
                feedOutcome
            },
            cleartextPermitted = { false },
        )
    }

    /** What the permission prompt answers, and which permissions were asked for. */
    private var allowCamera = false
    private val asked = mutableListOf<Any?>()
    private val registryOwner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry =
                object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: ActivityOptionsCompat?,
                    ) {
                        asked += input
                        dispatchResult(requestCode, allowCamera)
                    }
                }
        }

    /** What the fake camera "sees" when its button is tapped. */
    private var inView = ""
    private val paired = mutableListOf<Connection>()

    private fun show(hasCamera: Boolean = true) {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, hasCamera)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                SeekerVaultTheme {
                    AddConnectionRoute(
                        viewModel = viewModel,
                        onBack = {},
                        onAdded = { paired += it },
                        scanner = { onText, _ ->
                            Button(
                                onClick = { onText(inView) },
                                modifier = Modifier.testTag(FAKE_CAMERA),
                            ) {
                                Text("camera")
                            }
                        },
                    )
                }
            }
        }
    }

    private fun text(code: PairingCode) =
        "seekervault://pair?v=1&url=${URLEncoder.encode(code.serverUrl, Charsets.UTF_8)}" +
            "&server=${code.serverId}&token=${code.token}"

    private fun feedText(
        gateway: String = GATEWAY,
        serverId: String = SERVER_B,
        version: String = "1",
    ) =
        "seekervault://feed?v=$version&gateway=${URLEncoder.encode(gateway, Charsets.UTF_8)}" +
            "&server=$serverId"

    private fun feedConnection() =
        Connection(
            id = "00000000-0000-4000-8000-000000000107",
            label = "Copy trading",
            serverUrl = GATEWAY,
            serverId = SERVER_B,
            deviceName = "",
            pairedAt = Instant.parse("2026-09-18T12:00:00Z"),
            hasCredential = false,
            mode = ConnectionMode.GatewayFeed,
            server =
                ServerRecord.Known(
                    ServerManifest(
                        serverId = SERVER_B,
                        protocolVersion = 1,
                        settingsRevision = 1,
                        mode = ConnectionMode.GatewayFeed,
                        reference = ServerReference.Feed(GATEWAY, channelFor(SERVER_B)),
                        required = listOf(PluginRequirement(PluginId("jupiter.swap"), 1..1)),
                        environments =
                            setOf(PluginEnvironment.Sandbox, PluginEnvironment.Production),
                        name = "Copy trading",
                    )
                ),
            environment = PluginEnvironment.Sandbox,
        )

    private fun enter(text: String) {
        compose.onNodeWithTag(ConnectionsTags.CODE_FIELD).performTextReplacement(text)
        compose.onNodeWithTag(ConnectionsTags.CONTINUE).performClick()
    }

    private fun problem(id: Int) =
        compose.onNodeWithTag(ConnectionsTags.CODE_PROBLEM).assertTextEquals(app.getString(id))

    @Test
    fun fallsBackToEnteringTheCodeWhenCameraAccessIsRefused() {
        show()
        compose.onNodeWithTag(ConnectionsTags.SCAN).performClick()
        assertEquals(listOf(Manifest.permission.CAMERA), asked)
        compose
            .onNodeWithTag(ConnectionsTags.CAMERA_DENIED)
            .assertTextEquals(app.getString(R.string.camera_denied))
        compose.onNodeWithTag(ConnectionsTags.OPEN_SETTINGS).assertExists()
        compose.onNodeWithTag(FAKE_CAMERA).assertDoesNotExist()
        enter(text(server.issue(URL)))
        compose.onNodeWithTag(ConnectionsTags.CONFIRM_SERVER).assertTextContains(URL)
    }

    @Test
    fun scansOnceCameraAccessIsGranted() {
        allowCamera = true
        inView = text(server.issue(URL))
        show()
        compose.onNodeWithTag(ConnectionsTags.SCAN).performClick()
        compose.onNodeWithTag(FAKE_CAMERA).performClick()
        compose.onNodeWithTag(ConnectionsTags.CONFIRM_SERVER).assertTextContains(URL)
    }

    @Test
    fun doesNotAskAgainWhenAccessWasGranted() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        show()
        compose.onNodeWithTag(ConnectionsTags.SCAN).performClick()
        compose.onNodeWithTag(FAKE_CAMERA).assertExists()
        assertEquals(emptyList<Any?>(), asked)
    }

    @Test
    fun offersOnlyTheCodeOnAPhoneWithoutACamera() {
        show(hasCamera = false)
        compose
            .onNodeWithTag(ConnectionsTags.NO_CAMERA)
            .assertTextEquals(app.getString(R.string.no_camera))
        compose.onNodeWithTag(ConnectionsTags.SCAN).assertDoesNotExist()
    }

    @Test
    fun saysWhyAScannedCodeIsNotUsableAndKeepsScanning() {
        allowCamera = true
        inView = "https://vault.example.com"
        show()
        compose.onNodeWithTag(ConnectionsTags.SCAN).performClick()
        compose.onNodeWithTag(FAKE_CAMERA).performClick()
        problem(R.string.feed_reference_not_seeker_vault)
        inView = text(server.issue(URL))
        compose.onNodeWithTag(FAKE_CAMERA).performClick()
        compose.onNodeWithTag(ConnectionsTags.CONFIRM_SERVER).assertExists()
    }

    @Test
    fun saysWhyAnEnteredCodeIsNotUsable() {
        show()
        compose.onNodeWithTag(ConnectionsTags.CONTINUE).assertIsNotEnabled()
        val code = server.issue(URL)
        enter("pair me")
        problem(R.string.feed_reference_not_a_reference)
        enter(text(code).replace("v=1", "v=9"))
        problem(R.string.code_other_version)
        enter(text(code.copy(serverUrl = "http://192.168.1.20:8080")))
        problem(R.string.code_insecure_server_url)
        enter(text(code.copy(serverId = "server")))
        problem(R.string.code_bad_server_id)
        enter(text(code).dropLast(5))
        problem(R.string.code_bad_token)
    }

    @Test
    fun givesEveryFeedReferenceProblemFeedSpecificText() {
        show()
        val cases =
            listOf(
                "not a URI" to R.string.feed_reference_not_a_reference,
                "https://example.com/feed" to R.string.feed_reference_not_seeker_vault,
                feedText(version = "2") to R.string.feed_reference_other_version,
                feedText(gateway = "$GATEWAY/path") to R.string.feed_reference_bad_gateway,
                feedText(gateway = "http://gateway.example.com") to
                    R.string.feed_reference_insecure_gateway,
                feedText(serverId = "publisher") to R.string.feed_reference_bad_server_id,
            )

        cases.forEach { (text, message) ->
            enter(text)
            problem(message)
        }
    }

    @Test
    fun confirmsThePublicFeedThenAddsItExactlyOnce() {
        val feed = feedConnection()
        feedOutcome = FeedOutcome.Added(feed)
        show()

        enter(feedText())
        compose.onNodeWithTag(ConnectionsTags.CONFIRM_FEED).assertTextContains(GATEWAY)
        compose.onNodeWithText(app.getString(R.string.feed_confirm_public)).assertExists()
        assertEquals(0, feedCalls)

        compose.onNodeWithTag(ConnectionsTags.ADD_FEED).performClick()
        compose.waitForIdle()
        assertEquals(1, feedCalls)
        assertEquals(listOf(feed), paired)
        viewModel.confirmFeed()
        assertEquals(1, feedCalls)
    }

    @Test
    fun anExistingFeedSaysNothingWasStoredAndShowsItsPromise() {
        feedOutcome = FeedOutcome.Already(feedConnection())
        show()

        enter(feedText())
        compose.onNodeWithTag(ConnectionsTags.ADD_FEED).performClick()

        compose.onNodeWithText(app.getString(R.string.feed_already_title)).assertExists()
        compose
            .onNodeWithText(app.getString(R.string.feed_already_text, "Copy trading"))
            .assertExists()
        compose.onNodeWithText("Sandbox").assertExists()
        compose.onNodeWithText("jupiter.swap").assertExists()
        assertEquals(1, feedCalls)
        assertTrue(paired.isEmpty())
    }

    @Test
    fun cancellingAFeedConfirmationStoresNothing() {
        show()
        enter(feedText())

        compose.onNodeWithTag(ConnectionsTags.CANCEL_PAIRING).performClick()

        assertEquals(0, feedCalls)
        compose
            .onNodeWithTag(ConnectionsTags.CODE_FIELD)
            .assertTextEquals(app.getString(R.string.code_label), "")
    }

    @Test
    fun pairsAfterConfirmationWithoutShowingTheToken() {
        show()
        val code = server.issue(URL)
        compose.onNodeWithTag(ConnectionsTags.CODE_FIELD).performTextInput(text(code))
        compose.onNodeWithTag(ConnectionsTags.CONTINUE).performClick()
        compose.onNodeWithTag(ConnectionsTags.CONFIRM_SERVER).assertTextContains(URL)
        compose
            .onAllNodesWithText(code.token, substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithTag(ConnectionsTags.PAIR).performClick()
        compose.waitForIdle() // the Paired state reaches onAdded on the next composition
        val connection = paired.single()
        val credential = server.connections.getValue(connection.id)
        compose
            .onAllNodes(hasText(credential, substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun explainsARefusedCodeAndOffersNoRetry() {
        show()
        enter(text(PairingCode(URL, server.serverId, newSecret())))
        compose.onNodeWithTag(ConnectionsTags.PAIR).performClick()
        compose
            .onNodeWithTag(ConnectionsTags.PAIRING_FAILURE)
            .assertTextEquals(app.getString(R.string.pair_failed_code))
        compose.onNodeWithTag(ConnectionsTags.PAIR).assertDoesNotExist()
        compose.onNodeWithTag(ConnectionsTags.CANCEL_PAIRING).performClick()
        compose
            .onNodeWithTag(ConnectionsTags.CODE_FIELD)
            .assertTextEquals(app.getString(R.string.code_label), "")
    }

    @Test
    fun notesAServerThePhoneIsAlreadyPairedWith() {
        val existing = runBlocking { repository.pair(server.issue(URL)) }
        show()
        enter(text(server.issue(URL)))
        compose
            .onNodeWithTag(ConnectionsTags.CONFIRM_NOTE)
            .assertTextEquals(app.getString(R.string.confirm_same_server, existing.label))
        compose.onNodeWithTag(ConnectionsTags.PAIR).performClick()
        compose.waitForIdle()
        assertNotNull(repository.connection(existing.id)?.revokedAt)
    }

    private companion object {
        const val URL = "https://vault.example.com"
        const val FAKE_CAMERA = "fakeCamera"
    }
}
