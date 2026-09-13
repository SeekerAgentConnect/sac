package io.github.brrenat.seekervault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glass: the one surface this app is built from (SEE-57).
 *
 * A glass surface is the lit ground seen through a percentage of the ink, with a hairline border
 * and a highlight along its top inside edge. The design specifies a backdrop blur for it. Compose
 * has no backdrop blur that works across the versions this app supports, so the fill is flat
 * translucent at the same value — which is the fallback the design calls for. What is never dropped
 * is the layering: a sheet still sits in front of a panel, and the panel still sits in front of the
 * screen that opened it, at the values below.
 *
 * [blur] is carried on every surface even though nothing reads it yet, so that the day the platform
 * offers a backdrop blur, it is wired in one place instead of twenty.
 */
data class Glass(
    /** The fill, as a vertical gradient of ink percentages. */
    val fill: Brush,
    /** The hairline. */
    val border: Color,
    /** The highlight along the top inside edge, or null where the design has none. */
    val highlight: Color? = Nocturne.text(0.16f),
    /** The ring and drop of [GlassShadow], or null for no shadow at all. */
    val shadow: GlassShadow? = null,
    /** What the design asks the backdrop to be blurred by. */
    val blur: Dp = Blur.Card,
) {
    companion object {
        /** A content card: the quietest surface there is. */
        fun card(blur: Dp = Blur.Card) =
            Glass(fill = ink(0.09f, 0.04f), border = Nocturne.text(0.10f), blur = blur)

        /** A row inside a list, or a swipeable row's own face. */
        fun row() = Glass(fill = ink(0.10f, 0.04f), border = Nocturne.text(0.10f), blur = Blur.Row)

        /** The floating header and tab bar. */
        fun chrome(blur: Dp) =
            Glass(fill = ink(0.11f, 0.06f), border = Nocturne.text(0.10f), blur = blur)

        /** A carousel tile the finger is on. */
        fun tileActive() =
            Glass(
                fill = ink(0.13f, 0.03f),
                border = Nocturne.text(0.12f),
                shadow = GlassShadow.Md,
                blur = Blur.Tile,
            )

        /** A carousel tile beside it. */
        fun tileIdle() =
            Glass(fill = ink(0.06f, 0.02f), border = Nocturne.text(0.08f), blur = Blur.Tile)

        /** The request review panel. */
        fun panel() =
            Glass(
                fill = ink(0.15f, 0.05f),
                border = Nocturne.text(0.13f),
                highlight = Nocturne.text(0.20f),
                shadow = GlassShadow.Lg,
                blur = Blur.Panel,
            )

        /** A centred dialog: near-opaque, because what is behind it is not to be read. */
        fun dialog() =
            Glass(
                fill =
                    Brush.verticalGradient(
                        listOf(mix(Nocturne.bg(0.92f), 0.07f), mix(Nocturne.bg(0.96f), 0.04f))
                    ),
                border = Nocturne.text(0.13f),
                highlight = Nocturne.text(0.20f),
                shadow = GlassShadow.Lg,
                blur = Blur.Dialog,
            )

        /** The wallet hand-off sheet: the most opaque surface in the app. */
        fun sheet() =
            Glass(
                fill =
                    Brush.verticalGradient(
                        listOf(mix(Nocturne.bg(0.86f), 0.09f), mix(Nocturne.bg(0.93f), 0.05f))
                    ),
                border = Nocturne.text(0.13f),
                highlight = Nocturne.text(0.20f),
                shadow = GlassShadow.Lg,
                blur = Blur.Sheet,
            )

        /** An accent surface: the primary pill, the active tab, a chip that is on. */
        fun accent(fill: Float = 0.20f, border: Float = 0.46f) =
            Glass(
                fill = Brush.verticalGradient(listOf(Nocturne.accent(fill), Nocturne.accent(fill))),
                border = Nocturne.accent(border),
                highlight = Nocturne.text(0.16f),
            )

        /** Ink mixed into transparency, top to bottom: the only way a glass fill is written. */
        fun ink(top: Float, bottom: Float): Brush =
            Brush.verticalGradient(listOf(Nocturne.text(top), Nocturne.text(bottom)))

        /** A near-opaque ground with a little ink mixed into it, for sheets and dialogs. */
        private fun mix(ground: Color, ink: Float): Color =
            Color(
                red = ground.red * (1 - ink) + Nocturne.Text.red * ink,
                green = ground.green * (1 - ink) + Nocturne.Text.green * ink,
                blue = ground.blue * (1 - ink) + Nocturne.Text.blue * ink,
                alpha = ground.alpha,
            )
    }
}

/**
 * The three shadows the design has: a ring, a ring and a drop, a brighter ring and a deeper drop.
 */
enum class GlassShadow(val ring: Color, val elevation: Dp) {
    Sm(Nocturne.Neutral800, 0.dp),
    Md(Nocturne.Neutral700, 10.dp),
    Lg(Nocturne.Neutral500, 20.dp),
}

/**
 * Draws [glass] behind this content: the drop shadow, the fill, the hairline, and the highlight
 * along the top inside edge that gives the surface its lit look.
 */
fun Modifier.glass(glass: Glass, shape: Shape): Modifier =
    this.then(
            glass.shadow?.let { shadow ->
                if (shadow.elevation > 0.dp) Modifier.shadow(shadow.elevation, shape, clip = false)
                else Modifier
            } ?: Modifier
        )
        .clip(shape)
        .background(glass.fill, shape)
        .border(1.dp, glass.shadow?.ring ?: glass.border, shape)
        .then(glass.highlight?.let { Modifier.topHighlight(it, shape) } ?: Modifier)

/**
 * One hairline of light along the top inside edge. It is inset by the corner radius so it stops
 * where the corner turns rather than cutting across it.
 */
private fun Modifier.topHighlight(color: Color, shape: Shape): Modifier = drawWithContent {
    drawContent()
    val inset =
        if (shape is RoundedCornerShape) size.height.coerceAtMost(size.width) * 0.12f else 0f
    val y = 0.5.dp.toPx()
    drawLine(
        color = color,
        start = Offset(inset, y),
        end = Offset(size.width - inset, y),
        strokeWidth = 1.dp.toPx(),
    )
}

/** A glass card with the design's own inset and gap between its children. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glass: Glass = Glass.card(),
    shape: Shape = RoundedCornerShape(Radius.Card),
    padding: Dp = Space.Inset,
    spacing: Dp = Space.Sm,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().glass(glass, shape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * A muted section label — the design's `h6`. It names what follows rather than heading it, so it is
 * small, tracked out, and never the loudest thing on screen.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Nocturne.Neutral500,
        modifier = modifier,
    )
}
