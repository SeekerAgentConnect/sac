package io.github.brrenat.seekervault

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The stage boundary (AGENTS.md): no wallet keys, and storage only in the storage packages:
 * connection metadata and Keystore-encrypted credentials in `connections/storage/` (SAW-012), the
 * owner's wallet selection and its authorization in `wallet/storage/` (SAW-015), the owner's own
 * record of what this phone did in `activity/storage/` (SAW-023), and the rules they set for one
 * connection in `policy/storage/` (SAW-025). Nothing is backed up. SAW-048 authorizes only the
 * `sync/` package to use the production sidecar transport and its own storage subpackage; SAW-052
 * adds WorkManager code there. SAW-054 puts the optional Firebase Messaging client on the
 * classpath. SAW-055 adds one connection-scoped registration service under `push/`. SAW-056 lets
 * that service accept exactly one content-free invalidation and enqueue a unique WorkManager Sync
 * under `sync/`. SAW-057 keeps the callback to validation and this durable handoff; network fetches
 * stay in the bounded worker and coalesce with foreground/periodic synchronization. SAW-058 adds
 * one private request channel, an isolated runtime permission prompt, generic notifications after
 * authoritative Sync, and a validated read-only tap route. SAW-059 closes the stage with joined
 * acceptance while keeping Firebase optional and every Stage 5.2 path independent. Other
 * app-defined services, jobs, alarms, receivers, and wallet automation remain excluded. SAW-015
 * lifted the "no wallet library" limit for the Mobile Wallet Adapter client, on purpose: the app
 * drives the wallet the owner already has. It still holds no wallet key of its own, and Seed
 * Vault's own SDK stays out. These checks fail when a limit is crossed early; the stage that lifts
 * one changes them.
 */
