package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.gateway.v1.DeviceResult
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.server.v1.ServerEnvironment
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.privateManifest
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GatewayInvitationTest {
    @get:Rule val folder = TemporaryFolder()

    private val invitations = FakeInvitationGateway()
    private val key by lazy { softwareKey() }
    private val vault by lazy {
        CredentialVault(File(folder.root, "no_backup/credentials")) { key }
    }
    private val repository by lazy {
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "files/connections")),
            vault = vault,
            results = ResultStore(File(folder.root, "files/results")),
            gateway = FakeConnectionGateway(),
            invitations = invitations,
            deviceName = "Seeker",
            now = { Instant.parse("2026-09-18T12:00:00Z") },
            io = Dispatchers.Unconfined,
        )
    }
    private val reference =
        InvitationReference(GATEWAY, "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789")

    @Test
    fun resolvingIsPreviewOnlyAndConfirmationCreatesOneEncryptedBinding() = runBlocking {
        val ready = repository.resolveInvitation(reference) as InvitationOutcome.Ready

        assertEquals(1, invitations.resolves)
        assertEquals(0, invitations.redeems)
        assertTrue(repository.connections.value.isEmpty())
        assertTrue(vault.ids().isEmpty())

        val connected = repository.redeemInvitation(ready.confirmation)
        assertEquals(1, invitations.redeems)
        assertTrue(connected.gatewayUsable)
        assertEquals(setOf(connected.id), vault.ids())
        assertEquals("0123456789abcdefghijklmnopqrstuvwxyzABCDEFG", vault.get(connected.id))
        assertFalse(
            File(folder.root, "files/connections/${connected.id}.json")
                .readText()
                .contains("0123456789abcdefghijklmnopqrstuvwxyzABCDEFG")
        )

        val again = repository.resolveInvitation(reference) as InvitationOutcome.Invalid
        assertEquals(InvitationProblem.Used, again.problem)
        assertEquals(1, invitations.redeems)

        repository.disconnect(connected.id)
        assertEquals(listOf(connected.id), invitations.revoked)
        assertTrue(repository.connections.value.isEmpty())
        assertTrue(vault.ids().isEmpty())
    }

    @Test
    fun cancellingAConfirmationWritesNothingAndARepeatedImportStartsNoSecondResolve() =
        runBlocking {
            repository.resolveInvitation(reference) as InvitationOutcome.Ready

            assertTrue(repository.connections.value.isEmpty())
            assertTrue(vault.ids().isEmpty())
            assertEquals(0, invitations.redeems)
        }

    @Test
    fun freshInvitationsAddDevicesWithoutReplacingOrJointlyRevokingThem() = runBlocking {
        val first =
            repository.redeemInvitation(
                (repository.resolveInvitation(reference) as InvitationOutcome.Ready).confirmation
            )
        invitations.issue("33333333-4444-4555-8666-777777777777")
        val second =
            repository.redeemInvitation(
                (repository.resolveInvitation(reference) as InvitationOutcome.Ready).confirmation
            )

        assertEquals(setOf(first.id, second.id), repository.connections.value.map { it.id }.toSet())
        repository.disconnect(first.id)
        assertEquals(listOf(second.id), repository.connections.value.map { it.id })
        assertEquals(listOf(first.id), invitations.revoked)
        assertEquals(setOf(second.id), vault.ids())
    }

    @Test
    fun gatewayPrivateConnectionKeepsTheManifestEnvironment() = runBlocking {
        invitations.manifest =
            privateManifest(
                name = "Sandbox trader",
                environments = listOf(ServerEnvironment.SERVER_ENVIRONMENT_SANDBOX),
            )

        val connected =
            repository.redeemInvitation(
                (repository.resolveInvitation(reference) as InvitationOutcome.Ready).confirmation
            )

        assertEquals(PluginEnvironment.Sandbox, connected.environment)
        assertEquals(
            PluginEnvironment.Sandbox,
            repository.connections.value.single().environment,
        )
    }

    private class FakeInvitationGateway : InvitationGateway {
        var resolves = 0
        var redeems = 0
        val revoked = mutableListOf<String>()
        var connectionId = "22222222-3333-4444-8555-666666666666"
        var manifest = privateManifest(name = "Trading agent")
        private var connected = false

        fun issue(id: String) {
            connectionId = id
            connected = false
        }

        override suspend fun resolve(reference: InvitationReference): ResolvedInvitation {
            resolves++
            return ResolvedInvitation(
                invitationId = "11111111-2222-4333-8444-555555555555",
                serverId = SERVER_B,
                displayName = "Trading agent",
                expiresAt = Instant.parse("2026-09-18T12:15:00Z"),
                standing =
                    if (connected) InvitationStanding.Connected else InvitationStanding.Pending,
                manifest = manifest,
            )
        }

        override suspend fun redeem(
            reference: InvitationReference,
            deviceName: String,
        ): RedeemedInvitation {
            redeems++
            connected = true
            return RedeemedInvitation(
                connectionId,
                "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG",
                SERVER_B,
                manifest,
            )
        }

        override suspend fun serverManifest(
            gatewayUrl: String,
            credential: String,
            connectionId: String,
            knownRevision: Long,
        ) = null

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
        ) {
            revoked += connectionId
        }
    }
}
