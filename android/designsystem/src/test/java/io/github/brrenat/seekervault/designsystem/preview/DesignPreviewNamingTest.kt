package io.github.brrenat.seekervault.designsystem.preview

import io.github.brrenat.seekervault.designsystem.previewtesting.DesignPreviewNaming
import org.junit.Assert.assertEquals
import org.junit.Test
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner

class DesignPreviewNamingTest {
    @Test
    fun `variant slugs match SEE-113`() {
        assertEquals("state-centred", DesignPreviewNaming.slug("state=centred"))
        assertEquals(
            "verdict-warning-count-3",
            DesignPreviewNaming.slug("verdict=warning count=3"),
        )
        assertEquals("surf", DesignPreviewNaming.slug("--surf"))
        assertEquals(
            "variant-stacked-over-blurred",
            DesignPreviewNaming.slug("variant=stacked-over-blurred"),
        )
    }

    @Test
    fun `every design-system preview has one unique design path`() {
        val previews =
            AndroidComposablePreviewScanner()
                .scanPackageTrees("io.github.brrenat.seekervault.designsystem")
                .includePrivatePreviews()
                .getPreviews()
        val paths = previews.map { DesignPreviewNaming.relativePath(it, "png") }

        assertEquals(4, paths.size)
        assertEquals(paths.size, paths.distinct().size)
        assertEquals(
            setOf(
                "token-colour/surf.png",
                "size-probe/height-48dp.png",
                "font-weight-probe/roboto-400-500-700.png",
                "icon-probe/material-icons-outlined.png",
            ),
            paths.toSet(),
        )
    }
}
