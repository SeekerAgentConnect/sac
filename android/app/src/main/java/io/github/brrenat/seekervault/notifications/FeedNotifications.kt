package io.github.brrenat.seekervault.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.brrenat.seekervault.MainActivity
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.servers.ConnectionMode

/**
 * A proposal, by both IDs: a proposal ID is the publisher's, and the connection is where this phone
 * holds it. It is the whole of what a notification carries (SEE-92).
 */
data class ProposalRef(val connectionId: String, val proposalId: String)

/**
 * Alerts about proposals that are waiting for the owner, posted after an authoritative read
 * (SEE-92).
 *
 * It is [RequestNotificationManager] for the other kind of server, and deliberately the same shape:
 * a notification is posted for something that is newly waiting *after* the app has read the
 * authoritative state, it carries the two opaque IDs needed to find the document locally and
 * nothing about it, and the tap route opens a screen rather than doing anything.
 *
 * ## Why a second manager rather than one
 *
 * Because the two are about different things and must be able to disagree. A request belongs to one
 * paired sidecar and is answered on its behalf; a proposal is a broadcast, and what this device
 * does about one is its own business (SEE-89). They have separate channels so the owner can silence
 * one and keep the other, separate tags so neither can cancel the other's alerts, and separate
 * reconciliations because "waiting" means a different thing on each side.
 *
 * ## What is posted, and what is taken away
 *
 * The caller reads the reviewable proposals before and after the read and hands both sets here
 * (`sync/FeedSynchronization`). What appeared is posted; what left is cancelled. Everything that
 * makes a proposal stop waiting therefore takes its alert away and none of it is duplicated here:
 * the publisher withdrawing it, its expiry passing, the owner dismissing it on this device, and an
 * operation already begun are all reasons the caller's set no longer holds it
 * (`proposals/ProposalState.proposalStanding`).
 */
class ProposalNotificationManager(
    private val context: Context,
    private val configured: Boolean,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        if (!configured) return
        manager.createNotificationChannel(
            NotificationChannel(
                    FEEDS_CHANNEL_ID,
                    context.getString(R.string.notification_channel_feeds),
                    // Lower than a request's on purpose. A request is one server waiting for this
                    // owner's answer; a proposal is an offer to everyone subscribed, and nothing
                    // stops working if the owner looks at it later.
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    description = context.getString(R.string.notification_channel_feeds_description)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    setShowBadge(true)
                }
        )
    }

    /**
     * Posts what is newly waiting and cancels what is not, after a completed read.
     *
     * A hint is not state: nothing is posted for a message that arrived, only for a document the
     * app read from the gateway and applied through its own rules. Denied permission changes only
     * whether anything is displayed.
     */
    fun reconcile(
        before: Set<ProposalRef>,
        after: Set<ProposalRef>,
        connections: List<Connection>,
    ) {
        if (!configured) return
        try {
            (before - after).forEach(::cancel)
        } catch (_: SecurityException) {
            // There is no notification to clean up that this process is allowed to reach.
        }
        if (!notificationsGranted(context)) return
        createChannels()
        // A feed is never `usable` — that word is about a connection this phone calls with a
        // credential, and a feed is not one (SEE-88). What matters here is that the connection is
        // still a feed on this phone at all.
        val labels =
            connections
                .filter { it.mode == ConnectionMode.GatewayFeed }
                .associate {
                    it.id to it.label
                }
        (after - before).forEach { ref ->
            val label = labels[ref.connectionId] ?: return@forEach
            try {
                notify(ref, label)
            } catch (_: SecurityException) {
                // Revocation raced the post. The read already succeeded and remains authoritative.
            }
        }
    }

    /** Removes every posted alert owned by a feed that is gone. */
    fun cancelConnection(connectionId: String) {
        if (!configured) return
        val prefix = "proposal:$connectionId/"
        try {
            manager.activeNotifications
                .filter { it.id == PROPOSAL_NOTIFICATION_ID && it.tag?.startsWith(prefix) == true }
                .forEach { manager.cancel(it.tag, it.id) }
        } catch (_: SecurityException) {
            // Permission loss affects presentation only; the removal already succeeded.
        }
    }

    private fun notify(ref: ProposalRef, label: String) {
        val notification =
            Notification.Builder(context, FEEDS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.notification_proposal_title))
                .setContentText(context.getString(R.string.notification_proposal_text, label))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(ProposalNotificationIntent.pendingIntent(context, ref))
                .build()
        manager.notify(tag(ref), PROPOSAL_NOTIFICATION_ID, notification)
    }

    private fun cancel(ref: ProposalRef) {
        manager.cancel(tag(ref), PROPOSAL_NOTIFICATION_ID)
    }

    private fun tag(ref: ProposalRef) = "proposal:${ref.connectionId}/${ref.proposalId}"

    companion object {
        const val FEEDS_CHANNEL_ID = "feeds_waiting_v1"
        internal const val PROPOSAL_NOTIFICATION_ID = 2
    }
}

/** The explicit, immutable route attached to one proposal alert. */
object ProposalNotificationIntent {
    internal const val ACTION_OPEN_PROPOSAL =
        "io.github.brrenat.seekervault.action.OPEN_NOTIFICATION_PROPOSAL"
    private const val EXTRA_CONNECTION_ID = "notification_feed_connection_id"
    private const val EXTRA_PROPOSAL_ID = "notification_proposal_id"

    fun intent(context: Context, ref: ProposalRef): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_PROPOSAL)
            // PendingIntent identity ignores extras, so the data is what keeps two proposals from
            // replacing one another. It is an app-local URI and reaches nothing.
            .setData(
                Uri.Builder()
                    .scheme("seekervault")
                    .authority("notification")
                    .appendPath("feed")
                    .appendPath(ref.connectionId)
                    .appendPath(ref.proposalId)
                    .build()
            )
            .putExtra(EXTRA_CONNECTION_ID, ref.connectionId)
            .putExtra(EXTRA_PROPOSAL_ID, ref.proposalId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pendingIntent(context: Context, ref: ProposalRef): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent(context, ref),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Invalid or externally forged identifiers never become an internal route. */
    fun destination(intent: Intent?): ProposalRef? {
        if (intent?.action != ACTION_OPEN_PROPOSAL) return null
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID) ?: return null
        val proposalId = intent.getStringExtra(EXTRA_PROPOSAL_ID) ?: return null
        if (!isConnectionId(connectionId) || !isConnectionId(proposalId)) return null
        return ProposalRef(connectionId, proposalId)
    }
}
