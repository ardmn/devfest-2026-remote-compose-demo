plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktfmt)
}

// The Remote Compose UI of the Brew demo, shared by the backend (which captures it into documents)
// and the Android
// app (which captures the same sections on the device when the server is unreachable).
//   brew.remote  — the sections and their building blocks, public @RemoteComposable API only, no
// android.*
//   brew.capture — turns a section into document bytes (needs an Android Context)
//   brew.lab     — GPU shader documents; shaders have no public authoring API in alpha23 (see
// lint.xml)
// Consumed as "brew:shared" by the android and backend builds (composite build).
group = "brew"

android {
    namespace = "brew.shared"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // RestrictedApi is an error: the sections must stay on the public Remote Compose API...
        error += "RestrictedApi"
        // Every other warning fails too (there are none today); new ones would go to a lint-baseline.xml only on
        // purpose. "A newer version is available" depends on the day the build runs, not on the code.
        warningsAsErrors = true
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
        // ...except brew.lab, the shader showcase (lint.xml).
        lintConfig = file("lint.xml")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.remote.core)
    api(libs.remote.creation)
    api(libs.remote.creation.core)
    // The public authoring API: @RemoteComposable composables, captured into a document.
    api(libs.remote.creation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons)
}

// kotlinlang style (4-space indent), 140 columns. ./gradlew ktfmtFormat formats, ktfmtCheck checks.
ktfmt {
    kotlinLangStyle()
    maxWidth.set(140)
}
