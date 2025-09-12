plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.gradle.shadow)
    id("maven-publish")
}

android {
    namespace = "dev.brahmkshatriya.echo.extension"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)

    // Added dependencies for OkHttp and Kotlinx Serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")


    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.echo.common)
}


java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

// Extension properties goto `gradle.properties` to set values

val extType: String by project
val extId: String by project
val extClass: String by project

val extIconUrl: String? by project
val extName: String by project
val extDescription: String? by project

val extAuthor: String by project
val extAuthorUrl: String? by project

val extRepoUrl: String? by project
val extUpdateUrl: String? by project

val gitHash = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.get().trim().take(7)
val gitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()
val verCode = gitCount
val verName = "v$gitHash"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "dev.brahmkshatriya.echo.extension"
                artifactId = extId
                version = verName

                from(components["release"])
            }
        }
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set(extId)
    archiveVersion.set(verName)
    manifest {
        attributes(
            mapOf(
                "Extension-Id" to extId,
                "Extension-Type" to extType,
                "Extension-Class" to "dev.brahmkshatriya.echo.extension.$extClass",

                "Extension-Version-Code" to verCode,
                "Extension-Version-Name" to verName,

                "Extension-Icon-Url" to extIconUrl,
                "Extension-Name" to extName,
                "Extension-Description" to extDescription,

                "Extension-Author" to extAuthor,
                "Extension-Author-Url" to extAuthorUrl,

                "Extension-Repo-Url" to extRepoUrl,
                "Extension-Update-Url" to extUpdateUrl
            )
        )
    }
}

