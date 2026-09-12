package io.github.brrenat.seekervault

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The stage boundary (AGENTS.md): no wallet keys, nothing that runs in the background, and storage
 * only in the storage packages: connection metadata and Keystore-encrypted credentials in
 * `connections/storage/` (SAW-012), the owner's wallet selection and its authorization in
 * `wallet/storage/` (SAW-015), and the owner's own record of what this phone did in
 * `activity/storage/` (SAW-023). Nothing is backed up. SAW-015 lifted the "no wallet library" limit
 * for the Mobile Wallet Adapter client, on purpose: the app drives the wallet the owner already
 * has. It still holds no wallet key of its own, and Seed Vault's own SDK stays out. These checks
 * fail when a limit is crossed early; the stage that lifts one changes them.
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
    fun storageAndKeysStayInTheStoragePackagesAndNothingRunsInTheBackground() {
        // Substrings on purpose: getSharedPreferences, KeyStoreSpi, and the like must match too.
        val storage =
            Regex(
                """SharedPreferences|DataStore|openFileOutput|FileOutputStream|SQLiteDatabase|""" +
                    """RoomDatabase|AtomicFile|KeyStore|KeyGenerator|KeyGenParameterSpec"""
            )
        // No wallet key of the app's own, and nothing that runs in the background. SAW-015 drives
        // the wallet the owner already has; a key never reaches this app.
        val forbidden =
            Regex(
                """(KeyPairGenerator|PrivateKey|SecretKeySpec|WorkManager|JobScheduler|""" +
                    """AlarmManager|startForegroundService|startService|BroadcastReceiver)|""" +
                    """:\s*Service\("""
            )
        val storagePackages =
            listOf(
                File(main, "java/io/github/brrenat/seekervault/connections/storage"),
                File(main, "java/io/github/brrenat/seekervault/wallet/storage"),
                File(main, "java/io/github/brrenat/seekervault/activity/storage"),
            )
        val sources = File(main, "java").walk().filter { it.extension == "kt" }.toList()
        fun inStorage(file: File) = storagePackages.any { file.startsWith(it) }
        fun hits(pattern: Regex, files: List<File>) = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}: ${line.trim()}".takeIf { pattern.containsMatchIn(line) }
            }
        }
        assertEquals(emptyList<String>(), hits(storage, sources.filterNot(::inStorage)))
        assertEquals(emptyList<String>(), hits(forbidden, sources))
        // Both storage packages do use them, so the first check can't pass by finding nothing.
        for (storagePackage in storagePackages) {
            assertTrue(
                storagePackage.name,
                hits(storage, sources.filter { it.startsWith(storagePackage) }).isNotEmpty(),
            )
        }
    }

    @Test
    fun readingATransactionStaysInOnePackageAndOnlyReads() {
        // SAW-020: the phone reads a transfer's own bytes so the owner's review doesn't depend on
        // the server's description of them. That reading is worth keeping in one place, and worth
        // keeping free of anything that could build, sign, or send: it is a parser.
        val sources = File(main, "java").walk().filter { it.extension == "kt" }.toList()
        val decoding = Regex("""decodeTransaction|findProgramAddress|\bisOnCurve\b""")
        val transactions = File(main, "java/io/github/brrenat/seekervault/transactions")
        val outside =
            sources
                .filterNot { it.startsWith(transactions) }
                .filter { decoding.containsMatchIn(it.readText()) }
                .map { it.name }
        assertEquals(emptyList<String>(), outside)
        assertTrue(transactions.isDirectory)
        // The parser holds no key and makes none: it turns bytes into facts and nothing else.
        val keys = Regex("""KeyPairGenerator|PrivateKey|Signature\.getInstance|\bsign\(""")
        assertEquals(
            emptyList<String>(),
            transactions
                .walk()
                .filter { it.extension == "kt" }
                .filter { keys.containsMatchIn(it.readText()) }
                .map { it.name }
                .toList(),
        )
    }

    @Test
    fun theTransactionTheOwnerReviewsIsReadByThisAppsOwnParser() {
        // The Mobile Wallet Adapter client already brings a Solana SDK and a crypto provider onto
        // this classpath, so keeping them off is not the question. The question is what reads the
        // bytes the owner approves, and SAW-020 answers it here on purpose
        // (docs/security.md#inspecting-a-transfer): a parser that refuses anything it cannot
        // account for byte for byte, rather than a general-purpose decoder whose job is to parse
        // what it can. A file that starts importing one instead has changed that answer.
        val sources = File(main, "java").walk().filter { it.extension == "kt" }.toList()
        val sdk =
            Regex(
                """^import (com\.solana\.transaction|com\.solana\.serialization|""" +
                    """com\.solana\.programs|org\.sol4k)\.""",
                RegexOption.MULTILINE,
            )
        assertEquals(
            emptyList<String>(),
            sources.filter { sdk.containsMatchIn(it.readText()) }.map { it.name },
        )
        // It is on the classpath, so a check that only proved it absent would prove nothing.
        assertTrue(
            runCatching {
                Class.forName(
                    "com.solana.serialization.TransactionDecoder",
                    false,
                    javaClass.classLoader,
                )
            }
                .isSuccess
        )
    }

    @Test
    fun theWalletClientIsOnTheClasspathFromSaw015() {
        // The check below must fail for a library that is missing, so prove it finds one that is
        // there: the Mobile Wallet Adapter client the app now drives the wallet with.
        assertEquals(
            emptyList<String>(),
            listOf(
                    "com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter",
                    "com.solana.mobilewalletadapter.clientlib.ActivityResultSender",
                )
                .filterNot { name ->
                    runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
                },
        )
    }

    @Test
    fun noWalletDatabaseOrBackgroundLibraryIsOnTheClasspath() {
        val present =
            listOf(
                    // SAW-015 adds the MWA client on purpose; Seed Vault's own SDK is Stage 3's
                    // signing task, not this one.
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

    @Test
    fun theExplorerIsALinkAndNeverAConnection() {
        // SAW-023 puts one address outside this phone into the app: the public block explorer, so
        // the owner can read a transfer they made. It is handed to whatever app opens links, and
        // this app fetches nothing from it. Two things prove that rather than assert it:
        //
        // 1. the address is written in one file, `Explorer.kt`, which holds no HTTP client, and
        // 2. the app's own HTTP clients exist only where they talk to a sidecar.
        val sources = File(main, "java").walk().filter { it.extension == "kt" }.toList()
        val explorer = Regex("""explorer\.solana\.com""")
        assertEquals(
            listOf("Explorer.kt"),
            sources.filter { explorer.containsMatchIn(it.readText()) }.map { it.name },
        )
        val http = Regex("""OkHttpClient|HttpURLConnection|openConnection\(|Retrofit""")
        assertEquals(
            emptyList<String>(),
            sources
                .filter { it.name == "Explorer.kt" }
                .filter { http.containsMatchIn(it.readText()) }
                .map { it.name },
        )
        // The sidecar transports and the one client they share, and nothing else. A new file here
        // is a new host this app talks to, and has to be read as one.
        assertEquals(
            listOf(
                "ConnectConnectionGateway.kt",
                "ConnectLiveCommandTransport.kt",
                "SeekerVaultApplication.kt",
            ),
            sources.filter { http.containsMatchIn(it.readText()) }.map { it.name }.sorted(),
        )
    }

    @Test
    fun nothingSpendsSwapsOrAsksForABiometricOfItsOwn() {
        // SAW-021 lets the owner approve a transfer, and the wallet sign and send it. That is the
        // one line lifted, and it is lifted in one file: `MwaWalletAdapter`, which hands the
        // wallet bytes a sidecar built and this phone read. The app still builds no transaction,
        // makes no signature of its own, and reaches no chain — it has no RPC endpoint at all, and
        // couldn't broadcast or simulate anything if it wanted to. Swaps are Stage 6.
        //
        // SAW-022 lifts nothing here. Following a sent transaction is the sidecar's work, and this
        // app only asks it (`RequestService.CheckStatus`): it reads no chain of its own, and a
        // status check never reaches a wallet.
        val spending =
            Regex(
                """signTransactions\b|sendTransaction|sendRawTransaction|""" +
                    """simulateTransaction|getLatestBlockhash|""" +
                    """mainnet-beta|clusterApiUrl|api\.[a-z-]*solana\.com"""
            )
        val ownAuthentication =
            Regex(
                """BiometricPrompt|BiometricManager|FingerprintManager|KeyguardManager|""" +
                    """createConfirmDeviceCredentialIntent|setUserAuthenticationRequired"""
            )
        val sources = File(main, "java").walk().filter { it.extension == "kt" }.toList()
        fun hits(pattern: Regex) = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}: ${line.trim()}".takeIf { pattern.containsMatchIn(line) }
            }
        }
        assertEquals(emptyList<String>(), hits(spending))
        assertEquals(emptyList<String>(), hits(ownAuthentication))
        // Signing and sending is the wallet's, reached from one file and no other. The app asks;
        // the wallet signs and submits.
        assertEquals(
            listOf("MwaWalletAdapter.kt"),
            hits(Regex("signAndSendTransactions")).map { it.substringBefore(':') }.distinct(),
        )
        // Checking a status asks the server and nothing else. The wallet is reached from one
        // place, and following a transaction to the chain isn't it (SAW-022).
        assertEquals(
            emptyList<String>(),
            hits(Regex("checkStatus")).filter { it.startsWith("MwaWalletAdapter") },
        )
        assertEquals(
            emptyList<String>(),
            hits(Regex("""\bwallet\.sign""")).filter { it.startsWith("ConnectionRepo") },
        )
        // The scan reads the real sources, so a line that did match would be found.
        assertTrue(hits(Regex("checkStatus")).isNotEmpty())
        assertTrue(hits(Regex("""\bwallet\.sign""")).isNotEmpty())
        assertTrue(hits(Regex("signMessage")).isNotEmpty())
        assertTrue(hits(Regex("decodeTransaction")).isNotEmpty())
        assertEquals(
            emptyList<String>(),
            listOf("androidx.biometric.BiometricPrompt", "androidx.biometric.BiometricManager")
                .filter { name ->
                    runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
                },
        )
    }
}
