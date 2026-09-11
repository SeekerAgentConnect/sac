package io.github.brrenat.seekervault

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Plain HTTP is a debug-only exception for the loopback sidecar (docs/development/android.md).
 * Release builds must keep Android's default of no cleartext traffic, so nothing in the main source
 * set may relax it, and the debug exception must stay limited to loopback.
 */
class NetworkSecurityPolicyTest {
    private val src =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            },
            "android/app/src",
        )

    @Test
    fun mainSourceSetKeepsTheDefaultOfNoCleartext() {
        val manifest = File(src, "main/AndroidManifest.xml").readText()
        assertFalse("networkSecurityConfig" in manifest)
        assertFalse("usesCleartextTraffic" in manifest)
        assertFalse(File(src, "main/res/xml/network_security_config.xml").exists())
    }

    @Test
    fun debugExceptionCoversOnlyLoopback() {
        val config =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(File(src, "debug/res/xml/network_security_config.xml"))
        val domains = config.getElementsByTagName("domain")
        assertEquals(
            listOf("127.0.0.1", "localhost"),
            (0 until domains.length).map { domains.item(it).textContent.trim() },
        )
        assertEquals(0, config.getElementsByTagName("base-config").length)
    }
}
