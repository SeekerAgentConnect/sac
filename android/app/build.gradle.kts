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
//   android/gradlew -p android :app:assembleDebug -Pseekervault.solanaRpc=https://…
val solanaRpc = (providers.gradleProperty("seekervault.solanaRpc").orNull ?: "").trim()

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
            // Written by `pnpm generate` from proto/ (buf.gen.yaml); do not edit.
            java.srcDir("src/main/generated/java")
            kotlin.srcDir("src/main/generated/kotlin")
            // The vendored broker schema (buf.gen.centrifugo.yaml), kept in its own directory and
            // its own package so an import of it is visible: only feeds/CentrifugoFeedStream.kt
            // may have one (SEE-91, third_party/centrifugo/README.md).
            java.srcDir("src/main/generated/centrifugo/java")
            kotlin.srcDir("src/main/generated/centrifugo/kotlin")
        }
        // Cross-runtime fixtures, shared with the sidecar tests: the protobuf ones, and the
        // transfer transactions the sidecar builds and this app decodes (SAW-020).
        getByName("test") {
            resources.srcDir("../../proto/fixtures")
            resources.srcDir("../../fixtures")
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
    val repoRoot = layout.projectDirectory.dir("../..")
    systemProperty("seekervault.repoRoot", repoRoot.asFile.absolutePath)
    // Opt-in integration switches, forwarded from the Gradle invocation to the test JVM: a broker
    // and a Redis are services, so the tests that need them skip unless someone says where they
    // are (CentrifugoStreamIntegrationTest, docs/development/broadcast.md). `seekervault.jupiter`
    // is the same idea for a provider on the public internet (JupiterLiveTest, SEE-93): a default
    // run reaches nothing and spends nothing. A property Gradle was given does not reach a test on
    // its own, and a test that silently skipped because of that would be worse than one that
    // fails.
    for (name in listOf("seekervault.centrifugo", "seekervault.redis", "seekervault.jupiter")) {
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    inputs
        .dir(repoRoot.dir("sidecar/src"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("sidecarSources")
}
