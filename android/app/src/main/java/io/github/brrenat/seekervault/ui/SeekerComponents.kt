package io.github.brrenat.seekervault.ui

import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import io.github.brrenat.seekervault.designsystem.LocalSheetStackBack
import io.github.brrenat.seekervault.designsystem.LocalSheetStackHosted
import io.github.brrenat.seekervault.designsystem.SheetMotion
import io.github.brrenat.seekervault.designsystem.StackedSheetUnderlay
import io.github.brrenat.seekervault.designsystem.sheetStackContentColor
import io.github.brrenat.seekervault.designsystem.sheetStackSurfaceColor
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SheetSwipe {
    Shown,
    Hidden,
}

private fun sheetSwipeNestedScroll(
    state: AnchoredDraggableState<SheetSwipe>,
    onFling: (Float) -> Unit,
): NestedScrollConnection =
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val delta = available.y
            return if (delta < 0f && source == NestedScrollSource.UserInput) {
                Offset(0f, state.dispatchRawDelta(delta))
            } else {
                Offset.Zero
            }
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            return if (source == NestedScrollSource.UserInput) {
                Offset(0f, state.dispatchRawDelta(available.y))
            } else {
                Offset.Zero
            }
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val offset = state.offset
            return if (
                available.y < 0f &&
                    !offset.isNaN() &&
                    offset > 0f &&
                    state.anchors.hasPositionFor(SheetSwipe.Hidden)
            ) {
                onFling(available.y)
                available
            } else {
                Velocity.Zero
            }
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val offset = state.offset
            return if (!offset.isNaN() && offset > 0f) {
                onFling(available.y)
                available
            } else {
                Velocity.Zero
            }
        }
    }

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
    index: Int,
    back: Int,
    motionKey: Any? = Unit,
    visible: Boolean = true,
    onDismiss: () -> Unit,
    onPeekClick: (() -> Unit)? = null,
    chrome: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val topInset =
        (SeekerTheme.dimensions.dp100 - SeekerTheme.dimensions.dp14 * back.toFloat()).coerceAtLeast(
            SeekerTheme.dimensions.dp30
        )
    val bottomInset = SeekerTheme.dimensions.dp12 * back.toFloat()
    val transition =
        remember(motionKey) { MutableTransitionState(false).apply { targetState = visible } }
    LaunchedEffect(visible) { transition.targetState = visible }
    val animatedTop by
        animateDpAsState(
            topInset,
            tween(SheetMotion.StackMs, easing = SheetMotion.EnterEasing),
            label = "activeSheetTop",
        )
    val animatedBottom by
        animateDpAsState(
            bottomInset,
            tween(SheetMotion.StackMs, easing = SheetMotion.EnterEasing),
            label = "activeSheetBottom",
        )
    val swipe = remember(motionKey) { AnchoredDraggableState(SheetSwipe.Shown) }
    var sheetHeightPx by remember(motionKey) { mutableFloatStateOf(0f) }
    LaunchedEffect(sheetHeightPx) {
        if (sheetHeightPx > 0f) {
            swipe.updateAnchors(
                DraggableAnchors {
                    SheetSwipe.Shown at 0f
                    SheetSwipe.Hidden at sheetHeightPx
                }
            )
        }
    }
    val dismiss = rememberUpdatedState(onDismiss)
    LaunchedEffect(swipe.settledValue) {
        if (swipe.settledValue == SheetSwipe.Hidden) dismiss.value()
    }
    val scope = rememberCoroutineScope()
    val settleSpec = tween<Float>(SheetMotion.ExitMs, easing = SheetMotion.ExitEasing)
    val density = LocalDensity.current
    val dismissVelocityPx =
        with(density) {
            (SeekerTheme.dimensions.dp80 + SeekerTheme.dimensions.dp40 + SeekerTheme.dimensions.dp5)
                .toPx()
        }
    val settleSwipe: (Float) -> Unit = { velocity ->
        scope.launch {
            val offset = swipe.offset
            if (offset.isNaN() || !swipe.anchors.hasPositionFor(SheetSwipe.Hidden)) return@launch
            val hiddenAt = swipe.anchors.positionOf(SheetSwipe.Hidden)
            val dismissSheet =
                velocity >= dismissVelocityPx || (velocity >= 0f && offset >= hiddenAt / 2f)
            swipe.animateTo(
                if (dismissSheet) SheetSwipe.Hidden else SheetSwipe.Shown,
                settleSpec,
            )
        }
    }
    val nestedScroll = remember(swipe) { sheetSwipeNestedScroll(swipe, settleSwipe) }
    val sheetFling =
        AnchoredDraggableDefaults.flingBehavior(state = swipe, animationSpec = settleSpec)
    val interactive = visible && back == 0
    val swipeModifier =
        Modifier.fillMaxWidth()
            .onSizeChanged { sheetHeightPx = it.height.toFloat() }
            .offset {
                val y = swipe.offset
                IntOffset(0, if (y.isNaN()) 0 else y.roundToInt())
            }
            .nestedScroll(nestedScroll)
            .anchoredDraggable(
                state = swipe,
                orientation = Orientation.Vertical,
                enabled = interactive && sheetHeightPx > 0f,
                flingBehavior = sheetFling,
            )
    Box(modifier.fillMaxSize().zIndex(10f + index)) {
        // Max height is 100dp from the top, minus 14dp per stacked sheet (floor 30dp).
        // The sheet wraps content and slides by its own height.
        Box(
            Modifier.fillMaxSize().padding(top = animatedTop, bottom = animatedBottom),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visibleState = transition,
                enter =
                    slideInVertically(
                        animationSpec =
                            tween(SheetMotion.EnterMs, easing = SheetMotion.EnterEasing),
                        initialOffsetY = { it },
                    ),
                exit =
                    slideOutVertically(
                        animationSpec = tween(SheetMotion.ExitMs, easing = SheetMotion.ExitEasing),
                        targetOffsetY = { it },
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CompositionLocalProvider(
                    LocalSheetStackBack provides back,
                    LocalSheetStackHosted provides true,
                ) {
                    val peek = onPeekClick.takeIf { back > 0 && visible }
                    val exitGuard =
                        if (visible) Modifier
                        else
                            Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial)
                                            .changes
                                            .forEach { it.consume() }
                                    }
                                }
                            }
                    StackedSheetUnderlay(back = back, modifier = swipeModifier.then(exitGuard)) {
                        SheetLayer(chrome = chrome, peek = peek, content = content)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetLayer(
    chrome: Boolean,
    peek: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        if (chrome) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(
                        topStart = SeekerTheme.dimensions.dp28,
                        topEnd = SeekerTheme.dimensions.dp28,
                    ),
                color = sheetStackSurfaceColor(),
                contentColor = sheetStackContentColor(),
                shadowElevation = SeekerTheme.dimensions.dp0,
                tonalElevation = SeekerTheme.dimensions.dp0,
            ) {
                // The surface runs to the bottom edge, behind the gesture area or the navigation
                // buttons, and what is on it stops above them with a gap (SEE-150): the last line
                // of a review is read, not hidden under the home indicator.
                //
                // The host owns this and `:designsystem` does not, for the reason the banner's
                // placement moved here too: a sheet component is window-agnostic presentation,
                // every
                // sheet the app pushes goes through this one layer, and a design-system component
                // that measured itself against a window would put a simulated navigation bar into
                // its own reference capture. `windowInsetsPadding` also consumes what it applies,
                // so
                // nothing inside can pad for the same bar twice.
                Column(
                    Modifier.windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                        )
                        .padding(bottom = SeekerTheme.spacing.xl)
                ) {
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
        } else {
            content()
        }
        if (peek != null) {
            Box(
                Modifier.matchParentSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = peek,
                    )
            )
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
