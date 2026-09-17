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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.brrenat.seekervault.SeekerVaultApplication
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.feeds.FeedListenerState
import io.github.brrenat.seekervault.feeds.ForegroundFeedsState
import io.github.brrenat.seekervault.notifications.ProposalRef
import io.github.brrenat.seekervault.servers.ConnectionMode
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns a validated content-free feed hint into one authoritative read of the feeds this phone
 * holds (SEE-92).
 *
 * It is SAW-057's push path for the other kind of server, and the same two rules: the work input is
 * empty — no channel, no topic, no proposal ID is persisted into WorkManager's database — and one
 * job is queued however many hints arrive.
 *
 * The empty input is what makes a coalesced or dropped hint cost nothing. A hint says that
 * *something* on a feed changed; the read that follows covers every feed on this phone, so two
 * hints are one read, a hint that Firebase replaced under its collapse key loses nothing, and one
 * that never arrived is caught by the next one or by the owner's next glance.
 */
object FeedSyncScheduler {
    const val UNIQUE_WORK_NAME = "feed-authoritative-read"
    const val WORK_TAG = "feed-read"
    const val RETRY_BACKOFF_SECONDS = 60L

    fun enqueue(context: Context) {
        workManager(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request())
    }

    internal fun request(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<FeedSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            // Deliberately not expedited. A request is one server waiting for this owner's answer;
            // a feed is an offer to everyone subscribed, and it is not worth spending the app's
            // expedited quota — or the owner's battery — to read one a minute sooner.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

    private fun workManager(context: Context): WorkManager =
        try {
            WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            WorkManager.initialize(context, Configuration.Builder().build())
            WorkManager.getInstance(context)
        }
}

/**
 * Reads the feeds that are not already live, and reconciles what the owner is shown.
 *
 * A hint is only a signal. Everything the app acts on comes from the gateway's own unary API,
 * through the same validators a snapshot goes through (SEE-88, SEE-89) — so a hint that was forged,
 * replayed or delayed can at most cause a read, and a read of a feed that has not moved is one
 * small "unchanged" answer.
 *
 * A feed whose gateway is streaming to a foreground listener is skipped: that listener has the
 * documents already, and reading the same feed twice would only spend the radio.
 */
internal class FeedSyncRunner(
    private val load: suspend () -> Unit,
    private val connections: () -> List<Connection>,
    private val foreground: () -> ForegroundFeedsState,
    /** The boundary each feed was last read at, so an unchanged feed costs one round trip. */
    private val progress: (String) -> Long,
    /** Reads settings and then the whole feed, returning the sequence it was read at. */
    private val read: suspend (String, Long) -> Long?,
    private val remember: (String, Long) -> Unit,
    /** The proposals waiting for the owner, before and after. */
    private val reviewable: () -> Set<ProposalRef> = { emptySet() },
    private val reconcileNotifications: (Set<ProposalRef>, Set<ProposalRef>) -> Unit = { _, _ -> },
) {
    suspend fun run(): BackgroundSyncDecision {
        load()
        val before = reviewable()
        val feeds = connections().filter { it.mode == ConnectionMode.GatewayFeed }
        if (feeds.isEmpty()) return BackgroundSyncDecision.Complete
        val live = foreground()
        val recovery = feeds.filterNot {
            live.foreground && live.gateways[it.serverUrl] is FeedListenerState.Live
        }
        if (recovery.isEmpty()) return BackgroundSyncDecision.Complete

        var unreachable = false
        for (feed in recovery) {
            val sequence =
                try {
                    read(feed.id, progress(feed.serverId))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // One feed's gateway being unreachable is not the others' problem, and the
                    // reason it failed is the repository's business: what this decides is only
                    // whether the job is worth running again.
                    unreachable = true
                    null
                }
            if (sequence == null) unreachable = true else remember(feed.serverId, sequence)
        }
        reconcileNotifications(before, reviewable())
        return if (unreachable) BackgroundSyncDecision.Retry else BackgroundSyncDecision.Complete
    }
}

/**
 * A headless read for a coalesced hint; it has no operation that can answer or execute anything.
 */
class FeedSyncWorker : CoroutineWorker {
    private val execute: suspend () -> BackgroundSyncDecision

    constructor(appContext: Context, params: WorkerParameters) : super(appContext, params) {
        execute = { runApplicationRead(appContext) }
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

    private suspend fun runApplicationRead(context: Context): BackgroundSyncDecision {
        val application =
            context.applicationContext as? SeekerVaultApplication
                ?: return BackgroundSyncDecision.Failed
        return application.feedReadRunner().run()
    }
}
