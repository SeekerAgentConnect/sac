plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

kotlin { compilerOptions { allWarningsAsErrors = true } }

@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
roborazzi {
    // Approved baselines are source-controlled. Comparison artifacts stay disposable.
    outputDir.set(layout.projectDirectory.dir("src/test/snapshots/images"))
    compare { outputDir.set(layout.buildDirectory.dir("outputs/roborazzi-comparison")) }
    generateComposePreviewRobolectricTests {
        enable = true
        packages = listOf("io.github.brrenat.seekervault.designsystem")
        includePrivatePreviews = true
        testerQualifiedClassName =
            "io.github.brrenat.seekervault.designsystem.previewtesting.DesignPreviewTester"
        useScanOptionParametersInTester = true
        generatedTestClassCount = 1
        // The DS corpus includes unrolled sheets taller than a phone viewport. This is only the
        // maximum measurement window; wrap-content component previews retain their own height.
        robolectricConfig = mapOf("qualifiers" to "\"w390dp-h1500dp-xxhdpi\"")
    }
}

android {
    namespace = "io.github.brrenat.seekervault.designsystem"
    compileSdk = 37

    defaultConfig { minSdk = 31 }

    buildFeatures { compose = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
                it.maxHeapSize = "4096m"
            }
        }
    }

    sourceSets {
        getByName("test") {
            kotlin.directories.add(rootProject.file("preview-testing/src/main/kotlin").absolutePath)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += setOf("NewerVersionAvailable", "GradleDependency")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.compose.preview.scanner)
    testImplementation(libs.composable.preview.scanner)
}

tasks.withType<Test>().configureEach {
    val designTokens = rootProject.layout.projectDirectory.file("../../design/tokens.json")
    systemProperty("seekervault.designTokens", designTokens.asFile.absolutePath)
    inputs
        .file(designTokens)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("designTokens")
}
