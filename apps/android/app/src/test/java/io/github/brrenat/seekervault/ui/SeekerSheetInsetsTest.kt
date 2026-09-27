package io.github.brrenat.seekervault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.SheetScaffold
import io.github.brrenat.seekervault.designsystem.SheetScaffoldVariant
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bottom of an open sheet (SEE-150). The sheet's surface runs to the screen's bottom edge, and
 * what is written on it stops above the navigation bar — the home indicator of gesture navigation,
 * or the three buttons — with a visible gap, however tall that bar is.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class SeekerSheetInsetsTest {
    @get:Rule val compose = createComposeRule()

    private var root: android.view.View? = null

    private fun sheet(navigationBar: Dp, chrome: Boolean = true) {
        compose.setContent {
            root = LocalView.current
            SeekerTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    SeekerSheet(index = 0, back = 0, onDismiss = {}, chrome = chrome) {
                        if (chrome) {
                            Text("Revision 1", Modifier.testTag(LAST))
                        } else {
                            SheetScaffold(
                                title = "Connection",
                                variant = SheetScaffoldVariant.Plain,
                                onClose = {},
                                expandToAvailableHeight = true,
                                body = {
                                    repeat(30) { Text("Revision $it") }
                                    Text("Final revision", Modifier.testTag(LAST))
                                },
                            )
                        }
                    }
                }
            }
        }
        navigationBar(navigationBar)
        if (!chrome) scrollToEnd()
    }

    private fun navigationBar(navigationBar: Dp) {
        compose.runOnUiThread {
            // xxhdpi is 3 pixels to the dp.
            val pixels = (navigationBar.value * 3).toInt()
            ViewCompat.dispatchApplyWindowInsets(
                checkNotNull(root),
                WindowInsetsCompat.Builder()
                    .setInsets(
                        WindowInsetsCompat.Type.navigationBars(),
                        Insets.of(0, 0, 0, pixels),
                    )
                    .build(),
            )
        }
        compose.waitForIdle()
    }

    private fun scrollToEnd() {
        compose.onNodeWithTag(LAST).performScrollTo()
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, Float.MAX_VALUE)
        }
        compose.waitForIdle()
    }

    private fun gapBelowLastLine(): Dp {
        val screen = compose.onRoot().getBoundsInRoot().bottom
        return screen - compose.onNodeWithTag(LAST).getBoundsInRoot().bottom
    }

    @Test
    fun `with gesture navigation the last line stops above the home indicator with a gap`() {
        sheet(navigationBar = 24.dp)

        assertEquals(24.dp + 16.dp, gapBelowLastLine())
    }

    @Test
    fun `with three-button navigation the last line stops above the buttons with a gap`() {
        sheet(navigationBar = 48.dp)

        assertEquals(48.dp + 16.dp, gapBelowLastLine())
    }

    @Test
    fun `library-chromed sheet grows its bottom clearance with the navigation bar`() {
        sheet(navigationBar = 24.dp, chrome = false)

        assertEquals(24.dp + 24.dp, gapBelowLastLine())

        navigationBar(48.dp)
        scrollToEnd()

        assertEquals(48.dp + 24.dp, gapBelowLastLine())
    }

    private companion object {
        const val LAST = "last"
    }
}
