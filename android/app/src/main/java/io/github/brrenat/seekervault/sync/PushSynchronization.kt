package io.github.brrenat.seekervault.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.brrenat.seekervault.SeekerVaultApplication
import io.github.brrenat.seekervault.connections.CheckOutcome
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns a validated content-free FCM hint into one unique, network-constrained authoritative Sync.
 * The work input is empty: no request ID, connection ID, credential, or payload is persisted.
 */
object PushSyncScheduler {
    const val UNIQUE_WORK_NAME = "push-authoritative-sync"
    const val WORK_TAG = "push-sync"
    const val RETRY_BACKOFF_SECONDS = 30L

    fun enqueue(context: Context, expedited: Boolean) {
        workManager(context)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request(expedited),
            )
    }

    internal fun request(expedited: Boolean): OneTimeWorkRequest {
        val builder =
            OneTimeWorkRequestBuilder<PushSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RETRY_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .addTag(WORK_TAG)
        if (expedited) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return builder.build()
    }

    private fun workManager(context: Context): WorkManager =
        try {
            WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            WorkManager.initialize(context, Configuration.Builder().build())
            WorkManager.getInstance(context)
        }
}

/** Push receipt always fetches; foreground stream health does not turn the hint into authority. */
internal class PushSyncRunner(
    private val synchronizeAll: suspend () -> Map<String, SynchronizeOutcome>
) {
    suspend fun run(): BackgroundSyncDecision {
        val outcomes = synchronizeAll()
        return if (
            outcomes.any { (_, outcome) ->
                outcome is SynchronizeOutcome.Failed && outcome.failure == CheckOutcome.Unreachable
            }
        ) {
            BackgroundSyncDecision.Retry
        } else {
            BackgroundSyncDecision.Complete
        }
    }
}

/** A headless fetch for a coalesced push hint; it has no operation that can answer a request. */
class PushSyncWorker : CoroutineWorker {
    private val execute: suspend () -> BackgroundSyncDecision

    constructor(appContext: Context, params: WorkerParameters) : super(appContext, params) {
        execute = { runApplicationSync(appContext) }
    }

    internal constructor(
        appContext: Context,
        params: WorkerParameters,
        execute: suspend () -> BackgroundSyncDecision,
    ) : super(appContext, params) {
        this.execute = execute
    }

    override suspend fun doWork(): Result =
        try {
            when (execute()) {
                BackgroundSyncDecision.Complete -> Result.success()
                BackgroundSyncDecision.Retry -> Result.retry()
                BackgroundSyncDecision.Failed -> Result.failure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Result.retry()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: IllegalArgumentException) {
            Result.failure()
        }

    private suspend fun runApplicationSync(context: Context): BackgroundSyncDecision {
        val application =
            context.applicationContext as? SeekerVaultApplication
                ?: return BackgroundSyncDecision.Failed
        val repository = application.connectionRepository
        return PushSyncRunner(repository::synchronizeAll).run()
    }
}
