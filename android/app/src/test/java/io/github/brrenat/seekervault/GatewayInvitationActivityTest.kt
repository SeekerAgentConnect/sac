package io.github.brrenat.seekervault

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.GatewayPrivatePage
import io.github.brrenat.seekervault.connections.InvitationGateway
import io.github.brrenat.seekervault.connections.InvitationReference
import io.github.brrenat.seekervault.connections.InvitationStanding
import io.github.brrenat.seekervault.connections.LegacyUpdateTransport
import io.github.brrenat.seekervault.connections.RedeemedInvitation
import io.github.brrenat.seekervault.connections.ResolvedInvitation
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.gateway.v1.DeviceResult
import io.github.brrenat.seekervault.server.v1.ServerEnvironment
import io.github.brrenat.seekervault.server.v1.ServerManifest
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.privateManifest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold/warm intent routing for the same invitation flow, including duplicate Android delivery. */
@RunWith(AndroidJUnit4::class)
class GatewayInvitationActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
    private val invitations = IntentInvitationGateway()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun useFakes() {
        app.connectionGateway = { FakeConnectionGateway() }
        app.invitationGateway = { invitations }
        app.updateTransport = { LegacyUpdateTransport() }
        app.credentialKey = softwareKey().let { key -> { key } }
        app.connectionIo = Dispatchers.Unconfined
    }

    @After fun close() = scenario?.close() ?: Unit

    @Test
    fun coldLinkAndItsWarmDuplicateStartOnlyOnePreview() {
        scenario = ActivityScenario.launch<MainActivity>(intent())

        compose.waitUntil(5_000) { invitations.resolves == 1 }
        compose.waitForIdle()
        scenario?.onActivity { activity ->
            // ActivityScenario does not dispatch warm intents under Robolectric. Invoke Android's
            // callback itself so this still covers the same path without creating another task.
            val callback =
                MainActivity::class
                    .java
                    .getDeclaredMethod("onNewIntent", Intent::class.java)
                    .apply {
                        isAccessible = true
                    }
            callback.invoke(activity, intent())
        }

        compose.waitForIdle()
        assertEquals(1, invitations.resolves)
        assertEquals(0, invitations.redeems)
    }

    private fun intent() =
        Intent(Intent.ACTION_VIEW, URI.toUri()).setClass(app, MainActivity::class.java)

    private class IntentInvitationGateway : InvitationGateway {
        var resolves = 0
        var redeems = 0
        private val manifest =
            privateManifest(
                name = "Trading agent",
                environments = listOf(ServerEnvironment.SERVER_ENVIRONMENT_SANDBOX),
            )

        override suspend fun resolve(reference: InvitationReference): ResolvedInvitation {
            resolves++
            return ResolvedInvitation(
                invitationId = "11111111-2222-4333-8444-555555555555",
                serverId = SERVER_B,
                displayName = "Trading agent",
                expiresAt = Instant.parse("2026-09-18T12:15:00Z"),
                standing = InvitationStanding.Pending,
                manifest = manifest,
            )
        }

        override suspend fun redeem(
            reference: InvitationReference,
            deviceName: String,
        ): RedeemedInvitation {
            redeems++
            error("cancellation must not redeem")
        }

        override suspend fun serverManifest(
            gatewayUrl: String,
            credential: String,
            connectionId: String,
            knownRevision: Long,
        ): ServerManifest? = error("not used")

        override suspend fun listRequests(
            gatewayUrl: String,
            credential: String,
            connectionId: String,
            pageToken: String,
            knownSequence: Long,
        ) = GatewayPrivatePage(emptyList(), "", knownSequence, true)

        override suspend fun submitResult(
            gatewayUrl: String,
            credential: String,
            connectionId: String,
            result: DeviceResult,
        ) = Unit

        override suspend fun revoke(
            gatewayUrl: String,
            credential: String,
            connectionId: String,
        ) = Unit
    }

    private companion object {
        const val TOKEN = "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789"
        const val URI =
            "seekervault://invite?v=1&gateway=https%3A%2F%2Fgateway.example.com&token=$TOKEN"
    }
}
