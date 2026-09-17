package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.sync.UpdateEndpoint
import io.github.brrenat.seekervault.sync.UpdateTransport
import io.github.brrenat.seekervault.sync.storage.SyncStore
import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse
import io.github.brrenat.seekervault.update.v1.SyncedRequest
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
class ConnectionSynchronizationTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val transport = SnapshotTransport()
    private val key = softwareKey()
    private val history by lazy {
        ActivityLog(ActivityStore(File(folder.root, "files/activity"))) { NOW }
    }

    private fun newRepository(log: ActivityLog = history) =
        ConnectionRepository(
            ConnectionStore(File(folder.root, "files/connections")),
            CredentialVault(File(folder.root, "no_backup/credentials")) { key },
            ResultStore(File(folder.root, "files/results")),
            gateway,
            log,
            deviceName = "Seeker",
            now = { NOW },
            io = Dispatchers.Unconfined,
            syncStore = SyncStore(File(folder.root, "files/sync")),
            updateTransport = transport,
        )

    private val repository by lazy { newRepository() }

    @Test
    fun manualRefreshUsesSharedSyncAndPublishesItsDurablePendingCache() = runBlocking {
        val connection = repository.pair(server.issue(URL))
        val pending = server.addPending(connection.id, REQUEST)
        transport.response = { snapshot(it.connectionId, pending) }

        repository.refresh(connection.id)

        assertEquals(
            listOf(REQUEST),
            repository.inbox.value.pending[connection.id]?.map { it.ref.requestId },
        )
        assertEquals(1, transport.calls)
        // The pending list came from the snapshot: the shared sync path replaces ListPending, and
        // reading what the server says about itself (SEE-88) is not a fetch of requests.
        assertEquals(0, server.lists)
        assertEquals(CheckOutcome.Ok, repository.connection(connection.id)?.lastCheck?.outcome)
    }

    @Test
    fun syncRetriesOnlyAResultTheOwnerAlreadyRecordedAndReconcilesActivity() = runBlocking {
        val connection = repository.pair(server.issue(URL))
        val pending = server.addPending(connection.id, REQUEST)
        transport.response = { snapshot(it.connectionId, pending) }
        repository.refresh(connection.id)

        server.failure = GatewayException.Kind.Unreachable
        val waiting = repository.answer(RequestKey(connection.id, REQUEST), Answer.Acknowledge)
        assertEquals(Delivery.Waiting, waiting.delivery)
        assertEquals(ActivityOutcome.Waiting, history.records.value.single().outcome)

        server.failure = null
        transport.response = { request ->
            snapshot(
                request.connectionId,
                checkNotNull(server.settled[request.connectionId]?.get(REQUEST)),
                revision = 2,
            )
        }
        repository.refresh(connection.id)

        assertEquals(1, gateway.submits.size)
        assertEquals(Delivery.Accepted, repository.inbox.value.results.single().delivery)
        assertEquals(ActivityOutcome.Acknowledged, history.records.value.single().outcome)
    }

    @Test
    fun workerOnlyEntryLoadsConnectionsAndCredentialsAfterProcessDeath() = runBlocking {
        val connection = repository.pair(server.issue(URL))
        val pending = server.addPending(connection.id, REQUEST)
        transport.response = { snapshot(it.connectionId, pending) }

        val restarted =
            newRepository(ActivityLog(ActivityStore(File(folder.root, "files/activity"))) { NOW })
        val outcomes = restarted.synchronizeAll()

        assertTrue(
            outcomes.getValue(connection.id)
                is io.github.brrenat.seekervault.sync.SynchronizeOutcome.Updated
        )
        assertEquals(
            listOf(REQUEST),
            restarted.inbox.value.pending[connection.id]?.map { it.ref.requestId },
        )
    }

    private fun snapshot(
        connectionId: String,
        request: io.github.brrenat.seekervault.request.v1.ActionRequest,
        revision: Long = 1,
    ): SyncResponse =
        SyncResponse.newBuilder()
            .setConnectionId(connectionId)
            .setServerInstanceId("instance")
            .setSnapshotCursor("cursor-$revision")
            .addRequests(SyncedRequest.newBuilder().setRequest(request).setRevision(revision))
            .build()

    private class SnapshotTransport : UpdateTransport {
        var calls = 0
        lateinit var response: (SyncRequest) -> SyncResponse

        override suspend fun discover(
            serverUrl: String,
            credential: String,
            connectionId: String,
        ): UpdateEndpoint = UpdateEndpoint(1, "https://a.example.com")

        override suspend fun sync(
            endpoint: UpdateEndpoint,
            credential: String,
            request: SyncRequest,
        ): SyncResponse {
            calls++
            assertTrue(credential.isNotEmpty())
            return response(request)
        }
    }

    private companion object {
        const val URL = "https://a.example.com"
        const val REQUEST = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val NOW: Instant = Instant.parse("2026-09-13T12:00:00Z")
    }
}
