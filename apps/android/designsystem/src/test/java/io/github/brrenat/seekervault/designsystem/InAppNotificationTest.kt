package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gesture's thresholds and directions. What a dismissal looks like while it happens — the
 * banner under the finger, its opacity, the snap-back — is checked by eye against the specimens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class InAppNotificationTest {
    @get:Rule val compose = createComposeRule()

    private var opened = 0
    private var dismissed = 0

    @Test
    fun `a press that does not travel is a tap`() {
        banner()

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { click() }
        compose.waitForIdle()

        assertEquals(1, opened)
        assertEquals(0, dismissed)
    }

    @Test
    fun `a swipe sideways dismisses only once it is past seventy-two dp`() {
        banner()

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { swipeBy(70.dp.toPx(), 0f) }
        compose.waitForIdle()
        assertEquals(0, dismissed)

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { swipeBy(74.dp.toPx(), 0f) }
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(0, opened)
    }

    @Test
    fun `it dismisses in either sideways direction`() {
        banner()

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { swipeBy(-74.dp.toPx(), 0f) }
        compose.waitForIdle()

        assertEquals(1, dismissed)
    }

    @Test
    fun `a swipe up dismisses at forty dp, and a swipe down never does`() {
        banner()

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { swipeBy(0f, 120.dp.toPx()) }
        compose.waitForIdle()
        assertEquals(0, dismissed)

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { swipeBy(0f, -38.dp.toPx()) }
        compose.waitForIdle()
        assertEquals(0, dismissed)

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { swipeBy(0f, -42.dp.toPx()) }
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a travelling press is never also a tap`() {
        banner()

        compose.onNodeWithTag(InAppNotificationTag).performTouchInput { swipeBy(30.dp.toPx(), 0f) }
        compose.waitForIdle()

        // Under both thresholds: it snapped back, and it did not open anything on the way.
        assertEquals(0, dismissed)
        assertEquals(0, opened)
    }

    private fun TouchInjectionScope.swipeBy(x: Float, y: Float) {
        down(center)
        // Several moves, because one jump is not how a finger arrives and the handler accumulates.
        repeat(STEPS) {
            advanceEventTime(STEP_MILLIS)
            moveBy(Offset(x / STEPS, y / STEPS))
        }
        advanceEventTime(STEP_MILLIS)
        up()
    }

    private fun banner(subtitle: String? = "New request · studio-mac · funds move") {
        compose.setContent { Banner(subtitle) }
        compose.waitForIdle()
    }

    @Composable
    private fun Banner(subtitle: String?) {
        SeekerTheme(darkTheme = true) {
            Box(Modifier.fillMaxSize()) {
                InAppNotification(
                    kind = InAppNotificationKind.Request,
                    title = "Send 5 SOL",
                    subtitle = subtitle,
                    openActionLabel = "Send 5 SOL, open request",
                    dismissActionLabel = "Dismiss",
                    leaving = false,
                    onOpen = { opened += 1 },
                    onDismiss = { dismissed += 1 },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }

    private companion object {
        const val STEPS = 8
        const val STEP_MILLIS = 16L
    }
}
