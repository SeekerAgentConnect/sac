package io.github.brrenat.seekervault

import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The public product name and the launcher assets supplied for SEE-65. */
class BrandingTest {
    private val repository =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            }
        )
    private val main = File(repository, "android/app/src/main")

    private fun xml(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun resource(path: String, tag: String, name: String): String {
        val nodes = xml(File(main, path)).getElementsByTagName(tag)
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .single { it.getAttribute("name") == name }
            .textContent
            .trim()
    }

    @Test
    fun installedAndInAppNamesUseSeekerAgentConnectWithoutChangingTheApplicationId() {
        assertEquals(
            "Seeker Agent Connect",
            resource("res/values/strings.xml", "string", "app_name"),
        )
        assertEquals(
            "Seeker Agent Connect",
            resource("res/values/strings_connections.xml", "string", "home_title"),
        )

        val application =
            xml(File(main, "AndroidManifest.xml")).getElementsByTagName("application").item(0)
                as Element
        assertEquals("@string/app_name", application.getAttribute("android:label"))
        assertEquals("@mipmap/ic_launcher", application.getAttribute("android:icon"))
        assertEquals("@mipmap/ic_launcher", application.getAttribute("android:roundIcon"))

        val build = File(repository, "android/app/build.gradle.kts").readText()
        assertTrue("namespace changed", "namespace = \"io.github.brrenat.seekervault\"" in build)
        assertTrue(
            "application ID changed",
            "applicationId = \"io.github.brrenat.seekervault\"" in build,
        )
    }

    @Test
    fun adaptiveIconUsesTheLimeGroundAndProvidedForegroundForEveryMask() {
        assertEquals(
            "#C2E60F",
            resource("res/values/colors.xml", "color", "brand_lime"),
        )
        val adaptive = xml(File(main, "res/mipmap-anydpi-v26/ic_launcher.xml"))
        assertEquals("adaptive-icon", adaptive.tagName)
        assertEquals(
            "@color/brand_lime",
            (adaptive.getElementsByTagName("background").item(0) as Element).getAttribute(
                "android:drawable"
            ),
        )
        for (layer in listOf("foreground", "monochrome")) {
            assertEquals(
                "@drawable/ic_launcher_foreground",
                (adaptive.getElementsByTagName(layer).item(0) as Element).getAttribute(
                    "android:drawable"
                ),
            )
        }
    }

    @Test
    fun legacyAndAdaptiveRastersKeepTheirSuppliedDensitySizesAndTransparency() {
        val legacy =
            mapOf(
                "mdpi" to 48,
                "hdpi" to 72,
                "xhdpi" to 96,
                "xxhdpi" to 144,
                "xxxhdpi" to 192,
            )
        val adaptive =
            mapOf(
                "mdpi" to 108,
                "hdpi" to 162,
                "xhdpi" to 216,
                "xxhdpi" to 324,
                "xxxhdpi" to 432,
            )

        for ((density, size) in legacy) {
            val image = ImageIO.read(File(main, "res/mipmap-$density/ic_launcher.png"))
            assertEquals("legacy $density width", size, image.width)
            assertEquals("legacy $density height", size, image.height)
            assertEquals("legacy $density lime ground", 0xC2E60F, image.getRGB(0, 0) and 0xFFFFFF)
        }
        for ((density, size) in adaptive) {
            val image = ImageIO.read(File(main, "res/drawable-$density/ic_launcher_foreground.png"))
            assertEquals("adaptive $density width", size, image.width)
            assertEquals("adaptive $density height", size, image.height)
            assertEquals("adaptive $density transparent edge", 0, image.getRGB(0, 0) ushr 24)
            assertTrue(
                "adaptive $density contains the dark mark",
                (0 until image.width).any { x ->
                    (0 until image.height).any { y ->
                        val pixel = image.getRGB(x, y)
                        pixel ushr 24 > 0xF0 && pixel and 0xFFFFFF == 0x1B1B1B
                    }
                },
            )
        }
    }

    @Test
    fun userFacingResourcesAndDocumentationContainNoPreviousSpacedProductName() {
        val files =
            sequenceOf(File(repository, "README.md"), File(repository, "RFC.md")) +
                File(repository, "docs").walk().filter { it.isFile && it.extension == "md" } +
                File(main, "res/values").walk().filter { it.isFile && it.extension == "xml" }
        val previousNames =
            Regex("""\b(?:Seeker Vault|Seeker Agent Wallet)\b""", RegexOption.IGNORE_CASE)
        val matches = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (previousNames.containsMatchIn(line)) {
                    "${file.relativeTo(repository)}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertEquals(emptyList<String>(), matches.toList())
    }
}
