package io.github.brrenat.seekervault

import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.LegacyUpdateTransport
import io.github.brrenat.seekervault.connections.PairingCode
import io.github.brrenat.seekervault.connections.softwareKey
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A CLI/bot `seekervault://pair` link enters the existing direct confirmation flow. */
@RunWith(AndroidJUnit4::class)
class DirectPairingLinkActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun useFakes() {
        app.connectionGateway = { gateway }
        app.updateTransport = { LegacyUpdateTransport() }
        app.credentialKey = softwareKey().let { key -> { key } }
        app.connectionIo = Dispatchers.Unconfined
    }

    @After fun close() = scenario?.close() ?: Unit

    @Test
    fun coldDirectLinkOpensConfirmationWithoutPairingUntilTheOwnerApproves() {
        val code = server.issue(URL)
        scenario = ActivityScenario.launch<MainActivity>(intent(code))

        compose.onNodeWithTag(ConnectionsTags.CONFIRM_SERVER).assertTextContains(URL)
        check(server.connections.isEmpty())
    }

    @Test
    fun explicitLegacyInvitationLinkOnlyExplainsRetirement() {
        val legacy = "seekervault://invite?v=1&gateway=https%3A%2F%2Fgateway.example&token=old"
        scenario =
            ActivityScenario.launch<MainActivity>(
                Intent(Intent.ACTION_VIEW, legacy.toUri()).setClass(app, MainActivity::class.java)
            )

        compose
            .onNodeWithTag(ConnectionsTags.CODE_PROBLEM)
            .assertTextEquals(app.getString(R.string.gateway_invitation_retired))
        check(server.connections.isEmpty())
        check(gateway.sent.isEmpty())
    }

    private fun intent(code: PairingCode): Intent {
        val uri =
            "seekervault://pair?v=1&url=${URLEncoder.encode(code.serverUrl, Charsets.UTF_8)}" +
                "&server=${code.serverId}&token=${code.token}"
        return Intent(Intent.ACTION_VIEW, uri.toUri()).setClass(app, MainActivity::class.java)
    }

    private companion object {
        const val URL = "https://vault.example.com"
    }
}
