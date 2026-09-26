package io.github.brrenat.seekervault.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import io.github.brrenat.seekervault.R

/** The three pieces Android can keep distinct in a native collapsed or expanded notification. */
internal data class ReviewNotificationCopy(
    val title: String,
    val source: String,
    val summary: String,
)

/**
 * The shared native presentation for every review notification.
 *
 * Android owns the background, typography, truncation, and light/dark treatment. The app supplies
 * its monochrome status icon, full-colour identity, approved accent, and semantic text fields. A
 * native [Notification.BigTextStyle] keeps the complete source and summary available when a long
 * connection name or large system text cannot fit in the collapsed row.
 */
internal fun reviewNotification(
    context: Context,
    channelId: String,
    copy: ReviewNotificationCopy,
    contentIntent: PendingIntent,
): Notification {
    val source =
        copy.source.trim().ifEmpty { context.getString(R.string.notification_source_unknown) }
    val sourceLine = context.getString(R.string.notification_source, source)
    val expanded = context.getString(R.string.notification_expanded_text, sourceLine, copy.summary)
    return Notification.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification_sac)
        .setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
        .setColor(context.getColor(R.color.notification_accent))
        .setColorized(false)
        .setContentTitle(copy.title)
        .setSubText(sourceLine)
        .setContentText(copy.summary)
        .setStyle(Notification.BigTextStyle().setBigContentTitle(copy.title).bigText(expanded))
        .setCategory(Notification.CATEGORY_REMINDER)
        .setVisibility(Notification.VISIBILITY_SECRET)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)
        .build()
}
