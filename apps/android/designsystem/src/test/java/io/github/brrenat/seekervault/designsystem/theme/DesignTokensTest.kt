package io.github.brrenat.seekervault.designsystem.theme

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DesignTokensTest {
    private val tokens =
        JSONObject(File(requireNotNull(System.getProperty("seekervault.designTokens"))).readText())

    @Test
    fun `Kotlin named scales exactly match tokens json`() {
        assertEquals(
            setOf(
                "colour",
                "type",
                "iconSize",
                "radius",
                "space",
                "buttonSize",
                "iconButtonSize",
            ),
            tokens.keysSet(),
        )

        assertEquals(
            tokens.getJSONArray("space").floats(),
            listOf(
                    SeekerSpacingTokens.xxs,
                    SeekerSpacingTokens.xs,
                    SeekerSpacingTokens.sm,
                    SeekerSpacingTokens.md,
                    SeekerSpacingTokens.mdPlus,
                    SeekerSpacingTokens.lg,
                    SeekerSpacingTokens.lgPlus,
                    SeekerSpacingTokens.xl,
                    SeekerSpacingTokens.xxl,
                    SeekerSpacingTokens.xxxl,
                    SeekerSpacingTokens.huge,
                    SeekerSpacingTokens.jumbo,
                )
                .map { it.value },
        )
        assertEquals(
            tokens.getJSONArray("radius").floats(),
            listOf(
                    SeekerRadiusTokens.xs,
                    SeekerRadiusTokens.sm,
                    SeekerRadiusTokens.md,
                    SeekerRadiusTokens.lg,
                    SeekerRadiusTokens.xl,
                    SeekerRadiusTokens.xxl,
                    SeekerRadiusTokens.sheet,
                )
                .map { it.value },
        )
        assertEquals(
            (tokens.getJSONArray("type").floats() + PendingExportTypeSizes).sorted(),
            kotlinTypeScale(),
        )
        assertEquals(
            tokens.getJSONArray("iconSize").floats(),
            listOf(SeekerSizeTokens.icon.standard.value),
        )

        assertSizeObjectsMatch()
    }

    private fun assertSizeObjectsMatch() {
        val buttons = tokens.getJSONObject("buttonSize")
        assertEquals(setOf("sm", "md", "lg"), buttons.keysSet())
        assertEquals(
            buttons.sizeMap(setOf("h", "r", "f", "p")),
            mapOf(
                "sm" to SeekerSizeTokens.button.small.values(),
                "md" to SeekerSizeTokens.button.medium.values(),
                "lg" to SeekerSizeTokens.button.large.values(),
            ),
        )

        val iconButtons = tokens.getJSONObject("iconButtonSize")
        assertEquals(setOf("lg", "md"), iconButtons.keysSet())
        assertEquals(
            iconButtons.sizeMap(setOf("box", "glyph")),
            mapOf(
                "lg" to SeekerSizeTokens.iconButton.large.values(),
                "md" to SeekerSizeTokens.iconButton.medium.values(),
            ),
        )
    }

    private fun kotlinTypeScale(): List<Float> = buildSet {
        add(SeekerTypography.displayLarge.fontSize.value)
        add(SeekerTypography.displayMedium.fontSize.value)
        add(SeekerTypography.displaySmall.fontSize.value)
        add(SeekerTypography.headlineLarge.fontSize.value)
        add(SeekerTypography.headlineMedium.fontSize.value)
        add(SeekerTypography.headlineSmall.fontSize.value)
        add(SeekerTypography.titleLarge.fontSize.value)
        add(SeekerTypography.titleMedium.fontSize.value)
        add(SeekerTypography.titleSmall.fontSize.value)
        add(SeekerTypography.bodyLarge.fontSize.value)
        add(SeekerTypography.bodyMedium.fontSize.value)
        add(SeekerTypography.bodySmall.fontSize.value)
        add(SeekerTypography.labelLarge.fontSize.value)
        add(SeekerTypography.labelMedium.fontSize.value)
        add(SeekerTypography.labelSmall.fontSize.value)
        add(SeekerExtraTypographyTokens.buttonLarge.fontSize.value)
        add(SeekerExtraTypographyTokens.amount.fontSize.value)
        add(SeekerExtraTypographyTokens.screenTitle.fontSize.value)
        add(SeekerExtraTypographyTokens.identifier.fontSize.value)
        add(SeekerExtraTypographyTokens.tileTitle.fontSize.value)
        add(SeekerExtraTypographyTokens.badgeCount.fontSize.value)
    }
        .sorted()

    private fun JSONObject.sizeMap(fields: Set<String>): Map<String, Map<String, Float>> =
        keysSet().associateWith { key ->
            getJSONObject(key).also { assertEquals(fields, it.keysSet()) }.floatMap()
        }

    private fun JSONObject.floatMap(): Map<String, Float> =
        keysSet().associateWith { key -> getDouble(key).toFloat() }

    private fun JSONObject.keysSet(): Set<String> = keys().asSequence().toSet()

    private fun JSONArray.floats(): List<Float> =
        (0 until length()).map { index -> getDouble(index).toFloat() }

    private fun SeekerButtonSize.values(): Map<String, Float> =
        mapOf(
            "h" to height.value,
            "r" to radius.value,
            "f" to fontSize.value,
            "p" to horizontalPadding.value,
        )

    private fun SeekerIconButtonSize.values(): Map<String, Float> =
        mapOf("box" to box.value, "glyph" to glyph.value)
}

/**
 * Sizes a ticket specified before the export had them: SEE-183's 17sp request-tile title. Each one
 * leaves this list when the design export is refreshed and `tokens.json` carries it.
 */
private val PendingExportTypeSizes = listOf(17f)
