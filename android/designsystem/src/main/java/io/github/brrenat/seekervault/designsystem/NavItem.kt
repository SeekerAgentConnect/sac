package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class NavigationItemState {
    Rest,
    Selected,
}

@Composable
fun NavItem(
    label: String,
    icon: @Composable () -> Unit,
    state: NavigationItemState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state == NavigationItemState.Selected
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant
    val iconColor = if (selected) SeekerTheme.colors.onLimeContainer else contentColor

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SeekerTheme.spacing.xxl * 4)
                .background(SeekerTheme.colors.surface1)
                .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier.size(
                        width = SeekerTheme.spacing.jumbo * 2,
                        height = SeekerTheme.spacing.jumbo,
                    )
                    .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                    .background(
                        if (selected) {
                            SeekerTheme.colors.limeContainer
                        } else {
                            SeekerTheme.colors.surface1
                        }
                    ),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides iconColor, content = icon)
        }
        Box(Modifier.height(SeekerTheme.spacing.xs))
        Text(text = label, color = contentColor, style = MaterialTheme.typography.labelMedium)
    }
}

private const val NavItemDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun NavIcon(imageVector: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Icon(
        imageVector = imageVector,
        contentDescription = label,
        modifier = Modifier.size(SeekerTheme.spacing.xxxl),
    )
}

@DesignRef(component = "nav-item", variant = "state=rest")
@Preview(name = "nav-item/state-rest", widthDp = 120, uiMode = NavItemDarkMode)
@Composable
private fun NavItemRestPreview() {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface1) {
            NavItem(
                label = "Inbox",
                icon = { NavIcon(Icons.Outlined.Inbox, "Inbox") },
                state = NavigationItemState.Rest,
                onClick = {},
            )
        }
    }
}

@DesignRef(component = "nav-item", variant = "state=selected")
@Preview(name = "nav-item/state-selected", widthDp = 120, uiMode = NavItemDarkMode)
@Composable
private fun NavItemSelectedPreview() {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface1) {
            NavItem(
                label = "Home",
                icon = { NavIcon(Icons.Outlined.Home, "Home") },
                state = NavigationItemState.Selected,
                onClick = {},
            )
        }
    }
}

@DesignRef(component = "nav-bar", variant = "state=home-selected")
@Preview(name = "nav-bar/state-home-selected", widthDp = 390, uiMode = NavItemDarkMode)
@Composable
private fun FourItemNavBarPreview() {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface1) {
            Row(Modifier.fillMaxWidth()) {
                NavItem(
                    label = "Home",
                    icon = { NavIcon(Icons.Outlined.Home, "Home") },
                    state = NavigationItemState.Selected,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                NavItem(
                    label = "Inbox",
                    icon = { NavIcon(Icons.Outlined.Inbox, "Inbox") },
                    state = NavigationItemState.Rest,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                NavItem(
                    label = "Wallet",
                    icon = { NavIcon(Icons.Outlined.AccountBalanceWallet, "Wallet") },
                    state = NavigationItemState.Rest,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                NavItem(
                    label = "Activity",
                    icon = { NavIcon(Icons.Outlined.History, "Activity") },
                    state = NavigationItemState.Rest,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
