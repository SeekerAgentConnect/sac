import java.net.URI

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

// The one gateway this app trusts (SEE-185, docs/wiki/client-config.md): an origin, and the only
// network setting an APK carries. Everything the app reads from a gateway comes from here — the
// feeds it reads and the links it accepts (a feed reference or catalog card naming any other
// gateway is refused), the Discover catalog (SEE-176), the push relay a direct server may use
// (SEE-144), and the client configuration: the Solana RPC endpoint for each network and the SAC
// swap service fee (SEE-173), which the gateway's operator sets and the app fetches, caches and
// applies without a rebuild. No RPC URL, fee rate or fee account is compiled into the APK.
//
// **Empty by default**, on purpose: a checkout reaches no gateway, so no check here quietly
// depends on somebody else's deployment, and with none the app says it has no gateway rather than
// guessing one. It is the application's own setting, never a publisher's or a server's.
//
//   apps/android/gradlew -p apps/android :app:assembleDebug \
//     -Pseekervault.gatewayUrl=https://feeds.example.com
val gatewayUrl =
    (providers.gradleProperty("seekervault.gatewayUrl").orNull ?: "").trim().trimEnd('/')

if (gatewayUrl.isNotEmpty()) {
    val origin = runCatching { URI(gatewayUrl) }.getOrNull()
    if (
        origin == null ||
            origin.scheme !in setOf("https", "http") ||
            origin.host.isNullOrEmpty() ||
            origin.rawUserInfo != null ||
            !origin.rawPath.isNullOrEmpty() ||
            origin.rawQuery != null ||
            origin.rawFragment != null
    ) {
        throw GradleException(
            "seekervault.gatewayUrl must be an origin such as https://feeds.example.com, with no " +
                "path, query, user info or fragment (docs/wiki/client-config.md)"
        )
    }
}

// The app's public version (SEE-182). release/components.json is the one place every released
// component's version is written, the app included, so this reads it rather than repeating it:
// `versionName` is the `android` component's version and `versionCode` its explicit, monotonically
// increasing code. Change both with `pnpm release:bump android <version>`; never by hand here.
// Rebuilding the same commit therefore always produces the same code.
val androidRelease: Map<*, *> = run {
    val manifest =
        providers
            .fileContents(rootProject.layout.projectDirectory.file("../../release/components.json"))
            .asText
            .get()
    val components = (groovy.json.JsonSlurper().parseText(manifest) as Map<*, *>)["components"]
    (components as List<*>).map { it as Map<*, *> }.single { it["id"] == "android" }
}
val releaseVersionName = androidRelease["version"] as String
val releaseVersionCode = ((androidRelease["android"] as Map<*, *>)["versionCode"] as Number).toInt()

android {
    namespace = "io.github.brrenat.seekervault"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.brrenat.seekervault"
        minSdk = 31
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        // Lets the UI omit an irrelevant permission prompt from Firebase-off deployments.
        // It contains configuration presence only, never a Firebase identifier or credential.
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
        // The one trusted gateway's origin, or "" for a build with none (SEE-185). An origin, not
        // a credential: the app reads public feeds and its own client configuration there.
        buildConfigField("String", "GATEWAY_URL", "\"$gatewayUrl\"")
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
    // `seekervault.screenshots` names a directory the SEE-173 attribution screens are saved to
    // (AttributionScreensTest, docs/testing/see-173.md); unset, nothing is written.
    for (name in
        listOf(
            "seekervault.centrifugo",
            "seekervault.redis",
            "seekervault.jupiter",
            "seekervault.screenshots",
        )) {
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    inputs
        .dir(repoRoot.dir("servers/mcp-server/src"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("mcpServerSources")
}
