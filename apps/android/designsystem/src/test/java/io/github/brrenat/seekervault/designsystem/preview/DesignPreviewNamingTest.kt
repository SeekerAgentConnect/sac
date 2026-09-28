package io.github.brrenat.seekervault.designsystem.preview

import io.github.brrenat.seekervault.designsystem.gallery.componentGallerySpecimens
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
        val moleculePaths =
            setOf(
                "fact-row/value-full-address.png",
                "fact-row/value-longest-wraps.png",
                "fact-row/value-mono.png",
                "fact-row/value-short.png",
                "daily-row/state-nolimit-scope-global.png",
                "daily-row/state-over-scope-connection.png",
                "daily-row/state-within-scope-global.png",
                "segmented/count-2-mode.png",
                "segmented/count-2-selected-0.png",
                "segmented/count-3-selected-1.png",
                "tab-bar/selected-pending.png",
                "nav-item/state-rest.png",
                "nav-item/state-selected.png",
                "nav-bar/state-home-selected.png",
                "section-header/trailing-button.png",
                "section-header/trailing-none.png",
                "text-field/state-error.png",
                "text-field/state-rest.png",
                "notice-card/sandbox-card.png",
                "notice-card/stale-card.png",
                "empty-state/screen-inbox.png",
                "empty-state/screen-rules.png",
                "filter-bar/src-studio-mac.png",
            )
        val organismPaths =
            setOf(
                "request-tile/kind-ack-state-centred.png",
                "request-tile/kind-ack-state-in-rail.png",
                "request-tile/kind-pred-state-centred.png",
                "request-tile/kind-pred-state-in-rail.png",
                "request-tile/kind-sig-state-centred.png",
                "request-tile/kind-sig-state-in-rail.png",
                "request-tile/kind-sign-state-centred.png",
                "request-tile/kind-sign-state-in-rail.png",
                "request-tile/kind-tx-state-centred.png",
                "request-tile/kind-tx-state-in-rail.png",
                "request-carousel/state-rest-centred-0.png",
                "inbox-row/network-none-env-production.png",
                "inbox-row/origin-request-verdict-ok.png",
                "inbox-row/origin-request-verdict-warning.png",
                "inbox-row/origin-signal-count-3-title-two-line.png",
                "inbox-row/origin-signal-verdict-warning.png",
                "history-row/state-cancelled.png",
                "history-row/state-dismissed.png",
                "history-row/state-expired.png",
                "history-row/state-sent.png",
                "history-row/state-simulated.png",
                "history-row/state-unknown.png",
                "history-detail/screen-long-content.png",
                "history-detail/screen-approved-pending.png",
                "history-detail/screen-approved-confirmed.png",
                "history-detail/screen-approved-failed.png",
                "history-detail/screen-declined.png",
                "history-detail/screen-expired.png",
                "history-detail/screen-cancelled.png",
                "history-detail/screen-dismissed-signal.png",
                "history-detail/screen-signed-message.png",
                "history-detail/screen-sandbox.png",
                "history-detail/body-approved-confirmed.png",
                "history-detail/body-cancelled.png",
                "history-detail/body-position-live.png",
                "history-detail/body-position-pending-sale.png",
                "history-detail/body-position-sold.png",
                "history-detail/body-position-unavailable.png",
                "history-row/tappable-confirmed.png",
                "history-row/tappable-pending.png",
                "server-row/state-connected.png",
                "server-row/state-disconnected.png",
                "server-row/state-unreachable.png",
                "rule-row/kind-action-state-checked.png",
                "rule-row/kind-action-state-unchecked.png",
                "rule-row/kind-asset-state-readonly.png",
                "rule-row/kind-asset.png",
                "rule-row/kind-program.png",
                "rule-row/kind-recipient.png",
                "verdict-card/verdict-ok.png",
                "verdict-card/verdict-warning-count-1.png",
                "verdict-card/verdict-warning-count-3.png",
                "owner-input-card/state-chosen-kind-prediction.png",
                "owner-input-card/state-chosen-kind-swap.png",
                "owner-input-card/state-unchosen-kind-swap.png",
                "terms-card/kind-swap-state-quoted.png",
                "notification/kind-request.png",
                "notification/kind-signal.png",
                "notification/kind-disconnected.png",
                "wallet-banner/variant-compact.png",
                "wallet-banner/variant-expanded.png",
                "wallet-handoff/wallet-seed-vault-kind-transfer.png",
                "sheet-scaffold/variant-plain.png",
                "sheet-scaffold/variant-stacked-over-blurred.png",
            )
        val screenPaths =
            setOf(
                "screens/sheet-transfer.png",
                "screens/sheet-swap.png",
                "screens/sheet-prediction.png",
                "screens/sheet-position-sale.png",
                "screens/sheet-position-sale-refused.png",
                "screens/sheet-signature.png",
                "screens/sheet-acknowledge.png",
            )
        val expectedPaths =
            atomPaths +
                moleculePaths +
                organismPaths +
                screenPaths +
                setOf(
                    "token-colour/surf.png",
                    "size-probe/height-48dp.png",
                    "font-weight-probe/roboto-400-500-700.png",
                    "icon-probe/material-icons-outlined.png",
                )

        assertEquals(expectedPaths.size, paths.size)
        assertEquals(paths.size, paths.distinct().size)
        assertEquals(expectedPaths, paths.toSet())

        val galleryPaths = componentGallerySpecimens.map {
            "${DesignPreviewNaming.slug(it.component)}/${DesignPreviewNaming.slug(it.variant)}.png"
        }
        assertEquals(galleryPaths.size, galleryPaths.distinct().size)
        assertEquals(paths.toSet(), galleryPaths.toSet())

        previews
            .filter {
                DesignPreviewNaming.relativePath(it, "png") in
                    atomPaths + moleculePaths + organismPaths + screenPaths
            }
            .forEach { preview ->
                assertEquals(
                    DesignPreviewNaming.relativePath(preview, "png").removeSuffix(".png"),
                    preview.previewInfo.name,
                )
            }
    }
}
