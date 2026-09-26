package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceAvatarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun avatarKeepsTheDesignBoundsAndInitials() {
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                SourceAvatar(
                    sourceName = "hermes-box",
                    initials = "HB",
                    modifier = Modifier.testTag("avatar"),
                )
            }
        }

        compose.onNodeWithTag("avatar").assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        compose.onNodeWithText("HB").assertExists()
    }
}

@RunWith(AndroidJUnit4::class)
class SourceChipWidthTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aLongServerPillStaysInsideA204DpCard() {
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                SourceChip(
                    sourceName = "studio-mac-with-a-very-long-label-that-must-ellipsize",
                    modifier = Modifier.widthIn(max = SeekerTheme.spacing.huge * 7).testTag("pill"),
                )
            }
        }
        val width =
            compose.onNodeWithTag("pill", useUnmergedTree = true).fetchSemanticsNode().size.width
        val limit = with(compose.density) { 204.dp.roundToPx() }
        assertTrue("$width px is wider than 204 dp ($limit px)", width <= limit)
    }
}
