package io.github.brrenat.seekervault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R

/**
 * The persistent chrome (SEE-57): a floating glass header at the top, an optional floating tab bar
 * at the bottom, and the scrolling body between them. Both float — they do not sit in a bar that
 * owns an edge of the screen — so the lit ground runs under everything.
 */

/** One tab of the floating bar. */
data class Tab(
    val label: String,
    val icon: ImageVector,
    val tag: String,
    /** A tab shows a dot when something there is waiting. */
    val alerts: Boolean = false,
)

/** Which tabs there are, which one is on, and what to do when another is tapped. */
data class TabBar(val tabs: List<Tab>, val selected: Int, val onSelect: (Int) -> Unit)

/**
 * A screen: the lit ground, the floating header, a scrolling body, and the tab bar when the screen
 * is a root. The body's last card clears the tab bar, so nothing is ever hidden under it.
 */
@Composable
fun GlassScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** The 36dp back button, or null for the app's own mark. */
    onBack: (() -> Unit)? = null,
    /** The network tag on the right of the header. */
    tag: String? = null,
    headerAction: @Composable (RowScope.() -> Unit)? = null,
    tabs: TabBar? = null,
    overlay: @Composable (BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassFrame(title, modifier, subtitle, onBack, tag, headerAction, tabs, overlay) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
            verticalArrangement = Arrangement.spacedBy(Space.Gap),
            content = content,
        )
    }
}

/** The same screen with a lazy body, for the lists that can grow without a bound. */
@Composable
fun GlassListScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    tag: String? = null,
    headerAction: @Composable (RowScope.() -> Unit)? = null,
    tabs: TabBar? = null,
    overlay: @Composable (BoxScope.() -> Unit)? = null,
    listTag: String? = null,
    content: LazyListScope.() -> Unit,
) {
    GlassFrame(title, modifier, subtitle, onBack, tag, headerAction, tabs, overlay) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(Space.Gap),
            modifier =
                if (listTag == null) Modifier.fillMaxSize()
                else Modifier.fillMaxSize().testTag(listTag),
            content = content,
        )
    }
}

@Composable
private fun GlassFrame(
    title: String,
    modifier: Modifier,
    subtitle: String?,
    onBack: (() -> Unit)?,
    tag: String?,
    headerAction: @Composable (RowScope.() -> Unit)?,
    tabs: TabBar?,
    overlay: @Composable (BoxScope.() -> Unit)?,
    body: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LitGround(modifier) {
        Column(Modifier.fillMaxSize()) {
            GlassHeader(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                tag = tag,
                action = headerAction,
                modifier = Modifier.padding(top = top + 6.dp, start = Space.Edge, end = Space.Edge),
            )
            Box(Modifier.fillMaxSize()) {
                body(
                    androidx.compose.foundation.layout.PaddingValues(
                        start = Space.Edge,
                        end = Space.Edge,
                        top = Space.Edge,
                        bottom =
                            if (tabs == null) Space.Xl + bottom else Space.TabBarClearance + bottom,
                    )
                )
            }
        }
        if (tabs != null) {
            GlassTabBar(
                bar = tabs,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .padding(start = Space.Edge, end = Space.Edge, bottom = 16.dp + bottom),
            )
        }
        overlay?.invoke(this)
    }
}

/**
 * The floating header: where the owner is, and one fact about it. The title and subtitle are each
 * one line and ellipsised — a header that grows to fit a long server name would push the body about
 * every time the name changed.
 */
@Composable
fun GlassHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    tag: String? = null,
    action: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .glass(Glass.chrome(Blur.Header), RoundedCornerShape(22.dp))
            .padding(horizontal = Space.Md, vertical = Space.Sm),
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack == null) {
            AppMark()
        } else {
            Box(
                Modifier.size(36.dp)
                    .clip(CircleShape)
                    .background(Nocturne.text(0.08f))
                    .clickable(onClick = onBack)
                    .testTag(ChromeTags.BACK),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Glyph.Back,
                    contentDescription = stringResource(R.string.back),
                    tint = Nocturne.Neutral200,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Nocturne.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(ChromeTags.TITLE),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Nocturne.Neutral500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(ChromeTags.SUBTITLE),
                )
            }
        }
        action?.invoke(this)
        if (tag != null) Tag(tag, icon = Glyph.Network, modifier = Modifier.testTag(ChromeTags.TAG))
    }
}

/** The app's own mark, where a back button would otherwise be. */
@Composable
private fun AppMark() {
    Box(
        Modifier.size(36.dp)
            .clip(RoundedCornerShape(Radius.ChipTight))
            .background(Nocturne.accent(0.22f))
            .testTag(ChromeTags.MARK),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Glyph.WithinRules,
            contentDescription = stringResource(R.string.app_name),
            tint = Nocturne.Accent100,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** The floating tab bar: four roots, and a dot on the one that has something waiting. */
@Composable
fun GlassTabBar(bar: TabBar, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .glass(Glass.chrome(Blur.TabBar), RoundedCornerShape(Radius.Pill))
            .padding(6.dp)
            .testTag(ChromeTags.TAB_BAR),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        bar.tabs.forEachIndexed { index, tab ->
            val on = index == bar.selected
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(Radius.Pill))
                    .background(
                        if (on) Nocturne.accent(0.22f)
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { bar.onSelect(index) }
                    .padding(vertical = Space.Sm)
                    .testTag(tab.tag),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        tint = if (on) Nocturne.Accent100 else Nocturne.Neutral500,
                        modifier = Modifier.size(19.dp),
                    )
                    if (tab.alerts) {
                        Box(
                            Modifier.size(7.dp)
                                .clip(CircleShape)
                                .background(Nocturne.Accent)
                                .testTag(ChromeTags.PENDING_DOT)
                        )
                    }
                }
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) Nocturne.Accent100 else Nocturne.Neutral500,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The veil a panel, a sheet or a dialog puts over whatever it covers. */
@Composable
fun Veil(
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    alpha: Float = 0.62f,
    tag: String? = null,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Nocturne.bg(alpha))
            .then(if (onDismiss == null) Modifier else Modifier.clickable(onClick = onDismiss))
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
    )
}

/**
 * Where a message to the owner appears: above the tab bar when there is one, above the bottom edge
 * when there isn't. It floats over the body like everything else in this design.
 */
@Composable
fun MessageOverlay(
    host: androidx.compose.material3.SnackbarHostState,
    modifier: Modifier = Modifier,
    overTabBar: Boolean = true,
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    androidx.compose.material3.SnackbarHost(
        host,
        modifier =
            modifier.padding(
                start = Space.Edge,
                end = Space.Edge,
                bottom = bottom + if (overTabBar) Space.TabBarClearance else Space.Xl,
            ),
    )
}

/** Test tags for the chrome, so a test can find the header and the tabs by name. */
object ChromeTags {
    const val BACK = "back"
    const val MARK = "chrome.mark"
    const val TITLE = "chrome.title"
    const val SUBTITLE = "chrome.subtitle"
    const val TAG = "chrome.tag"
    const val TAB_BAR = "chrome.tabs"
    const val PENDING_DOT = "chrome.pending"
    const val HOME = "tab.home"
    const val REQUESTS = "tab.requests"
    const val WALLET = "tab.wallet"
    const val ACTIVITY = "tab.activity"
}
