import java.math.BigInteger

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Full-screen design previews live in one package so unrelated legacy previews do not become part
// of the screen-reference contract.
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
roborazzi {
    // Approved baselines are source-controlled. Comparison artifacts stay disposable.
    outputDir.set(layout.projectDirectory.dir("src/test/snapshots/images"))
    compare { outputDir.set(layout.buildDirectory.dir("outputs/roborazzi-comparison")) }
    generateComposePreviewRobolectricTests {
        enable = true
        packages = listOf("io.github.brrenat.seekervault.designpreviews")
        includePrivatePreviews = true
        testerQualifiedClassName =
            "io.github.brrenat.seekervault.designsystem.previewtesting.DesignPreviewTester"
        useScanOptionParametersInTester = true
        generatedTestClassCount = 1
        robolectricConfig = mapOf("qualifiers" to "\"w390dp-h844dp-xxhdpi\"")
    }
}

// Firebase is an optional deployment integration. A normal checkout has no project-specific file,
// does not apply the Google Services plugin, and keeps every Stage 5.2 path buildable. Operators
// opt in by placing the Firebase console's untracked file here (docs/guides/firebase.md).
val firebaseConfigured = layout.projectDirectory.file("google-services.json").asFile.isFile

if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
}

// The Solana endpoint the app reads accounts from, which one plugin needs and nothing else does
// (SEE-94, docs/wiki/jupiter-prediction.md#why-the-phone-reads-the-chain). It is **empty by
// default**, on purpose and in two senses: a checkout reaches no cluster, so no check here ever
// quietly depends on somebody else's public endpoint; and it is the application's own setting,
// never a publisher's, so nothing a server sends can point the phone at an endpoint of the server's
// choosing. Set it for a build that wants prediction orders:
//
//   apps/android/gradlew -p apps/android :app:assembleDebug -Pseekervault.solanaRpc=https://…
val solanaRpc = (providers.gradleProperty("seekervault.solanaRpc").orNull ?: "").trim()

// Per-cluster endpoints the phone checks its own sent transactions against (SEE-165,
// docs/wiki/chain-confirmation.md#endpoints). Each is **empty by default** for the reasons above.
// A record is only ever checked on the cluster it was bound to, so a transaction sent on devnet is
// asked about at the devnet endpoint (or at `seekervault.solanaRpc` once its genesis hash proves it
// serves devnet) and nowhere else. A debug build may point one at a local test validator, whose
// genesis hash no cluster has; a release build may not.
//
//   apps/android/gradlew -p apps/android :app:assembleDebug \
//     -Pseekervault.solanaRpc.devnet=https://…
fun clusterRpc(cluster: String) =
    (providers.gradleProperty("seekervault.solanaRpc.$cluster").orNull ?: "").trim()

// The gateway this app will register with for push relayed on a direct server's behalf (SEE-144,
// docs/guides/server-development.md#the-gateway-push-relay). **Empty by default**, for the same two
// reasons as above and one more that matters more here: it is the one place this decision can be
// made, so no server can point the phone at a relay of the server's choosing. A server may
// *advertise* a relay over its authenticated connection, and the app ignores the advertisement
// unless it names exactly this origin — which is what stops an advertisement from being a way to
// collect device registrations. With no value, the app registers with no relay at all and every
// other push path is unchanged.
//
//   apps/android/gradlew -p apps/android :app:assembleDebug \
//     -Pseekervault.relayUrl=https://feeds.example.com
val relayUrl = (providers.gradleProperty("seekervault.relayUrl").orNull ?: "").trim().trimEnd('/')

// The SAC service fee on swaps (SEE-173, docs/development/swap-fee-config.md). **Off by default**:
// a build nobody configured charges nothing, and needs nothing set. An operator who wants one sets
// three public values — a rate, the wallet that owns the receiving token accounts, and one
// receiving token account per output mint — as Gradle properties or, for CI, environment
// variables. A property wins over its environment variable; an empty value counts as unset.
//
//   apps/android/gradlew -p apps/android :app:assembleRelease \
//     -Pseekervault.solanaRpc=https://… \
//     -Pseekervault.swapFee.bps=20 \
//     -Pseekervault.swapFee.owner=<fee wallet public key> \
//     -Pseekervault.swapFee.accounts=<mint>=<token account>,<mint>=<token account>
//
// Nothing here is a secret and nothing secret may go here: the APK carries public addresses and a
// rate, never a key. The values are checked below, at configuration time, so a malformed or
// incomplete setting fails the build instead of shipping an APK that charges something nobody
// meant. The phone then verifies each account on chain before it is ever used.
fun swapFeeSetting(property: String, variable: String): String =
    providers.gradleProperty(property).orNull?.trim()?.takeIf(String::isNotEmpty)
        ?: providers.environmentVariable(variable).orNull?.trim()?.takeIf(String::isNotEmpty)
        ?: ""

