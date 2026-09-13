package io.github.brrenat.seekervault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * The ground everything else is drawn on (SEE-57): not a flat dark fill but a lit one — three
 * radial washes over [Nocturne.Bg], with a faint star field over them.
 *
 * It is drawn once, under the whole app, and every glass surface above it is a percentage of ink
 * over this. That is why the chrome reads as translucent even where the platform gives no backdrop
 * blur: there is something behind it worth seeing.
 */
@Composable
fun LitGround(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().drawBehind { drawLitGround() }, content = content)
}

/** The washes and the stars, in that order. Deterministic: the same frame draws the same sky. */
fun DrawScope.drawLitGround() {
    drawRect(Nocturne.Bg)
    wash(
        Nocturne.Accent800,
        Offset(size.width * 0.08f, size.height * 0.04f),
        size.maxDimension * 0.72f,
        0.55f,
    )
    wash(
        Nocturne.Accent900,
        Offset(size.width * 1.02f, size.height * 0.38f),
        size.maxDimension * 0.66f,
        0.60f,
    )
    wash(
        Nocturne.Neutral900,
        Offset(size.width * 0.44f, size.height * 1.04f),
        size.maxDimension * 0.80f,
        0.70f,
    )
    stars()
}

private fun DrawScope.wash(color: Color, centre: Offset, radius: Float, strength: Float) {
    if (radius <= 0f) return
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(color.copy(alpha = strength), Color.Transparent),
                center = centre,
                radius = radius,
            ),
        radius = radius,
        center = centre,
    )
}

/**
 * The star field: six-point stars, half opaque, in the same places every time. The seed is fixed on
 * purpose — a sky that reshuffled on every recomposition would be movement nobody asked for.
 */
private fun DrawScope.stars() {
    val random = Random(57)
    val arm = 2.dp.toPx()
    val stroke = 1.dp.toPx()
    val diagonal = arm * 0.62f
    repeat(STAR_COUNT) {
        val centre = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
        val scale = 0.6f + random.nextFloat() * 0.8f
        val colour = Nocturne.Neutral100.copy(alpha = 0.5f * (0.4f + random.nextFloat() * 0.6f))
        val a = arm * scale
        val d = diagonal * scale
        drawLine(colour, centre - Offset(a, 0f), centre + Offset(a, 0f), stroke)
        drawLine(colour, centre - Offset(0f, a), centre + Offset(0f, a), stroke)
        drawLine(colour, centre - Offset(d, d), centre + Offset(d, d), stroke)
        drawLine(colour, centre - Offset(d, -d), centre + Offset(d, -d), stroke)
    }
}

private const val STAR_COUNT = 46

private val Size.maxDimension: Float
    get() = maxOf(width, height)
