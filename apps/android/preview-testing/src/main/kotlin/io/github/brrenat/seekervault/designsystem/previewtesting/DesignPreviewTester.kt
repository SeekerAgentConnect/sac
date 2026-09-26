package io.github.brrenat.seekervault.designsystem.previewtesting

import com.github.takahirom.roborazzi.AndroidComposePreviewTester
import com.github.takahirom.roborazzi.ComposePreviewTester
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.InternalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziRecordFilePathStrategy
import com.github.takahirom.roborazzi.provideRoborazziContext
import com.github.takahirom.roborazzi.roborazziRecordFilePathStrategy
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import java.io.File
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

internal object DesignPreviewNaming {
    private val equalsOrWhitespace = Regex("[=\\s]+")
    private val unsupported = Regex("[^a-z0-9-]")
    private val repeatedHyphens = Regex("-+")
    private val edgeHyphen = Regex("^-|-$")

    /** Mirrors `slugVariant` in `design/tools/capture.mjs` byte for byte. */
    fun slug(value: String): String =
        value
            .lowercase()
            .replace(equalsOrWhitespace, "-")
            .replace(unsupported, "")
            .replace(repeatedHyphens, "-")
            .replace(edgeHyphen, "")

    fun relativePath(preview: ComposablePreview<AndroidPreviewInfo>, extension: String): String {
        val method =
            Class.forName(preview.declaringClass).declaredMethods.firstOrNull { candidate ->
                candidate.name == preview.methodName &&
                    candidate.getAnnotation(DesignRef::class.java) != null
            }
                ?: error(
                    "Every @Preview in :designsystem must have @DesignRef: " +
                        "${preview.declaringClass}.${preview.methodName}"
                )
        val designRef = requireNotNull(method.getAnnotation(DesignRef::class.java))
        val component = slug(designRef.component)
        val variant = slug(designRef.variant)
        require(component.isNotEmpty()) {
            "Empty component slug in @DesignRef on ${preview.methodName}"
        }
        require(variant.isNotEmpty()) {
            "Empty variant slug in @DesignRef on ${preview.methodName}"
        }
        return "$component/$variant.$extension"
    }
}

@OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)
class DesignPreviewTester :
    ComposePreviewTester<
        ComposePreviewTester.TestParameter.JUnit4TestParameter.AndroidPreviewJUnit4TestParameter
    > by AndroidComposePreviewTester(
        capturer = { parameter ->
            val relativePath =
                DesignPreviewNaming.relativePath(
                    parameter.preview,
                    provideRoborazziContext().imageExtension,
                )
            val filePath =
                if (
                    roborazziRecordFilePathStrategy() ==
                        RoborazziRecordFilePathStrategy.RelativePathFromCurrentDirectory
                ) {
                    File(roborazziSystemPropertyOutputDirectory(), relativePath).path
                } else {
                    relativePath
                }
            AndroidComposePreviewTester.DefaultCapturer()
                .capture(parameter.copy(filePath = filePath))
        }
    )
