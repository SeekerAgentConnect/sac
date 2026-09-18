package io.github.brrenat.seekervault.ui

import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import kotlinx.coroutines.delay

private val SheetEnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val SheetExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

enum class SeekerButtonRole {
    Primary,
    Tonal,
    Neutral,
    Error,
    StrongError,
}

@Composable
fun SeekerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: SeekerButtonRole = SeekerButtonRole.Primary,
    enabled: Boolean = true,
    leading: String? = null,
    automationTag: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        when {
            !enabled -> colors.surfaceContainerHighest to colors.onSurfaceVariant
            role == SeekerButtonRole.Primary -> colors.primary to colors.onPrimary
            role == SeekerButtonRole.Tonal -> colors.primaryContainer to colors.onPrimaryContainer
            role == SeekerButtonRole.StrongError -> colors.error to colors.onError
            role == SeekerButtonRole.Error -> colors.errorContainer to colors.onErrorContainer
            else -> colors.surfaceContainerHighest to colors.onSurface
        }
    Row(
        modifier =
            modifier
                .then(
                    if (automationTag == null) {
                        Modifier
                    } else {
                        Modifier.semantics(mergeDescendants = true) { testTag = automationTag }
                    }
                )
                .heightIn(min = SeekerTheme.dimensions.dp48)
                .clip(RoundedCornerShape(SeekerTheme.dimensions.dp24))
                .background(container)
                .clickable(
                    enabled = enabled,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(
                    horizontal = SeekerTheme.dimensions.dp20,
                    vertical = SeekerTheme.dimensions.dp6,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Text(leading, color = content, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(SeekerTheme.dimensions.dp8))
        }
        Text(
            text,
            color = content,
            style = SeekerTheme.typography.buttonLarge,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
        )
    }
}

