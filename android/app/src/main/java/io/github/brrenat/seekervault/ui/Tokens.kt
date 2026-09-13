package io.github.brrenat.seekervault.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Nocturne tokens (SEE-57). Every colour, radius, space, blur and duration the app draws with
 * is named here once, so a screen never invents a value and the palette study that is still out can
 * land by changing [Accent] and nothing else.
 *
 * The ground is dark and lit rather than flat: three radial washes under translucent chrome. Glass
 * is built by mixing [Text] into transparency instead of by adding colours, which is why there are
 * no "surface variant" shades below — a card is the ground seen through a percentage of the ink.
 */
object Nocturne {
    /** The ground everything is drawn on, and the opaque fill of a sheet that must hide it. */
    val Bg = Color(0xFF161826)
    val Surface = Color(0xFF232532)

    /** The ink. Glass fills, borders and highlights are all percentages of it. */
    val Text = Color(0xFFE9E9ED)

    /**
     * The accent. **Not final** — a palette study is out for review, and when a candidate is chosen
     * this line changes and nothing else does.
     */
    val Accent = Color(0xFF9184D9)

    val Neutral100 = Color(0xFFF3F5FE)
    val Neutral200 = Color(0xFFE4E7F5)
    val Neutral300 = Color(0xFFCFD3E5)
    val Neutral400 = Color(0xFFB2B6CA)
    val Neutral500 = Color(0xFF9397AB)
    val Neutral600 = Color(0xFF75798C)
    val Neutral700 = Color(0xFF595D6C)
    val Neutral800 = Color(0xFF3F424D)
    val Neutral900 = Color(0xFF292B31)

    val Accent100 = Color(0xFFF5F4FF)
    val Accent200 = Color(0xFFE7E5FE)
    val Accent300 = Color(0xFFD2CEFD)
    val Accent400 = Color(0xFFB5ABFC)
    val Accent500 = Color(0xFF968AE0)
    val Accent600 = Color(0xFF796CBF)
    val Accent700 = Color(0xFF5D5294)
    val Accent800 = Color(0xFF423A6A)
    val Accent900 = Color(0xFF2B2741)

    /** Ink at [alpha]: the only way a glass fill, border or highlight is ever written. */
    fun text(alpha: Float): Color = Text.copy(alpha = alpha)

    fun accent(alpha: Float): Color = Accent.copy(alpha = alpha)

    fun bg(alpha: Float): Color = Bg.copy(alpha = alpha)

    /** The hairline between rows of one card. */
    val Divider = text(0.16f)

    /**
     * The one colour outside the two ramps, and the reason it exists: a warning the rules raise is
     * carried by a dashed border and by words, because it is advisory. "Do not approve — the
     * transaction does not match this request" is not advisory, and it must not read as ordinary
     * muted text. It is tuned to sit in the same value range as [Neutral200] on this ground.
     */
    val Danger = Color(0xFFE29BA0)
}

/** Radii, from the phone frame down to a pill. */
object Radius {
    val Frame = 44.dp
    val Sheet = 30.dp
    val Panel = 28.dp
    val Dialog = 28.dp
    val Tile = 24.dp
    val Card = 22.dp
    val Row = 20.dp
    val Inner = 18.dp
    val InnerTight = 16.dp
    val Chip = 13.dp
    val ChipTight = 11.dp
    val Pill = 999.dp
}

/**
 * The spacing scale at density 0.7, plus the two paddings the frame actually lands on and the gap
 * between cards. Rounded to whole dp where a fraction would land off a pixel boundary.
 */
object Space {
    val Xxs = 3.dp
    val Xs = 6.dp
    val Sm = 8.dp
    val Md = 11.dp
    val Lg = 17.dp
    val Xl = 22.dp

    /** The body's own padding, and the header's. */
    val Edge = 14.dp

    /** The padding inside a card. */
    val Inset = 16.dp

    /** Between two cards. */
    val Gap = 12.dp

    /** Under the floating tab bar, so the last card clears it. */
    val TabBarClearance = 108.dp
}

/** Blur radii, from a content card to the sheet that has to hide the screen behind it. */
object Blur {
    val Card = 20.dp
    val Row = 22.dp
    val Tile = 24.dp
    val Header = 26.dp
    val TabBar = 28.dp
    val Panel = 34.dp
    val Dialog = 54.dp
    val Sheet = 64.dp
}

/** Type sizes, in the frame's own scale. */
object TypeScale {
    val Headline = 34.sp
    val Value = 27.sp
    val Display = 23.sp
    val Title = 19.sp
    val RowTitle = 15.sp
    val Body = 14.sp
    val Secondary = 13.sp
    val Caption = 12.sp
    val Pill = 11.sp
}

/**
 * Motion. One easing for everything that moves under a finger, and durations that differ only where
 * the thing being moved differs in weight.
 */
object Motion {
    val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    const val PANEL_MILLIS = 300
    const val SHEET_MILLIS = 300
    const val TILE_MILLIS = 240
    const val VEIL_MILLIS = 240
    const val TOGGLE_MILLIS = 220
    const val SPRING_BACK_MILLIS = 200

    fun <T> panel(): FiniteAnimationSpec<T> = tween(PANEL_MILLIS, easing = Ease)

    fun <T> tile(): FiniteAnimationSpec<T> = tween(TILE_MILLIS, easing = Ease)

    fun <T> toggle(): FiniteAnimationSpec<T> = tween(TOGGLE_MILLIS, easing = Ease)

    fun <T> springBack(): FiniteAnimationSpec<T> = tween(SPRING_BACK_MILLIS, easing = Ease)
}

/**
 * The review panel's own measurements. They are as much part of the design as a radius is, and a
 * screen that wrote them inline would be a second place to tune them from.
 */
object PanelSize {
    /** How far the panel is held off the top and bottom of the frame. */
    val Margin = 78.dp

    /** The bar that says the panel can be pushed away. */
    val HandleWidth = 40.dp
    val HandleHeight = 4.dp

    /** The circles either side of the pager. */
    val PagerButton = 32.dp

    /** One dot per waiting request, and the wider one for the request being read. */
    val Dot = 6.dp
    val ActiveDot = 18.dp

    /** How far back the panel steps while the wallet is being asked. */
    const val STACKED_SCALE = 0.93f
    val StackedLift = (-24).dp
}

/** How far a gesture has to go before it means something. */
object Gesture {
    /** A swipe row answers past this, and springs back under it. */
    val RowThreshold = 88.dp

    /** The distance over which a row's revealed cue fades in. */
    val RowCueTravel = 88.dp

    /** The review panel moves to the next request past this. */
    val PanelThreshold = 64.dp

    /** A downward drag past this leaves the request unanswered. */
    val PanelDismiss = 110.dp

    /** Below this the panel hasn't chosen an axis yet. */
    val AxisLock = 8.dp

    /** A drag longer than this is not a tap. */
    val TapSlop = 4.dp

    /** How much of a drag past the ends of the pager is given back. */
    const val RUBBER_BAND = 0.28f
}
