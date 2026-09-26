package io.github.brrenat.seekervault.designsystem

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class SeekerFabTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `fab keeps the exact height`() {
        compose.setContent {
            SeekerTheme {
                SeekerFab(
                    label = "Add connection",
                    icon = { Text("+") },
                    onClick = {},
                    modifier = Modifier.testTag("fab"),
                )
            }
        }

        compose.onNodeWithTag("fab").assertHeightIsEqualTo(56.dp)
    }

    @Test
    fun `full width fab fills its available width`() {
        compose.setContent {
            SeekerTheme {
                SeekerFab(
                    label = "Scan QR code",
                    icon = { Text("+") },
                    onClick = {},
                    width = SeekerFabWidth.Full,
                    modifier = Modifier.testTag("fab"),
                )
            }
        }

        compose.onNodeWithTag("fab").assertWidthIsEqualTo(390.dp)
    }
}
