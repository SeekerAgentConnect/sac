package io.github.brrenat.seekervault.designsystem

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h1500dp-xxhdpi")
class ReviewSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `warning confirmation gates the primary action`() {
        var approvals = 0
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                ReviewSheet(
                    state = ReviewSheetFixtures.Transfer,
                    onPrimary = { approvals += 1 },
                    onSecondary = {},
                    onRules = {},
                    onChoose = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Approve and send").assertIsNotEnabled().performClick()
        assertEquals(0, approvals)

        compose.onNodeWithText("I have read the warning and want to approve anyway").performClick()
        compose.onNodeWithText("Approve and send").assertIsEnabled().performClick()
        assertEquals(1, approvals)
    }
}
