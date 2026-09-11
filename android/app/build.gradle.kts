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
        // Cross-runtime protocol fixtures, shared with the sidecar tests.
        getByName("test") { resources.srcDir("../../proto/fixtures") }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.connect.kotlin)
    implementation(libs.protobuf.kotlin.lite)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
