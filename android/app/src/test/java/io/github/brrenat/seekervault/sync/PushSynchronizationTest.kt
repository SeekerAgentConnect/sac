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
    fun receiptFetchesAllAuthoritativeStateAndRetriesOnlyTransientUnreachability() = runTest {
        var calls = 0
        val complete = PushSyncRunner {
            calls++
            mapOf(
                A to SynchronizeOutcome.Updated(ConnectionSyncState(A)),
                B to SynchronizeOutcome.Failed(CheckOutcome.CertificateRejected),
            )
        }
        assertEquals(BackgroundSyncDecision.Complete, complete.run())
        assertEquals(1, calls)

        val transient = PushSyncRunner {
            mapOf(A to SynchronizeOutcome.Failed(CheckOutcome.Unreachable))
        }
        assertEquals(BackgroundSyncDecision.Retry, transient.run())
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

    private companion object {
        const val A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
