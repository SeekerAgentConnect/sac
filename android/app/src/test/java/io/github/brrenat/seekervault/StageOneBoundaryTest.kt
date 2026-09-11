package io.github.brrenat.seekervault

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * Stage 1 is a wallet-free hello world (AGENTS.md): no wallet SDK, no keys, no stored commands, and
 * nothing that runs in the background. These checks fail when one of those arrives early; the stage
 * that adds it on purpose changes them.
 */
class StageOneBoundaryTest {
    private val main =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            },
            "android/app/src/main",
        )

    @Test
    fun manifestDeclaresOnlyTheActivityAndTheInternetPermission() {
        val manifest =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(File(main, "AndroidManifest.xml"))
                .documentElement
        val application = manifest.getElementsByTagName("application").item(0) as Element
        val components =
            (0 until application.childNodes.length)
                .map { application.childNodes.item(it) }
                .filterIsInstance<Element>()
                .map { "${it.tagName} ${it.getAttribute("android:name")}" }
        assertEquals(listOf("activity .MainActivity"), components)
        val permissions = manifest.getElementsByTagName("uses-permission")
        assertEquals(
            listOf("android.permission.INTERNET"),
            (0 until permissions.length).map {
                (permissions.item(it) as Element).getAttribute("android:name")
            },
        )
    }

    @Test
    fun appCodeStoresNothingCreatesNoKeysAndRunsNothingInTheBackground() {
        // Substrings on purpose: getSharedPreferences, KeyStoreSpi, and the like must match too.
        val forbidden =
            Regex(
                """(SharedPreferences|DataStore|openFileOutput|FileOutputStream|SQLiteDatabase|""" +
                    """RoomDatabase|KeyStore|KeyPairGenerator|KeyGenerator|WorkManager|JobScheduler|""" +
                    """AlarmManager|startForegroundService|startService|BroadcastReceiver)|""" +
                    """:\s*Service\("""
            )
        val hits =
            File(main, "java")
                .walk()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        "${file.name}:${index + 1}: ${line.trim()}"
                            .takeIf { forbidden.containsMatchIn(line) }
                    }
                }
                .toList()
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun noWalletStorageOrBackgroundLibraryIsOnTheClasspath() {
        val present =
            listOf(
                    "com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter",
                    "com.solanamobile.seedvault.Wallet",
                    "androidx.room.RoomDatabase",
                    "androidx.datastore.core.DataStore",
                    "androidx.security.crypto.EncryptedSharedPreferences",
                    "androidx.work.WorkManager",
                )
                .filter { name ->
                    runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
                }
        assertEquals(emptyList<String>(), present)
    }
}