val swapFeeBpsText = swapFeeSetting("seekervault.swapFee.bps", "SEEKERVAULT_SWAP_FEE_BPS")
val swapFeeOwner = swapFeeSetting("seekervault.swapFee.owner", "SEEKERVAULT_SWAP_FEE_OWNER")
val swapFeeAccountsText =
    swapFeeSetting("seekervault.swapFee.accounts", "SEEKERVAULT_SWAP_FEE_ACCOUNTS")

// The same bound the app enforces (jupiter/SwapFee.kt `MOST_SWAP_FEE_BPS`): one percent. The
// program's own field would take 255; this keeps a typo from becoming a 2.55% cut.
val mostSwapFeeBps = 100

/** Whether [text] is a base58 Solana public key: exactly 32 bytes once decoded. */
fun isSolanaAddress(text: String): Boolean {
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    if (text.isEmpty() || text.any { it !in alphabet }) return false
    var value = BigInteger.ZERO
    for (char in text) {
        value =
            value.multiply(BigInteger.valueOf(58)) +
                BigInteger.valueOf(alphabet.indexOf(char).toLong())
    }
    val body = value.toByteArray().dropWhile { it == 0.toByte() }.size
    val leading = text.takeWhile { it == '1' }.length
    return body + leading == 32
}

fun swapFeeProblem(message: String): Nothing =
    throw GradleException(
        "Invalid SAC swap fee configuration: $message " + "(docs/development/swap-fee-config.md)"
    )

val swapFeeBps: Int =
    if (swapFeeBpsText.isEmpty()) 0
    else
        swapFeeBpsText.toIntOrNull()?.takeIf { it in 0..mostSwapFeeBps }
            ?: swapFeeProblem(
                "seekervault.swapFee.bps must be a whole number of basis points from 0 to " +
                    "$mostSwapFeeBps, not \"$swapFeeBpsText\""
            )

val swapFeeAccounts: List<Pair<String, String>> =
    swapFeeAccountsText.split(',').map(String::trim).filter(String::isNotEmpty).map { entry ->
        val parts = entry.split('=', limit = 2).map(String::trim)
        if (parts.size != 2 || !isSolanaAddress(parts[0]) || !isSolanaAddress(parts[1])) {
            swapFeeProblem(
                "each seekervault.swapFee.accounts entry must be <mint>=<token account>, " +
                    "both base58 public keys, not \"$entry\""
            )
        }
        parts[0] to parts[1]
    }

// Validated whenever anything is set, so a half-finished setting is caught even with the rate at
// zero; enforced as complete only when the rate is not.
if (swapFeeOwner.isNotEmpty() && !isSolanaAddress(swapFeeOwner)) {
    swapFeeProblem("seekervault.swapFee.owner must be a base58 public key")
}

if (swapFeeAccounts.map { it.first }.toSet().size != swapFeeAccounts.size) {
    swapFeeProblem("seekervault.swapFee.accounts names the same mint twice")
}

swapFeeAccounts.forEach { (mint, account) ->
    if (account == swapFeeOwner) {
        swapFeeProblem(
            "the account for $mint is the owner wallet itself; a fee is received by a token " +
                "account for that mint, not by a wallet address"
        )
    }
    if (account == mint) swapFeeProblem("the account for $mint is the mint itself")
}

if (swapFeeBps > 0) {
    if (swapFeeOwner.isEmpty()) {
        swapFeeProblem("a nonzero rate needs seekervault.swapFee.owner")
    }
    if (swapFeeAccounts.isEmpty()) {
        swapFeeProblem("a nonzero rate needs at least one seekervault.swapFee.accounts entry")
    }
    // The phone reads each account from the chain before it charges anything to it, through the
    // app's own endpoint. Without one it would never charge, so a fee build without it is a
    // mistake worth stopping here.
    if (solanaRpc.isEmpty()) {
        swapFeeProblem("a nonzero rate needs seekervault.solanaRpc to verify the fee accounts")
    }
}

