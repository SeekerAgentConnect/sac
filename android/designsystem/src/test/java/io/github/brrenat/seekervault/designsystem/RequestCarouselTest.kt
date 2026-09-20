package io.github.brrenat.seekervault.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestCarouselTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun insertionBeforeTheVisibleCardPreservesItAndCountsNewItemsUntilViewed() {
        var items by mutableStateOf(listOf(item("a"), item("b"), item("c"), item("d")))
        compose.setContent {
            SeekerTheme {
                RequestCarousel(
                    items = items,
                    centredIndex = 2,
                    onItemClick = {},
                )
            }
        }
        compose.waitForIdle()
        val before =
            compose
                .onNodeWithTag(RequestCarouselTags.item("c"))
                .fetchSemanticsNode()
                .boundsInRoot
                .left

        compose.runOnIdle { items = listOf(item("new-1"), item("new-2")) + items }
        compose.waitForIdle()

        val after =
            compose
                .onNodeWithTag(RequestCarouselTags.item("c"))
                .fetchSemanticsNode()
                .boundsInRoot
                .left
        assertEquals(before, after, 0.5f)
        compose.onNodeWithText("← 2 new").assertTextEquals("← 2 new")

        compose.onNodeWithTag(RequestCarouselTags.LIST).performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithTag(RequestCarouselTags.NEW_ITEMS).assertDoesNotExist()
    }

    private fun item(id: String) =
        RequestCarouselItem(
            id = id,
            kind = RequestTileKind.Acknowledgement,
            tile =
                RequestTileModel(
                    title = id,
                    sourceName = "source",
                    supportingText = "source asks",
                    warningCount = 0,
                ),
        )
}
