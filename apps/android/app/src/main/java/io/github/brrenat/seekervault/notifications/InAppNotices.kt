package io.github.brrenat.seekervault.notifications

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Service messages for the top banner: what the app did or learned — a connection added, a
 * publisher's decision, rules saved — said in the [InAppNotifications] host's `Info` style rather
 * than in a snackbar at the bottom of whichever screen raised it.
 *
 * A screen posts the text and forgets it. The host queues it behind whatever banner is showing; a
 * message posted while the app is not being looked at has nobody to read it and is dropped, as a
 * banner would be.
 */
class InAppNotices {
    private val _texts = MutableSharedFlow<String>(extraBufferCapacity = BUFFER)

    val texts: SharedFlow<String> = _texts.asSharedFlow()

    fun show(text: String) {
        if (text.isNotBlank()) _texts.tryEmit(text)
    }

    private companion object {
        const val BUFFER = 8
    }
}

/** The app's one [InAppNotices]. A preview or a screen test without the host gets a silent one. */
val LocalInAppNotices = staticCompositionLocalOf { InAppNotices() }
