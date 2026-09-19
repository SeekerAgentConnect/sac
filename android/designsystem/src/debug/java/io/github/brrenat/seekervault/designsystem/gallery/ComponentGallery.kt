package io.github.brrenat.seekervault.designsystem.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import io.github.brrenat.seekervault.designsystem.*
import io.github.brrenat.seekervault.designsystem.preview.*
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

internal data class GallerySpecimen(
    val component: String,
    val variant: String,
    val content: @Composable () -> Unit,
) {
    val key: String
        get() = "$component/$variant"
}

/**
 * The live fixtures used by the Roborazzi previews, exposed only in the debug library variant.
 *
 * The list is deliberately explicit: DesignPreviewNamingTest compares it with the scanner's
 * complete DesignRef set so a new preview cannot land without becoming reachable here.
 */
internal val componentGallerySpecimens =
    listOf(
        GallerySpecimen(component = "button", variant = "variant=disabled size=lg") {
            ButtonDisabledLargePreview()
        },
        GallerySpecimen(component = "button", variant = "variant=disabled size=md") {
            ButtonDisabledMediumPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=disabled size=sm") {
            ButtonDisabledSmallPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=error size=lg") {
            ButtonErrorLargePreview()
        },
        GallerySpecimen(component = "button", variant = "variant=error size=md") {
            ButtonErrorMediumPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=error size=sm") {
            ButtonErrorSmallPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=errorStrong size=lg") {
            ButtonErrorStrongLargePreview()
        },
        GallerySpecimen(component = "button", variant = "variant=errorStrong size=md") {
            ButtonErrorStrongMediumPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=errorStrong size=sm") {
            ButtonErrorStrongSmallPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=filled size=lg") {
            ButtonFilledLargePreview()
        },
        GallerySpecimen(component = "button", variant = "variant=filled size=md") {
            ButtonFilledMediumPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=filled size=sm") {
            ButtonFilledSmallPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=neutral size=lg") {
            ButtonNeutralLargePreview()
        },
        GallerySpecimen(component = "button", variant = "variant=neutral size=md") {
            ButtonNeutralMediumPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=neutral size=sm") {
            ButtonNeutralSmallPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=tertiary size=lg") {
            ButtonTertiaryLargePreview()
        },
        GallerySpecimen(component = "button", variant = "variant=tertiary size=md") {
            ButtonTertiaryMediumPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=tertiary size=sm") {
            ButtonTertiarySmallPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=tonal size=lg") {
            ButtonTonalLargePreview()
        },
        GallerySpecimen(component = "button", variant = "variant=tonal size=md") {
            ButtonTonalMediumPreview()
        },
        GallerySpecimen(component = "button", variant = "variant=tonal size=sm") {
            ButtonTonalSmallPreview()
        },
        GallerySpecimen(component = "check-row", variant = "state=checked") {
            CheckRowCheckedPreview()
        },
        GallerySpecimen(component = "check-row", variant = "state=unchecked") {
            CheckRowUncheckedPreview()
        },
        GallerySpecimen(component = "check-row", variant = "state=unchecked label=warning-ack") {
            CheckRowWarningAcknowledgementPreview()
        },
        GallerySpecimen(component = "daily-row", variant = "state=nolimit scope=global") {
            DailyRowNoLimitGlobalPreview()
        },
        GallerySpecimen(component = "daily-row", variant = "state=over scope=connection") {
            DailyRowOverConnectionPreview()
        },
        GallerySpecimen(component = "daily-row", variant = "state=within scope=global") {
            DailyRowWithinGlobalPreview()
        },
        GallerySpecimen(component = "empty-state", variant = "screen=inbox") {
            EmptyStateInboxPreview()
        },
        GallerySpecimen(component = "empty-state", variant = "screen=rules") {
            EmptyStateRulesPreview()
        },
        GallerySpecimen(component = "env-chip", variant = "env=production") {
            EnvChipProductionPreview()
        },
        GallerySpecimen(component = "env-chip", variant = "env=sandbox") {
            EnvChipSandboxPreview()
        },
        GallerySpecimen(component = "env-chip", variant = "env=sandbox short") {
            EnvChipSandboxShortPreview()
        },
        GallerySpecimen(component = "fab", variant = "variant=filled icon=add") {
            FabFilledAddPreview()
        },
        GallerySpecimen(component = "fab", variant = "variant=filled width=full") {
            FabFilledFullWidthPreview()
        },
        GallerySpecimen(component = "fact-row", variant = "value=full-address") {
            FactRowFullAddressPreview()
        },
        GallerySpecimen(component = "fact-row", variant = "value=longest wraps") {
            FactRowLongestWrapsPreview()
        },
        GallerySpecimen(component = "fact-row", variant = "value=mono") { FactRowMonoPreview() },
        GallerySpecimen(component = "fact-row", variant = "value=short") { FactRowShortPreview() },
        GallerySpecimen(component = "filter-bar", variant = "src=studio-mac") {
            FilterBarStudioMacPreview()
        },
        GallerySpecimen(component = "font-weight-probe", variant = "roboto=400-500-700") {
            FontWeightProbe()
        },
        GallerySpecimen(component = "history-row", variant = "state=cancelled") {
            HistoryRowCancelledPreview()
        },
        GallerySpecimen(component = "history-row", variant = "state=dismissed") {
            HistoryRowDismissedPreview()
        },
        GallerySpecimen(component = "history-row", variant = "state=expired") {
            HistoryRowExpiredPreview()
        },
        GallerySpecimen(component = "history-row", variant = "state=sent") {
            HistoryRowSentPreview()
        },
        GallerySpecimen(component = "history-row", variant = "state=simulated") {
            HistoryRowSimulatedPreview()
        },
        GallerySpecimen(component = "history-row", variant = "state=unknown") {
            HistoryRowUnknownPreview()
        },
        GallerySpecimen(component = "icon-probe", variant = "material-icons-outlined") {
            IconProbePreview()
        },
        GallerySpecimen(component = "inbox-row", variant = "network=none env=production") {
            InboxRowProductionNoNetworkPreview()
        },
        GallerySpecimen(component = "inbox-row", variant = "origin=request verdict=ok") {
            InboxRowRequestOkPreview()
        },
        GallerySpecimen(component = "inbox-row", variant = "origin=request verdict=warning") {
            InboxRowRequestWarningPreview()
        },
        GallerySpecimen(component = "inbox-row", variant = "origin=signal count=3 title=two-line") {
            InboxRowSignalThreeWarningsPreview()
        },
        GallerySpecimen(component = "inbox-row", variant = "origin=signal verdict=warning") {
            InboxRowSignalWarningPreview()
        },
        GallerySpecimen(component = "nav-bar", variant = "state=home-selected") {
            FourItemNavBarPreview()
        },
        GallerySpecimen(component = "nav-item", variant = "state=rest") { NavItemRestPreview() },
        GallerySpecimen(component = "nav-item", variant = "state=selected") {
            NavItemSelectedPreview()
        },
        GallerySpecimen(component = "network-chip", variant = "network=devnet") {
            NetworkChipDevnetPreview()
        },
        GallerySpecimen(component = "network-chip", variant = "network=mainnet") {
            NetworkChipMainnetPreview()
        },
        GallerySpecimen(component = "notice-card", variant = "sandbox-card") {
            NoticeCardSandboxPreview()
        },
        GallerySpecimen(component = "notice-card", variant = "stale-card") {
            NoticeCardStalePreview()
        },
        GallerySpecimen(component = "owner-input-card", variant = "state=chosen kind=prediction") {
            OwnerInputCardPredictionChosenPreview()
        },
        GallerySpecimen(component = "owner-input-card", variant = "state=chosen kind=swap") {
            OwnerInputCardSwapChosenPreview()
        },
        GallerySpecimen(component = "owner-input-card", variant = "state=unchosen kind=swap") {
            OwnerInputCardSwapUnchosenPreview()
        },
        GallerySpecimen(component = "radio-row", variant = "state=off") { RadioRowOffPreview() },
        GallerySpecimen(component = "radio-row", variant = "state=on") { RadioRowOnPreview() },
        GallerySpecimen(component = "request-carousel", variant = "state=rest centred=0") {
            RequestCarouselRestPreview()
        },
        GallerySpecimen(component = "request-tile", variant = "kind=ack state=centred") {
            RequestTileAckCentredPreview()
        },
        GallerySpecimen(component = "request-tile", variant = "kind=ack state=in-rail") {
            RequestTileAckInRailPreview()
        },
        GallerySpecimen(component = "request-tile", variant = "kind=pred state=centred") {
            RequestTilePredictionCentredPreview()
        },
        GallerySpecimen(component = "request-tile", variant = "kind=pred state=in-rail") {
            RequestTilePredictionInRailPreview()
        },
        GallerySpecimen(component = "request-tile", variant = "kind=sig state=centred") {
            RequestTileSwapCentredPreview()
        },
        GallerySpecimen(component = "request-tile", variant = "kind=sig state=in-rail") {
            RequestTileSwapInRailPreview()
        },
        GallerySpecimen(component = "request-tile", variant = "kind=sign state=centred") {
            RequestTileSignatureCentredPreview()
        },
        GallerySpecimen(component = "request-tile", variant = "kind=sign state=in-rail") {
            RequestTileSignatureInRailPreview()
        },
        GallerySpecimen(component = "request-tile", variant = "kind=tx state=centred") {
            RequestTileTransferCentredPreview()
        },
        GallerySpecimen(component = "request-tile", variant = "kind=tx state=in-rail") {
            RequestTileTransferInRailPreview()
        },
        GallerySpecimen(component = "rule-row", variant = "kind=action state=checked") {
            RuleRowActionCheckedPreview()
        },
        GallerySpecimen(component = "rule-row", variant = "kind=action state=unchecked") {
            RuleRowActionUncheckedPreview()
        },
        GallerySpecimen(component = "rule-row", variant = "kind=asset") { RuleRowAssetPreview() },
        GallerySpecimen(component = "rule-row", variant = "kind=asset state=readonly") {
            RuleRowAssetReadOnlyPreview()
        },
        GallerySpecimen(component = "rule-row", variant = "kind=program") {
            RuleRowProgramPreview()
        },
        GallerySpecimen(component = "rule-row", variant = "kind=recipient") {
            RuleRowRecipientPreview()
        },
        GallerySpecimen(component = "scope-chip", variant = "from=connection") {
            ScopeChipConnectionPreview()
        },
        GallerySpecimen(component = "scope-chip", variant = "from=global") {
            ScopeChipGlobalPreview()
        },
        GallerySpecimen(component = "scope-chip", variant = "from=none") { ScopeChipNonePreview() },
        GallerySpecimen(component = "screens", variant = "sheet-acknowledge") {
            ReviewSheetAcknowledgePreview()
        },
        GallerySpecimen(component = "screens", variant = "sheet-prediction") {
            ReviewSheetPredictionPreview()
        },
        GallerySpecimen(component = "screens", variant = "sheet-signature") {
            ReviewSheetSignaturePreview()
        },
        GallerySpecimen(component = "screens", variant = "sheet-swap") { ReviewSheetSwapPreview() },
        GallerySpecimen(component = "screens", variant = "sheet-transfer") {
            ReviewSheetTransferPreview()
        },
        GallerySpecimen(component = "section-header", variant = "trailing=button") {
            SectionHeaderButtonPreview()
        },
        GallerySpecimen(component = "section-header", variant = "trailing=none") {
            SectionHeaderNonePreview()
        },
        GallerySpecimen(component = "segmented", variant = "count=2 mode") {
            SegmentedTwoModePreview()
        },
        GallerySpecimen(component = "segmented", variant = "count=2 selected=0") {
            SegmentedTwoSelectedPreview()
        },
        GallerySpecimen(component = "segmented", variant = "count=3 selected=1") {
            SegmentedThreeSelectedPreview()
        },
        GallerySpecimen(component = "server-row", variant = "state=connected") {
            ServerRowConnectedPreview()
        },
        GallerySpecimen(component = "server-row", variant = "state=disconnected") {
            ServerRowDisconnectedPreview()
        },
        GallerySpecimen(component = "server-row", variant = "state=unreachable") {
            ServerRowUnreachablePreview()
        },
        GallerySpecimen(component = "sheet-scaffold", variant = "variant=plain") {
            SheetScaffoldPlainPreview()
        },
        GallerySpecimen(component = "sheet-scaffold", variant = "variant=stacked-over-blurred") {
            SheetScaffoldStackedPreview()
        },
        GallerySpecimen(component = "signal-label", variant = "origin=signal") {
            SignalLabelPreview()
        },
        GallerySpecimen(component = "signal-label", variant = "origin=signal onTile") {
            SignalLabelOnTilePreview()
        },
        GallerySpecimen(component = "size-probe", variant = "height=48dp") { SizeProbePreview() },
        GallerySpecimen(component = "source-avatar", variant = "src=hermes-box") {
            SourceAvatarHermesBoxPreview()
        },
        GallerySpecimen(component = "source-avatar", variant = "src=runner-node") {
            SourceAvatarRunnerNodePreview()
        },
        GallerySpecimen(component = "source-avatar", variant = "src=studio-mac") {
            SourceAvatarStudioMacPreview()
        },
        GallerySpecimen(component = "source-chip", variant = "src=feed size=13") {
            SourceChipFeedPreview()
        },
        GallerySpecimen(component = "source-chip", variant = "src=feed size=13 longest") {
            SourceChipFeedLongestPreview()
        },
        GallerySpecimen(component = "source-chip", variant = "src=hermes-box") {
            SourceChipHermesBoxPreview()
        },
        GallerySpecimen(component = "source-chip", variant = "src=runner-node") {
            SourceChipRunnerNodePreview()
        },
        GallerySpecimen(component = "source-chip", variant = "src=studio-mac") {
            SourceChipStudioMacPreview()
        },
        GallerySpecimen(component = "source-chip", variant = "truncating at 120px") {
            SourceChipTruncatedPreview()
        },
        GallerySpecimen(component = "switch-row", variant = "state=off") { SwitchRowOffPreview() },
        GallerySpecimen(component = "switch-row", variant = "state=on") { SwitchRowOnPreview() },
        GallerySpecimen(component = "tab-bar", variant = "selected=pending") {
            TabBarPendingPreview()
        },
        GallerySpecimen(component = "terms-card", variant = "kind=swap state=quoted") {
            TermsCardSwapQuotedPreview()
        },
        GallerySpecimen(component = "text-field", variant = "state=error") {
            TextFieldErrorPreview()
        },
        GallerySpecimen(component = "text-field", variant = "state=rest") {
            TextFieldRestPreview()
        },
        GallerySpecimen(component = "token-colour", variant = "--surf") { ThemeSwatchPreview() },
        GallerySpecimen(component = "verdict-card", variant = "verdict=ok") {
            VerdictCardOkPreview()
        },
        GallerySpecimen(component = "verdict-card", variant = "verdict=warning count=1") {
            VerdictCardOneWarningPreview()
        },
        GallerySpecimen(component = "verdict-card", variant = "verdict=warning count=3") {
            VerdictCardThreeWarningsPreview()
        },
        GallerySpecimen(component = "verdict-pill", variant = "verdict=ok") {
            VerdictPillOkPreview()
        },
        GallerySpecimen(component = "verdict-pill", variant = "verdict=ok onTile") {
            VerdictPillOkOnTilePreview()
        },
        GallerySpecimen(component = "verdict-pill", variant = "verdict=warning") {
            VerdictPillWarningPreview()
        },
        GallerySpecimen(component = "verdict-pill", variant = "verdict=warning count=3") {
            VerdictPillWarningCountPreview()
        },
        GallerySpecimen(component = "wallet-banner", variant = "variant=compact") {
            WalletBannerCompactPreview()
        },
        GallerySpecimen(component = "wallet-banner", variant = "variant=expanded") {
            WalletBannerExpandedPreview()
        },
        GallerySpecimen(component = "wallet-handoff", variant = "wallet=seed-vault kind=transfer") {
            WalletHandoffTransferPreview()
        },
    )

