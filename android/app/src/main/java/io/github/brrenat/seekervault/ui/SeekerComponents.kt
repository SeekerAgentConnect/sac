package io.github.brrenat.seekervault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.brrenat.seekervault.SeekerTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

enum class SeekerButtonRole {
    Primary,
    Tonal,
    Neutral,
    Error,
}

@Composable
fun SeekerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: SeekerButtonRole = SeekerButtonRole.Primary,
    enabled: Boolean = true,
    leading: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        when {
            !enabled -> colors.surfaceContainerHighest to colors.onSurfaceVariant
            role == SeekerButtonRole.Primary -> colors.primary to colors.onPrimary
            role == SeekerButtonRole.Tonal -> colors.primaryContainer to colors.onPrimaryContainer
            role == SeekerButtonRole.Error -> colors.errorContainer to colors.onErrorContainer
            else -> colors.surfaceContainerHighest to colors.onSurface
        }
    Row(
        modifier =
            modifier
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(container)
                .clickable(
                    enabled = enabled,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Text(leading, color = content, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SeekerCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    radius: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val clickable =
        if (onClick == null) {
            Modifier
        } else {
            Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick,
            )
        }
    Surface(
        modifier = modifier.then(clickable),
        shape = RoundedCornerShape(radius),
        color = color,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}

/** A solid, shadow-free status message with no fade or transparent host animation. */
@Composable
fun SeekerSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val message = hostState.currentSnackbarData
    LaunchedEffect(message) {
        if (message != null && message.visuals.duration != SnackbarDuration.Indefinite) {
            delay(if (message.visuals.duration == SnackbarDuration.Short) 4_000 else 10_000)
            message.dismiss()
        }
    }
    if (message != null) {
        SeekerCard(
            modifier = modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            radius = 16.dp,
        ) {
            Text(
                message.visuals.message,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun NetworkChip(network: String, modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Outlined.Public,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(network, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun Identifier(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Every text-field state is an opaque token, including disabled and selected text. */
@Composable
fun seekerTextFieldColors(): TextFieldColors {
    val colors = MaterialTheme.colorScheme
    return TextFieldDefaults.colors(
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
        disabledTextColor = colors.onSurfaceVariant,
        errorTextColor = colors.onSurface,
        focusedContainerColor = colors.surfaceContainerHighest,
        unfocusedContainerColor = colors.surfaceContainerHighest,
        disabledContainerColor = colors.surfaceContainerHigh,
        errorContainerColor = colors.errorContainer,
        cursorColor = colors.primary,
        errorCursorColor = colors.error,
        selectionColors =
            TextSelectionColors(
                handleColor = colors.primary,
                backgroundColor = colors.primaryContainer,
            ),
        focusedIndicatorColor = colors.primary,
        unfocusedIndicatorColor = colors.outline,
        disabledIndicatorColor = colors.outlineVariant,
        errorIndicatorColor = colors.error,
        focusedLeadingIconColor = colors.onSurfaceVariant,
        unfocusedLeadingIconColor = colors.onSurfaceVariant,
        disabledLeadingIconColor = colors.outline,
        errorLeadingIconColor = colors.error,
        focusedTrailingIconColor = colors.onSurfaceVariant,
        unfocusedTrailingIconColor = colors.onSurfaceVariant,
        disabledTrailingIconColor = colors.outline,
        errorTrailingIconColor = colors.error,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.onSurfaceVariant,
        disabledLabelColor = colors.outline,
        errorLabelColor = colors.error,
        focusedPlaceholderColor = colors.onSurfaceVariant,
        unfocusedPlaceholderColor = colors.onSurfaceVariant,
        disabledPlaceholderColor = colors.outline,
        errorPlaceholderColor = colors.onErrorContainer,
        focusedSupportingTextColor = colors.onSurfaceVariant,
        unfocusedSupportingTextColor = colors.onSurfaceVariant,
        disabledSupportingTextColor = colors.outline,
        errorSupportingTextColor = colors.error,
        focusedPrefixColor = colors.onSurfaceVariant,
        unfocusedPrefixColor = colors.onSurfaceVariant,
        disabledPrefixColor = colors.outline,
        errorPrefixColor = colors.error,
        focusedSuffixColor = colors.onSurfaceVariant,
        unfocusedSuffixColor = colors.onSurfaceVariant,
        disabledSuffixColor = colors.outline,
        errorSuffixColor = colors.error,
    )
}

/** Material list rows draw a solid container instead of the component's transparent default. */
@Composable
fun seekerListItemColors(
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer
): ListItemColors = ListItemDefaults.colors(containerColor = containerColor)

data class BottomDestination(
    val route: String,
    val icon: ImageVector,
    val label: String,
    val hasBadge: Boolean = false,
)

@Composable
fun SeekerBottomBar(
    destinations: List<BottomDestination>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .navigationBarsPadding()
                .height(80.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        destinations.forEach { destination ->
            val active = destination.route == selected
            Column(
                modifier =
                    Modifier.weight(1f)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            role = Role.Tab,
                            onClick = { onSelect(destination.route) },
                        )
                        .semantics { role = Role.Tab },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    Modifier.size(width = 64.dp, height = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        destination.icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint =
                            if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (destination.hasBadge) {
                        Box(
                            Modifier.align(Alignment.TopEnd)
                                .padding(end = 14.dp, top = 4.dp)
                                .size(7.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                    }
                }
                Text(
                    destination.label,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (active) SeekerTheme.colors.primaryText
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SeekerSheet(
    depth: Int,
    motionKey: Any? = Unit,
    visible: Boolean = true,
    onBackplateClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val top = 100.dp
    var entered by remember(motionKey) { mutableStateOf(false) }
    LaunchedEffect(motionKey) { entered = true }
    val entryOffset by
        animateFloatAsState(
            targetValue = if (entered) 0f else 1f,
            animationSpec = tween(260),
            label = "sheet-entry",
        )
    Box(modifier.fillMaxSize().zIndex(10f + depth)) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(animationSpec = tween(260), initialOffsetY = { it }),
            exit = slideOutVertically(animationSpec = tween(240), targetOffsetY = { it }),
            modifier =
                Modifier.fillMaxSize().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(
                            0,
                            (placeable.height * entryOffset).roundToInt(),
                        )
                    }
                },
        ) {
            Surface(
                modifier =
                    Modifier.fillMaxWidth()
                        .fillMaxHeight()
                        .padding(top = top)
                        .then(
                            if (onBackplateClick == null) Modifier
                            else
                                Modifier.clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onBackplateClick,
                                )
                        ),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
            ) {
                Column {
                    Box(
                        Modifier.fillMaxWidth().height(22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(width = 40.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(2.dp),
                                )
                        )
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                }
            }
        }
    }
}

@Composable
fun SheetBackplate(depth: Int, title: String, onClick: () -> Unit) {
    val back = depth.coerceAtLeast(1)
    val top = (100 - back * 14).coerceAtLeast(30).dp
    val bottom = (back * 12).dp
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .fillMaxHeight()
                .padding(top = top, bottom = bottom)
                .zIndex(10f - back)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClick,
                ),
        color = SeekerTheme.colors.dim,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier.size(width = 40.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
            )
            Text(
                title,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

/** Opaque replacement for modal scrims: the background is a solid token, never an alpha layer. */
@Composable
fun SolidDialog(
    title: String,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Box(
        modifier.fillMaxSize().background(SeekerTheme.colors.dim).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        SeekerCard(
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            radius = 28.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).clipToBounds()) { body() }
                Box(Modifier.fillMaxWidth().zIndex(1f)) { actions() }
            }
        }
    }
}
