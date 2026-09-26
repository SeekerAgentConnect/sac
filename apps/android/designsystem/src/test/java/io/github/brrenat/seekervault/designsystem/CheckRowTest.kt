package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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

@RunWith(AndroidJUnit4::class)
class CheckRowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun standardRowRequestsChecked() {
        var requested: CheckRowState? = null
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                CheckRow(
                    label = "Not expected",
                    state = CheckRowState.Unchecked,
                    onStateChange = { requested = it },
                    modifier = Modifier.width(358.dp).testTag("check-row"),
                )
            }
        }

        compose.onNodeWithTag("check-row").assertIsOff().performClick()
        assertEquals(CheckRowState.Checked, requested)
    }

    @Test
    fun checkedRowRequestsUnchecked() {
        var requested: CheckRowState? = null
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                CheckRow(
                    label = "Expected",
                    state = CheckRowState.Checked,
                    onStateChange = { requested = it },
                    modifier = Modifier.width(358.dp).testTag("check-row"),
                )
            }
        }

        compose.onNodeWithTag("check-row").assertIsOn().performClick()
        assertEquals(CheckRowState.Unchecked, requested)
    }

    @Test
    fun warningAcknowledgementUsesCheckboxSemantics() {
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                CheckRow(
                    label = "I have read all 3 warnings and want to approve anyway",
                    state = CheckRowState.Unchecked,
                    onStateChange = {},
                    contentKind = CheckRowContentKind.WarningAcknowledgement,
                    modifier = Modifier.width(358.dp).testTag("check-row"),
                )
            }
        }

        compose.onNodeWithTag("check-row").assertIsOff()
    }
}
