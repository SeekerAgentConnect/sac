package io.github.brrenat.seekervault.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.brrenat.seekervault.SeekerVaultApplication
import io.github.brrenat.seekervault.confirmations.ConfirmationTracker
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When the phone looks at the chain for its unfinished transactions (SEE-165,
 * docs/wiki/chain-confirmation.md#when-checks-run).
 *
 * Two ways, one coordinator. While the app is in the foreground a loop runs each check when it is
 * due, promptly after a submission and with the tracker's own backoff after that. When the app goes
 * to the background, or a check is due while it isn't running, one unique one-time WorkManager
 * request waits for a network and for the next due check. None of it depends on a connection being
 * usable, on a server, or on the fifteen-minute sync: the question is the chain's, and the chain is
 * all it asks.
 *
 * Background timing is Android's to decide. WorkManager defers under Doze and App Standby, and a
 * force-stopped app runs nothing until it is opened again, when [onForeground] catches up. Nothing
 * here promises a time.
 */
class ConfirmationScheduler(
    private val workManager: WorkManager,
    private val tracker: ConfirmationTracker,
    private val scope: CoroutineScope,
    private val connectivity: ConnectivityManager? = null,
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val network =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Checks that were waiting out an unreachable endpoint are due again now; the
                // loop only runs what is due, so waking it alone would leave them on their backoff.
                tracker.connectivityRestored()
                wake.trySend(Unit)
            }
        }

    /**
     * Counts lifecycle transitions. Each one takes a number when it is called, and acts only if no
     * later transition has been called since, so a foreground whose preparation is still loading
     * when the app is hidden again never starts the loop behind the background's back.
     */
    private val transitions = AtomicLong()

    init {
        tracker.onDue = ::poke
    }

    /**
     * The app is visible: [prepare] (reading what is stored, backfilling) runs first, then what is
     * due is checked, and checking continues while the app stays visible. If the app is hidden
     * again before [prepare] finishes, the loop is not started.
     */
    fun onForeground(prepare: suspend () -> Unit = {}) {
        val transition = transitions.incrementAndGet()
        scope.launch {
            prepare()
            synchronized(lock) {
                if (transitions.get() != transition) return@launch
                if (loop?.isActive == true) {
                    wake.trySend(Unit)
                    return@launch
                }
                // Restored connectivity is a reason to look again straight away.
                runCatching { connectivity?.registerDefaultNetworkCallback(network) }
                loop = scope.launch {
                    while (isActive) {
                        val next = tracker.checkDue()
                        val wait = next?.let {
                            Duration.between(now(), it).toMillis().coerceAtLeast(0)
                        }
                        if (wait == null) wake.receive()
                        else
                            withTimeoutOrNull(wait.coerceAtLeast(MIN_LOOP_MILLIS)) {
                                wake.receive()
                            }
                    }
                }
            }
        }
    }

    /**
     * The app is hidden: stop the foreground loop and hand what is unfinished to WorkManager. A
     * later [onForeground] supersedes it.
     */
    fun onBackground() {
        val transition = transitions.incrementAndGet()
        scope.launch {
            synchronized(lock) {
                if (transitions.get() != transition) return@launch
                loop?.cancel()
                loop = null
                runCatching { connectivity?.unregisterNetworkCallback(network) }
            }
            schedule(ExistingWorkPolicy.REPLACE)
        }
    }

    /** Something became due. The foreground loop wakes; without one, the work is (re)scheduled. */
    fun poke() {
        val foreground = synchronized(lock) { loop?.isActive == true }
        if (foreground) wake.trySend(Unit) else schedule(ExistingWorkPolicy.REPLACE)
    }

    /**
     * Enqueues the next background check, if anything is waiting. [policy] is REPLACE from the app
     * — a newer schedule is the better one — and APPEND_OR_REPLACE from a worker scheduling its own
     * successor, which REPLACE would cancel mid-run.
     */
    fun schedule(policy: ExistingWorkPolicy) {
        val next = tracker.nextDue() ?: return
        val delay = Duration.between(now(), next).toMillis().coerceAtLeast(0)
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request(delay))
    }

    companion object {
        const val UNIQUE_WORK_NAME = "chain-confirmations"
        const val WORK_TAG = "chain-confirmations"
        const val RETRY_BACKOFF_SECONDS = 30L
        /** The shortest pause between two foreground passes, so a burst of wakes can't spin. */
        const val MIN_LOOP_MILLIS = 1_000L

        fun create(
            context: Context,
            tracker: ConfirmationTracker,
            scope: CoroutineScope,
        ): ConfirmationScheduler {
            val manager =
                try {
                    WorkManager.getInstance(context)
                } catch (_: IllegalStateException) {
                    WorkManager.initialize(context, Configuration.Builder().build())
                    WorkManager.getInstance(context)
                }
            return ConfirmationScheduler(
                manager,
                tracker,
                scope,
                context.getSystemService(ConnectivityManager::class.java),
            )
        }

        fun request(delayMillis: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ConfirmationWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
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

/**
 * One background pass over the unfinished transactions. It reads the tracking store and asks the
 * chain; it needs no connection, credential or server, and it opens no wallet. When something is
 * still unfinished it schedules its successor for when that is due, and each tracked transaction's
 * own attempt budget is what makes the chain of runs end.
 */
class ConfirmationWorker : CoroutineWorker {
    private val execute: suspend () -> Boolean

    constructor(appContext: Context, params: WorkerParameters) : super(appContext, params) {
        execute = { runApplicationChecks(appContext) }
    }

    internal constructor(
        appContext: Context,
        params: WorkerParameters,
        execute: suspend () -> Boolean,
    ) : super(appContext, params) {
        this.execute = execute
    }

    override suspend fun doWork(): Result =
        try {
            if (execute()) Result.success() else Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Result.retry()
        }

    private suspend fun runApplicationChecks(context: Context): Boolean {
        val application = context.applicationContext as? SeekerVaultApplication ?: return false
        val tracker = application.confirmations
        tracker.checkDue()
        application.confirmationScheduler.schedule(ExistingWorkPolicy.APPEND_OR_REPLACE)
        return true
    }
}
