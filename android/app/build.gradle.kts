plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.detekt)
}

// Brew for Android: a regular Compose app (tabs, cart, pull to refresh, snackbar) whose content sections are Remote
// Compose documents from the backend. Offline it captures the same sections on the device (from :shared).
android {
    namespace = "brew.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "brew.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        // `adb reverse tcp:8080 tcp:8080` (go run ./cmd/demo serve in backend/ does it) makes the laptop reachable as 127.0.0.1 on the
        // device.
        buildConfigField("String", "BASE_URL", "\"${providers.gradleProperty("brew.baseUrl").getOrElse("http://127.0.0.1:8080")}\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint {
        // Every lint warning fails the build, except the ones recorded in lint-baseline.xml when this was turned on
        // (regenerate on purpose only: ./gradlew :app:updateLintBaseline, then review the diff).
        warningsAsErrors = true
        baseline = file("lint-baseline.xml")
    }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/{AL2.0,LGPL2.1}") }
}

// Lint checks that can fail with no code change (the date, the network, the installed SDK) are off: the one list for app
// and shared, with the reasons, is config/lint-volatile-checks.txt.
apply(from = rootProject.file("../config/lint-volatile-checks.gradle"))

kotlin { jvmToolchain(17) }

dependencies {
    implementation("brew:shared")
    implementation(libs.remote.player.core)
    implementation(libs.remote.player.view)
    implementation(libs.remote.player.compose)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.okhttp)
}

// kotlinlang style (4-space indent), 140 columns. ./gradlew ktfmtFormat formats, ktfmtCheck checks.
ktfmt {
    kotlinLangStyle()
    maxWidth.set(140)
}

// detekt: the same setup for every Kotlin module (config/detekt.gradle). ./gradlew :app:detekt checks,
// :app:detektBaseline rewrites this module's detekt-baseline.xml (on purpose only; review its diff).
apply(from = rootProject.file("../config/detekt.gradle"))
