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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RemoveCircleOutline
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

enum class DailyLimitRowState {
    Within,
    Over,
    NoLimit,
}

enum class DailyLimitRowScope {
    Global,
    Connection,
    /** No daily rule anywhere: the neutral "Not configured" chip, with no glyph (SEE-158). */
    None,
}

@Composable
fun DailyRow(
    headline: String,
    supportingText: String,
    state: DailyLimitRowState,
    scope: DailyLimitRowScope,
    modifier: Modifier = Modifier,
) {
    val over = state == DailyLimitRowState.Over
    val contentColor = if (over) SeekerTheme.colors.orange else MaterialTheme.colorScheme.onSurface

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(
                    horizontal = SeekerTheme.spacing.xl,
                    vertical = SeekerTheme.spacing.lgPlus,
                ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = state.icon(),
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.spacing.xxl),
            tint = state.iconTint(),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
        ) {
            Text(
                text = headline,
                color = contentColor,
                style = SeekerTheme.typography.buttonLarge.copy(fontWeight = FontWeight.Normal),
            )
            Text(
                text = supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ScopeChip(source = scope.chipSource())
    }
}

/**
 * A row with no limit behind it is not a check that passed, so it never wears the passed glyph: it
 * says "nothing to check" in the variant ink (SEE-158).
 */
internal fun DailyLimitRowState.icon(): ImageVector =
    when (this) {
        DailyLimitRowState.Over -> Icons.Outlined.WarningAmber
        DailyLimitRowState.Within -> Icons.Outlined.CheckCircle
        DailyLimitRowState.NoLimit -> Icons.Outlined.RemoveCircleOutline
    }

@Composable
internal fun DailyLimitRowState.iconTint(): Color =
    when (this) {
        DailyLimitRowState.Over -> SeekerTheme.colors.orange
        DailyLimitRowState.Within -> MaterialTheme.colorScheme.onSurface
        DailyLimitRowState.NoLimit -> MaterialTheme.colorScheme.onSurfaceVariant
    }

internal fun DailyLimitRowScope.chipSource(): ScopeChipSource =
    when (this) {
        DailyLimitRowScope.Global -> ScopeChipSource.Global
        DailyLimitRowScope.Connection -> ScopeChipSource.Connection
        DailyLimitRowScope.None -> ScopeChipSource.None
    }

private const val DailyRowDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun DailyRowPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "daily-row", variant = "state=nolimit scope=global")
@Preview(name = "daily-row/state-nolimit-scope-global", widthDp = 358, uiMode = DailyRowDarkMode)
@Composable
internal fun DailyRowNoLimitGlobalPreview() {
    DailyRowPreviewSurface {
        DailyRow(
            headline = "6 SOL used, no limit set",
            supportingText = "Across all connections and feeds",
            state = DailyLimitRowState.NoLimit,
            scope = DailyLimitRowScope.Global,
        )
    }
}

@DesignRef(component = "daily-row", variant = "state=over scope=connection")
@Preview(
    name = "daily-row/state-over-scope-connection",
    widthDp = 358,
    uiMode = DailyRowDarkMode,
)
@Composable
internal fun DailyRowOverConnectionPreview() {
    DailyRowPreviewSurface {
        DailyRow(
            headline = "1.5 of 8 SOL, this takes it to 9.5",
            supportingText = "Through studio-mac only",
            state = DailyLimitRowState.Over,
            scope = DailyLimitRowScope.Connection,
        )
    }
}

@DesignRef(component = "daily-row", variant = "state=within scope=global")
@Preview(name = "daily-row/state-within-scope-global", widthDp = 358, uiMode = DailyRowDarkMode)
@Composable
internal fun DailyRowWithinGlobalPreview() {
    DailyRowPreviewSurface {
        DailyRow(
            headline = "6 of 10 SOL, this takes it to 7.5",
            supportingText = "Across all connections and feeds",
            state = DailyLimitRowState.Within,
            scope = DailyLimitRowScope.Global,
        )
    }
}
