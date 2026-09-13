package io.github.brrenat.seekervault.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The app's one theme (SEE-57): Nocturne, dark, and the same in every system setting.
 *
 * It is not a light theme with a dark variant. The whole design is a lit dark ground with
 * translucent chrome over it, and the glass is built by mixing the ink into transparency — a light
 * ground would not be a recolouring of it but a different design, so the app doesn't pretend to
 * have one.
 *
 * Inter is the design's face. No font binary is committed here, so the scale, weights and tracking
 * are reproduced on the platform's own sans; substituting the face changes one value in
 * [nocturneTypography].
 */
@Composable
fun SeekerVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NocturneColors,
        typography = nocturneTypography,
        shapes = nocturneShapes,
        content = content,
    )
}

/**
 * Material's roles, filled from the Nocturne ramps, for the stock components the app still uses
 * (text fields, dialogs, the snackbar). The chrome the design draws itself — cards, pills, the
 * header, the tab bar — takes its values from [Nocturne] directly rather than through these roles.
 */
val NocturneColors =
    darkColorScheme(
        primary = Nocturne.Accent,
        onPrimary = Nocturne.Bg,
        primaryContainer = Nocturne.Accent800,
        onPrimaryContainer = Nocturne.Accent100,
        secondary = Nocturne.Accent400,
        onSecondary = Nocturne.Bg,
        background = Nocturne.Bg,
        onBackground = Nocturne.Text,
        surface = Nocturne.Surface,
        onSurface = Nocturne.Text,
        surfaceVariant = Nocturne.Neutral900,
        onSurfaceVariant = Nocturne.Neutral400,
        surfaceContainer = Nocturne.Surface,
        surfaceContainerHigh = Nocturne.Neutral900,
        surfaceContainerHighest = Nocturne.Neutral800,
        outline = Nocturne.text(0.22f),
        outlineVariant = Nocturne.text(0.12f),
        error = Nocturne.Danger,
        onError = Nocturne.Bg,
        errorContainer = Nocturne.Neutral900,
        onErrorContainer = Nocturne.Danger,
        scrim = Nocturne.bg(0.62f),
    )

/**
 * The in-frame scale. Display sizes carry the design's tight tracking; everything from a row title
 * down sits at normal tracking, because negative tracking on 13px muted text costs legibility for
 * nothing.
 */
val nocturneTypography =
    Typography().run {
        val heading = FontWeight.Medium
        copy(
            displayLarge =
                displayLarge.copy(
                    fontSize = TypeScale.Headline,
                    lineHeight = 38.sp,
                    fontWeight = heading,
                    letterSpacing = (-0.03).em,
                ),
            headlineLarge =
                headlineLarge.copy(
                    fontSize = TypeScale.Value,
                    lineHeight = 31.sp,
                    fontWeight = heading,
                    letterSpacing = (-0.025).em,
                ),
            headlineMedium =
                headlineMedium.copy(
                    fontSize = TypeScale.Display,
                    lineHeight = 27.sp,
                    fontWeight = heading,
                    letterSpacing = (-0.02).em,
                ),
            headlineSmall =
                headlineSmall.copy(
                    fontSize = TypeScale.Title,
                    lineHeight = 24.sp,
                    fontWeight = heading,
                    letterSpacing = (-0.02).em,
                ),
            titleMedium =
                titleMedium.copy(
                    fontSize = TypeScale.RowTitle,
                    lineHeight = 20.sp,
                    fontWeight = heading,
                    letterSpacing = 0.sp,
                ),
            titleSmall =
                titleSmall.copy(
                    fontSize = TypeScale.Body,
                    lineHeight = 19.sp,
                    fontWeight = heading,
                    letterSpacing = 0.sp,
                ),
            bodyLarge = bodyLarge.copy(fontSize = TypeScale.Body, lineHeight = 20.sp),
            bodyMedium = bodyMedium.copy(fontSize = TypeScale.Secondary, lineHeight = 19.sp),
            bodySmall = bodySmall.copy(fontSize = TypeScale.Caption, lineHeight = 17.sp),
            // The muted section label the design writes as `h6`.
            labelLarge =
                labelLarge.copy(
                    fontSize = TypeScale.Caption,
                    lineHeight = 16.sp,
                    fontWeight = heading,
                    letterSpacing = 0.04.em,
                ),
            labelMedium = labelMedium.copy(fontSize = TypeScale.Caption, lineHeight = 16.sp),
            labelSmall =
                labelSmall.copy(
                    fontSize = TypeScale.Pill,
                    lineHeight = 14.sp,
                    fontWeight = heading,
                    letterSpacing = 0.sp,
                ),
        )
    }

/**
 * An identifier — an address, a mint, a server ID, a blockhash, a signature — is read character by
 * character, so it is set in a monospace face wherever it appears. Half an address read out of a
 * proportional font looks like the one the owner meant.
 */
val MonospaceStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

val nocturneShapes =
    Shapes(
        extraSmall = RoundedCornerShape(Radius.ChipTight),
        small = RoundedCornerShape(Radius.InnerTight),
        medium = RoundedCornerShape(Radius.Card),
        large = RoundedCornerShape(Radius.Panel),
        extraLarge = RoundedCornerShape(Radius.Frame),
    )
