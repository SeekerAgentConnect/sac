package io.github.brrenat.seekervault.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioRowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun offRowInvokesSelection() {
        var clicks = 0
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                RadioRow(
                    label = "Another token",
                    state = RadioRowState.Off,
                    onClick = { clicks += 1 },
                    modifier = Modifier.testTag("radio-row"),
                )
            }
        }

        compose.onNodeWithTag("radio-row").assertIsNotSelected().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun onRowIsSelected() {
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                RadioRow(
                    label = "Native SOL",
                    state = RadioRowState.On,
                    onClick = {},
                    modifier = Modifier.testTag("radio-row"),
                )
            }
        }

        compose.onNodeWithTag("radio-row").assertIsSelected()
    }
}
