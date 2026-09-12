plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        // src/androidTest: the device round trip, run by `pnpm test:hello --device`.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Written by `pnpm generate` from proto/ (buf.gen.yaml); do not edit.
            java.srcDir("src/main/generated/java")
            kotlin.srcDir("src/main/generated/kotlin")
        }
        // Cross-runtime fixtures, shared with the sidecar tests: the protobuf ones, and the
        // transfer transactions the sidecar builds and this app decodes (SAW-020).
        getByName("test") {
            resources.srcDir("../../proto/fixtures")
            resources.srcDir("../../fixtures")
        }
    }

    // Robolectric runs the Compose tests on the JVM and needs the app's resources.
    testOptions { unitTests { isIncludeAndroidResources = true } }

    lint {
        // Versions are pinned and reviewed on purpose (docs/development/toolchain.md); lint's
        // network lookups for newer releases would make its results change from day to day.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
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
    inputs
        .dir(repoRoot.dir("sidecar/src"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("sidecarSources")
}
