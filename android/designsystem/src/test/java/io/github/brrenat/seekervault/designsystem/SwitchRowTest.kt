package io.github.brrenat.seekervault.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
class SwitchRowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun offRowUsesSwitchSemanticsAndRequestsOn() {
        var requested: SwitchRowState? = null
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                SwitchRow(
                    label = "Only these assets may move",
                    state = SwitchRowState.Off,
                    onStateChange = { requested = it },
                    modifier = Modifier.testTag("switch-row"),
                )
            }
        }

        compose.onNodeWithTag("switch-row").assertIsOff().performClick()
        assertLayoutHeight("switch-row", 36)
        assertEquals(SwitchRowState.On, requested)
    }

    @Test
    fun onRowRequestsOff() {
        var requested: SwitchRowState? = null
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                SwitchRow(
                    label = "Only these assets may move",
                    state = SwitchRowState.On,
                    onStateChange = { requested = it },
                    modifier = Modifier.testTag("switch-row"),
                )
            }
        }

        compose.onNodeWithTag("switch-row").assertIsOn().performClick()
        assertEquals(SwitchRowState.Off, requested)
    }

    private fun assertLayoutHeight(tag: String, expected: Int) {
        val height =
            compose.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates.size.height
        assertEquals(expected.toFloat(), with(compose.density) { height.toDp().value }, 0.1f)
    }
}
