package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class VerdictCardVerdict {
    Ok,
    Warning,
}

enum class VerdictWarningKind {
    Finding,
    DailyLimit,
}

data class VerdictWarning(
    val message: String,
    val scopeLabel: String,
    val kind: VerdictWarningKind = VerdictWarningKind.Finding,
    val scope: ScopeChipSource = ScopeChipSource.Global,
)

@Composable
fun VerdictCard(
    verdict: VerdictCardVerdict,
    warnings: List<VerdictWarning>,
    onRulesClick: () -> Unit,
    modifier: Modifier = Modifier,
    additionalContext: String? = null,
    /**
     * A heading that names the verdict more exactly than the count, e.g. why there are no rules.
     */
    heading: String? = null,
) {
    require(verdict == VerdictCardVerdict.Warning || warnings.isEmpty()) {
        "An OK verdict cannot contain warnings"
    }
    val colors =
        when (verdict) {
            VerdictCardVerdict.Ok ->
                VerdictCardColors(
                    container = SeekerTheme.colors.limeContainer,
                    content = SeekerTheme.colors.onLimeContainer,
                )
            VerdictCardVerdict.Warning ->
                VerdictCardColors(
                    container = SeekerTheme.colors.orangeContainer,
                    content = SeekerTheme.colors.onOrangeContainer,
                )
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(colors.container)
                .padding(SeekerTheme.spacing.xl)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    if (verdict == VerdictCardVerdict.Ok) Icons.Outlined.Verified
                    else Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.xxl + SeekerTheme.spacing.xxs),
                tint = colors.content,
            )
            Text(
                text = heading ?: verdict.heading(warnings.size),
                modifier = Modifier.weight(1f),
                color = colors.content,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
        }
        warnings.forEach { warning ->
            VerdictWarningRow(warning = warning, contentColor = colors.content)
        }
        additionalContext?.let { context ->
            Text(
                text = context,
                modifier = Modifier.padding(top = SeekerTheme.spacing.mdPlus),
                color = colors.content,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.padding(top = SeekerTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Advisory only. You answer this request by hand.",
                modifier = Modifier.weight(1f),
                color = colors.content,
                style = MaterialTheme.typography.bodySmall,
            )
            SeekerButton(
                label = "Rules",
                onClick = onRulesClick,
                variant =
                    if (verdict == VerdictCardVerdict.Ok) {
                        SeekerButtonVariant.OnVerdictOk
                    } else {
                        SeekerButtonVariant.OnVerdictWarn
                    },
                size = SeekerButtonSize.Sm,
            )
        }
    }
}

@Composable
private fun VerdictWarningRow(warning: VerdictWarning, contentColor: Color) {
    Row(
        modifier = Modifier.padding(top = SeekerTheme.spacing.mdPlus),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.mdPlus),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = warning.kind.icon(),
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xl + SeekerTheme.spacing.xxs),
            tint = contentColor,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
        ) {
            Text(
                text = warning.message,
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            ScopeChip(
                source = warning.scope,
                label = warning.scopeLabel,
                context = ScopeChipContext.OnVerdict,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class VerdictCardColors(val container: Color, val content: Color)

private fun VerdictCardVerdict.heading(warningCount: Int): String =
    when (this) {
        VerdictCardVerdict.Ok -> "Within the rules you set"
        VerdictCardVerdict.Warning ->
            "Needs attention · $warningCount ${if (warningCount == 1) "warning" else "warnings"}"
    }

private fun VerdictWarningKind.icon(): ImageVector =
    when (this) {
        VerdictWarningKind.Finding -> Icons.Outlined.ErrorOutline
        VerdictWarningKind.DailyLimit -> Icons.Outlined.Schedule
    }

private const val VerdictCardPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun VerdictCardPreview(
    verdict: VerdictCardVerdict,
    warnings: List<VerdictWarning>,
    additionalContext: String? = null,
) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            VerdictCard(
                verdict = verdict,
                warnings = warnings,
                onRulesClick = {},
                additionalContext = additionalContext,
            )
        }
    }
}

private val assetWarning =
    VerdictWarning(
        message = "USDC on devnet is not a listed asset.",
        scopeLabel = "Global rule",
    )

@DesignRef(component = "verdict-card", variant = "verdict=ok")
@Preview(name = "verdict-card/verdict-ok", widthDp = 358, uiMode = VerdictCardPreviewDarkMode)
@Composable
internal fun VerdictCardOkPreview() = VerdictCardPreview(VerdictCardVerdict.Ok, emptyList())

@DesignRef(component = "verdict-card", variant = "verdict=warning count=1")
@Preview(
    name = "verdict-card/verdict-warning-count-1",
    widthDp = 358,
    uiMode = VerdictCardPreviewDarkMode,
)
@Composable
internal fun VerdictCardOneWarningPreview() =
    VerdictCardPreview(VerdictCardVerdict.Warning, listOf(assetWarning))

@DesignRef(component = "verdict-card", variant = "verdict=warning count=3")
@Preview(
    name = "verdict-card/verdict-warning-count-3",
    widthDp = 358,
    uiMode = VerdictCardPreviewDarkMode,
)
@Composable
internal fun VerdictCardThreeWarningsPreview() =
    VerdictCardPreview(
        verdict = VerdictCardVerdict.Warning,
        warnings =
            listOf(
                assetWarning,
                VerdictWarning(
                    message =
                        "Over the global daily limit of 10 SOL. 6 SOL has already moved across " +
                            "all connections today, so this would reach 11.5.",
                    scopeLabel = "Global rule",
                    kind = VerdictWarningKind.DailyLimit,
                ),
                VerdictWarning(
                    message = "Jupiter Aggregator is not a listed program.",
                    scopeLabel = "No rule configured",
                    scope = ScopeChipSource.None,
                ),
            ),
        additionalContext =
            "Deliberately not checked: who is paid. No rule configured for: which programs are " +
                "called.",
    )
