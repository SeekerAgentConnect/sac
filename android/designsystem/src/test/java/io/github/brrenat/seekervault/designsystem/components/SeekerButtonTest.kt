package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class SeekerButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `sizes keep their exact compact heights`() {
        val expected =
            mapOf(
                SeekerButtonSize.Sm to 32.dp,
                SeekerButtonSize.Md to 40.dp,
                SeekerButtonSize.Lg to 48.dp,
            )

        compose.setContent {
            SeekerTheme {
                Column {
                    expected.keys.forEach { size ->
                        SeekerButton(
                            label = "Action",
                            onClick = {},
                            variant = SeekerButtonVariant.Filled,
                            size = size,
                            modifier = Modifier.testTag("button-$size"),
                        )
                    }
                }
            }
        }

        expected.forEach { (size, height) ->
            compose.onNodeWithTag("button-$size").assertHeightIsEqualTo(height)
        }
    }

    @Test
    fun `disabled variant cannot invoke its action`() {
        var clicks = 0
        compose.setContent {
            SeekerTheme {
                SeekerButton(
                    label = "Action",
                    onClick = { clicks += 1 },
                    variant = SeekerButtonVariant.Disabled,
                    size = SeekerButtonSize.Md,
                    modifier = Modifier.testTag("button"),
                )
            }
        }

        compose.onNodeWithTag("button").assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun `leading icon tightens start padding and keeps an eight dp gap`() {
        compose.setContent {
            SeekerTheme {
                Column {
                    SeekerButton(
                        label = "Action",
                        onClick = {},
                        variant = SeekerButtonVariant.Filled,
                        size = SeekerButtonSize.Md,
                        modifier = Modifier.testTag("without-icon"),
                    )
                    SeekerButton(
                        label = "Action",
                        onClick = {},
                        variant = SeekerButtonVariant.Filled,
                        size = SeekerButtonSize.Md,
                        modifier = Modifier.testTag("with-icon"),
                        leadingIcon = { Box(Modifier.size(8.dp)) },
                    )
                }
            }
        }

        val withoutIcon = compose.onNodeWithTag("without-icon").getUnclippedBoundsInRoot()
        val withIcon = compose.onNodeWithTag("with-icon").getUnclippedBoundsInRoot()
        val withoutIconWidth = withoutIcon.right - withoutIcon.left
        val withIconWidth = withIcon.right - withIcon.left
        assertEquals(8f, withIconWidth.value - withoutIconWidth.value, 0.01f)
    }
}
