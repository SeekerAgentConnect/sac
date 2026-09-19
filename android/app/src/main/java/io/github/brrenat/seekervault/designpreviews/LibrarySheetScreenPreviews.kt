package io.github.brrenat.seekervault.designpreviews

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.connections.HomeScreen
import io.github.brrenat.seekervault.designsystem.AddAddressSheet
import io.github.brrenat.seekervault.designsystem.AddAddressSheetCallbacks
import io.github.brrenat.seekervault.designsystem.AssetEditorSheet
import io.github.brrenat.seekervault.designsystem.AssetEditorSheetCallbacks
import io.github.brrenat.seekervault.designsystem.ConnectionDetailSheet
import io.github.brrenat.seekervault.designsystem.ConnectionDetailSheetCallbacks
import io.github.brrenat.seekervault.designsystem.ConnectionRulesSheet
import io.github.brrenat.seekervault.designsystem.GlobalRulesSheet
import io.github.brrenat.seekervault.designsystem.ReviewSheet
import io.github.brrenat.seekervault.designsystem.ReviewSheetFixtures
import io.github.brrenat.seekervault.designsystem.RulesSectionKind
import io.github.brrenat.seekervault.designsystem.RulesSheetCallbacks
import io.github.brrenat.seekervault.designsystem.SheetCompositionFixtures
import io.github.brrenat.seekervault.designsystem.StackedSheetUnderlay
import io.github.brrenat.seekervault.designsystem.WalletHandoffSheet
import io.github.brrenat.seekervault.designsystem.WalletHandoffSheetCallbacks
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

private const val SheetPreviewWidth = 390
private const val SheetPreviewHeight = 844
private const val SheetPreviewDark = Configuration.UI_MODE_NIGHT_YES

private val FullSheetTop
    @Composable get() = SeekerTheme.spacing.jumbo * 3 + SeekerTheme.spacing.xs

private val BackplateTop
    @Composable
    get() = SeekerTheme.spacing.jumbo * 2 + SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs

@Composable
private fun SheetScreen(content: @Composable BoxScope.() -> Unit) {
    SeekerTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            HomeScreen(state = homeDesignFixture(), callbacks = homePreviewCallbacks())
            content()
        }
    }
}

private val sheetCallbacks =
    RulesSheetCallbacks(
        onClose = {},
        onIntroToggle = {},
        onOpenGlobal = {},
        onOverrideChange = { _: RulesSectionKind, _: Boolean -> },
        onEnabledChange = { _: RulesSectionKind, _: Boolean -> },
        onItemClick = { _: RulesSectionKind, _: String -> },
        onDelete = { _: RulesSectionKind, _: String -> },
        onAdd = { _: RulesSectionKind -> },
        onClear = {},
    )

private val connectionCallbacks = ConnectionDetailSheetCallbacks({}, {}, {}, {}, {}, {})

@DesignRef(component = "screens", variant = "wallet-handoff")
@Preview(
    name = "screens/wallet-handoff",
    widthDp = SheetPreviewWidth,
    heightDp = SheetPreviewHeight,
    uiMode = SheetPreviewDark,
)
@Composable
private fun WalletHandoffScreenPreview() = SheetScreen {
    StackedSheetUnderlay(Modifier.align(Alignment.BottomCenter)) {
        ReviewSheet(
            state = ReviewSheetFixtures.Transfer,
            onPrimary = {},
            onSecondary = {},
            onRules = {},
            onChoose = {},
            onClose = {},
        )
    }
    WalletHandoffSheet(
        state = SheetCompositionFixtures.WalletHandoff,
        callbacks = WalletHandoffSheetCallbacks({}, {}, {}),
        modifier = Modifier.align(Alignment.BottomCenter),
    )
}

@DesignRef(component = "screens", variant = "connection")
@Preview(
    name = "screens/connection",
    widthDp = SheetPreviewWidth,
    heightDp = SheetPreviewHeight,
    uiMode = SheetPreviewDark,
)
@Composable
private fun ConnectionScreenPreview() = SheetScreen {
    ConnectionDetailSheet(
        state = SheetCompositionFixtures.Connection,
        callbacks = connectionCallbacks,
        modifier = Modifier.fillMaxSize().padding(top = FullSheetTop),
    )
}

@DesignRef(component = "screens", variant = "rules-connection")
@Preview(
    name = "screens/rules-connection",
    widthDp = SheetPreviewWidth,
    heightDp = SheetPreviewHeight,
    uiMode = SheetPreviewDark,
)
@Composable
private fun ConnectionRulesScreenPreview() = SheetScreen {
    StackedSheetUnderlay(Modifier.fillMaxSize().padding(top = BackplateTop)) {
        ConnectionDetailSheet(
            state = SheetCompositionFixtures.Connection,
            callbacks = connectionCallbacks,
            modifier = Modifier.fillMaxSize(),
        )
    }
    ConnectionRulesSheet(
        state = SheetCompositionFixtures.ConnectionRules,
        callbacks = sheetCallbacks,
        modifier = Modifier.fillMaxSize().padding(top = FullSheetTop),
    )
}

@DesignRef(component = "screens", variant = "rules-global")
@Preview(
    name = "screens/rules-global",
    widthDp = SheetPreviewWidth,
    heightDp = SheetPreviewHeight,
    uiMode = SheetPreviewDark,
)
@Composable
private fun GlobalRulesScreenPreview() = SheetScreen {
    GlobalRulesSheet(
        state = SheetCompositionFixtures.GlobalRules,
        callbacks = sheetCallbacks,
        modifier = Modifier.fillMaxSize().padding(top = FullSheetTop),
    )
}

@DesignRef(component = "screens", variant = "asset-edit")
@Preview(
    name = "screens/asset-edit",
    widthDp = SheetPreviewWidth,
    heightDp = SheetPreviewHeight,
    uiMode = SheetPreviewDark,
)
@Composable
private fun AssetEditScreenPreview() = SheetScreen {
    StackedSheetUnderlay(Modifier.fillMaxSize().padding(top = BackplateTop)) {
        ConnectionRulesSheet(
            state = SheetCompositionFixtures.ConnectionRules,
            callbacks = sheetCallbacks,
            modifier = Modifier.fillMaxSize(),
        )
    }
    AssetEditorSheet(
        state = SheetCompositionFixtures.AssetEdit,
        callbacks = AssetEditorSheetCallbacks({}, {}, {}, {}, {}, {}, {}, {}),
        modifier = Modifier.align(Alignment.BottomCenter),
    )
}

@DesignRef(component = "screens", variant = "add-address")
@Preview(
    name = "screens/add-address",
    widthDp = SheetPreviewWidth,
    heightDp = SheetPreviewHeight,
    uiMode = SheetPreviewDark,
)
@Composable
private fun AddAddressScreenPreview() = SheetScreen {
    StackedSheetUnderlay(Modifier.fillMaxSize().padding(top = BackplateTop)) {
        ConnectionRulesSheet(
            state = SheetCompositionFixtures.ConnectionRules,
            callbacks = sheetCallbacks,
            modifier = Modifier.fillMaxSize(),
        )
    }
    AddAddressSheet(
        state = SheetCompositionFixtures.AddAddress,
        callbacks = AddAddressSheetCallbacks({}, {}, {}),
        modifier = Modifier.align(Alignment.BottomCenter),
    )
}