android {
    namespace = "io.github.brrenat.seekervault"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.brrenat.seekervault"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Lets the UI omit an irrelevant permission prompt from Firebase-off deployments.
        // It contains configuration presence only, never a Firebase identifier or credential.
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
        // A read-only endpoint, or the empty string. It is not a credential and it is not a
        // secret: it is an address the owner's phone reads public account data from.
        buildConfigField("String", "SOLANA_RPC", "\"$solanaRpc\"")
        // The same, per cluster, for following sent transactions (SEE-165). Read-only addresses
        // like the one above; only their hosts are ever stored.
        buildConfigField("String", "SOLANA_RPC_MAINNET", "\"${clusterRpc("mainnet")}\"")
        buildConfigField("String", "SOLANA_RPC_DEVNET", "\"${clusterRpc("devnet")}\"")
        buildConfigField("String", "SOLANA_RPC_TESTNET", "\"${clusterRpc("testnet")}\"")
        // The one relay this app will hand its Firebase registration to, or the empty string. It
        // is an origin, not a credential: what it grants is nothing until the owner's phone
        // authorizes a server at it, one direct connection at a time.
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        // The SAC service fee on swaps (SEE-173): a rate and public addresses, or 0 and nothing.
        // Written normalized, so the app reads exactly what was validated above.
        buildConfigField("int", "SWAP_FEE_BPS", swapFeeBps.toString())
        buildConfigField(
            "String",
            "SWAP_FEE_OWNER",
            "\"${if (swapFeeBps > 0) swapFeeOwner else ""}\"",
        )
        buildConfigField(
            "String",
            "SWAP_FEE_ACCOUNTS",
            "\"${if (swapFeeBps > 0) swapFeeAccounts.joinToString(",") { "${it.first}=${it.second}" } else ""}\"",
        )
        // src/androidTest: the device round trip, run by `pnpm test:hello --device`.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Written by `pnpm generate` from packages/protocol/proto/ (buf.gen.yaml); do not edit.
            java.srcDir("src/main/generated/java")
            kotlin.srcDir("src/main/generated/kotlin")
            // The vendored broker schema (buf.gen.centrifugo.yaml), kept in its own directory and
            // its own package so an import of it is visible: only feeds/CentrifugoFeedStream.kt
            // may have one (SEE-91, packages/protocol/third_party/centrifugo/README.md).
            java.srcDir("src/main/generated/centrifugo/java")
            kotlin.srcDir("src/main/generated/centrifugo/kotlin")
        }
        // Cross-runtime fixtures, shared with the sidecar tests: the protobuf ones, and the
        // transfer transactions the sidecar builds and this app decodes (SAW-020).
        getByName("test") {
            resources.srcDir("../../../packages/protocol/proto/fixtures")
            resources.srcDir("../../../fixtures")
            kotlin.directories.add(rootProject.file("preview-testing/src/main/kotlin").absolutePath)
        }
    }

    // Robolectric runs the Compose tests on the JVM and needs the app's resources. Native graphics
    // keeps the full-screen captures on the same renderer as the design-system specimens.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
                it.maxHeapSize = "4096m"
            }
        }
    }

    lint {
        // Versions are pinned and reviewed on purpose (docs/development/toolchain.md); lint's
        // network lookups for newer releases would make its results change from day to day.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(project(":designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.zxing.core)
    implementation(libs.solana.mwa.clientlib.ktx)
    implementation(libs.connect.kotlin)
    implementation(libs.connect.kotlin.javalite)
    implementation(libs.connect.kotlin.okhttp)
    implementation(libs.protobuf.kotlin.lite)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.compose.preview.scanner)
    testImplementation(libs.composable.preview.scanner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.withType<Test>().configureEach {
    // ConnectLiveCommandTransportTest runs the real sidecar from this repository.
    val repoRoot = layout.projectDirectory.dir("../../..")
    systemProperty("seekervault.repoRoot", repoRoot.asFile.absolutePath)
    // Opt-in integration switches, forwarded from the Gradle invocation to the test JVM: a broker
    // and a Redis are services, so the tests that need them skip unless someone says where they
    // are (CentrifugoStreamIntegrationTest, docs/development/feed-gateway.md).
    // `seekervault.jupiter`
    // is the same idea for a provider on the public internet (JupiterLiveTest, SEE-93): a default
    // run reaches nothing and spends nothing. A property Gradle was given does not reach a test on
    // its own, and a test that silently skipped because of that would be worse than one that
    // fails.
    for (name in listOf("seekervault.centrifugo", "seekervault.redis", "seekervault.jupiter")) {
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    inputs
        .dir(repoRoot.dir("servers/mcp-server/src"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("mcpServerSources")
}
