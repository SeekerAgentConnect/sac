package io.github.brrenat.seekervault.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.brrenat.seekervault.SeekerVaultApplication
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Keeps one OS-persisted periodic request while this phone has a usable sidecar connection.
 * [ExistingPeriodicWorkPolicy.KEEP] matters: reopening or foregrounding the app must not replace
 * the request and move its next eligible run another fifteen minutes away.
 */
class BackgroundSyncScheduler(
    private val workManager: WorkManager,
    private val loaded: StateFlow<Boolean>,
    private val connections: StateFlow<List<Connection>>,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var observer: Job? = null

    /**
     * Starts one application-scoped observer. An unknown, not-yet-loaded empty list changes none.
     */
    fun start() {
        synchronized(lock) {
            if (observer?.isActive == true) return
            observer = scope.launch {
                combine(loaded, connections) { hasLoaded, current ->
                        current.any(Connection::usable).takeIf { hasLoaded }
                    }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { hasUsableConnection ->
                        if (hasUsableConnection) {
                            workManager.enqueueUniquePeriodicWork(
                                UNIQUE_WORK_NAME,
                                ExistingPeriodicWorkPolicy.KEEP,
                                periodicRequest(),
                            )
                        } else {
                            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                        }
                    }
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "sidecar-background-sync"
        const val WORK_TAG = "sidecar-sync"
        const val REPEAT_MINUTES = 15L
        const val RETRY_BACKOFF_SECONDS = 30L

        /**
         * Resolves the manifest-initialized manager. WorkManager's test artifact deliberately
         * removes that initializer, so ordinary Robolectric activity tests use the same default
         * configuration through this fallback without putting WorkManager APIs outside sync/.
         */
        fun create(
            context: Context,
            loaded: StateFlow<Boolean>,
            connections: StateFlow<List<Connection>>,
            scope: CoroutineScope,
        ): BackgroundSyncScheduler {
            val manager =
                try {
                    WorkManager.getInstance(context)
                } catch (_: IllegalStateException) {
                    WorkManager.initialize(context, Configuration.Builder().build())
                    WorkManager.getInstance(context)
                }
            return BackgroundSyncScheduler(manager, loaded, connections, scope)
        }

        fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                    REPEAT_MINUTES,
                    TimeUnit.MINUTES,
                )
                // The first periodic pass is background recovery, not a second app-open refresh.
                .setInitialDelay(REPEAT_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RETRY_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .addTag(WORK_TAG)
                .build()
    }
}

internal enum class BackgroundSyncDecision {
    Complete,
    Retry,
    Failed,
}

/** The worker's policy, separated from WorkManager so overlap and retry rules are deterministic. */
internal class BackgroundSyncRunner(
    private val load: suspend () -> Unit,
    private val connections: () -> List<Connection>,
    private val foreground: () -> ForegroundUpdatesState,
    private val synchronizeAll: suspend () -> Map<String, SynchronizeOutcome>,
) {
    suspend fun run(): BackgroundSyncDecision {
        load()
        val usable = connections().filter(Connection::usable).mapTo(mutableSetOf(), Connection::id)
        if (usable.isEmpty()) return BackgroundSyncDecision.Complete

        val live = foreground()
        if (
            live.foreground &&
                usable.all { id -> live.connections[id] == ForegroundConnectionState.Live }
        ) {
            return BackgroundSyncDecision.Complete
        }

        val outcomes = synchronizeAll()
        return if (
            outcomes.any { (id, outcome) ->
                id in usable &&
                    outcome is SynchronizeOutcome.Failed &&
                    outcome.failure == CheckOutcome.Unreachable
            }
        ) {
            BackgroundSyncDecision.Retry
        } else {
            BackgroundSyncDecision.Complete
        }
    }
}

/**
 * A bounded, headless unary fetch. WorkManager constructs this in a process with no Activity; all
 * metadata and encrypted credentials are therefore reloaded through [SeekerVaultApplication].
 */
class BackgroundSyncWorker : CoroutineWorker {
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
        return BackgroundSyncRunner(
                load = repository::load,
                connections = { repository.connections.value },
                foreground = { application.foregroundUpdates.state.value },
                synchronizeAll = repository::synchronizeAll,
            )
            .run()
    }
}