class StageBoundaryTest {
    private val main =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            },
            "android/app/src/main",
        )

    /** [file]'s code, with the comments taken out, for a check that is about what it does. */
    private fun withoutComments(file: File): String =
        file
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//.*"""), "")

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
    fun manifestDeclaresOnlyTheActivityFcmRegistrationAndRequiredPermissions() {
        val manifest = xml("AndroidManifest.xml")
        val application = manifest.children("application").single()
        val components =
            (0 until application.childNodes.length)
                .map { application.childNodes.item(it) }
                .filterIsInstance<Element>()
                .map { "${it.tagName} ${it.getAttribute("android:name")}" }
        assertEquals(
            listOf(
                "meta-data firebase_messaging_auto_init_enabled",
                "meta-data firebase_messaging_installation_id_enabled",
                "service .push.SeekerVaultMessagingService",
                "activity .MainActivity",
            ),
            components,
        )
        assertEquals(
            "false",
            application
                .children("meta-data")
                .single {
                    it.getAttribute("android:name") == "firebase_messaging_auto_init_enabled"
                }
                .getAttribute("android:value"),
        )
        assertEquals(
            "true",
            application
                .children("meta-data")
                .single {
                    it.getAttribute("android:name") == "firebase_messaging_installation_id_enabled"
                }
                .getAttribute("android:value"),
        )
        val messaging = application.children("service").single()
        assertEquals("false", messaging.getAttribute("android:exported"))
        assertEquals(
            listOf("com.google.firebase.MESSAGING_EVENT"),
            messaging.children("action").map { it.getAttribute("android:name") },
        )
        assertEquals(
            listOf(
                "android.permission.INTERNET",
                "android.permission.CAMERA",
                "android.permission.POST_NOTIFICATIONS",
            ),
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
    fun storageKeysAndBackgroundWorkStayInsideTheirNarrowPackages() {
        // Substrings on purpose: getSharedPreferences, KeyStoreSpi, and the like must match too.
        val storage =
            Regex(
                """SharedPreferences|DataStore|openFileOutput|FileOutputStream|SQLiteDatabase|""" +
                    """RoomDatabase|AtomicFile|KeyStore|KeyGenerator|KeyGenParameterSpec"""
            )
        // No wallet key of the app's own. SAW-015 drives the wallet the owner already has; a key
        // never reaches this app.
        val keys = Regex("""KeyPairGenerator|PrivateKey|SecretKeySpec""")
        // Stage 5.2's background work is WorkManager only, and only from sync/. A foreground
        // service, Android service, JobScheduler, alarm, or receiver remains outside the stage.
        val forbiddenBackground =
            Regex(
                """(JobScheduler|AlarmManager|startForegroundService|startService|""" +
                    """BroadcastReceiver)|:\s*Service\("""
            )
        val workManager =
            Regex("""\bWorkManager\b|^import androidx\.work\.""", RegexOption.MULTILINE)
        val requiredStoragePackages =
            listOf(
                File(main, "java/io/github/brrenat/seekervault/connections/storage"),
                File(main, "java/io/github/brrenat/seekervault/wallet/storage"),
                File(main, "java/io/github/brrenat/seekervault/activity/storage"),
                File(main, "java/io/github/brrenat/seekervault/policy/storage"),
            )
        val syncPackage = File(main, "java/io/github/brrenat/seekervault/sync")
        val storagePackages =
            requiredStoragePackages + File(main, "java/io/github/brrenat/seekervault/sync/storage")
        val sources = File(main, "java").walk().filter { it.extension == "kt" }.toList()
        fun inStorage(file: File) = storagePackages.any { file.startsWith(it) }
        fun hits(pattern: Regex, files: List<File>) = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}: ${line.trim()}".takeIf { pattern.containsMatchIn(line) }
            }
        }
        assertEquals(emptyList<String>(), hits(storage, sources.filterNot(::inStorage)))
        assertEquals(emptyList<String>(), hits(keys, sources))
        assertEquals(emptyList<String>(), hits(forbiddenBackground, sources))
        assertEquals(
            emptyList<String>(),
            hits(workManager, sources.filterNot { it.startsWith(syncPackage) }),
        )
        // The existing storage packages do use storage APIs, so the first check can't pass by
        // finding nothing. sync/storage becomes the only additional location when SAW-050 lands.
        for (storagePackage in requiredStoragePackages) {
            assertTrue(
                storagePackage.name,
                hits(storage, sources.filter { it.startsWith(storagePackage) }).isNotEmpty(),
            )
        }
    }

    @Test
    fun aPolicyDecidesNothingAndNeverLeavesThePhone() {
        // SAW-025 opens Stage 5. A policy is the owner's own note about one connection: no agent
        // can read it or change it, and it settles nothing by itself. The package that holds it is
        // kept without any means of acting or speaking, so that stays true as the stage is built —
        // the editor the owner writes the rules on (SAW-027) included.
        val policy = File(main, "java/io/github/brrenat/seekervault/policy")
        assertTrue(policy.isDirectory)
        val sources = policy.walk().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        // What it reaches for in the rest of the app, and every one of them is a read: the
        // connection ID rule, the protocol's requests and networks, what the phone read out of a
        // transaction's own bytes (SAW-020), the owner's own record of what this app did (SAW-023),
        // and the address rule. SAW-027 added the editor, so three more: the app's strings, its
        // back button, its date format, and the checked-in v4 theme's semantic accent. Nothing that
        // opens a wallet, a connection, or a socket — the screen the owner writes the rules on
        // can't act on them either.
        val reaches =
            sources
                .flatMap { it.readLines() }
                .map { it.trim() }
                .filter { it.startsWith("import io.github.brrenat.seekervault.") }
                .map { it.removePrefix("import ") }
                .filterNot { it.startsWith("io.github.brrenat.seekervault.policy.") }
                .distinct()
                .sorted()
        assertEquals(
            listOf(
                "io.github.brrenat.seekervault.R",
                "io.github.brrenat.seekervault.SeekerTheme",
                "io.github.brrenat.seekervault.activity.ActivityKind",
                "io.github.brrenat.seekervault.activity.ActivityOutcome",
                "io.github.brrenat.seekervault.activity.ActivityRecord",
                "io.github.brrenat.seekervault.connections.CloseButton",
                "io.github.brrenat.seekervault.connections.formatInstant",
                "io.github.brrenat.seekervault.connections.isConnectionId",
                "io.github.brrenat.seekervault.request.v1.Action",
                "io.github.brrenat.seekervault.request.v1.ActionRequest",
                "io.github.brrenat.seekervault.request.v1.Network",
                "io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS",
                "io.github.brrenat.seekervault.transactions.TransferInspection",
                "io.github.brrenat.seekervault.transactions.formatBaseUnits",
                "io.github.brrenat.seekervault.ui.SeekerCard",
                "io.github.brrenat.seekervault.ui.SeekerSnackbarHost",
                "io.github.brrenat.seekervault.ui.SolidDialog",
                "io.github.brrenat.seekervault.ui.seekerListItemColors",
                "io.github.brrenat.seekervault.ui.seekerTextFieldColors",
                "io.github.brrenat.seekervault.wallet.isSolanaAddress",
            ),
            reaches,
        )
        // The rules never leave the phone, and neither does what they made of a request
        // (SAW-028). Files that speak to a sidecar have never heard of a policy, and the one file
        // in `connections/` that has, has it to delete a removed connection's rules and for
        // nothing else — an assessment is the owner's to read, and no agent's to learn of.
        val speakingNames =
            setOf(
                "ConnectConnectionGateway.kt",
                "ConnectLiveCommandTransport.kt",
                "ConnectUpdateTransport.kt",
                "ConnectionRepository.kt",
            )
        val speaking = File(main, "java").walk().filter { it.name in speakingNames }.toList()
        assertTrue(
            speaking.map { it.name }.containsAll(speakingNames - "ConnectUpdateTransport.kt")
        )
        assertEquals(
            listOf(
                "ConnectionRepository.kt: io.github.brrenat.seekervault.policy.storage.PolicyStore"
            ),
            speaking
                .flatMap { file ->
                    file
                        .readLines()
                        .map { it.trim() }
                        .filter { it.startsWith("import io.github.brrenat.seekervault.policy") }
                        .map { "${file.name}: ${it.removePrefix("import ")}" }
                }
                .sorted(),
        )
        // There is no BLOCKED verdict and no branch that acts on either of the two there are. The
        // comments say so too, so this reads the code with the comments taken out of it.
        val acting =
            Regex(
                """\b(signMessage|signAndSendTransactions|WalletAdapter|ConnectionGateway|""" +
                    """OkHttp|ResultStore|approveTransfer|Blocked|BLOCKED)\b"""
            )
        assertEquals(
            emptyList<String>(),
            sources.filter { acting.containsMatchIn(withoutComments(it)) }.map { it.name },
        )
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
    fun theClientPluginBoundaryCantReachAWalletOrASidecar() {
        // SEE-86 adds a place a bundled client plugin can be registered, and it lifts no limit.
        // A plugin says what parameters an operation takes, prepares bytes, and reads them back as
        // typed facts. It is handed a request, an operation, an environment and the owner's public
        // address; it is handed no credential, no wallet authorization token, and no way to reach a
        // sidecar — so the owner's approval and the one wallet interaction stay where they were
        // (docs/wiki/client-plugins.md).
        val plugins = File(main, "java/io/github/brrenat/seekervault/plugins")
        assertTrue(plugins.isDirectory)
        val sources = plugins.walk().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        // What it reaches for in the rest of the app, and every one of them is a read: the typed
        // facts a policy is applied to and the asset those facts name, the protocol's own requests,
        // actions and networks, the app's three-state verdict, and the owner's selected wallet —
        // which is a public address and a network, never a token (SEE-84).
        val reaches =
            sources
                .flatMap { it.readLines() }
                .map { it.trim() }
                .filter { it.startsWith("import io.github.brrenat.seekervault.") }
                .map { it.removePrefix("import ") }
                .filterNot { it.startsWith("io.github.brrenat.seekervault.plugins.") }
                .distinct()
                .sorted()
        assertEquals(
            listOf(
                "io.github.brrenat.seekervault.policy.PolicyAsset",
                "io.github.brrenat.seekervault.policy.RequestFacts",
                "io.github.brrenat.seekervault.policy.policyAction",
                "io.github.brrenat.seekervault.request.v1.Action",
                "io.github.brrenat.seekervault.request.v1.ActionRequest",
                "io.github.brrenat.seekervault.request.v1.Network",
                "io.github.brrenat.seekervault.transactions.Verdict",
                "io.github.brrenat.seekervault.wallet.SelectedWallet",
            ),
            reaches,
        )
        // And nothing in it names a wallet interaction, a wallet token, a transport, an HTTP
        // client, a store, or an approval. The comments discuss all of those on purpose, so this
        // reads the code with the comments taken out of it.
        val authority =
            Regex(
                """\b(WalletAdapter|WalletSession|WalletRepository|WalletStore|authToken|""" +
                    """signMessage|signAndSend|signAndSendTransactions|withWallet|""" +
                    """ConnectionGateway|ConnectionRepository|UpdateTransport|LiveCommandTransport|""" +
                    """OkHttp|HttpClient|CredentialVault|ConnectionStore|ResultStore|PolicyStore|""" +
                    """ActivityLog|ActivityStore|SyncStore|approve|Approval)\b"""
            )
        assertEquals(
            emptyList<String>(),
            sources.filter { authority.containsMatchIn(withoutComments(it)) }.map { it.name },
        )
    }

    @Test
    fun coreNamesNoProviderAndOnlyCompositionAndReviewKnowPluginsExist() {
        // The point of the boundary: adding `jupiter.swap` (SEE-93) or `jupiter.prediction`
        // (SEE-94) must not put a provider's name, or a plugin's ID, into the code that carries
        // requests, synchronizes them, reads a transaction, or applies the owner's rules. This
        // fails if one appears there.
        val packages =
            listOf("connections", "sync", "live", "push", "policy", "transactions", "activity")
                .map { File(main, "java/io/github/brrenat/seekervault/$it") }
        packages.forEach { assertTrue(it.name, it.isDirectory) }
        val provider = Regex("""(?i)\b(jupiter|centrifugo|centrifuge|redis)\b""")
        assertEquals(
            emptyList<String>(),
            packages
                .flatMap { it.walk().filter { file -> file.extension == "kt" } }
                .filter { provider.containsMatchIn(it.readText()) }
                .map { it.name },
        )
        // Two files know the registry exists at all: the one that composes the app, and the one
        // that asks which operation a request is for so the owner's rules can be applied to it.
        // MainActivity passes the composed registry along without naming the package.
        val importers =
            File(main, "java")
                .walk()
                .filter { it.extension == "kt" }
                .filterNot {
                    it.startsWith(File(main, "java/io/github/brrenat/seekervault/plugins"))
                }
                .filter {
                    it.readText().contains("import io.github.brrenat.seekervault.plugins.")
                }
                .map { it.name }
                .sorted()
                .toList()
        assertEquals(listOf("InboxViewModel.kt", "SeekerVaultApplication.kt"), importers)
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
    fun workManagerIsOnTheClasspathFromSaw052() {
        assertEquals(
            emptyList<String>(),
            listOf(
                    "androidx.work.WorkManager",
                    "androidx.work.CoroutineWorker",
                )
                .filterNot { name ->
                    runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
                },
        )
    }

    @Test
    fun firebaseMessagingIsOnTheClasspathFromSaw054() {
        assertTrue(
            runCatching {
                Class.forName(
                    "com.google.firebase.messaging.FirebaseMessaging",
                    false,
                    javaClass.classLoader,
                )
            }
                .isSuccess
        )
    }

    @Test
    fun saw058KeepsFirebaseCallbackBoundedAndNotificationTapWithoutWalletAuthority() {
        val sources = File(main, "java").walk().filter { it.extension == "kt" }.toList()
        val firebaseImports = Regex("""^import com\.google\.firebase\.""", RegexOption.MULTILINE)
        assertEquals(
            setOf("FcmRegistrationManager.kt", "SeekerVaultMessagingService.kt"),
            sources
                .filter { firebaseImports.containsMatchIn(it.readText()) }
                .map { it.name }
                .toSet(),
        )
        val service =
            withoutComments(
                File(
                    main,
                    "java/io/github/brrenat/seekervault/push/SeekerVaultMessagingService.kt",
                )
            )
        assertTrue("override fun onRegistered" in service)
        assertTrue("override fun onUnregistered" in service)
        assertTrue("override fun onMessageReceived" in service)
        assertTrue("RemoteMessage" in service)
        assertTrue("PushSyncScheduler" in service)
        assertTrue("ConnectionRepository" !in service)
        assertTrue("UpdateTransport" !in service)
        assertTrue("synchronize" !in service)
        assertTrue("CoroutineScope" !in service)
        assertTrue("Notification" !in service)
        assertTrue("PendingIntent" !in service)
        assertTrue("wallet" !in service.lowercase())
        assertTrue("approve" !in service.lowercase())
        assertTrue("signAndSendTransactions" !in service)

        val notification =
            withoutComments(
                File(
                    main,
                    "java/io/github/brrenat/seekervault/notifications/RequestNotifications.kt",
                )
            )
        assertTrue("NotificationChannel" in notification)
        assertTrue("POST_NOTIFICATIONS" in notification)
        assertTrue("PendingIntent.FLAG_IMMUTABLE" in notification)
        assertTrue("MainActivity" in notification)
        assertTrue("RequestKey" in notification)
        assertTrue("ActionRequest" !in notification)
        assertTrue("wallet" !in notification.lowercase())
        assertTrue("approve" !in notification.lowercase())
        assertTrue("signAndSendTransactions" !in notification)
        assertTrue("WorkManager" !in notification)
        assertTrue("ForegroundUpdateManager" !in notification)
        assertTrue("BackgroundSyncScheduler" !in notification)

        val push =
            withoutComments(
                File(main, "java/io/github/brrenat/seekervault/sync/PushSynchronization.kt")
            )
        assertTrue(push.indexOf("synchronizeConnections(recovery)") >= 0)
        assertTrue(
            push.indexOf("synchronizeConnections(recovery)") <
                push.indexOf("reconcileNotifications(before")
        )
        assertTrue("POST_NOTIFICATIONS" !in push)
    }

    @Test
    fun saw059KeepsFirebaseOptionalAndOutOfStage52Recovery() {
        val app = checkNotNull(main.parentFile?.parentFile)
        val build = File(app, "build.gradle.kts").readText()
        assertTrue("val firebaseConfigured" in build)
        assertTrue("if (firebaseConfigured)" in build)
        assertTrue("FIREBASE_CONFIGURED" in build)

        val stage52 =
            listOf(
                    "sync/BackgroundSynchronization.kt",
                    "sync/ForegroundUpdateManager.kt",
                    "sync/SynchronizationRepository.kt",
                )
                .map { File(main, "java/io/github/brrenat/seekervault/$it") }
                .onEach { assertTrue(it.path, it.isFile) }
                .joinToString("\n") { withoutComments(it) }
        assertTrue("Firebase" !in stage52)
        assertTrue("POST_NOTIFICATIONS" !in stage52)
        assertTrue("RequestNotification" !in stage52)
    }

    @Test
    fun noSeedVaultSecurityOrLegacyPushLibraryIsOnTheClasspath() {
        val present =
            listOf(
                    // SAW-015 adds the MWA client on purpose; Seed Vault's own SDK is Stage 3's
                    // signing task, not this one.
                    "com.solanamobile.seedvault.Wallet",
                    // WorkManager brings Room and Firebase brings DataStore internally. The source
                    // scan above still rejects either storage API in this app's own code.
                    "androidx.security.crypto.EncryptedSharedPreferences",
                    // SAW-054 uses current FCM, never the legacy GCM service.
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
        val allowed =
            setOf(
                "ConnectConnectionGateway.kt",
                "ConnectLiveCommandTransport.kt",
                "ConnectUpdateTransport.kt",
                "SeekerVaultApplication.kt",
            )
        val clients = sources.filter { http.containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertTrue(clients.all { it in allowed })
        assertTrue(clients.containsAll(allowed - "ConnectUpdateTransport.kt"))
    }

    @Test
    fun theV4PresentationUsesOnlySolidOpaqueLayers() {
        val sources = File(main, "java").walk().filter { it.extension == "kt" }.toList()
        val forbidden =
            Regex(
                """Color\.Transparent|copy\s*\(\s*alpha|\.alpha\s*\(|""" +
                    """\bshadow\s*\(|\bblur\s*\(|graphicsLayer|drawBehind|""" +
                    """\bBrush\.|\bfadeIn\s*\(|\bfadeOut\s*\("""
            )
        val effects = sources.flatMap { file ->
            withoutComments(file).lines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}: ${line.trim()}"
                    .takeIf {
                        forbidden.containsMatchIn(line)
                    }
            }
        }
        assertEquals(emptyList<String>(), effects)

        val rgba = Regex("""Color\(0x([0-9A-Fa-f]{8})\)""")
        val translucentTokens = sources.flatMap { file ->
            rgba.findAll(withoutComments(file)).mapNotNull { match ->
                "${file.name}: ${match.value}"
                    .takeUnless {
                        match.groupValues[1].startsWith("FF", ignoreCase = true)
                    }
            }
        }
        assertEquals(emptyList<String>(), translucentTokens)

        val theme = File(main, "java/io/github/brrenat/seekervault/MainActivity.kt").readText()
        assertTrue(
            "Raw app bars must inherit the approved scheme's surface ink in both themes",
            theme.contains("LocalContentColor provides colors.onSurface"),
        )
        assertTrue(
            "The production theme must retain the approved lime dark roles",
            theme.contains("primary = Color(0xFFE7FC6E)") &&
                theme.contains("primaryContainer = Color(0xFFC2E60F)"),
        )
        assertTrue(
            "Parameterless Material defaults would replace the approved v4 design",
            !Regex("""(?:dark|light)ColorScheme\s*\(\s*\)""").containsMatchIn(theme),
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

        // SAW-050's automatic path can observe and retry an answer already on disk. Its package
        // has no way to prepare, approve, sign, send, or even open Mobile Wallet Adapter, so adding
        // synchronization cannot increase the wallet execution count.
        val sync = File(main, "java/io/github/brrenat/seekervault/sync")
        val automaticAction =
            Regex(
                """MwaWalletAdapter|WalletAdapter|prepareRequest|approveTransfer|""" +
                    """signAndSendTransactions|signMessage"""
            )
        assertTrue(sync.isDirectory)
        assertEquals(
            emptyList<String>(),
            sync
                .walk()
                .filter { it.extension == "kt" }
                .filter { automaticAction.containsMatchIn(withoutComments(it)) }
                .map { it.name }
                .toList(),
        )
    }
}