@Composable
fun SeekerCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    radius: Dp = SeekerTheme.dimensions.dp16,
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
        shadowElevation = SeekerTheme.dimensions.dp0,
        tonalElevation = SeekerTheme.dimensions.dp0,
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
            modifier = modifier.padding(SeekerTheme.dimensions.dp16),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            radius = SeekerTheme.dimensions.dp16,
        ) {
            Text(
                message.visuals.message,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            horizontal = SeekerTheme.dimensions.dp16,
                            vertical = SeekerTheme.dimensions.dp14,
                        ),
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
                .height(SeekerTheme.dimensions.dp32)
                .clip(RoundedCornerShape(SeekerTheme.dimensions.dp8))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    SeekerTheme.dimensions.dp1,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(SeekerTheme.dimensions.dp8),
                )
                .padding(horizontal = SeekerTheme.dimensions.dp12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
    ) {
        Icon(
            Icons.Outlined.Public,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.dimensions.dp16),
        )
        Text(network, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun Identifier(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    Text(
        text,
        modifier = modifier,
        style = SeekerTheme.typography.identifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        textAlign = textAlign,
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
                .height(SeekerTheme.dimensions.dp80)
                .padding(
                    horizontal = SeekerTheme.dimensions.dp8,
                    vertical = SeekerTheme.dimensions.dp8,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        destinations.forEach { destination ->
            val active = destination.route == selected
            Column(
                modifier =
                    Modifier.weight(1f)
                        .selectable(
                            selected = active,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            role = Role.Tab,
                            onClick = { onSelect(destination.route) },
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp2),
            ) {
                Box(
                    Modifier.size(
                            width = SeekerTheme.dimensions.dp64,
                            height = SeekerTheme.dimensions.dp32,
                        )
                        .clip(RoundedCornerShape(SeekerTheme.dimensions.dp16))
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        destination.icon,
                        contentDescription = null,
                        modifier = Modifier.size(SeekerTheme.dimensions.dp24),
                        tint =
                            if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (destination.hasBadge) {
                        Box(
                            Modifier.align(Alignment.TopEnd)
                                .padding(
                                    end = SeekerTheme.dimensions.dp14,
                                    top = SeekerTheme.dimensions.dp4,
                                )
                                .size(SeekerTheme.dimensions.dp7)
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
    promoteFromBackplate: Boolean = false,
    onBackplateClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val activeTop = SeekerTheme.dimensions.dp100
    val transition =
        remember(motionKey) {
            MutableTransitionState(promoteFromBackplate).apply { targetState = visible }
        }
    LaunchedEffect(visible) { transition.targetState = visible }
    var promoted by
        remember(motionKey) { androidx.compose.runtime.mutableStateOf(!promoteFromBackplate) }
    LaunchedEffect(promoteFromBackplate) { promoted = true }
    val animatedTop by
        animateDpAsState(
            if (promoted) activeTop else SeekerTheme.dimensions.dp86,
            tween(300, easing = SheetEnterEasing),
            label = "activeSheetTop",
        )
    val animatedBottom by
        animateDpAsState(
            if (promoted) SeekerTheme.dimensions.dp0 else SeekerTheme.dimensions.dp12,
            tween(300, easing = SheetEnterEasing),
            label = "activeSheetBottom",
        )
    Box(modifier.fillMaxSize().zIndex(10f + depth)) {
        AnimatedVisibility(
            visibleState = transition,
            enter =
                slideInVertically(
                    animationSpec = tween(260, easing = SheetEnterEasing),
                    initialOffsetY = { it },
                ),
            exit =
                slideOutVertically(
                    animationSpec = tween(240, easing = SheetExitEasing),
                    targetOffsetY = { it },
                ),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier.fillMaxSize().padding(top = animatedTop, bottom = animatedBottom),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier =
                        Modifier.fillMaxWidth()
                            .then(
                                if (onBackplateClick == null) Modifier
                                else
                                    Modifier.clickable(
                                        indication = null,
                                        interactionSource =
                                            remember {
                                                MutableInteractionSource()
                                            },
                                        onClick = onBackplateClick,
                                    )
                            ),
                    shape =
                        RoundedCornerShape(
                            topStart = SeekerTheme.dimensions.dp28,
                            topEnd = SeekerTheme.dimensions.dp28,
                        ),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = SeekerTheme.dimensions.dp0,
                    tonalElevation = SeekerTheme.dimensions.dp0,
                ) {
                    Column {
                        Box(
                            Modifier.fillMaxWidth().height(SeekerTheme.dimensions.dp22),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(
                                        width = SeekerTheme.dimensions.dp32,
                                        height = SeekerTheme.dimensions.dp4,
                                    )
                                    .background(
                                        MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(SeekerTheme.dimensions.dp2),
                                    )
                            )
                        }
                        Box(Modifier.fillMaxWidth()) { content() }
                    }
                }
            }
        }
    }
}

/**
 * Keeps the still-mounted root from receiving gestures through a sheet's exposed top edge. Sheet
 * backplates sit above this layer, so their own surfaces remain the only interactive content behind
 * the active sheet.
 */
@Composable
fun SheetInputBarrier(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    )
}

@Composable
fun SheetBackplate(depth: Int, title: String, onClick: () -> Unit) {
    val back = depth.coerceAtLeast(1)
    var stacked by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) { stacked = true }
    val top by
        animateDpAsState(
            if (stacked) {
                (SeekerTheme.dimensions.dp100 - SeekerTheme.dimensions.dp14 * back.toFloat())
                    .coerceAtLeast(SeekerTheme.dimensions.dp30)
            } else {
                SeekerTheme.dimensions.dp100
            },
            tween(300, easing = SheetEnterEasing),
            label = "sheetBackplateTop",
        )
    val bottom by
        animateDpAsState(
            if (stacked) SeekerTheme.dimensions.dp12 * back.toFloat()
            else SeekerTheme.dimensions.dp0,
            tween(300, easing = SheetEnterEasing),
            label = "sheetBackplateBottom",
        )
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
        shape =
            RoundedCornerShape(
                topStart = SeekerTheme.dimensions.dp28,
                topEnd = SeekerTheme.dimensions.dp28,
            ),
        shadowElevation = SeekerTheme.dimensions.dp0,
        tonalElevation = SeekerTheme.dimensions.dp0,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(SeekerTheme.dimensions.dp9))
            Box(
                Modifier.size(
                        width = SeekerTheme.dimensions.dp32,
                        height = SeekerTheme.dimensions.dp4,
                    )
                    .background(
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(SeekerTheme.dimensions.dp2),
                    )
            )
            Text(
                title,
                modifier = Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
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
    Dialog(
        onDismissRequest = {},
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        val dialogWindow = (LocalView.current.parent as DialogWindowProvider).window
        val dim = SeekerTheme.colors.dim
        SideEffect {
            // Dialog is the input and accessibility barrier; its own window draws only solid
            // tokens and explicitly disables the platform's translucent dim-behind treatment.
            dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow.setWindowAnimations(0)
            dialogWindow.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            dialogWindow.setBackgroundDrawable(ColorDrawable(dim.toArgb()))
        }
        Box(
            modifier.fillMaxSize().background(dim).padding(SeekerTheme.dimensions.dp24),
            contentAlignment = Alignment.Center,
        ) {
            SeekerCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = SeekerTheme.dimensions.dp420),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                radius = SeekerTheme.dimensions.dp28,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp24),
                    verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp18),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Box(
                        Modifier.fillMaxWidth()
                            .heightIn(max = SeekerTheme.dimensions.dp240)
                            .verticalScroll(rememberScrollState())
                    ) {
                        body()
                    }
                    Box(Modifier.fillMaxWidth().zIndex(1f)) { actions() }
                }
            }
        }
    }
}
