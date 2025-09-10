plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.gradle.shadow) apply false
}

// Add this block to force a consistent Kotlin version
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-")) {
                useVersion(libs.versions.kotlin.get())
                because("Align all Kotlin dependencies to the same version") // Corrected from reason() to because()
            }
        }
    }
}
