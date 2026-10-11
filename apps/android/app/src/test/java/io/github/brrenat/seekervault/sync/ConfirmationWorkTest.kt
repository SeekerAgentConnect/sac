package io.github.brrenat.seekervault.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.CONNECTION
import io.github.brrenat.seekervault.activity.REQUEST
import io.github.brrenat.seekervault.activity.WALLET
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.confirmations.ConfirmationTracker
import io.github.brrenat.seekervault.confirmations.Submission
import io.github.brrenat.seekervault.confirmations.TrackingOrigin
import io.github.brrenat.seekervault.confirmations.storage.TrackingStore
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.rpc.testRpc
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * When the phone looks at the chain in the background (SEE-165): one unique, network-constrained,
 * one-time request, due when the next check is, and nothing at all when nothing is unfinished. It
 * depends on no connection.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationWorkTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = Instant.parse("2026-09-26T12:00:00Z")

    @Before
    fun initializeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After fun stop() = scope.cancel()

    @Test
    fun nothingUnfinishedSchedulesNothing() {
        val scheduler = ConfirmationScheduler(workManager, tracker(), scope) { now }
        scheduler.onBackground()
        assertTrue(workInfos().isEmpty())
    }

    @Test
    fun aSubmissionInTheBackgroundIsOneUniqueNetworkConstrainedRequestDueWhenItsCheckIs() {
        val tracker = tracker()
        val scheduler = ConfirmationScheduler(workManager, tracker, scope) { now }
        val key = RequestKey(CONNECTION, REQUEST)
        tracker.expect(
            Submission(
                key,
                TrackingOrigin.Operation,
                Network.NETWORK_DEVNET,
                WALLET,
                byteArrayOf(1) + ByteArray(64) + ByteArray(20) { 3 },
            )
        )
        // The app is not in the foreground, so the tracker's poke goes straight to WorkManager.
        tracker.submitted(key, ByteArray(64) { 7 })
        scheduler.onBackground()
        scheduler.onBackground()

        val work = workInfos().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertEquals(ConfirmationTracker.FIRST_CHECK.toMillis(), work.initialDelayMillis)
        assertTrue(ConfirmationScheduler.WORK_TAG in work.tags)
    }

    @Test
    fun aForegroundStillLoadingWhenTheAppIsHiddenAgainNeverStartsTheLoop() {
        val tracker = tracker()
        val scheduler = ConfirmationScheduler(workManager, tracker, scope) { now }
        val loading = CompletableDeferred<Unit>()
        scheduler.onForeground { loading.await() }
        scheduler.onBackground()
        loading.complete(Unit)

        // No loop is running, so a new submission goes to WorkManager rather than to a loop.
        submit(tracker, RequestKey(CONNECTION, REQUEST))
        assertEquals(1, workInfos().size)
    }

    @Test
    fun aForegroundThatFinishesLoadingWhileVisibleRunsTheLoop() {
        val tracker = tracker()
        val scheduler = ConfirmationScheduler(workManager, tracker, scope) { now }
        scheduler.onForeground()

        // The loop takes the poke; nothing is handed to WorkManager while the app is visible.
        submit(tracker, RequestKey(CONNECTION, REQUEST))
        assertTrue(workInfos().isEmpty())

        scheduler.onBackground()
        assertEquals(1, workInfos().size)
    }

    @Test
    fun aWorkerPassSucceedsOrRetriesOnAStorageFailure() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker { true }.doWork())
        assertEquals(ListenableWorker.Result.retry(), worker { throw IOException("x") }.doWork())
    }

    @Test
    fun theWorkersSuccessorIsAppendedRatherThanReplacingARunningPass() {
        val tracker = tracker()
        val scheduler = ConfirmationScheduler(workManager, tracker, scope) { now }
        val key = RequestKey(CONNECTION, REQUEST)
        tracker.expect(
            Submission(
                key,
                TrackingOrigin.Direct,
                Network.NETWORK_DEVNET,
                WALLET,
                byteArrayOf(1) + ByteArray(64) + ByteArray(20) { 3 },
            )
        )
        tracker.submitted(key, ByteArray(64) { 7 })
        scheduler.schedule(ExistingWorkPolicy.APPEND_OR_REPLACE)
        assertTrue(workInfos().isNotEmpty())
    }

    private fun submit(tracker: ConfirmationTracker, key: RequestKey) {
        tracker.expect(
            Submission(
                key,
                TrackingOrigin.Operation,
                Network.NETWORK_DEVNET,
                WALLET,
                byteArrayOf(1) + ByteArray(64) + ByteArray(20) { 3 },
            )
        )
        tracker.submitted(key, ByteArray(64) { 7 })
    }

    private fun tracker() =
        ConfirmationTracker(
            TrackingStore(File(folder.root, "confirmations")),
            ActivityLog(ActivityStore(File(folder.root, "activity"))),
            testRpc(File(folder.root, "rpc")) { error("no chain in this test") },
        ) {
            now
        }

    private fun workInfos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(ConfirmationScheduler.UNIQUE_WORK_NAME).get()

    private fun worker(execute: suspend () -> Boolean): ConfirmationWorker =
        TestListenableWorkerBuilder<ConfirmationWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = ConfirmationWorker(appContext, workerParameters, execute)
                }
            )
            .build()
}
