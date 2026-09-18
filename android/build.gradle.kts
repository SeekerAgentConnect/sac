plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.spotless)
}

spotless {
    val ktfmtVersion = libs.versions.ktfmt.get()
    kotlin {
        target("app/src/**/*.kt", "designsystem/src/**/*.kt")
        targetExclude("app/src/main/generated/**")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts", "designsystem/*.gradle.kts")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
}

val themeDirectory =
    layout.projectDirectory.dir(
        "designsystem/src/main/java/io/github/brrenat/seekervault/designsystem/theme"
    )
val productionKotlin =
    files(
        fileTree("app/src/main") {
            include("**/*.kt")
            exclude("generated/**")
        },
        fileTree("designsystem/src/main") {
            include("**/*.kt")
            exclude("java/io/github/brrenat/seekervault/designsystem/theme/**")
        },
    )

val checkDesignSystemLiterals =
    tasks.register<Exec>("checkDesignSystemLiterals") {
        group = "verification"
        description = "Rejects raw colour, dp, and sp literals outside the design-system theme."
        inputs.files(productionKotlin)
        inputs.dir(themeDirectory)
        workingDir(layout.projectDirectory)
        commandLine("node", layout.projectDirectory.file("scripts/check-design-literals.mjs"))
    }

tasks.named("check") {
    dependsOn(checkDesignSystemLiterals, "spotlessCheck", ":designsystem:check", ":app:check")
}

tasks.named("spotlessCheck") { dependsOn(checkDesignSystemLiterals) }
