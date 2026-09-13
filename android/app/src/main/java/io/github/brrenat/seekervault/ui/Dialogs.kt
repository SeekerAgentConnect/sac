package io.github.brrenat.seekervault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.brrenat.seekervault.R

/**
 * A centred dialog on the app's own glass (SEE-57): near-opaque, because what is behind it is not
 * to be read while it is open, and shaped and lit like every other surface.
 */
@Composable
fun GlassDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirm: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    confirmTag: String? = null,
    dismiss: String? = null,
    dismissTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties()) {
        Column(
            modifier
                .fillMaxWidth()
                .glass(Glass.dialog(), RoundedCornerShape(Radius.Dialog))
                .padding(Space.Xl),
            verticalArrangement = Arrangement.spacedBy(Space.Gap),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = Nocturne.Text,
            )
            // The body scrolls and the actions do not: a dialog whose Add button is pushed off
            // the screen by its own fields is a dialog nobody can finish.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.Sm),
                content = content,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.Sm, Alignment.End),
            ) {
                PillButton(
                    dismiss ?: stringResource(R.string.cancel),
                    onDismiss,
                    tone = PillTone.Ghost,
                    modifier = if (dismissTag == null) Modifier else Modifier.testTag(dismissTag),
                )
                if (confirm != null && onConfirm != null) {
                    PillButton(
                        confirm,
                        onConfirm,
                        tone = PillTone.Accent,
                        enabled = confirmEnabled,
                        modifier =
                            if (confirmTag == null) Modifier else Modifier.testTag(confirmTag),
                    )
                }
            }
        }
    }
}

/**
 * A text field on the glass. Its error state is a **dashed** border and a line of words, never a
 * colour on its own: the design carries "this is not right" the same way it carries "under
 * restrictions", so one reading of the screen serves everybody.
 */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    problem: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = singleLine,
            minLines = minLines,
            isError = problem != null,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(Radius.InnerTight),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Nocturne.text(0.06f),
                    unfocusedContainerColor = Nocturne.text(0.06f),
                    errorContainerColor = Nocturne.text(0.06f),
                    focusedBorderColor = Nocturne.accent(0.46f),
                    unfocusedBorderColor = Nocturne.text(0.14f),
                    errorBorderColor = Nocturne.Neutral300.copy(alpha = 0.5f),
                    focusedTextColor = Nocturne.Text,
                    unfocusedTextColor = Nocturne.Text,
                    errorTextColor = Nocturne.Text,
                ),
            modifier = modifier.fillMaxWidth(),
        )
        val message = problem ?: supporting
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (problem != null) Nocturne.Neutral200 else Nocturne.Neutral500,
            )
        }
    }
}
