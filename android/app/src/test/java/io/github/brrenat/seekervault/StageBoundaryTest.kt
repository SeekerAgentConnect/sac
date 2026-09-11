package io.github.brrenat.seekervault

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The stage boundary (AGENTS.md): no wallet SDK or keys, nothing that runs in the background, and
 * storage only where SAW-012 put it: connection metadata and Keystore-encrypted credentials, in
 * `connections/storage/`, never backed up. These checks fail when a limit is crossed early; the
 * stage that lifts one changes them on purpose.
 */
class StageBoundaryTest {
    private val main =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            },
            "android/app/src/main",
        )

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(main, path))
            .documentElement

    private fun Element.children(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }

    @Test
    fun manifestDeclaresOnlyTheActivityTheNetworkAndAnOptionalCamera() {
        val manifest = xml("AndroidManifest.xml")
        val application = manifest.children("application").single()
        val components =
            (0 until application.childNodes.length)
                .map { application.childNodes.item(it) }
                .filterIsInstance<Element>()
                .map { "${it.tagName} ${it.getAttribute("android:name")}" }
        assertEquals(listOf("activity .MainActivity"), components)
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.CAMERA"),
            manifest.children("uses-permission").map { it.getAttribute("android:name") },
        )
        assertEquals(
            listOf("android.hardware.camera.any required=false"),
            manifest.children("uses-feature").map {
                "${it.getAttribute("android:name")} required=${it.getAttribute("android:required")}"
            },
        )
    }

    @Test
    fun nothingIsBackedUpOrTransferredToAnotherDevice() {
        val application = xml("AndroidManifest.xml").children("application").single()
        assertEquals("false", application.getAttribute("android:allowBackup"))
        assertEquals(
            "@xml/data_extraction_rules",
            application.getAttribute("android:dataExtractionRules"),
        )
        val rules = xml("res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.children(section).single()
            assertEquals(section, emptyList<Element>(), element.children("include"))
            assertEquals(
                section,
                // The root domain covers no_backup/, where the credentials are.
                setOf("root", "file", "database", "sharedpref", "external"),
                element.children("exclude").map { it.getAttribute("domain") }.toSet(),
            )
        }
    }

    @Test
    fun storageAndKeysStayInTheStoragePackageAndNothingRunsInTheBackground() {
        // Substrings on purpose: getSharedPreferences, KeyStoreSpi, and the like must match too.
        val storage =
            Regex(
                """SharedPreferences|DataStore|openFileOutput|FileOutputStream|SQLiteDatabase|""" +
                    """RoomDatabase|AtomicFile|KeyStore|KeyGenerator|KeyGenParameterSpec"""
            )
        val forbidden =
            Regex(
                """(KeyPairGenerator|WorkManager|JobScheduler|AlarmManager|""" +
                    """startForegroundService|startService|BroadcastReceiver)|:\s*Service\("""
            )
        val storagePackage = File(main, "java/io/github/brrenat/seekervault/connections/storage")
        val sources = File(main, "java").walk().filter { it.extension == "kt" }.toList()
        fun hits(pattern: Regex, files: List<File>) = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}: ${line.trim()}".takeIf { pattern.containsMatchIn(line) }
            }
        }
        assertEquals(
            emptyList<String>(),
            hits(storage, sources.filterNot { it.startsWith(storagePackage) }),
        )
        assertEquals(emptyList<String>(), hits(forbidden, sources))
        // The storage package does use them, so the first check can't pass by finding nothing.
        assertTrue(hits(storage, sources.filter { it.startsWith(storagePackage) }).isNotEmpty())
    }

    @Test
    fun noWalletDatabaseOrBackgroundLibraryIsOnTheClasspath() {
        val present =
            listOf(
                    "com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter",
                    "com.solanamobile.seedvault.Wallet",
                    "androidx.room.RoomDatabase",
                    "androidx.datastore.core.DataStore",
                    "androidx.security.crypto.EncryptedSharedPreferences",
                    "androidx.work.WorkManager",
                    // No push: the phone fetches when the app opens or the owner refreshes.
                    "com.google.firebase.messaging.FirebaseMessaging",
                    "com.google.android.gms.gcm.GcmListenerService",
                )
                .filter { name ->
                    runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
                }
        assertEquals(emptyList<String>(), present)
    }
}
