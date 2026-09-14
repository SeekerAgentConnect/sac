package io.github.brrenat.seekervault.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import io.github.brrenat.seekervault.MainActivity
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.isConnectionId

/**
 * Posts privacy-preserving request alerts after authoritative Sync. A notification carries only the
 * two opaque IDs needed to find the request locally; no request content or credential is shown or
 * handed to [MainActivity].
 */
class RequestNotificationManager(
    private val context: Context,
    private val configured: Boolean,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        if (!configured) return
        manager.createNotificationChannel(
            NotificationChannel(
                    REQUESTS_CHANNEL_ID,
                    context.getString(R.string.notification_channel_requests),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply {
                    description =
                        context.getString(R.string.notification_channel_requests_description)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    setShowBadge(true)
                }
        )
    }

    /**
     * A push is not request state. Only a request newly present after the completed Sync gets an
     * alert; a request that left PENDING has its old alert removed. Denied permission changes only
     * whether [notify] displays anything.
     */
    fun reconcile(
        before: Set<RequestKey>,
        after: Set<RequestKey>,
        connections: List<Connection>,
    ) {
        if (!configured) return
        // Permission can change between the check and NotificationManager. Losing it is a display
        // outcome only and must never turn a completed authoritative Sync into failed work.
        try {
            (before - after).forEach(::cancel)
        } catch (_: SecurityException) {
            // There is no notification to clean up that this process is allowed to reach.
        }
        if (!notificationsGranted(context)) return
        createChannels()
        val labels = connections.associate { it.id to it.label }
        (after - before).forEach { key ->
            val label = labels[key.connectionId] ?: return@forEach
            try {
                notify(key, label)
            } catch (_: SecurityException) {
                // Revocation raced the post. Sync already succeeded and remains authoritative.
            }
        }
    }

    private fun notify(key: RequestKey, label: String) {
        val notification =
            Notification.Builder(context, REQUESTS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.notification_request_title))
                .setContentText(context.getString(R.string.notification_request_text, label))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(RequestNotificationIntent.pendingIntent(context, key))
                .build()
        manager.notify(tag(key), REQUEST_NOTIFICATION_ID, notification)
    }

    private fun cancel(key: RequestKey) {
        manager.cancel(tag(key), REQUEST_NOTIFICATION_ID)
    }

    private fun tag(key: RequestKey) = "request:${key.connectionId}/${key.requestId}"

    companion object {
        const val REQUESTS_CHANNEL_ID = "requests_waiting_v1"
        internal const val REQUEST_NOTIFICATION_ID = 1
    }
}

/** The explicit, immutable route attached to one local request notification. */
object RequestNotificationIntent {
    internal const val ACTION_OPEN_REQUEST =
        "io.github.brrenat.seekervault.action.OPEN_NOTIFICATION_REQUEST"
    private const val EXTRA_CONNECTION_ID = "notification_connection_id"
    private const val EXTRA_REQUEST_ID = "notification_request_id"

    fun intent(context: Context, key: RequestKey): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_REQUEST)
            // PendingIntent identity ignores extras. Unique data keeps two requests from replacing
            // one another while remaining an explicit, app-local route.
            .setData(
                Uri.Builder()
                    .scheme("seekervault")
                    .authority("notification")
                    .appendPath(key.connectionId)
                    .appendPath(key.requestId)
                    .build()
            )
            .putExtra(EXTRA_CONNECTION_ID, key.connectionId)
            .putExtra(EXTRA_REQUEST_ID, key.requestId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pendingIntent(context: Context, key: RequestKey): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent(context, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Invalid or externally forged identifiers never become an internal route. */
    fun destination(intent: Intent?): RequestKey? {
        if (intent?.action != ACTION_OPEN_REQUEST) return null
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID) ?: return null
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return null
        if (!isConnectionId(connectionId) || !isConnectionId(requestId)) return null
        return RequestKey(connectionId, requestId)
    }
}

/**
 * Requests Android 13+'s runtime permission once, after a configured installation has a usable
 * paired connection. The OS owns the decision; denial has no callback into FCM registration,
 * foreground streams, manual Sync, or the periodic recovery scheduler.
 */
@Composable
fun RequestNotificationPermission(enabled: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var requestedHere by rememberSaveable { mutableStateOf(false) }
    var granted by remember { mutableStateOf(notificationsGranted(context)) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accepted ->
            requestedHere = true
            granted = accepted
        }
    val activity = LocalActivity.current
    val shouldExplain =
        activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            )
    val shouldRequest =
        shouldRequestNotificationPermission(
            configuredWithUsableConnection = enabled,
            granted = granted,
            requestedHere = requestedHere,
            shouldExplain = shouldExplain,
        )
    LaunchedEffect(shouldRequest) {
        if (shouldRequest) {
            requestedHere = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

internal fun shouldRequestNotificationPermission(
    configuredWithUsableConnection: Boolean,
    granted: Boolean,
    requestedHere: Boolean,
    shouldExplain: Boolean,
): Boolean = configuredWithUsableConnection && !granted && !requestedHere && !shouldExplain

internal fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