/** Debug-only, on-device index for every component preview in designsystem. */
@Composable
fun ComponentGallery(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = componentGallerySpecimens.firstOrNull { it.key == selectedKey }

    SeekerTheme(darkTheme = true) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(SeekerTheme.colors.surface0)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            GalleryToolbar(
                selected = selected,
                onBack = { selectedKey = null },
                onClose = onClose,
            )
            if (selected == null) {
                GalleryIndex(onSelect = { selectedKey = it.key })
            } else {
                GalleryDetail(selected)
            }
        }
    }
}

@Composable
private fun GalleryToolbar(
    selected: GallerySpecimen?,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = SeekerTheme.spacing.md,
                    vertical = SeekerTheme.spacing.xs,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to component list",
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = selected?.component ?: "Component gallery",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text =
                    selected?.variant ?: "${componentGallerySpecimens.size} live preview fixtures",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onClose) {
            Icon(imageVector = Icons.Outlined.Close, contentDescription = "Close gallery")
        }
    }
    HorizontalDivider()
}

@Composable
private fun GalleryIndex(onSelect: (GallerySpecimen) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().semantics { testTag = "component-gallery-index" },
        contentPadding =
            PaddingValues(
                horizontal = SeekerTheme.spacing.xl,
                vertical = SeekerTheme.spacing.md,
            ),
    ) {
        itemsIndexed(
            items = componentGallerySpecimens,
            key = { _, specimen -> specimen.key },
        ) { index, specimen ->
            if (
                index == 0 || componentGallerySpecimens[index - 1].component != specimen.component
            ) {
                Text(
                    text = specimen.component,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                top = SeekerTheme.spacing.lg,
                                bottom = SeekerTheme.spacing.xs,
                            ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Surface(
                modifier =
                    Modifier.fillMaxWidth()
                        .semantics {
                            testTag = "gallery/${specimen.key}"
                            contentDescription = "${specimen.component}, ${specimen.variant}"
                        }
                        .clickable { onSelect(specimen) },
                color = SeekerTheme.colors.surface1,
            ) {
                Text(
                    text = specimen.variant,
                    modifier =
                        Modifier.padding(
                            horizontal = SeekerTheme.spacing.xl,
                            vertical = SeekerTheme.spacing.lg,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun GalleryDetail(specimen: GallerySpecimen) {
    val base =
        Modifier.fillMaxSize().semantics {
            testTag = "component-gallery-detail"
            contentDescription = "${specimen.component}, ${specimen.variant}"
        }

    if (specimen.component == "sheet-scaffold") {
        Box(modifier = base, contentAlignment = Alignment.TopCenter) { specimen.content() }
    } else {
        Column(
            modifier = base.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            specimen.content()
        }
    }
}
