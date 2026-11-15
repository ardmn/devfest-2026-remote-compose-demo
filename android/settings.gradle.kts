pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download the JDK 17 toolchain (kotlin { jvmToolchain(17) }) when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // One version catalog for all three Gradle builds of the repo (android, backend, shared).
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "brew-android"
// The Remote Compose UI shared with the backend: the app uses it to build the sections itself when offline.
includeBuild("../shared")
include(":app")

// A fresh clone has no local.properties (it is per machine): find the Android SDK like Android Studio would —
// ANDROID_HOME, else the usual install place — and write it, so the project builds without any setup.
file("local.properties").takeIf { !it.exists() }?.let { lp ->
    val home = System.getProperty("user.home")
    listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
        "$home/Library/Android/sdk", "$home/Android/Sdk", System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" })
        .map(::File).firstOrNull { File(it, "platforms").isDirectory }
        ?.let { lp.writeText("sdk.dir=${it.invariantSeparatorsPath}\n") }
}
