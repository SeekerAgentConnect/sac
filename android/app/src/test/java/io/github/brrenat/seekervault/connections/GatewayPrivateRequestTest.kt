package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.Timestamp
import io.github.brrenat.seekervault.connections.storage.ProposalStore
import io.github.brrenat.seekervault.gateway.v1.DeviceResult
import io.github.brrenat.seekervault.gateway.v1.DeviceResultStatus
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.PluginRegistry
import io.github.brrenat.seekervault.plugins.TestPlugin
import io.github.brrenat.seekervault.proposals.PROPOSAL_A
import io.github.brrenat.seekervault.proposals.SWAP
import io.github.brrenat.seekervault.request.v2.ActionCapability
import io.github.brrenat.seekervault.request.v2.Audience
import io.github.brrenat.seekervault.request.v2.Presentation
import io.github.brrenat.seekervault.request.v2.PresentationCategory
import io.github.brrenat.seekervault.request.v2.PrivateAudience
import io.github.brrenat.seekervault.request.v2.Request
import io.github.brrenat.seekervault.request.v2.RequestIdentity
import io.github.brrenat.seekervault.request.v2.RequestLifecycle
import io.github.brrenat.seekervault.request.v2.RequestStatus
import io.github.brrenat.seekervault.request.v2.ResultHandling
import io.github.brrenat.seekervault.request.v2.ResultMode
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.SWAP_PLUGIN
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GatewayPrivateRequestTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakePrivateGateway()
    private val repository by lazy {
        ProposalRepository(
            store = ProposalStore(File(folder.root, "proposals")),
            connections = { listOf(connection) },
            plugins = PluginRegistry.of(TestPlugin(id = SWAP_PLUGIN)),
            privateGateway = gateway,
            credential = { DEVICE_TOKEN },
            now = { NOW },
            io = Dispatchers.Unconfined,
        )
    }

    @Test
    fun privateRequestsUseTheSharedReviewStoreAndReturnOnlyTheirOutcome() = runBlocking {
        gateway.requests = listOf(request())

        assertEquals(FeedRefresh.Read(1, emptyList(), 7), repository.refresh(CONNECTION))
        val held = repository.proposalsFor(CONNECTION).single()
        assertEquals("private/$SERVER_B", held.key.channel)
        assertTrue(gateway.results.isEmpty())

        repository.dismiss(CONNECTION, PROPOSAL_A)

        val result = gateway.results.single()
        assertEquals(PROPOSAL_A, result.requestId)
        assertEquals(1, result.requestRevision)
        assertEquals(DeviceResultStatus.DEVICE_RESULT_STATUS_REJECTED, result.status)
        assertEquals(listOf(DEVICE_TOKEN, DEVICE_TOKEN), gateway.credentials)
    }

    @Test
    fun anOfflineResultUploadRetriesFromTheDurableRecordWithoutAnotherDecision() = runBlocking {
        gateway.requests = listOf(request())
        gateway.failuresRemaining = 1
        repository.refresh(CONNECTION)

        repository.dismiss(CONNECTION, PROPOSAL_A)
        assertEquals(1, gateway.attempts)
        assertTrue(gateway.results.isEmpty())

        ProposalRepository(
                store = ProposalStore(File(folder.root, "proposals")),
                connections = { listOf(connection) },
                plugins = PluginRegistry.of(TestPlugin(id = SWAP_PLUGIN)),
                privateGateway = gateway,
                credential = { DEVICE_TOKEN },
                now = { NOW },
                io = Dispatchers.Unconfined,
            )
            .load()

        assertEquals(2, gateway.attempts)
        assertEquals(PROPOSAL_A, gateway.results.single().requestId)
    }

    private class FakePrivateGateway : InvitationGateway {
        var requests: List<Request> = emptyList()
        val results = mutableListOf<DeviceResult>()
        val credentials = mutableListOf<String>()
        var failuresRemaining = 0
        var attempts = 0

        override suspend fun resolve(reference: InvitationReference) = error("not used")

        override suspend fun redeem(reference: InvitationReference, deviceName: String) =
            error("not used")

        override suspend fun serverManifest(
            gatewayUrl: String,
            credential: String,
            connectionId: String,
            knownRevision: Long,
        ): WireManifest? = error("not used")

        override suspend fun listRequests(
            gatewayUrl: String,
            credential: String,
            connectionId: String,
            pageToken: String,
            knownSequence: Long,
        ): GatewayPrivatePage {
            credentials += credential
            return GatewayPrivatePage(requests, "", 7, false)
        }

        override suspend fun submitResult(
            gatewayUrl: String,
            credential: String,
            connectionId: String,
            result: DeviceResult,
        ) {
            credentials += credential
            attempts++
            if (failuresRemaining > 0) {
                failuresRemaining--
                throw GatewayException(GatewayException.Kind.Unreachable, "offline")
            }
            results += result
        }

        override suspend fun revoke(
            gatewayUrl: String,
            credential: String,
            connectionId: String,
        ) = error("not used")
    }

    private companion object {
        const val CONNECTION = "11111111-2222-4333-8444-555555555555"
        const val DEVICE_TOKEN = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
        val NOW: Instant = Instant.parse("2026-09-18T12:00:00Z")
        val manifest =
            ServerManifest(
                serverId = SERVER_B,
                protocolVersion = SERVER_PROTOCOL,
                settingsRevision = 1,
                mode = ConnectionMode.GatewayPrivate,
                reference = ServerReference.GatewayPrivate(GATEWAY),
                required = listOf(PluginRequirement(PluginId(SWAP_PLUGIN), 1..1)),
                environments = setOf(PluginEnvironment.Production),
                name = "Trading agent",
            )
        val connection =
            Connection(
                id = CONNECTION,
                label = "Trading agent",
                serverUrl = GATEWAY,
                serverId = SERVER_B,
                deviceName = "Seeker",
                pairedAt = NOW,
                mode = ConnectionMode.GatewayPrivate,
                server = ServerRecord.Known(manifest),
            )

        fun request(): Request =
            Request.newBuilder()
                .setContractVersion(1)
                .setIdentity(
                    RequestIdentity.newBuilder()
                        .setSourceId(SERVER_B)
                        .setScope("private/$SERVER_B")
                        .setRequestId(PROPOSAL_A)
                )
                .setLifecycle(
                    RequestLifecycle.newBuilder()
                        .setRevision(1)
                        .setStatus(RequestStatus.REQUEST_STATUS_OPEN)
                        .setCreatedAt(time(NOW.minusSeconds(60)))
                        .setUpdatedAt(time(NOW.minusSeconds(60)))
                        .setExpiresAt(time(NOW.plusSeconds(600)))
                )
                .setPresentation(
                    Presentation.newBuilder()
                        .setTitle("Swap SOL for USDC")
                        .setDescription("Server request")
                        .setCategory(PresentationCategory.PRESENTATION_CATEGORY_REQUEST)
                )
                .setAction(
                    ActionCapability.newBuilder()
                        .setCapabilityId(SWAP)
                        .setCapabilityVersion(1)
                        .setPluginId(SWAP_PLUGIN)
                )
                .setAudience(
                    Audience.newBuilder()
                        .setPrivate(PrivateAudience.newBuilder().setRecipientId("onboarding-42"))
                )
                .setResultHandling(
                    ResultHandling.newBuilder().setMode(ResultMode.RESULT_MODE_RETURN_TO_ORIGIN)
                )
                .build()

        fun time(value: Instant): Timestamp =
            Timestamp.newBuilder().setSeconds(value.epochSecond).setNanos(value.nano).build()
    }
}
