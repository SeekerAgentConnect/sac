package io.github.brrenat.seekervault.designsystem.gallery

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class ComponentGalleryTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `gallery index opens a live preview fixture`() {
        compose.setContent { ComponentGallery(onClose = {}) }

        compose.onNodeWithTag("component-gallery-index").assertExists()
        compose
            .onNodeWithTag("gallery/button/variant=disabled size=lg")
            .assertExists()
            .performClick()
        compose.onNodeWithTag("component-gallery-detail").assertExists()
    }
}
