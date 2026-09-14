package io.github.brrenat.seekervault.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.RequestKey
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushSynchronizationTest {
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
    fun requestHasEmptyInputConnectedNetworkBackoffAndBoundedExpedition() {
        val highRequest = PushSyncScheduler.request(expedited = true)
        val normalRequest = PushSyncScheduler.request(expedited = false)
        val high = highRequest.workSpec
        val normal = normalRequest.workSpec

        for (work in listOf(high, normal)) {
            assertEquals(Data.EMPTY, work.input)
            assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
            assertEquals(BackoffPolicy.EXPONENTIAL, work.backoffPolicy)
            assertEquals(
                TimeUnit.SECONDS.toMillis(PushSyncScheduler.RETRY_BACKOFF_SECONDS),
                work.backoffDelayDuration,
            )
        }
        assertTrue(PushSyncScheduler.WORK_TAG in highRequest.tags)
        assertTrue(PushSyncScheduler.WORK_TAG in normalRequest.tags)
        assertTrue(high.expedited)
        assertFalse(normal.expedited)
    }

    @Test
    fun duplicateReceiptsKeepOneUniqueAuthoritativeSync() {
        PushSyncScheduler.enqueue(context, expedited = true)
        PushSyncScheduler.enqueue(context, expedited = false)

        val work =
            workManager
                .getWorkInfosForUniqueWork(PushSyncScheduler.UNIQUE_WORK_NAME)
                .get(5, TimeUnit.SECONDS)
        assertEquals(1, work.size)
        assertTrue(work.single().tags.contains(PushSyncScheduler.WORK_TAG))
    }

    @Test
    fun receiptLeavesLiveStreamsActiveAndFetchesOnlyConnectionsThatNeedRecovery() = runTest {
        var calls = 0
        var requested = emptySet<String>()
        val complete =
            runner(
                foreground =
                    ForegroundUpdatesState(
                        foreground = true,
                        connections =
                            mapOf(
                                A to ForegroundConnectionState.Live,
                                B to ForegroundConnectionState.Reconnecting(1),
                            ),
                    ),
                synchronizeConnections = { ids ->
                    calls++
                    requested = ids
                    mapOf(B to SynchronizeOutcome.Failed(CheckOutcome.CertificateRejected))
                },
            )
        assertEquals(BackgroundSyncDecision.Complete, complete.run())
        assertEquals(1, calls)
        assertEquals(setOf(B), requested)

        val transient =
            runner(
                synchronizeConnections = {
                    mapOf(A to SynchronizeOutcome.Failed(CheckOutcome.Unreachable))
                }
            )
        assertEquals(BackgroundSyncDecision.Retry, transient.run())
    }

    @Test
    fun duplicatePushAndWorkerSignalsDoNoUnaryWorkWhileEveryForegroundStreamIsLive() = runTest {
        var calls = 0
        val runner =
            runner(
                foreground =
                    ForegroundUpdatesState(
                        foreground = true,
                        connections =
                            mapOf(
                                A to ForegroundConnectionState.Live,
                                B to ForegroundConnectionState.Live,
                            ),
                    ),
                synchronizeConnections = {
                    calls++
                    emptyMap()
                },
            )

        assertEquals(BackgroundSyncDecision.Complete, runner.run())
        assertEquals(BackgroundSyncDecision.Complete, runner.run())
        assertEquals(0, calls)
    }

    @Test
    fun workerMapsTheBoundedFetchDecisionWithoutAnyPayloadInput() = runTest {
        val complete =
            TestListenableWorkerBuilder<PushSyncWorker>(context)
                .setWorkerFactory(executeWorkerFactory { BackgroundSyncDecision.Complete })
                .build()
        val retry =
            TestListenableWorkerBuilder<PushSyncWorker>(context)
                .setWorkerFactory(executeWorkerFactory { BackgroundSyncDecision.Retry })
                .build()

        assertEquals(ListenableWorker.Result.success(), complete.doWork())
        assertEquals(ListenableWorker.Result.retry(), retry.doWork())
    }

    @Test
    fun notificationsAreDerivedOnlyAfterAuthoritativeSyncFindsANewPendingRequest() = runTest {
        var current = emptySet<RequestKey>()
        val changes = mutableListOf<Pair<Set<RequestKey>, Set<RequestKey>>>()
        val key = RequestKey(A, A_REQUEST)
        val runner =
            runner(
                synchronizeConnections = {
                    current = setOf(key)
                    mapOf(A to SynchronizeOutcome.Updated(ConnectionSyncState(A)))
                },
                pending = { current },
                reconcileNotifications = { before, after -> changes += before to after },
            )

        assertEquals(BackgroundSyncDecision.Complete, runner.run())
        assertEquals(listOf(emptySet<RequestKey>() to setOf(key)), changes)
    }

    private fun executeWorkerFactory(
        execute: suspend () -> BackgroundSyncDecision
    ): androidx.work.WorkerFactory =
        object : androidx.work.WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: androidx.work.WorkerParameters,
            ): ListenableWorker? =
                if (workerClassName == PushSyncWorker::class.java.name) {
                    PushSyncWorker(appContext, workerParameters, execute)
                } else {
                    null
                }
        }

    private fun runner(
        foreground: ForegroundUpdatesState = ForegroundUpdatesState(),
        synchronizeConnections: suspend (Set<String>) -> Map<String, SynchronizeOutcome>,
        pending: () -> Set<RequestKey> = { emptySet() },
        reconcileNotifications: (Set<RequestKey>, Set<RequestKey>) -> Unit = { _, _ -> },
    ) =
        PushSyncRunner(
            load = {},
            connections = { listOf(connection(A), connection(B)) },
            foreground = { foreground },
            synchronizeConnections = synchronizeConnections,
            pending = pending,
            reconcileNotifications = reconcileNotifications,
        )

    private fun connection(id: String) =
        Connection(
            id = id,
            label = id,
            serverUrl = "https://$id.example",
            serverId = "server-$id",
            deviceName = "Seeker",
            pairedAt = Instant.parse("2026-09-14T12:00:00Z"),
        )

    private companion object {
        const val A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val A_REQUEST = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    }
}
