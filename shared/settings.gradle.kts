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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // One version catalog for all three Gradle builds of the repo (android, backend, shared).
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

// Not opened on its own: android/ and backend/ include it (includeBuild("../shared")) and depend on
// "brew:shared".
rootProject.name = "shared"

// A fresh clone has no local.properties (it is per machine): find the Android SDK like Android
// Studio would —
// ANDROID_HOME, else the usual install place — and write it, so the project builds without any
// setup.
file("local.properties")
    .takeIf { !it.exists() }
    ?.let { lp ->
        val home = System.getProperty("user.home")
        listOfNotNull(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                "$home/Library/Android/sdk",
                "$home/Android/Sdk",
                System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" },
            )
            .map(::File)
            .firstOrNull { File(it, "platforms").isDirectory }
            ?.let { lp.writeText("sdk.dir=${it.invariantSeparatorsPath}\n") }
    }
