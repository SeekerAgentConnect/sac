package io.github.brrenat.seekervault.designsystem.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class PreviewProbeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `48dp probe is exactly 144 pixels high`() {
        compose.setContent { SizeProbePreview() }

        val image = compose.onNodeWithTag("size-probe").captureToImage()
        assertEquals(144, image.height)
    }

    @Test
    fun `variable Roboto weights render differently from each other and platform default`() {
        compose.setContent { FontWeightProbe() }

        val normal = compose.onNodeWithTag("roboto-400").captureToImage().pixels()
        val medium = compose.onNodeWithTag("roboto-500").captureToImage().pixels()
        val bold = compose.onNodeWithTag("roboto-700").captureToImage().pixels()
        val platform = compose.onNodeWithTag("platform-default").captureToImage().pixels()

        assertFalse("Roboto 400 and 500 rendered identically", normal == medium)
        assertFalse("Roboto 500 and 700 rendered identically", medium == bold)
        assertFalse("Roboto 400 and 700 rendered identically", normal == bold)
        assertFalse("Bundled Roboto and platform default rendered identically", normal == platform)
    }

    @Test
    fun `Roboto Mono gives i and M equal glyph advance`() {
        compose.setContent { FontWeightProbe() }

        val narrowBounds = compose.onNodeWithTag("mono-iiii").getUnclippedBoundsInRoot()
        val wideBounds = compose.onNodeWithTag("mono-MMMM").getUnclippedBoundsInRoot()
        val narrow = narrowBounds.right - narrowBounds.left
        val wide = wideBounds.right - wideBounds.left
        assertEquals(narrow.value, wide.value, 0.01f)
    }

    private fun ImageBitmap.pixels(): List<Int> {
        val pixelMap = toPixelMap()
        return buildList(width * height) {
            for (y in 0 until height) {
                for (x in 0 until width) add(pixelMap[x, y].toArgb())
            }
        }
    }
}
