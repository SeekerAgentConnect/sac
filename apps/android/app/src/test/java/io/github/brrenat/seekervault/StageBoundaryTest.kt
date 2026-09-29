package io.github.brrenat.seekervault

import java.io.File
import java.security.MessageDigest
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
 * stay in the bounded worker and coalesce with foreground/periodic synchronization. SAW-058 adds an
 * isolated runtime permission prompt, notifications after authoritative Sync, and a validated
 * read-only tap route. SEE-105 lets presentation read only the cached request/proposal kind and
 * local source name while adding no authority. SAW-059 closes the stage with joined acceptance
 * while keeping Firebase optional and every Stage 5.2 path independent. Other app-defined services,
 * jobs, alarms, receivers, and wallet automation remain excluded. SEE-92 extends that same push
 * pipeline to a publisher's public feed and lifts nothing: one more content-free invalidation on
 * the existing service, one topic client beside the registration client, one WorkManager job under
 * `sync/`, and one notification channel with a read-only tap route. SAW-015 lifted the "no wallet
 * library" limit for the Mobile Wallet Adapter client, on purpose: the app drives the wallet the
 * owner already has. It still holds no wallet key of its own, and Seed Vault's own SDK stays out.
 * SEE-114 moves visual authority into a separate design-system module with no dependency back into
 * app behavior. SEE-156 adds one storage package, `access/storage/`, for where a restricted feed's
 * access stands, and one carve-out from the no-keys rule: `access/DeviceKeys.kt`, a per-feed
 * signing key generated in the Keystore that proves an installation to the publisher that approved
 * it. It is not a wallet key — nothing it signs is a transaction — and the carve-out is the one
 * file. These checks fail when a limit is crossed early; the stage that lifts one changes them.
 */
