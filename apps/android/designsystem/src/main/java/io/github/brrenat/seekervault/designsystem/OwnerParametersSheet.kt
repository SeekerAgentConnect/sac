package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

/** A two-way choice in the owner's part, e.g. which side of a market. */
data class OwnerParametersChoice(
    val label: String,
    val options: List<String>,
    /** [SegmentedNoSelection] until the owner picks: a market side has no default. */
    val selectedIndex: Int,
)

/**
 * The owner's own parameters for a signal, edited in a sheet stacked over the review (SEE-158).
 *
 * The review shows only their summary; this is where they are chosen. Nothing here is prepared or
 * sent: **Use these** hands the values back, and the review quotes again from them.
 */
data class OwnerParametersSheetState(
    val title: String,
    val choice: OwnerParametersChoice? = null,
    val amountLabel: String,
    val amount: String,
    val amountHelp: String,
    val amountError: String? = null,
    val note: String,
    val canUse: Boolean,
    val useLabel: String = "Use these",
)

data class OwnerParametersSheetCallbacks(
    val onChoose: (Int) -> Unit,
    val onAmountChange: (String) -> Unit,
    val onUse: () -> Unit,
    val onClose: () -> Unit,
)

/** Test handles for the sheet's controls; the app owns the names. */
data class OwnerParametersSheetTags(
    val use: String? = null,
    val options: List<String> = emptyList(),
    val amount: String? = null,
    val close: String? = null,
)

@Composable
fun OwnerParametersSheet(
    state: OwnerParametersSheetState,
    callbacks: OwnerParametersSheetCallbacks,
    modifier: Modifier = Modifier,
    tags: OwnerParametersSheetTags = OwnerParametersSheetTags(),
    /** Focuses the amount when the sheet opens, which brings up the keyboard with it. */
    focusAmount: Boolean = false,
) {
    val amountFocus = remember { FocusRequester() }
    if (focusAmount) LaunchedEffect(amountFocus) { amountFocus.requestFocus() }
    SheetScaffold(
        title = state.title,
        variant = SheetScaffoldVariant.StackedOverBlurred,
        onClose = callbacks.onClose,
        modifier = modifier,
        closeTag = tags.close,
        headerAction = {
            SeekerButton(
                label = state.useLabel,
                onClick = callbacks.onUse,
                variant =
                    if (state.canUse) SeekerButtonVariant.Filled else SeekerButtonVariant.Disabled,
                size = SeekerButtonSize.Md,
                enabled = state.canUse,
                modifier = tags.use?.let { Modifier.testTag(it) } ?: Modifier,
            )
        },
        body = {
            state.choice?.let { choice ->
                Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
                    Text(
                        text = choice.label,
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                    )
                    Segmented(
                        options = choice.options,
                        selectedIndex = choice.selectedIndex,
                        onSelect = callbacks.onChoose,
                        count =
                            if (choice.options.size == 2) SegmentedCount.Two
                            else SegmentedCount.Three,
                        optionModifiers =
                            choice.options.indices.map { index ->
                                tags.options.getOrNull(index)?.let { Modifier.testTag(it) }
                                    ?: Modifier
                            },
                    )
                }
            }
            SeekerTextField(
                label = state.amountLabel,
                value = state.amount,
                onValueChange = callbacks.onAmountChange,
                state =
                    if (state.amountError == null) DesignTextFieldState.Rest
                    else DesignTextFieldState.Error,
                errorMessage = state.amountError,
                placeholder = "0",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                reserveErrorSpace = false,
                inputModifier =
                    (tags.amount?.let { Modifier.testTag(it) } ?: Modifier).focusRequester(
                        amountFocus
                    ),
            )
            Text(
                text = state.amountHelp,
                modifier = Modifier.padding(horizontal = SeekerTheme.spacing.xs),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = state.note,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = SeekerTheme.spacing.xs,
                            top = SeekerTheme.spacing.md,
                            end = SeekerTheme.spacing.xs,
                        ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}
