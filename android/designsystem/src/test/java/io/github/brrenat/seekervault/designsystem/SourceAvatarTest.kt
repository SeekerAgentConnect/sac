package io.github.brrenat.seekervault.designsystem

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
