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

        val atomPaths = buildSet {
            addAll(
                setOf(
                    "verdict-pill/verdict-ok.png",
                    "verdict-pill/verdict-ok-ontile.png",
                    "verdict-pill/verdict-warning.png",
                    "verdict-pill/verdict-warning-count-3.png",
                    "signal-label/origin-signal.png",
                    "signal-label/origin-signal-ontile.png",
                    "source-chip/src-feed-size-13.png",
                    "source-chip/src-feed-size-13-longest.png",
                    "source-chip/src-hermes-box.png",
                    "source-chip/src-runner-node.png",
                    "source-chip/src-studio-mac.png",
                    "source-chip/truncating-at-120px.png",
                    "env-chip/env-production.png",
                    "env-chip/env-sandbox.png",
                    "env-chip/env-sandbox-short.png",
                    "network-chip/network-devnet.png",
                    "network-chip/network-mainnet.png",
                    "scope-chip/from-connection.png",
                    "scope-chip/from-global.png",
                    "scope-chip/from-none.png",
                    "source-avatar/src-hermes-box.png",
                    "source-avatar/src-runner-node.png",
                    "source-avatar/src-studio-mac.png",
                    "switch-row/state-off.png",
                    "switch-row/state-on.png",
                    "check-row/state-checked.png",
                    "check-row/state-unchecked.png",
                    "check-row/state-unchecked-label-warning-ack.png",
                    "radio-row/state-off.png",
                    "radio-row/state-on.png",
                    "fab/variant-filled-icon-add.png",
                    "fab/variant-filled-width-full.png",
                )
            )
            listOf("disabled", "error", "errorstrong", "filled", "neutral", "tertiary", "tonal")
                .forEach { variant ->
                    listOf("lg", "md", "sm").forEach { size ->
                        add("button/variant-$variant-size-$size.png")
                    }
                }
        }
        val expectedPaths =
            atomPaths +
                setOf(
                    "token-colour/surf.png",
                    "size-probe/height-48dp.png",
                    "font-weight-probe/roboto-400-500-700.png",
                    "icon-probe/material-icons-outlined.png",
                )

        assertEquals(expectedPaths.size, paths.size)
        assertEquals(paths.size, paths.distinct().size)
        assertEquals(expectedPaths, paths.toSet())

        previews
            .filter { DesignPreviewNaming.relativePath(it, "png") in atomPaths }
            .forEach { preview ->
                assertEquals(
                    DesignPreviewNaming.relativePath(preview, "png").removeSuffix(".png"),
                    preview.previewInfo.name,
                )
            }
    }
}
