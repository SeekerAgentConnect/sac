package io.github.brrenat.seekervault.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.brrenat.seekervault.SeekerVaultApplication
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.newSecret
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.sync.storage.SyncStore
import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse
import io.github.brrenat.seekervault.update.v1.SyncedRequest
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundSynchronizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager

    @Before
    fun initializeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun uniqueScheduleWaitsForLoadedStateKeepsItsClockAndCancelsWithoutAConnection() = runTest {
        val loaded = MutableStateFlow(false)
        val connections = MutableStateFlow(listOf(connection(A)))
        val scheduler = BackgroundSyncScheduler(workManager, loaded, connections, backgroundScope)
        scheduler.start()
        runCurrent()
        assertTrue(workInfos().isEmpty())

        loaded.value = true
        runCurrent()
        val first = workInfos().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        assertEquals(
            TimeUnit.MINUTES.toMillis(BackgroundSyncScheduler.REPEAT_MINUTES),
            first.initialDelayMillis,
        )
        assertEquals(
            TimeUnit.MINUTES.toMillis(BackgroundSyncScheduler.REPEAT_MINUTES),
            checkNotNull(first.periodicityInfo).repeatIntervalMillis,
        )

        // Starting again and publishing non-usability changes never replace the persisted
        // request, so its first eligible run is not continually postponed.
        scheduler.start()
        connections.value = listOf(connection(A).copy(label = "Renamed"))
        runCurrent()
        assertEquals(first.id, workInfos().single().id)

        // A newly created observer models a normal process restart. KEEP adopts the durable
        // schedule instead of replacing it with a fresh fifteen-minute delay.
        BackgroundSyncScheduler(workManager, loaded, connections, backgroundScope).start()
        runCurrent()
        assertEquals(first.id, workInfos().single().id)

        connections.value = listOf(connection(A).copy(revokedAt = NOW))
        runCurrent()
        assertEquals(WorkInfo.State.CANCELLED, workInfo(first.id)?.state)
    }

    @Test
    fun requestUsesTheMinimumPeriodNetworkConstraintAndExponentialBackoff() {
        val request = BackgroundSyncScheduler.periodicRequest()
        val work = request.workSpec

        assertEquals(TimeUnit.MINUTES.toMillis(15), work.intervalDuration)
        assertEquals(TimeUnit.MINUTES.toMillis(15), work.initialDelay)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, work.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), work.backoffDelayDuration)
        assertEquals(Data.EMPTY, work.input)
        assertFalse(work.expedited)
        assertTrue(BackgroundSyncScheduler.WORK_TAG in request.tags)
    }

    @Test
    fun healthyForegroundStreamsSkipTheWorkerButOneUnhealthyStreamUsesUnarySync() = runTest {
        val connections = listOf(connection(A), connection(B))
        var calls = 0
        val foreground =
            ForegroundUpdatesState(
                foreground = true,
                connections =
                    mapOf(
                        A to ForegroundConnectionState.Live,
                        B to ForegroundConnectionState.Live,
                    ),
            )
        val healthy =
            BackgroundSyncRunner(
                load = {},
                connections = { connections },
                foreground = { foreground },
                synchronizeAll = {
                    calls++
                    emptyMap()
                },
            )

        assertEquals(BackgroundSyncDecision.Complete, healthy.run())
        assertEquals(0, calls)

        val recovering =
            BackgroundSyncRunner(
                load = {},
                connections = { connections },
                foreground = {
                    foreground.copy(
                        connections =
                            foreground.connections +
                                (B to ForegroundConnectionState.Reconnecting(1))
                    )
                },
                synchronizeAll = {
                    calls++
                    mapOf(A to updated(A), B to updated(B))
                },
            )
        assertEquals(BackgroundSyncDecision.Complete, recovering.run())
        assertEquals(1, calls)
    }

    @Test
    fun transientNetworkFailureRetriesButPermanentOutcomesWaitForTheNextPeriod() = runTest {
        val unavailable =
            runnerWith(mapOf(A to SynchronizeOutcome.Failed(CheckOutcome.Unreachable)))
        val configuration =
            runnerWith(mapOf(A to SynchronizeOutcome.Failed(CheckOutcome.CertificateRejected)))
        val revoked = runnerWith(mapOf(A to SynchronizeOutcome.Removed))

        assertEquals(BackgroundSyncDecision.Retry, unavailable.run())
        assertEquals(BackgroundSyncDecision.Complete, configuration.run())
        assertEquals(BackgroundSyncDecision.Complete, revoked.run())
    }

    @Test
    fun workerOnlyInitializationReloadsCredentialsAndPersistsTheFetchedSnapshot() = runTest {
        val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
        val request = FakeConnectionGateway.request(A, REQUEST)
        val transport = SnapshotTransport(request)
        seedConnection(app)
        app.connectionGateway = { FakeConnectionGateway() }
        app.updateTransport = { transport }
        app.connectionIo = Dispatchers.Unconfined

        val worker = TestListenableWorkerBuilder<BackgroundSyncWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        val stored = SyncStore(File(app.filesDir, "sync")).get(A)
        assertNotNull(stored)
        assertEquals(listOf(REQUEST), stored?.pending?.map { it.ref.requestId })
        assertNotNull(stored?.lastSuccessfulSync)
        assertEquals(1, transport.calls)
        assertFalse(transport.credentialSeen.isNullOrEmpty())
    }

    @Test
    fun workerReturnsRetryForATransientUnaryFailure() = runTest {
        val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
        seedConnection(app)
        app.connectionGateway = { FakeConnectionGateway() }
        app.updateTransport = { UnreachableTransport() }
        app.connectionIo = Dispatchers.Unconfined

        val worker = TestListenableWorkerBuilder<BackgroundSyncWorker>(app).build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun authenticationFailureRevokesTheConnectionAndCancelsPeriodicWork() = runTest {
        val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
        seedConnection(app)
        app.connectionGateway = { FakeConnectionGateway() }
        app.updateTransport = { UnauthenticatedTransport() }
        app.connectionIo = Dispatchers.Unconfined

        val worker = TestListenableWorkerBuilder<BackgroundSyncWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        assertFalse(checkNotNull(app.connectionRepository.connection(A)).usable)
        assertEquals(WorkInfo.State.CANCELLED, workInfos().single().state)
    }

    private fun runnerWith(outcomes: Map<String, SynchronizeOutcome>) =
        BackgroundSyncRunner(
            load = {},
            connections = { listOf(connection(A)) },
            foreground = { ForegroundUpdatesState() },
            synchronizeAll = { outcomes },
        )

    private fun updated(id: String) =
        SynchronizeOutcome.Updated(ConnectionSyncState(connectionId = id))

    private fun workInfos() =
        workManager
            .getWorkInfosForUniqueWork(BackgroundSyncScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)

    private fun workInfo(id: java.util.UUID) =
        workManager.getWorkInfoById(id).get(5, TimeUnit.SECONDS)

    private fun seedConnection(app: SeekerVaultApplication) {
        val key = softwareKey()
        app.credentialKey = { key }
        ConnectionStore(File(app.filesDir, "connections")).put(connection(A))
        CredentialVault(File(app.noBackupFilesDir, "credentials")) { key }.put(A, newSecret())
    }

    private fun connection(id: String) =
        Connection(
            id = id,
            label = id,
            serverUrl = "https://$id.example",
            serverId = "server-$id",
            deviceName = "Seeker",
            pairedAt = NOW,
        )

    private class SnapshotTransport(
        private val request: io.github.brrenat.seekervault.request.v1.ActionRequest
    ) : UpdateTransport {
        var calls = 0
        var credentialSeen: String? = null

        override suspend fun discover(
            serverUrl: String,
            credential: String,
            connectionId: String,
        ): UpdateEndpoint = UpdateEndpoint(1, serverUrl)

        override suspend fun sync(
            endpoint: UpdateEndpoint,
            credential: String,
            request: SyncRequest,
        ): SyncResponse {
            calls++
            credentialSeen = credential
            return SyncResponse.newBuilder()
                .setConnectionId(request.connectionId)
                .setServerInstanceId("instance")
                .setSnapshotCursor("cursor")
                .addRequests(SyncedRequest.newBuilder().setRequest(this.request).setRevision(1))
                .build()
        }
    }

    private class UnreachableTransport : UpdateTransport {
        override suspend fun discover(
            serverUrl: String,
            credential: String,
            connectionId: String,
        ): UpdateEndpoint =
            throw UpdateTransportException(UpdateTransportException.Kind.Unreachable, "offline")

        override suspend fun sync(
            endpoint: UpdateEndpoint,
            credential: String,
            request: SyncRequest,
        ): SyncResponse = error("discover must fail first")
    }

    private class UnauthenticatedTransport : UpdateTransport {
        override suspend fun discover(
            serverUrl: String,
            credential: String,
            connectionId: String,
        ): UpdateEndpoint =
            throw UpdateTransportException(
                UpdateTransportException.Kind.Unauthenticated,
                "revoked",
            )

        override suspend fun sync(
            endpoint: UpdateEndpoint,
            credential: String,
            request: SyncRequest,
        ): SyncResponse = error("discover must fail first")
    }

    private companion object {
        const val A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val REQUEST = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val NOW: Instant = Instant.parse("2026-09-14T12:00:00Z")
    }
}
