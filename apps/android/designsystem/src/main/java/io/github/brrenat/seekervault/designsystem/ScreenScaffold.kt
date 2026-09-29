package io.github.brrenat.seekervault.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class ScreenDestination {
    Home,
    Inbox,
    Discover,
    Wallet,
    Activity,
}

data class ScreenNavigationCallbacks(
    val onHome: () -> Unit,
    val onInbox: () -> Unit,
    val onWallet: () -> Unit,
    val onActivity: () -> Unit,
    /** The Discover tab (SEE-176), the feed catalog. */
    val onDiscover: () -> Unit = {},
)

/** Shared chrome for the five SEE-121 full-screen references. */
@Composable
fun ScreenScaffold(
    title: String,
    selectedDestination: ScreenDestination?,
    navigationCallbacks: ScreenNavigationCallbacks,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backButtonModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(SeekerTheme.colors.surface0)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(SeekerTheme.spacing.huge))
            ScreenAppBar(
                title = title,
                onBack = onBack,
                backButtonModifier = backButtonModifier,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
        ScreenNavigationBar(
            selected = selectedDestination,
            callbacks = navigationCallbacks,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * A full-height page reached from a tab (SEE-161): the same app bar with Back, and no bottom
 * navigation, so the page reads as a step into the tab rather than a peer of it.
 */
@Composable
fun DetailScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backButtonModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(SeekerTheme.colors.surface0)) {
        Spacer(Modifier.height(SeekerTheme.spacing.huge))
        ScreenAppBar(title = title, onBack = onBack, backButtonModifier = backButtonModifier)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/**
 * Scroll body whose trailing space lets the final item clear the overlaid navigation bar.
 *
 * [state] is the caller's when the offset has to outlive this body, as the Inbox History tab's does
 * across a visit to a record's details (SEE-161).
 */
@Composable
fun ScreenScrollBody(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(state)
                .padding(
                    start = SeekerTheme.spacing.xl,
                    top = SeekerTheme.spacing.xs,
                    end = SeekerTheme.spacing.xl,
                    bottom =
                        SeekerTheme.spacing.huge * ScreenBottomPaddingHugeUnits +
                            SeekerTheme.spacing.xxl,
                ),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        content = content,
    )
}

@Composable
private fun ScreenAppBar(
    title: String,
    onBack: (() -> Unit)?,
    backButtonModifier: Modifier,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(SeekerTheme.spacing.jumbo * ScreenAppBarJumboUnits)
                .padding(horizontal = SeekerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs),
    ) {
        if (onBack == null) {
            Spacer(Modifier.width(SeekerTheme.spacing.lg))
        } else {
            OrganismIconAction(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                size = OrganismIconActionSize.Large,
                style = OrganismIconActionStyle.Transparent,
                modifier = backButtonModifier,
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.width(SeekerTheme.spacing.md))
    }
}

@Composable
fun ScreenNavigationBar(
    selected: ScreenDestination?,
    callbacks: ScreenNavigationCallbacks,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SeekerTheme.spacing.xxl * ScreenNavigationXxlUnits)
                .background(SeekerTheme.colors.surface1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenNavigationItem(
            destination = ScreenDestination.Home,
            label = "Home",
            icon = Icons.Outlined.Home,
            selected = selected,
            onClick = callbacks.onHome,
            modifier = Modifier.weight(1f),
        )
        ScreenNavigationItem(
            destination = ScreenDestination.Inbox,
            label = "Inbox",
            icon = Icons.Outlined.Inbox,
            selected = selected,
            onClick = callbacks.onInbox,
            modifier = Modifier.weight(1f),
        )
        ScreenNavigationItem(
            destination = ScreenDestination.Discover,
            label = "Discover",
            icon = Icons.Outlined.Explore,
            selected = selected,
            onClick = callbacks.onDiscover,
            modifier = Modifier.weight(1f),
        )
        ScreenNavigationItem(
            destination = ScreenDestination.Wallet,
            label = "Wallet",
            icon = Icons.Outlined.Wallet,
            selected = selected,
            onClick = callbacks.onWallet,
            modifier = Modifier.weight(1f),
        )
        ScreenNavigationItem(
            destination = ScreenDestination.Activity,
            label = "Activity",
            icon = Icons.Outlined.History,
            selected = selected,
            onClick = callbacks.onActivity,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScreenNavigationItem(
    destination: ScreenDestination,
    label: String,
    icon: ImageVector,
    selected: ScreenDestination?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    NavItem(
        label = label,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.spacing.xxxl),
            )
        },
        state =
            if (destination == selected) {
                NavigationItemState.Selected
            } else {
                NavigationItemState.Rest
            },
        onClick = onClick,
        modifier = modifier,
    )
}

private const val ScreenAppBarJumboUnits = 2
private const val ScreenNavigationXxlUnits = 4
private const val ScreenBottomPaddingHugeUnits = 3