class StageBoundaryTest {
    private val repoRoot =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            }
        )

    private val main = File(repoRoot, "apps/android/app/src/main")

    /**
     * [file]'s source with its comments removed, so an assertion about what the code does is not
     * answered by prose about what it does.
     *
     * A line comment starts at `//` — except after a colon or a slash, which is a URL's own double
     * slash and not a comment at all. Without that exception this helper truncates every address in
     * the source to `https:`, and an assertion that looks for one can never find it.
     */
    private fun withoutComments(file: File): String =
        file
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(?<![:/])//.*"""), "")

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
                "meta-data com.google.firebase.messaging.default_notification_icon",
                "meta-data com.google.firebase.messaging.default_notification_color",
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
        assertEquals(
            "@drawable/ic_notification_sac",
            application
                .children("meta-data")
                .single {
                    it.getAttribute("android:name") ==
                        "com.google.firebase.messaging.default_notification_icon"
                }
                .getAttribute("android:resource"),
        )
        assertEquals(
            "@color/notification_accent",
            application
                .children("meta-data")
                .single {
                    it.getAttribute("android:name") ==
                        "com.google.firebase.messaging.default_notification_color"
                }
                .getAttribute("android:resource"),
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
        // SEE-156's one exception, and it is not a wallet key: a per-feed P-256 key generated
        // inside the Keystore, usable only for signing, never exported, and never used to sign
        // anything a chain would accept. It is what makes an installation provable to a publisher
        // that approved it. The carve-out is one file, checked below to still be what it claims.
        val deviceKeys = File(main, "java/io/github/brrenat/seekervault/access/DeviceKeys.kt")
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
                // Where a feed's listener left off (SEE-91). Progress, never content: it holds a
                // broker position and a snapshot boundary, and the documents live elsewhere.
                File(main, "java/io/github/brrenat/seekervault/feeds/storage"),
                // The gateway relay's enrollment (SEE-144). Routing, never content: an opaque
                // installation identity, the sealed secret that proves this device is that
                // installation, which authorization belongs to which connection, and the
                // revocations this phone still owes a gateway it could not reach. No request, no
                // decision, no FCM registration and no push handle is kept here.
                File(main, "java/io/github/brrenat/seekervault/push/storage"),
                // Where a restricted feed's access stands (SEE-156). A decision and what it was
                // bound to — the wallet that proved it, the device key's fingerprint, the
                // publisher's request ID, the state and its time — and no secret: the device key
                // is in the Keystore and the session is sealed in the credential vault's format.
                File(main, "java/io/github/brrenat/seekervault/access/storage"),
                // The transactions this phone follows to the chain (SEE-165). Public facts only:
                // the approved message, a signature, the cluster it was bound to, and what an
                // endpoint's host said — never an endpoint URL, which can carry a key.
                File(main, "java/io/github/brrenat/seekervault/confirmations/storage"),
                // The owner's prediction positions and sale attempts (SEE-172). Public facts only:
                // addresses, amounts, the provider's words for a state, a sale's signature and a
                // hash of the bytes approved — never a URL and never a transaction.
                File(main, "java/io/github/brrenat/seekervault/positions/storage"),
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
        assertEquals(
            emptyList<String>(),
            hits(storage, sources.filterNot { inStorage(it) || it == deviceKeys }),
        )
        assertEquals(emptyList<String>(), hits(keys, sources.filterNot { it == deviceKeys }))
        // The carve-out holds only while the file is still the thing it was let through for: a
        // signing key, generated in the Keystore, that nothing asks for the private half of.
        val deviceKeyText = deviceKeys.readText()
        assertTrue(deviceKeyText.contains("KeyProperties.PURPOSE_SIGN"))
        assertEquals(
            emptyList<String>(),
            hits(
                Regex("""PURPOSE_DECRYPT|PURPOSE_ENCRYPT|PURPOSE_AGREE_KEY|SecretKeySpec"""),
                listOf(deviceKeys),
            ),
        )
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
                "io.github.brrenat.seekervault.activity.ActivityKind",
                "io.github.brrenat.seekervault.activity.ActivityOutcome",
                "io.github.brrenat.seekervault.activity.ActivityRecord",
                "io.github.brrenat.seekervault.connections.CloseButton",
                "io.github.brrenat.seekervault.connections.formatInstant",
                "io.github.brrenat.seekervault.connections.isConnectionId",
                "io.github.brrenat.seekervault.designsystem.AddAddressSheet",
                "io.github.brrenat.seekervault.designsystem.AddAddressSheetCallbacks",
                "io.github.brrenat.seekervault.designsystem.AddAddressSheetState",
                "io.github.brrenat.seekervault.designsystem.AssetEditorKind",
                "io.github.brrenat.seekervault.designsystem.AssetEditorNetwork",
                "io.github.brrenat.seekervault.designsystem.AssetEditorSheet",
                "io.github.brrenat.seekervault.designsystem.AssetEditorSheetCallbacks",
                "io.github.brrenat.seekervault.designsystem.AssetEditorSheetState",
                "io.github.brrenat.seekervault.designsystem.ConnectionRulesSheet",
                "io.github.brrenat.seekervault.designsystem.ConnectionRulesSheetState",
                "io.github.brrenat.seekervault.designsystem.GlobalRulesSheet",
                "io.github.brrenat.seekervault.designsystem.GlobalRulesSheetState",
                "io.github.brrenat.seekervault.designsystem.RuleRowKind",
                "io.github.brrenat.seekervault.designsystem.RuleRowModel",
                "io.github.brrenat.seekervault.designsystem.RulesSectionKind",
                "io.github.brrenat.seekervault.designsystem.RulesSheetCallbacks",
                "io.github.brrenat.seekervault.designsystem.RulesSheetItem",
                "io.github.brrenat.seekervault.designsystem.RulesSheetSection",
                "io.github.brrenat.seekervault.designsystem.ScopeChipSource",
                "io.github.brrenat.seekervault.designsystem.theme.SeekerTheme",
                // In-app notices replaced the editor's own snackbar host (SEE-161).
                "io.github.brrenat.seekervault.notifications.LocalInAppNotices",
                "io.github.brrenat.seekervault.request.v1.Action",
                "io.github.brrenat.seekervault.request.v1.ActionRequest",
                "io.github.brrenat.seekervault.request.v1.Network",
                // SEE-146 adds two more reads, both of them facts somebody else established: the
                // mint a staking action is denominated in, and what this phone read out of a
                // staking transaction's own bytes. A rule is still decided here and nowhere else.
                "io.github.brrenat.seekervault.skr.SKR_MINT",
                "io.github.brrenat.seekervault.skr.StakingInspection",
                "io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS",
                "io.github.brrenat.seekervault.transactions.TransferInspection",
                "io.github.brrenat.seekervault.transactions.formatBaseUnits",
                "io.github.brrenat.seekervault.ui.SeekerButton",
                "io.github.brrenat.seekervault.ui.SeekerButtonRole",
                "io.github.brrenat.seekervault.ui.SeekerCard",
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
    fun theChainReaderReadsAndIsTheApplicationsOwn() {
        // SEE-94 gives this app a chain endpoint for the first time, for one purpose: resolving the
        // address lookup tables a prediction order's transaction names, without which the phone
        // cannot see what it would be signing. That is a real addition and these are the limits on
        // it (docs/security.md#resolving-a-lookup-table).
        val solana = File(main, "java/io/github/brrenat/seekervault/solana")
        assertTrue(solana.isDirectory)
        val sources = solana.walk().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        // It reads accounts and rebuilds an account list, and it reaches for nothing else in the
        // app but the decoder's own types and base58.
        val reaches =
            sources
                .flatMap { it.readLines() }
                .map { it.trim() }
                .filter { it.startsWith("import io.github.brrenat.seekervault.") }
                .map { it.removePrefix("import ").substringBeforeLast('.') }
                .filterNot { it == "io.github.brrenat.seekervault.solana" }
                .distinct()
                .sorted()
        assertEquals(
            listOf(
                "io.github.brrenat.seekervault.transactions",
                "io.github.brrenat.seekervault.wallet",
            ),
            reaches,
        )
        // One method, and it is a read. There is no send, no simulate, no subscribe, no signature
        // lookup and no balance query — not because they are unreachable over the same wire, but
        // because a component with one method cannot grow a second use by accident.
        val writing =
            Regex(
                """\b(sendTransaction|simulateTransaction|requestAirdrop|signatureSubscribe|""" +
                    """getSignatureStatuses|getBalance|getTokenAccount|accountSubscribe|""" +
                    """WalletAdapter|WalletRepository|signAndSend|approve)\b"""
            )
        assertEquals(
            emptyList<String>(),
            sources.filter { writing.containsMatchIn(withoutComments(it)) }.map { it.name },
        )
        // The JSON-RPC methods it names, as string literals: exactly one.
        assertEquals(
            listOf("\"getMultipleAccounts\""),
            Regex(""""(get|send|simulate|request)[A-Z][A-Za-z]+"""")
                .findAll(sources.joinToString("\n") { withoutComments(it) })
                .map { it.value }
                .distinct()
                .toList(),
        )
        // It names no provider: it is a Solana component, and both plugins may use it.
        val provider = Regex("""(?i)\b(jupiter|polymarket|centrifugo|redis)\b""")
        assertEquals(
            emptyList<String>(),
            sources.filter { provider.containsMatchIn(it.readText()) }.map { it.name },
        )
        // And the endpoint is the application's own. Nothing a server sends can set it: it comes
        // from the build, through the composition root, and no manifest, proposal or provider
        // answer reaches it.
        val composition = File(main, "java/io/github/brrenat/seekervault/SeekerVaultApplication.kt")
        assertTrue(
            composition
                .readText()
                .contains("HttpSolanaAccounts(httpClient, BuildConfig.SOLANA_RPC)")
        )
        assertEquals(
            listOf("SeekerVaultApplication.kt"),
            File(main, "java")
                .walk()
                .filter { it.extension == "kt" }
                .filter { Regex("""SOLANA_RPC""").containsMatchIn(it.readText()) }
                .map { it.name }
                .toList(),
        )
        // No URL is ever persisted. A link read back off disk is a link something else could have
        // written, so every destination is built at the moment it is shown (SEE-94).
        val stores =
            listOf(
                    "activity/storage",
                    "connections/storage",
                    "policy/storage",
                    "wallet/storage",
                    // A restricted feed's access record (SEE-156) holds the decision and what it
                    // was bound to; the authentication origin it was made at is read off the
                    // manifest the gateway serves, every time, and never off this disk.
                    "access/storage",
                    // What the confirmation tracker keeps (SEE-165): an endpoint's host, never its
                    // URL.
                    "confirmations/storage",
                    // Positions and sales (SEE-172): the provider's links are built where shown.
                    "positions/storage",
                )
                .map { File(main, "java/io/github/brrenat/seekervault/$it") }
        assertEquals(
            emptyList<String>(),
            stores
                .flatMap { it.walk().filter { file -> file.extension == "kt" } }
                .filter { Regex("""https?://""").containsMatchIn(withoutComments(it)) }
                .map { it.name },
        )
    }

    @Test
    fun theConfirmationReaderOnlyReads() {
        // SEE-165 lets the phone follow its own sent transactions to the chain. That is a handful
        // of reads and nothing else: which cluster an endpoint serves, a signature's status, the
        // transaction under it, whether its blockhash still counts, and how far back the
        // endpoint's ledger reaches (minimumLedgerSlot and that slot's block time) — and, for a
        // sale
        // whose wallet never answered (SEE-172), which transactions named its order account. The
        // tracker is
        // told what the wallet
        // was handed and what it answered; it never asks the wallet anything, and it can't sign,
        // build, simulate or send (docs/wiki/chain-confirmation.md).
        val confirmations = File(main, "java/io/github/brrenat/seekervault/confirmations")
        assertTrue(confirmations.isDirectory)
        val sources = confirmations.walk().filter { it.extension == "kt" }.toList()
        val code = sources.joinToString("\n") { withoutComments(it) }
        assertEquals(
            listOf(
                "\"getBlockTime\"",
                "\"getGenesisHash\"",
                "\"getSignatureStatuses\"",
                "\"getSignaturesForAddress\"",
                "\"getTransaction\"",
                "\"isBlockhashValid\"",
            ),
            Regex(""""(get|send|simulate|is)[A-Z][A-Za-z]+"""")
                .findAll(code)
                .map { it.value }
                .distinct()
                .sorted()
                .toList(),
        )
        val acting =
            Regex(
                """\b(sendTransaction|sendRawTransaction|simulateTransaction|requestAirdrop|""" +
                    """getLatestBlockhash|WalletAdapter|WalletRepository|WalletSession|""" +
                    """signAndSend|signAndSendTransactions|signMessage|withWallet|""" +
                    """ConnectionGateway|CredentialVault|UpdateTransport|FeedGateway)\b"""
            )
        assertEquals(
            emptyList<String>(),
            sources.filter { acting.containsMatchIn(withoutComments(it)) }.map { it.name },
        )
        // Its endpoints are the application's own, from the build, through the composition root.
        val composition = File(main, "java/io/github/brrenat/seekervault/SeekerVaultApplication.kt")
        val text = composition.readText()
        listOf("SOLANA_RPC_MAINNET", "SOLANA_RPC_DEVNET", "SOLANA_RPC_TESTNET").forEach {
            assertTrue(it, text.contains("BuildConfig.$it"))
        }
        assertTrue(text.contains("HttpChainReader(httpClient, url)"))
        // And the one place that knows a genesis hash says only which cluster is which.
        assertEquals(
            listOf("ChainReader.kt"),
            File(main, "java")
                .walk()
                .filter { it.extension == "kt" }
                .filter { it.readText().contains("5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d") }
                .map { it.name }
                .toList(),
        )
    }

    @Test
    fun theJupiterPluginsReachTheirProviderAndNothingElseOfThisApps() {
        // SEE-93 was the first thing written against the plugin boundary and SEE-94 the second, so
        // between them they are the test of whether that boundary was worth having: a plugin that
        // had to reach into the app to work would mean the boundary described nothing. These reach
        // their own provider, and one shared Solana component, and take everything else from what
        // they are handed (docs/wiki/jupiter-swap.md, docs/wiki/jupiter-prediction.md).
        val jupiter = File(main, "java/io/github/brrenat/seekervault/jupiter")
        assertTrue(jupiter.isDirectory)
        val sources = jupiter.walk().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        // What they reach for in the rest of the app: the boundary they implement, the one decoder
        // and the instruction readers that already exist, the shared component that resolves a
        // versioned message's accounts (SEE-94), the app's three-state verdict, the owner's
        // selected wallet as a public address, base58, and the string resources their own words
        // live in. No wallet interaction, no store, no approval, and neither of the app's own
        // transports — they do not know a sidecar or a gateway exists.
        val reaches =
            sources
                .flatMap { it.readLines() }
                .map { it.trim() }
                .filter { it.startsWith("import io.github.brrenat.seekervault.") }
                .map { it.removePrefix("import ") }
                .filterNot { it.startsWith("io.github.brrenat.seekervault.jupiter.") }
                .map { it.substringBeforeLast('.') }
                .distinct()
                .sorted()
        assertEquals(
            listOf(
                "io.github.brrenat.seekervault",
                "io.github.brrenat.seekervault.plugins",
                // The provider-neutral action payloads SEE-145 moved out of this package: the
                // `swap` and `prediction.buy` schemas belong to the actions rather than to
                // whoever executes them, and the adapter is handed them already read.
                "io.github.brrenat.seekervault.plugins.actions",
                "io.github.brrenat.seekervault.request.v1",
                "io.github.brrenat.seekervault.solana",
                "io.github.brrenat.seekervault.transactions",
                "io.github.brrenat.seekervault.wallet",
            ),
            reaches,
        )
        val authority =
            Regex(
                """\b(WalletAdapter|WalletSession|WalletRepository|WalletStore|authToken|""" +
                    """signMessage|signAndSend|signAndSendTransactions|withWallet|""" +
                    """ConnectionGateway|ConnectionRepository|ProposalRepository|UpdateTransport|""" +
                    """FeedGateway|FeedStream|CredentialVault|ConnectionStore|ResultStore|""" +
                    """PolicyStore|ProposalStore|ActivityLog|ActivityStore|SyncStore|""" +
                    """PolicyEvaluator|approve|Approval)\b"""
            )
        assertEquals(
            emptyList<String>(),
            sources.filter { authority.containsMatchIn(withoutComments(it)) }.map { it.name },
        )
        // The provider's addresses are written once each, and there are two of them with two
        // different jobs: the API the plugins dial, and the platform a link is handed to. Both are
        // parameters or constants in the plugin's own package, which is what lets a test point a
        // plugin at a local server and what keeps every hostname out of the rest of the app.
        val everywhere = File(main, "java").walk().filter { it.extension == "kt" }.toList()
        fun named(host: Regex) =
            everywhere.filter { host.containsMatchIn(it.readText()) }.map { it.name }.sorted()
        // A third, since SEE-173: Jupiter's official pages about its own integrations — its
        // developer documentation, terms and privacy policy — which a review links to and never
        // dials, kept together in the one file that says how the app attributes Jupiter.
        assertEquals(
            listOf("JupiterAttribution.kt", "JupiterPredictionAction.kt", "JupiterProvider.kt"),
            named(Regex("""jup\.ag""")),
        )
        // The endpoint the plugins read from, in the one file that makes a request to it.
        assertEquals(listOf("JupiterProvider.kt"), named(Regex("""lite-api\.jup\.ag""")))
        // And the platform the owner is sent to afterwards, which this app never dials: it is a
        // link, built at the moment it is shown and never stored (SEE-94).
        assertEquals(
            listOf("JupiterPredictionAction.kt"),
            named(Regex(""""https://jup\.ag"""")),
        )
        // And the build actually carries it: the list is in the composition root, because a real
        // provider needs something built, and `plugins/` holds no client (SEE-86's `bundled()`
        // could only ever list providers that needed nothing). Since SEE-145 it is one provider
        // serving two actions rather than two plugins.
        val composition = File(main, "java/io/github/brrenat/seekervault/SeekerVaultApplication.kt")
        assertTrue(composition.readText().contains("JupiterExecutionProvider("))
        assertTrue(composition.readText().contains("HttpJupiterProvider(httpClient)"))
        assertTrue(composition.readText().contains("HttpJupiterPrediction(httpClient)"))
        assertEquals(
            emptyList<String>(),
            File(main, "java/io/github/brrenat/seekervault/plugins")
                .walk()
                .filter { it.extension == "kt" }
                // The comment there explains why it is gone, so this reads the code without it.
                .filter { Regex("""bundled\(""").containsMatchIn(withoutComments(it)) }
                .map { it.name }
                .toList(),
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
        val jupiter = File(main, "java/io/github/brrenat/seekervault/jupiter")
        val skr = File(main, "java/io/github/brrenat/seekervault/skr")
        val outside =
            sources
                .filterNot { it.startsWith(transactions) }
                .filterNot { it.startsWith(jupiter) }
                .filterNot { it.startsWith(skr) }
                .filter { decoding.containsMatchIn(it.readText()) }
                .map { it.name }
        assertEquals(emptyList<String>(), outside)
        // `jupiter/` is allowed to *call* the decoder, because a plugin has to read back the bytes
        // it prepared and this is the one decoder there is (SEE-93). `skr/` calls it for the
        // opposite reason and with the same rule: it reads bytes somebody else built, and it
        // derives the staking program's addresses from their seeds rather than being told them
        // (SEE-146). What neither may do is have a second decoder: the message format — the
        // signature array, the header, the account list, the shortvec lengths — is read in exactly
        // one place, and a reader that parsed it again is a reader that could disagree with the
        // review about what a transaction even contains.
        val format = Regex("""compactU16|recentBlockhash =|addressTableLookups =""")
        assertEquals(
            emptyList<String>(),
            sources
                .filterNot { it.startsWith(transactions) }
                .filter { format.containsMatchIn(withoutComments(it)) }
                .map { it.name },
        )
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
                // The string resources the actions' own words live in. Text stays in resources,
                // which is the rule every other part of this app is held to (SEE-145).
                "io.github.brrenat.seekervault.R",
                // The vocabulary a rule names an action in. SEE-93 reads it because a broadcast
                // proposal carries no `ActionRequest` to take the kind of action from, and the
                // operation's own name is the same word a rule uses (`swap`).
                "io.github.brrenat.seekervault.policy.PolicyAction",
                "io.github.brrenat.seekervault.policy.PolicyAsset",
                "io.github.brrenat.seekervault.policy.RequestFacts",
                "io.github.brrenat.seekervault.policy.policyAction",
                "io.github.brrenat.seekervault.request.v1.Action",
                "io.github.brrenat.seekervault.request.v1.ActionRequest",
                "io.github.brrenat.seekervault.request.v1.Network",
                "io.github.brrenat.seekervault.transactions.Verdict",
                "io.github.brrenat.seekervault.wallet.SelectedWallet",
                // A pure check that a string is a base58 32-byte address. It reads no wallet and
                // reaches no store; it is where "an asset is a mint, never a ticker" already
                // lives, and SEE-145 moved the action payloads that apply it into this package.
                "io.github.brrenat.seekervault.wallet.isSolanaAddress",
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
            listOf(
                    "connections",
                    "sync",
                    "live",
                    "push",
                    "policy",
                    "transactions",
                    "activity",
                    // And the screens that review a proposal and act on it (SEE-93). They are the
                    // part a person actually uses, and they are written against the boundary's
                    // own types — a labelled value, a finding, a verdict — so the next plugin
                    // (SEE-94) is shown by the same screens without touching them.
                    "operations",
                )
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
        // Which code knows the registry exists at all. Four packages: the boundary itself,
        // `servers/`, where a server's stated requirements are matched against it (SEE-88),
        // `proposals/`, where a publisher's document is matched against the plugin this build
        // resolves for its operation (SEE-89), and `jupiter/`, which *is* a plugin — listing its
        // files would say nothing, since being written against the boundary is what it is (SEE-93).
        // Outside them, eight files: the one that composes the app, the one that asks which
        // operation a request is for so the owner's rules can be applied to it, the one that shows
        // the owner whether their servers are supported, the one that holds what the owner chose
        // about a proposal, the two stores that read a cached manifest's plugin names and a stored
        // choice back off disk, and the two that review a proposal and prepare it. MainActivity
        // passes the composed registry along without naming the package.
        val owners =
            listOf("plugins", "servers", "proposals", "jupiter").map {
                File(main, "java/io/github/brrenat/seekervault/$it")
            }
        val outside =
            File(main, "java")
                .walk()
                .filter { it.extension == "kt" }
                .filterNot { file -> owners.any { file.startsWith(it) } }
                .map {
                    it to
                        Regex("""import io\.github\.brrenat\.seekervault\.plugins\.(\S+)""")
                            .findAll(it.readText())
                            .map { match -> match.groupValues[1] }
                            .toList()
                }
                .filter { (_, imports) -> imports.isNotEmpty() }
                .toList()
        // One name in that package is not about plugins at all: PluginEnvironment is which promise
        // a connection keeps when the owner approves (SEE-97). It lives beside the boundary
        // because a plugin says which environments it serves, but the decision is core's — a
        // connection records it, the owner changes it, a binding pins it and a record keeps it —
        // so the files below know what a sandbox is and still know nothing about a registry.
        val promise =
            outside
                .filter { (_, imports) -> imports.all { it == "PluginEnvironment" } }
                .map { (file, _) -> file.name }
                .sorted()
        assertEquals(
            listOf(
                "ActivityRecord.kt",
                "ActivityStore.kt",
                "Connection.kt",
                "ConnectionDetailsScreen.kt",
                "ConnectionRepository.kt",
                "ConnectionText.kt",
            ),
            promise,
        )
        val importers =
            outside
                .filterNot { (_, imports) -> imports.all { it == "PluginEnvironment" } }
                .map { (file, _) -> file.name }
                .sorted()
        assertEquals(
            listOf(
                // The one that re-emits a proposal as the common envelope, which writes the
                // action's legacy spelling so an older client reads what it always did (SEE-145).
                "CommonEnvelope.kt",
                "ConnectionStore.kt",
                "ConnectionsViewModel.kt",
                // The alert's words, chosen by the action's own identity rather than by a spelling
                // of it: both wire spellings of the prediction action normalize to one constant,
                // so matching the constant is the only way the copy cannot drift from what
                // `actionOf` produces (SEE-145).
                "FeedNotifications.kt",
                // The History record says which promise an item was made under, and who routed it
                // and what service fee it carried: the binding's, as the record kept it (SEE-161,
                // SEE-173). It reads a receipt's rows and prepares nothing.
                "HistoryDetailMapping.kt",
                // The History item's live position (SEE-172): it names the position a purchase went
                // into so the provider's own links can be built for it, and prepares nothing.
                "HistoryDetailRoute.kt",
                "InboxViewModel.kt",
                // The words for each of the six reasons nothing serves an action here.
                "OperationText.kt",
                "OperationViewModel.kt",
                // Positions (SEE-172): the provider-neutral readings and the sale the provider
                // built
                // and read, mapped for History, stored, and handed to the wallet under its lock by
                // the one coordinator — which resolves the provider by the ID the purchase was
                // bound to, and never by anything else.
                "PositionDetailMapping.kt",
                "PositionStore.kt",
                "PositionTracker.kt",
                "Positions.kt",
                "PositionsViewModel.kt",
                // The design's prediction review (SEE-158): it reads the owner's side and stake off
                // the form the provider declared, and the terms off the parsed payload, to show
                // them — it prepares nothing and resolves no provider.
                "PredictionReview.kt",
                "PredictionReviewScreen.kt",
                "ProposalRepository.kt",
                "ProposalReviewScreen.kt",
                "ProposalStore.kt",
                "SeekerVaultApplication.kt",
            ),
            importers,
        )
    }

    @Test
    fun aProposalIsBoundedDataAndTheCodeThatReadsOneDecidesNothing() {
        // SEE-89 adds the document a publisher broadcasts: its identity, its revision, the
        // operation it proposes, its expiry, whether the publisher still stands behind it, and the
        // operation's common terms. The package that reads one is data and pure functions — it
        // says where a proposal stands and what would have to hold before its operation could be
        // executed, and it cannot execute anything (docs/wiki/shared-proposals.md).
        val proposals = File(main, "java/io/github/brrenat/seekervault/proposals")
        assertTrue(proposals.isDirectory)
        val sources = proposals.walk().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        // What it reaches for, and every one of them is a rule or a name: the ID rule a publisher's
        // identity is held to, the channel rule it owns, the plugin registry this build carries and
        // the names a proposal may use for it, the owner's selected wallet as a public address and
        // a network, what this build supports, and the protocol message itself.
        val reaches =
            sources
                .flatMap { it.readLines() }
                .map { it.trim() }
                .filter { it.startsWith("import io.github.brrenat.seekervault.") }
                .map { it.removePrefix("import ").substringBefore(" as ") }
                .filterNot { it.startsWith("io.github.brrenat.seekervault.proposals.") }
                .distinct()
                .sorted()
        assertEquals(
            listOf(
                "io.github.brrenat.seekervault.connections.isConnectionId",
                "io.github.brrenat.seekervault.plugins.ActionId",
                // Who would execute it, and the one table that turns a name written before
                // SEE-145 into one (`jupiter.swap` -> the provider `jupiter`, `prediction` -> the
                // action `prediction.buy`). Reading a document decides nothing either way.
                "io.github.brrenat.seekervault.plugins.ExecutionProviderId",
                "io.github.brrenat.seekervault.plugins.ParameterChoice",
                "io.github.brrenat.seekervault.plugins.PluginEnvironment",
                "io.github.brrenat.seekervault.plugins.PluginId",
                // What the owner was shown about who carried it out and its service fee, kept
                // with the binding for the record and gating nothing (SEE-173).
                "io.github.brrenat.seekervault.plugins.PluginReference",
                "io.github.brrenat.seekervault.plugins.ProviderRegistry",
                "io.github.brrenat.seekervault.plugins.ProviderResolution",
                "io.github.brrenat.seekervault.plugins.SUPPORTED_PROVIDER_CONTRACTS",
                "io.github.brrenat.seekervault.plugins.UnsupportedReason",
                "io.github.brrenat.seekervault.plugins.actionOf",
                // The action's own payload, read before any provider is consulted, and exactly
                // which market or pair it is about — which a binding pins (SEE-145).
                "io.github.brrenat.seekervault.plugins.actions.ActionPayloadResult",
                "io.github.brrenat.seekervault.plugins.actions.Instrument",
                "io.github.brrenat.seekervault.plugins.actions.actionPayloadFrom",
                "io.github.brrenat.seekervault.plugins.isDottedName",
                "io.github.brrenat.seekervault.plugins.isPluginId",
                // Which legacy names mean what, and — the same question asked the other way —
                // which pairing of a legacy name and an action was never published at all
                // (SEE-145). Both are lookups in one table; neither is a decision taken here.
                "io.github.brrenat.seekervault.plugins.legacyNameContradicts",
                "io.github.brrenat.seekervault.plugins.providerOf",
                "io.github.brrenat.seekervault.proposal.v1.Proposal",
                "io.github.brrenat.seekervault.proposal.v1.ProposalStatus",
                "io.github.brrenat.seekervault.proposal.v1.ProposalValue",
                "io.github.brrenat.seekervault.request.v1.Network",
                "io.github.brrenat.seekervault.request.v2.Audience",
                "io.github.brrenat.seekervault.request.v2.OwnerInputKind",
                "io.github.brrenat.seekervault.request.v2.PresentationCategory",
                "io.github.brrenat.seekervault.request.v2.Request",
                "io.github.brrenat.seekervault.request.v2.RequestStatus",
                "io.github.brrenat.seekervault.request.v2.ResultMode",
                "io.github.brrenat.seekervault.request.v2.Value",
                "io.github.brrenat.seekervault.servers.ServerSupport",
                "io.github.brrenat.seekervault.servers.channelFor",
                "io.github.brrenat.seekervault.servers.executable",
                "io.github.brrenat.seekervault.wallet.SelectedWallet",
            ),
            reaches,
        )
        // And nothing in it subscribes to a feed, opens a wallet, stores anything, approves
        // anything, or so much as suspends. The rules it holds are about what *would* have to be
        // true; carrying an operation out is the repository's and the owner's. The comments discuss
        // all of that on purpose, so this reads the code with the comments taken out of it.
        val authority =
            Regex(
                """\b(WalletAdapter|WalletSession|WalletRepository|WalletStore|authToken|""" +
                    """signMessage|signAndSend|withWallet|ConnectionGateway|FeedGateway|""" +
                    """ProposalFeed|ConnectionRepository|ProposalRepository|UpdateTransport|""" +
                    """OkHttp|HttpClient|CredentialVault|ConnectionStore|ResultStore|""" +
                    """ProposalStore|PolicyStore|ActivityLog|ActivityStore|SyncStore|approve|""" +
                    """Approval|suspend)\b"""
            )
        assertEquals(
            emptyList<String>(),
            sources.filter { authority.containsMatchIn(withoutComments(it)) }.map { it.name },
        )
        // The document itself is bounded, and this is the whole of it. There is nothing in it
        // about any subscriber — no address they would pay from, no quantity they chose, nothing
        // prepared for them to sign — and a field that could carry one would have to be added here
        // first.
        val proto =
            File(repoRoot, "packages/protocol/proto/seekervault/proposal/v1/proposal.proto")
                .readLines()
                .filterNot { it.trim().startsWith("//") }
                .joinToString("\n")
        val fields =
            Regex("""^\s*(?:repeated\s+)?[\w.]+\s+(\w+)\s*=\s*\d+;""", RegexOption.MULTILINE)
                .findAll(proto)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(
            listOf(
                "server_id",
                "channel",
                "proposal_id",
                "revision",
                "operation",
                "plugin_id",
                "status",
                "created_at",
                "updated_at",
                "expires_at",
                "publisher_note",
                "values",
                "key",
                "text",
            ),
            fields,
        )
        assertEquals(
            emptyList<String>(),
            Regex(
                    """(?i)\b(permission|policy|wallet|credential|token|secret|install|script|""" +
                        """amount|signature|approval|transaction|prepared)\b"""
                )
                .findAll(proto)
                .map { it.value }
                .toList(),
        )
    }

    @Test
    fun theCommonRequestEnvelopeIsBoundedDataWithNoOwnerOutcome() {
        // SEE-108 normalizes the two durable legacy models into one source-authored envelope.
        // This package may adapt data, but cannot store, transport, approve or execute it.
        val requests = File(main, "java/io/github/brrenat/seekervault/requests")
        assertTrue(requests.isDirectory)
        val sources = requests.walk().filter { it.extension == "kt" }.toList()
        val authority =
            Regex(
                """\b(WalletAdapter|WalletRepository|WalletStore|authToken|signAndSend|""" +
                    """withWallet|ConnectionGateway|FeedGateway|OkHttp|HttpClient|""" +
                    """CredentialVault|ConnectionStore|ResultStore|ProposalStore|approve|suspend)\b"""
            )
        assertEquals(
            emptyList<String>(),
            sources.filter { authority.containsMatchIn(withoutComments(it)) }.map { it.name },
        )

        val proto =
            File(repoRoot, "packages/protocol/proto/seekervault/request/v2/request.proto")
                .readLines()
                .filterNot { it.trim().startsWith("//") }
                .joinToString("\n")
        val fields =
            Regex("""^\s*(?:repeated\s+)?[\w.]+\s+(\w+)\s*=\s*\d+;""", RegexOption.MULTILINE)
                .findAll(proto)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(
            listOf(
                "contract_version",
                "identity",
                "lifecycle",
                "presentation",
                "action",
                "owner_inputs",
                "audience",
                "result_handling",
                "source_id",
                "scope",
                "request_id",
                "revision",
                "status",
                "created_at",
                "updated_at",
                "expires_at",
                "title",
                "description",
                "category",
                "capability_id",
                "capability_version",
                "plugin_id",
                "parameters",
                "key",
                "text",
                "integer",
                "flag",
                "opaque",
                "key",
                "label",
                "kind",
                "required",
                "minimum",
                "maximum",
                "options",
                "help",
                "value",
                "label",
                "private",
                "feed",
                "recipient_id",
                "channel",
                "mode",
            ),
            fields,
        )
        assertEquals(
            emptyList<String>(),
            Regex(
                    """(?i)\b(wallet_binding|selected_wallet|owner_value|subscriber|credential|""" +
                        """decision|signature|approval|prepared|transaction|execution|outcome|url|code)\b"""
                )
                .findAll(proto)
                .map { it.value }
                .toList(),
        )
    }

    @Test
    fun aServerManifestIsBoundedDataAndTheCodeThatReadsItActsOnNothing() {
        // SEE-88 lets a server say what it is: its identity, the contract it speaks, its settings
        // revision, its mode, one endpoint or channel reference, and the client plugins its
        // operations need. The package that reads one is data and pure functions — it decides
        // whether this build supports a server, and it cannot do anything about it
        // (docs/wiki/server-manifests.md).
        val servers = File(main, "java/io/github/brrenat/seekervault/servers")
        assertTrue(servers.isDirectory)
        val sources = servers.walk().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        // What it reaches for, and every one of them is a rule or a name: the pairing code's own
        // URL and ID rules, which a manifest's endpoint is held to as well; the plugin registry
        // this build carries, and the names a manifest may use for it; and the protocol message
        // itself.
        val reaches =
            sources
                .flatMap { it.readLines() }
                .map { it.trim() }
                .filter { it.startsWith("import io.github.brrenat.seekervault.") }
                .map { it.removePrefix("import ").substringBefore(" as ") }
                .filterNot { it.startsWith("io.github.brrenat.seekervault.servers.") }
                .distinct()
                .sorted()
        assertEquals(
            listOf(
                "io.github.brrenat.seekervault.connections.PairingCodeProblem",
                "io.github.brrenat.seekervault.connections.PairingCodes",
                "io.github.brrenat.seekervault.connections.isConnectionId",
                "io.github.brrenat.seekervault.plugins.PluginEnvironment",
                "io.github.brrenat.seekervault.plugins.PluginId",
                "io.github.brrenat.seekervault.plugins.ProviderRegistry",
                "io.github.brrenat.seekervault.plugins.isPluginId",
                // The published contract of the bundled-plugin name a manifest requires — looked
                // up, never parsed out of the name (SEE-145).
                "io.github.brrenat.seekervault.plugins.legacyCapabilityOf",
                "io.github.brrenat.seekervault.server.v1.ConnectionMode",
                // A feed's access policy and the message that carries it (SEE-156). Still data:
                // this package decides whether the policy is one this build supports, and an
                // unknown one is refused rather than read as public.
                "io.github.brrenat.seekervault.server.v1.FeedAccessPolicy",
                "io.github.brrenat.seekervault.server.v1.GatewayFeed",
                "io.github.brrenat.seekervault.server.v1.ServerEnvironment",
                "io.github.brrenat.seekervault.server.v1.ServerManifest",
                // The Solana networks a server declares (SEE-174), and the value this build
                // names each one by. Data again: a network is compared, never acted on here.
                "io.github.brrenat.seekervault.server.v1.SolanaNetwork",
                "io.github.brrenat.seekervault.wallet.WalletNetwork",
            ),
            reaches,
        )
        // And nothing in it opens a connection, holds a credential, stores anything, approves
        // anything, or so much as suspends: reading a manifest is a decision about what this build
        // can do, made from data, with no way to act on the answer. The comments discuss all of
        // that on purpose, so this reads the code with the comments taken out of it.
        val authority =
            Regex(
                """\b(WalletAdapter|WalletSession|WalletRepository|WalletStore|authToken|""" +
                    """signMessage|signAndSend|withWallet|ConnectionGateway|FeedGateway|""" +
                    """ConnectionRepository|UpdateTransport|OkHttp|HttpClient|CredentialVault|""" +
                    """ConnectionStore|ResultStore|PolicyStore|ActivityLog|SyncStore|approve|""" +
                    """Approval|suspend)\b"""
            )
        assertEquals(
            emptyList<String>(),
            sources.filter { authority.containsMatchIn(withoutComments(it)) }.map { it.name },
        )
        // The document itself is bounded, and this is the whole of it. A field that could carry a
        // permission, a policy, or a wallet endpoint would have to be added here first.
        val proto =
            File(repoRoot, "packages/protocol/proto/seekervault/server/v1/manifest.proto")
                .readLines()
                .filterNot { it.trim().startsWith("//") }
                .joinToString("\n")
        val fields =
            Regex("""^\s*(?:repeated\s+)?[\w.]+\s+(\w+)\s*=\s*\d+;""", RegexOption.MULTILINE)
                .findAll(proto)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(
            listOf(
                "server_id",
                "protocol_version",
                "settings_revision",
                "mode",
                "required_plugins",
                "environments",
                "display_name",
                "direct",
                "feed",
                "url",
                // The Solana networks the server's operations run on (SEE-174), in each reference:
                // a statement the phone filters wallet profiles by, and never a wallet endpoint.
                "supported_networks",
                "gateway_url",
                "channel",
                // Who may read the feed (SEE-156), and nothing else about it: the policy, and the
                // one origin a restricted feed's subscriber proves a wallet to.
                "access",
                "supported_networks",
                "policy",
                "auth_origin",
                "plugin_id",
                "min_contract",
                "max_contract",
            ),
            fields,
        )
        // The document itself is still bounded, and the one exception is the one SEE-156 argued
        // for here: a feed's access policy, which says who may read the feed and nothing about
        // what the phone may do with it. The gateway stamps it from its operator's registration,
        // so it is not the publisher's claim either. Anything else would have to be added here
        // first, which is the whole point of the list.
        assertEquals(
            listOf("policy"),
            Regex("""(?i)\b(permission|policy|wallet|credential|token|secret|install|script)\b""")
                .findAll(proto)
                .map { it.value }
                .toList(),
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
            // SEE-92 adds the third and last one: joining a feed's public topic, behind one
            // interface, so everything above it is testable without Firebase and an installation
            // built without a project stays an ordinary app.
            setOf(
                "FcmRegistrationManager.kt",
                "SeekerVaultMessagingService.kt",
                "FeedTopicClient.kt",
            ),
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
        // SEE-92's second kind, and the whole of what it may do: match the payload, hand on the
        // topic it arrived on, and enqueue one read. No document, no connection, no repository.
        assertTrue("FeedSyncScheduler" in service)
        assertTrue("isFeedInvalidation" in service)
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
        // SEE-105 derives words from the authoritative post-Sync kind; it does not render the
        // request's free text, note, bytes, or identifiers into the notification.
        assertTrue("ActionRequest" in notification)
        assertTrue("request.action.ack" !in notification)
        assertTrue("request.action.signMessage" !in notification)
        assertTrue("request.action.transfer" !in notification)
        assertTrue("request.action.swap" !in notification)
        assertTrue("request.ref.requestId" !in notification)
        assertTrue("wallet" !in notification.lowercase())
        assertTrue("approve" !in notification.lowercase())
        assertTrue("signAndSendTransactions" !in notification)
        assertTrue("WorkManager" !in notification)
        assertTrue("ForegroundUpdateManager" !in notification)
        assertTrue("BackgroundSyncScheduler" !in notification)

        val appearance =
            withoutComments(
                File(
                    main,
                    "java/io/github/brrenat/seekervault/notifications/NotificationAppearance.kt",
                )
            )
        assertTrue("setSmallIcon(R.drawable.ic_notification_sac)" in appearance)
        assertTrue(
            "setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))" in appearance
        )
        assertTrue("Notification.BigTextStyle" in appearance)
        assertTrue("Notification.VISIBILITY_SECRET" in appearance)
        assertTrue("wallet" !in appearance.lowercase())
        assertTrue("approve" !in appearance.lowercase())
        assertTrue("WorkManager" !in appearance)

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

        // SEE-92 repeats both rules for the feeds' own path: the alert comes after the read, and
        // the presentation is in notifications/ rather than in the worker.
        val feeds =
            withoutComments(
                File(main, "java/io/github/brrenat/seekervault/sync/FeedSynchronization.kt")
            )
        assertTrue(feeds.indexOf("read(feed.id") < feeds.indexOf("reconcileNotifications(before"))
        assertTrue("POST_NOTIFICATIONS" !in feeds)
        assertTrue("Notification" !in feeds.replace("reconcileNotifications", ""))
        assertTrue("wallet" !in feeds.lowercase())
        assertTrue("approve" !in feeds.lowercase())

        val proposals =
            withoutComments(
                File(main, "java/io/github/brrenat/seekervault/notifications/FeedNotifications.kt")
            )
        assertTrue("NotificationChannel" in proposals)
        assertTrue("PendingIntent.FLAG_IMMUTABLE" in proposals)
        assertTrue("MainActivity" in proposals)
        assertTrue("isConnectionId" in proposals)
        // The same limits the request alert is held to: it reads only the validated operation kind
        // and local source name, routes to a screen, and has no means of acting on anything.
        assertTrue("ProposalRecord" in proposals)
        assertTrue("record.proposal.note" !in proposals)
        assertTrue("record.proposal.terms" !in proposals)
        assertTrue("record.proposal.pluginId" !in proposals)
        assertTrue("record.key.proposalId" !in proposals)
        assertTrue("ProposalRepository" !in proposals)
        assertTrue("wallet" !in proposals.lowercase())
        assertTrue("approve" !in proposals.lowercase())
        assertTrue("signAndSendTransactions" !in proposals)
        assertTrue("WorkManager" !in proposals)
        assertTrue("ForegroundFeedManager" !in proposals)
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
        // The sidecar transports, the feed gateway's feed transport, and the one client they
        // share.
        // Nothing else. A new file here is a new host this app talks to, and has to be read as one:
        // the first three reach the owner's own sidecar, and the last two reach a shared gateway
        // that is told which channels a phone is interested in and nothing else (SEE-91).
        val allowed =
            setOf(
                "ConnectConnectionGateway.kt",
                "ConnectLiveCommandTransport.kt",
                "ConnectUpdateTransport.kt",
                "ConnectFeedGateway.kt",
                "CentrifugoFeedStream.kt",
                "SeekerVaultApplication.kt",
                // And one more, deliberately: a swap's execution data comes from a provider, and
                // the plugin fetches it itself from the owner's own phone (SEE-93). What goes there
                // is two mint addresses, an amount, and — for the build alone — the owner's public
                // address, because a transaction has to be built for the account that signs it.
                // The publisher and the shared gateway are told none of it.
                "JupiterProvider.kt",
                // The same provider, for a prediction market: which market, which side, how much,
                // and the address that will sign (SEE-94).
                "JupiterPrediction.kt",
                // And the one chain endpoint this app has ever had, for one purpose: reading the
                // address lookup tables a prediction order's transaction names, without which the
                // phone cannot see what it would be signing. One method, read-only, and the
                // application's own endpoint rather than any publisher's
                // (docs/security.md#resolving-a-lookup-table).
                "SolanaAccounts.kt",
                // The gateway push relay (SEE-144). It reaches exactly one host — the relay this
                // build was configured with, in BuildConfig.RELAY_URL — and no server can change
                // that: a server may advertise a relay over its authenticated connection, and the
                // app ignores the advertisement unless it names that same origin. What it sends
                // there is this installation's Firebase registration and which servers the owner
                // authorized to wake it; what it never sends is a request, a decision, or
                // anything a server told it.
                "RelayClient.kt",
                // A restricted feed's authentication endpoint (SEE-156). It reaches only the
                // origin the gateway stamped on that feed's manifest from its operator's
                // registration, never one a reference or a publisher supplies. What it sends is
                // the wallet's one signature over text that says it is not a transaction, and
                // after that only statements signed by this installation's device key.
                "FeedAccessApi.kt",
                // The chain endpoints this build was configured with, asked what became of a
                // transaction this phone's wallet already sent (SEE-165). Reads only — a status,
                // the
                // transaction under a signature, whether a blockhash still counts, and which
                // cluster it serves — and never a publisher's or a server's endpoint.
                "ChainReader.kt",
            )
        val clients = sources.filter { http.containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertTrue(clients.all { it in allowed })
        assertTrue(clients.containsAll(allowed - "ConnectUpdateTransport.kt"))
    }

    @Test
    fun theDesignSystemOwnsOnlyVisualCodeAndTheAppDependsOnIt() {
        val designSystem = File(repoRoot, "apps/android/designsystem")
        val build = File(designSystem, "build.gradle.kts").readText()
        val settings = File(repoRoot, "apps/android/settings.gradle.kts").readText()
        val appBuild = File(repoRoot, "apps/android/app/build.gradle.kts").readText()
        val rootBuild = File(repoRoot, "apps/android/build.gradle.kts").readText()
        val packageJson = File(repoRoot, "package.json").readText()
        val sources =
            File(designSystem, "src/main/java").walk().filter { it.extension == "kt" }.toList()

        assertTrue(designSystem.isDirectory)
        assertTrue(settings.contains("include(\":designsystem\")"))
        assertTrue(appBuild.contains("implementation(project(\":designsystem\"))"))
        assertTrue(!build.contains("project("))
        assertTrue(
            listOf("lifecycle", "ViewModel", "okhttp", "retrofit", "grpc", "firebase").none {
                build.contains(it, ignoreCase = true)
            }
        )

        val appImports =
            sources
                .flatMap { it.readLines() }
                .map { it.trim() }
                .filter { it.startsWith("import io.github.brrenat.seekervault.") }
                .filterNot {
                    it.startsWith("import io.github.brrenat.seekervault.designsystem.")
                }
        assertEquals(emptyList<String>(), appImports)
        assertTrue(
            sources.none {
                Regex("""\bViewModel\b|okhttp|retrofit|grpc|firebase|java\.net|android\.net""")
                    .containsMatchIn(withoutComments(it))
            }
        )

        assertTrue(rootBuild.contains("checkDesignSystemLiterals"))
        assertTrue(rootBuild.contains("tasks.named(\"check\")"))
        assertTrue(packageJson.contains("checkDesignSystemLiterals"))

        assertEquals(
            "d7598e12c5dbef095ff8272cfc55da0250bd07fbdecbac8a530b9b277872a134",
            sha256(File(designSystem, "src/main/res/font/roboto_variable.ttf")),
        )
        assertEquals(
            "66a80e79d17e4c7cabd162e2916578a4cc08fd19eef6e2a643305eae9c567b2b",
            sha256(File(designSystem, "src/main/res/font/roboto_mono_variable.ttf")),
        )
        assertTrue(File(designSystem, "licenses/ROBOTO_OFL.txt").isFile)
        assertTrue(File(designSystem, "licenses/ROBOTO_MONO_OFL.txt").isFile)
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

        val theme =
            File(
                    repoRoot,
                    "apps/android/designsystem/src/main/java/io/github/brrenat/seekervault/designsystem/theme",
                )
                .walk()
                .filter { it.extension == "kt" }
                .joinToString("\n") { it.readText() }
        assertTrue(
            "Raw app bars must inherit the approved scheme's surface ink in both themes",
            theme.contains("LocalContentColor provides scheme.onSurface"),
        )
        assertTrue(
            "The production theme must retain the approved lime dark roles",
            theme.contains("lime = Color(0xFFE7FC6E)") &&
                theme.contains("limeContainer = Color(0xFFC2E60F)") &&
                theme.contains("primary = DarkSeekerColors.lime"),
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
        // SAW-022 lifted nothing here, and neither does SEE-165, which lets the phone follow a sent
        // transaction to the chain itself: it reads a status and the transaction under a
        // signature, and it can't send, simulate or fetch a blockhash to build with. A status
        // check never reaches a wallet, whether it asks the sidecar or the chain.
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

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }
}
