import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.spotless)
}

abstract class DesignCompareTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val referenceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val actualDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val screenReferences: ConfigurableFileCollection

    @get:OutputDirectory abstract val comparisonDirectory: DirectoryProperty

    @TaskAction
    fun compare() {
        val referenceRoot = referenceDirectory.get().asFile
        val outputRoot = comparisonDirectory.get().asFile
        outputRoot.deleteRecursively()
        outputRoot.mkdirs()

        val references =
            pngFiles(referenceRoot) + screenReferences.files.associateBy { "screens/${it.name}" }
        val actuals = buildMap {
            actualDirectories.files.filter(File::exists).forEach { root ->
                pngFiles(root).forEach { (path, file) ->
                    check(path !in this) { "Duplicate Roborazzi output path: $path" }
                    put(path, file)
                }
            }
        }
        val paired = references.keys.intersect(actuals.keys).sorted()
        paired.forEach { relativePath ->
            writeComparison(
                reference = references.getValue(relativePath),
                actual = actuals.getValue(relativePath),
                output = outputRoot.resolve(relativePath),
            )
        }

        val missingReferences = (actuals.keys - references.keys).sorted()
        val missingActuals = (references.keys - actuals.keys).sorted()
        val report = buildString {
            appendLine("paired (${paired.size})")
            paired.forEach { appendLine("  $it") }
            appendLine("missing reference (${missingReferences.size})")
            missingReferences.forEach { appendLine("  $it") }
            appendLine("missing actual (${missingActuals.size})")
            missingActuals.forEach { appendLine("  $it") }
        }
        outputRoot.resolve("report.txt").writeText(report)
        logger.lifecycle(report.trimEnd())
        logger.lifecycle("Design comparisons: ${outputRoot.absolutePath}")
    }

    private fun pngFiles(root: File): Map<String, File> =
        root
            .walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.extension.equals("png", ignoreCase = true) &&
                    !file.nameWithoutExtension.endsWith("_compare") &&
                    !file.nameWithoutExtension.endsWith("_actual")
            }
            .associateBy { it.relativeTo(root).invariantSeparatorsPath }

    private fun writeComparison(reference: File, actual: File, output: File) {
        val referenceImage =
            requireNotNull(ImageIO.read(reference)) { "Unreadable PNG: $reference" }
        val actualImage = requireNotNull(ImageIO.read(actual)) { "Unreadable PNG: $actual" }
        val imageHeight = maxOf(referenceImage.height, actualImage.height)
        // Tiny spacing/token specimens can be only a few pixels wide. Keep enough caption room so
        // their dimensions are still printed rather than clipped out of the review artifact.
        val referenceWidth = maxOf(referenceImage.width, 240)
        val actualWidth = maxOf(actualImage.width, 240)
        val captionHeight = 44
        val canvas =
            BufferedImage(
                referenceWidth + actualWidth,
                imageHeight + captionHeight,
                BufferedImage.TYPE_INT_ARGB,
            )
        val graphics = canvas.createGraphics()
        try {
            graphics.color = Color(18, 18, 18)
            graphics.fillRect(0, 0, canvas.width, canvas.height)
            graphics.drawImage(referenceImage, 0, 0, null)
            graphics.drawImage(actualImage, referenceWidth, 0, null)
            graphics.color = Color(111, 111, 111)
            graphics.drawLine(referenceWidth, 0, referenceWidth, imageHeight)
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
            graphics.color = Color.WHITE
            graphics.drawString(
                "reference ${referenceImage.width}×${referenceImage.height}",
                8,
                imageHeight + 28,
            )
            graphics.drawString(
                "actual ${actualImage.width}×${actualImage.height}",
                referenceWidth + 8,
                imageHeight + 28,
            )
        } finally {
            graphics.dispose()
        }
        output.parentFile.mkdirs()
        check(ImageIO.write(canvas, "png", output)) { "No PNG writer is available" }
    }
}

spotless {
    val ktfmtVersion = libs.versions.ktfmt.get()
    kotlin {
        target("app/src/**/*.kt", "designsystem/src/**/*.kt", "preview-testing/src/**/*.kt")
        targetExclude("app/src/main/generated/**")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts", "designsystem/*.gradle.kts")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
}

val themeDirectory =
    layout.projectDirectory.dir(
        "designsystem/src/main/java/io/github/brrenat/seekervault/designsystem/theme"
    )
val productionKotlin =
    files(
        fileTree("app/src/main") {
            include("**/*.kt")
            exclude("generated/**")
        },
        fileTree("designsystem/src/main") {
            include("**/*.kt")
            exclude("java/io/github/brrenat/seekervault/designsystem/theme/**")
        },
    )

val checkDesignSystemLiterals =
    tasks.register<Exec>("checkDesignSystemLiterals") {
        group = "verification"
        description = "Rejects raw colour, dp, and sp literals outside the design-system theme."
        inputs.files(productionKotlin)
        inputs.dir(themeDirectory)
        workingDir(layout.projectDirectory)
        commandLine("node", layout.projectDirectory.file("scripts/check-design-literals.mjs"))
    }

tasks.named("check") {
    dependsOn(checkDesignSystemLiterals, "spotlessCheck", ":designsystem:check", ":app:check")
}

tasks.named("spotlessCheck") { dependsOn(checkDesignSystemLiterals) }

tasks.register<DesignCompareTask>("designCompare") {
    group = "verification"
    description =
        "Writes reference | Roborazzi PNGs with dimensions and reports missing design pairs."
    referenceDirectory.set(rootProject.layout.projectDirectory.dir("../design/components"))
    screenReferences.from(
        listOf(
                "sheet-transfer.png",
                "sheet-swap.png",
                "sheet-prediction.png",
                "sheet-signature.png",
                "sheet-acknowledge.png",
                "home.png",
                "requests.png",
                "wallet.png",
                "activity.png",
                "add.png",
            )
            .map { rootProject.layout.projectDirectory.file("../design/screens/$it") }
    )
    actualDirectories.from(
        project(":designsystem").layout.buildDirectory.dir("outputs/roborazzi"),
        project(":app").layout.buildDirectory.dir("outputs/roborazzi"),
    )
    comparisonDirectory.set(layout.buildDirectory.dir("design-compare"))
}
