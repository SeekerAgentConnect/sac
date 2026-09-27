package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

private const val DarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun ChipDesignSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "verdict-pill", variant = "verdict=ok")
@Preview(name = "verdict-pill/verdict-ok", uiMode = DarkMode)
@Composable
internal fun VerdictPillOkPreview() {
    ChipDesignSurface { VerdictPill(verdict = VerdictPillVerdict.Ok) }
}

@DesignRef(component = "verdict-pill", variant = "verdict=ok onTile")
@Preview(name = "verdict-pill/verdict-ok-ontile", uiMode = DarkMode)
@Composable
internal fun VerdictPillOkOnTilePreview() {
    ChipDesignSurface {
        VerdictPill(verdict = VerdictPillVerdict.Ok, context = VerdictPillContext.OnTile)
    }
}

@DesignRef(component = "verdict-pill", variant = "verdict=warning")
@Preview(name = "verdict-pill/verdict-warning", uiMode = DarkMode)
@Composable
internal fun VerdictPillWarningPreview() {
    ChipDesignSurface { VerdictPill(verdict = VerdictPillVerdict.Warning) }
}

@DesignRef(component = "verdict-pill", variant = "verdict=warning count=3")
@Preview(name = "verdict-pill/verdict-warning-count-3", uiMode = DarkMode)
@Composable
internal fun VerdictPillWarningCountPreview() {
    ChipDesignSurface { VerdictPill(verdict = VerdictPillVerdict.Warning, warningCount = 3) }
}

@DesignRef(component = "signal-label", variant = "origin=signal")
@Preview(name = "signal-label/origin-signal", uiMode = DarkMode)
@Composable
internal fun SignalLabelPreview() {
    ChipDesignSurface { SignalLabel() }
}

@DesignRef(component = "signal-label", variant = "origin=signal onTile")
@Preview(name = "signal-label/origin-signal-ontile", uiMode = DarkMode)
@Composable
internal fun SignalLabelOnTilePreview() {
    ChipDesignSurface { SignalLabel(context = SignalLabelContext.OnTile) }
}

@DesignRef(component = "source-chip", variant = "src=feed size=13")
@Preview(name = "source-chip/src-feed-size-13", uiMode = DarkMode)
@Composable
internal fun SourceChipFeedPreview() {
    ChipDesignSurface {
        SourceChip(sourceName = "CopyTrading demo", size = SourceChipSize.Compact)
    }
}

@DesignRef(component = "source-chip", variant = "src=feed size=13 longest")
@Preview(name = "source-chip/src-feed-size-13-longest", uiMode = DarkMode)
@Composable
internal fun SourceChipFeedLongestPreview() {
    ChipDesignSurface {
        SourceChip(sourceName = "Jupiter Prediction demo", size = SourceChipSize.Compact)
    }
}

@DesignRef(component = "source-chip", variant = "src=hermes-box")
@Preview(name = "source-chip/src-hermes-box", uiMode = DarkMode)
@Composable
internal fun SourceChipHermesBoxPreview() {
    ChipDesignSurface { SourceChip(sourceName = "hermes-box") }
}

@DesignRef(component = "source-chip", variant = "src=runner-node")
@Preview(name = "source-chip/src-runner-node", uiMode = DarkMode)
@Composable
internal fun SourceChipRunnerNodePreview() {
    ChipDesignSurface { SourceChip(sourceName = "runner-node") }
}

@DesignRef(component = "source-chip", variant = "src=studio-mac")
@Preview(name = "source-chip/src-studio-mac", uiMode = DarkMode)
@Composable
internal fun SourceChipStudioMacPreview() {
    ChipDesignSurface { SourceChip(sourceName = "studio-mac") }
}

@DesignRef(component = "source-chip", variant = "truncating at 120px")
@Preview(name = "source-chip/truncating-at-120px", uiMode = DarkMode)
@Composable
internal fun SourceChipTruncatedPreview() {
    ChipDesignSurface {
        SourceChip(
            sourceName = "Jupiter Prediction demo",
            size = SourceChipSize.Compact,
            width = SourceChipWidth.Truncated,
        )
    }
}

@DesignRef(component = "env-chip", variant = "env=production")
@Preview(name = "env-chip/env-production", uiMode = DarkMode)
@Composable
internal fun EnvChipProductionPreview() {
    ChipDesignSurface { EnvChip(environment = EnvChipEnvironment.Production) }
}

@DesignRef(component = "env-chip", variant = "env=sandbox")
@Preview(name = "env-chip/env-sandbox", uiMode = DarkMode)
@Composable
internal fun EnvChipSandboxPreview() {
    ChipDesignSurface { EnvChip(environment = EnvChipEnvironment.Sandbox) }
}

@DesignRef(component = "env-chip", variant = "env=sandbox short")
@Preview(name = "env-chip/env-sandbox-short", uiMode = DarkMode)
@Composable
internal fun EnvChipSandboxShortPreview() {
    ChipDesignSurface {
        EnvChip(
            environment = EnvChipEnvironment.Sandbox,
            verbosity = EnvChipVerbosity.Short,
        )
    }
}

@DesignRef(component = "network-chip", variant = "network=devnet")
@Preview(name = "network-chip/network-devnet", uiMode = DarkMode)
@Composable
internal fun NetworkChipDevnetPreview() {
    ChipDesignSurface { NetworkChip(network = NetworkChipNetwork.Devnet) }
}

@DesignRef(component = "network-chip", variant = "network=mainnet")
@Preview(name = "network-chip/network-mainnet", uiMode = DarkMode)
@Composable
internal fun NetworkChipMainnetPreview() {
    ChipDesignSurface { NetworkChip(network = NetworkChipNetwork.Mainnet) }
}

@DesignRef(component = "scope-chip", variant = "from=connection")
@Preview(name = "scope-chip/from-connection", uiMode = DarkMode)
@Composable
internal fun ScopeChipConnectionPreview() {
    ChipDesignSurface { ScopeChip(source = ScopeChipSource.Connection) }
}

@DesignRef(component = "scope-chip", variant = "from=global")
@Preview(name = "scope-chip/from-global", uiMode = DarkMode)
@Composable
internal fun ScopeChipGlobalPreview() {
    ChipDesignSurface { ScopeChip(source = ScopeChipSource.Global) }
}

@DesignRef(component = "scope-chip", variant = "from=none")
@Preview(name = "scope-chip/from-none", uiMode = DarkMode)
@Composable
internal fun ScopeChipNonePreview() {
    ChipDesignSurface { ScopeChip(source = ScopeChipSource.None) }
}
