package io.github.brrenat.seekervault.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The controls the design draws itself (SEE-57): pills instead of buttons, a track-and-knob switch,
 * a square check, chips, tags and the dashed border that carries "under restrictions" without a
 * colour.
 *
 * Everything here is a presentation of state it is handed. Nothing in this file decides anything.
 */

/** How loud a pill is. */
enum class PillTone {
    /** The one affirmative action on a screen. */
    Accent,
    /** Everything else the owner can do. */
    Neutral,
    /** A quiet third option that must not read as an answer. */
    Ghost,
    /** Disconnect, and nothing else. */
    Danger,
}

/**
 * A pill. The design has no rectangular buttons: every action is a pill, and the tone says how loud
 * it is rather than what it does — what it does is in its words.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val fill: Color
    val border: Color
    val ink: Color
    when (tone) {
        PillTone.Accent -> {
            fill = Nocturne.accent(0.20f)
            border = Nocturne.accent(0.50f)
            ink = Nocturne.Accent100
        }
        PillTone.Neutral -> {
            fill = Nocturne.text(0.07f)
            border = Nocturne.text(0.12f)
            ink = Nocturne.Neutral200
        }
        PillTone.Ghost -> {
            fill = Color.Transparent
            border = Nocturne.text(0.10f)
            ink = Nocturne.Neutral400
        }
        PillTone.Danger -> {
            fill = Nocturne.text(0.07f)
            border = Nocturne.Danger.copy(alpha = 0.45f)
            ink = Nocturne.Danger
        }
    }
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.Pill))
            .background(fill.copy(alpha = fill.alpha * alpha))
            .border(
                1.dp,
                border.copy(alpha = border.alpha * alpha),
                RoundedCornerShape(Radius.Pill),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = Space.Xl, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = ink.copy(alpha = alpha),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = ink.copy(alpha = alpha),
            textAlign = TextAlign.Center,
        )
    }
}

/** A text link: the quietest way to leave a screen for another one. */
@Composable
fun TextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) Nocturne.Accent300 else Nocturne.Neutral600,
        modifier =
            modifier
                .clip(RoundedCornerShape(Radius.ChipTight))
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { if (!enabled) disabled() }
                .padding(horizontal = Space.Xs, vertical = Space.Xxs),
    )
}

/** How a tag reads. */
enum class TagTone {
    /** An outline tag: a fact about the screen, such as the network. */
    Outline,
    /** An accent tag: the request matched the rules the owner wrote. */
    Accent,
    /** A dashed tag: under restrictions. The dashes carry it, not a colour. */
    Dashed,
}

/** A small pill of one fact: the network tag, the stake of a request, the rule verdict. */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    tone: TagTone = TagTone.Outline,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(Radius.Pill)
    val ink =
        when (tone) {
            TagTone.Outline -> Nocturne.Neutral300
            TagTone.Accent -> Nocturne.Accent100
            TagTone.Dashed -> Nocturne.Neutral300
        }
    val base =
        when (tone) {
            TagTone.Outline ->
                Modifier.background(Nocturne.text(0.06f), shape)
                    .border(1.dp, Nocturne.text(0.14f), shape)
            TagTone.Accent ->
                Modifier.background(Nocturne.accent(0.20f), shape)
                    .border(1.dp, Nocturne.accent(0.40f), shape)
            TagTone.Dashed ->
                Modifier.background(Nocturne.text(0.07f), shape)
                    .dashedBorder(Nocturne.text(0.26f), Radius.Pill)
        }
    Row(
        modifier
            .clip(shape)
            .then(base)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Space.Sm, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1)
    }
}

/**
 * A chip that is one of a few choices: a network, a chain.
 *
 * It carries the choice in its semantics (`selectable`, with a radio button's role) and marks the
 * chosen one with a tick as well as with the accent, because a chip that says "this is the one"
 * only in colour says it to nobody who cannot see the colour — and the network a wallet connects on
 * is not a detail to leave ambiguous.
 */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(Radius.Pill)
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier
            .clip(shape)
            .background(
                if (selected) Nocturne.accent(0.20f * alpha) else Nocturne.text(0.06f * alpha),
                shape,
            )
            .border(
                1.dp,
                if (selected) Nocturne.accent(0.46f * alpha) else Nocturne.text(0.12f * alpha),
                shape,
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = Space.Md, vertical = Space.Sm),
        horizontalArrangement = Arrangement.spacedBy(Space.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                Glyph.Acknowledge,
                contentDescription = null,
                tint = Nocturne.Accent100.copy(alpha = alpha),
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (selected) Nocturne.Accent100.copy(alpha = alpha)
                else Nocturne.Neutral400.copy(alpha = alpha),
        )
    }
}

/**
 * A dashed hairline round a shape. It is how this design says "under restrictions" without leaning
 * on a colour: a reader who sees no colour at all still sees a border that is broken rather than
 * whole, and the words next to it say the same thing again.
 */
fun Modifier.dashedBorder(color: Color, corner: Dp, width: Dp = 1.dp): Modifier = drawBehind {
    val stroke = width.toPx()
    val radius = CornerRadius(corner.toPx().coerceAtMost(size.minDimension / 2f))
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = radius,
        style =
            Stroke(
                width = stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 4f, stroke * 3f)),
            ),
    )
}

/**
 * The design's switch: a 46 × 28 track with a 20dp knob that slides across it. It draws state and
 * nothing else — the row around it carries the click and the accessibility role, so the words and
 * the switch are one target and one announcement.
 */
@Composable
fun GlassSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val knob by animateDpAsState(if (checked) 21.dp else 3.dp, Motion.toggle(), label = "knob")
    val alpha = if (enabled) 1f else 0.45f
    Box(
        modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(RoundedCornerShape(Radius.Pill))
            .background(
                if (checked) Nocturne.accent(0.62f * alpha) else Nocturne.text(0.10f * alpha),
                RoundedCornerShape(Radius.Pill),
            )
            .then(
                if (checked) Modifier
                else
                    Modifier.border(
                        1.dp,
                        Nocturne.text(0.14f * alpha),
                        RoundedCornerShape(Radius.Pill),
                    )
            )
    ) {
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset(x = knob)
                .size(20.dp)
                .clip(CircleShape)
                .background(Nocturne.Neutral100.copy(alpha = alpha))
                .border(1.dp, Nocturne.Neutral800, CircleShape)
        )
    }
}

/** The design's check: a 22dp rounded square, filled and ticked when it is on. */
@Composable
fun GlassCheck(checked: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier
            .size(22.dp)
            .clip(shape)
            .background(if (checked) Nocturne.accent(0.55f) else Nocturne.text(0.06f), shape)
            .then(if (checked) Modifier else Modifier.border(1.dp, Nocturne.text(0.22f), shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Glyph.Acknowledge,
                contentDescription = null,
                tint = Nocturne.Accent100,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** The design's radio: a 22dp ring that gains an accent edge and a dot when it is chosen. */
@Composable
fun GlassRadio(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Nocturne.text(0.06f), CircleShape)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Nocturne.accent(0.70f) else Nocturne.text(0.22f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Nocturne.Accent200))
        }
    }
}

/** A rounded-square chip with one icon in it: what a row is about, at a glance. */
@Composable
fun IconChip(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    accent: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(Radius.Chip))
            .background(if (accent) Nocturne.accent(0.18f) else Nocturne.text(0.07f))
            .border(
                1.dp,
                if (accent) Nocturne.accent(0.38f) else Nocturne.text(0.12f),
                RoundedCornerShape(Radius.Chip),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (accent) Nocturne.Accent100 else Nocturne.Neutral300,
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

/** A paired server's avatar: the initials of the name the owner gave it. */
@Composable
fun InitialsChip(label: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(Radius.Chip))
            .background(Nocturne.text(0.08f))
            .border(1.dp, Nocturne.text(0.12f), RoundedCornerShape(Radius.Chip)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsOf(label),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral300,
            maxLines = 1,
        )
    }
}

/**
 * An identifier shortened in the middle: `FyfWsS…SpEA`. Never cut at one end — half an address that
 * still looks like a whole one is worse than an obviously shortened one.
 */
fun truncateMiddle(value: String, keepStart: Int = 6, keepEnd: Int = 4): String =
    if (value.length <= keepStart + keepEnd + 1) value
    else "${value.take(keepStart)}…${value.takeLast(keepEnd)}"

/** At most two initials, from the words of [label]. */
fun initialsOf(label: String): String =
    label
        .split(' ', '-', '_', '.')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

/** An identifier, set monospace so it can be read character by character. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Nocturne.Neutral300,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The hairline between two rows of one card. */
@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Nocturne.text(0.08f)))
}

/** A row of a fact table: a muted label and its value. */
@Composable
fun FactRow(label: String, modifier: Modifier = Modifier, value: @Composable RowScope.() -> Unit) {
    Row(
        modifier.padding(vertical = Space.Md),
        horizontalArrangement = Arrangement.spacedBy(Space.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral500,
            modifier = Modifier.weight(0.9f),
        )
        Row(Modifier.weight(1.4f), horizontalArrangement = Arrangement.End, content = value)
    }
}
