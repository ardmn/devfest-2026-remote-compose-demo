plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.detekt)
}

// rc — the Kotlin half of the server pipeline. A plain JVM program: no Android SDK, no emulator, no
// Robolectric.
//   rc render    the @RemoteComposable sections → .rc documents for the Go server (and the iOS
// app's copies)
//   rc json2rc   a document's JSON representation → the binary .rc (the Go server calls it for the
// promo)
//   rc validate  checks .rc files with the same validator the Android app runs
// The sections are not copied: this build compiles the very same sources as the Android app
// (shared/…/brew/remote).

kotlin { jvmToolchain(17) }

// Remote Compose's JSON parser encodes text with the JVM's default charset: UTF-8 on macOS, but ASCII in a Linux
// container without a locale and cp1252 on many Windows machines — "−" and "→" became "?". Always UTF-8.
val utf8 = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")

application {
    mainClass.set("brew.tools.MainKt")
    applicationName = "rc"
    applicationDefaultJvmArgs = utf8
}

sourceSets.main {
    kotlin.srcDir("../shared/src/main/kotlin")
    // From shared: only the platform-free parts. brew.capture (Android Context) and the Lab's
    // documents (Android
    // Canvas) stay Android-only.
    kotlin.include(
        "brew/tools/**",
        "brew/remote/**",
        "brew/document/**",
        "brew/lab/LabCards.kt",
        "brew/lab/LabShaders.kt",
    )
}

// remote-creation-compose (the @RemoteComposable API) is Kotlin Multiplatform since alpha24, but its JVM build is the
// common part only: the Compose UI interop the sections use (Color.rc, ImageVector icons) is in the
// Android build alone. The code path that writes a document needs no Android: take the classes out of the Android
// AAR (remote-creation-compose-android) and run them on the JVM. Its dependencies are listed below, with Compose's
// JVM build in place of the Android one.
val aar: Configuration by configurations.creating { isTransitive = false }

interface Archives {
    @get:Inject val ops: ArchiveOperations
}

val unpackAar by
    tasks.registering(Copy::class) {
        val archives = objects.newInstance<Archives>().ops
        from(aar.elements.map { files -> files.map { archives.zipTree(it) } }) { include("classes.jar") }
        rename { "remote-creation-compose.jar" }
        into(layout.buildDirectory.dir("aar"))
    }

dependencies {
    aar("androidx.compose.remote:remote-creation-compose-android:${libs.versions.remoteCompose.get()}@aar")
    implementation(files(unpackAar.map { it.destinationDir.resolve("remote-creation-compose.jar") }))

    // remote-creation-compose-android's own dependencies (from its .module), JVM builds. Omitted: tracing,
    // lifecycle-viewmodel and core-ktx, used only by the Context capture this JVM path never calls. remote-core and
    // remote-creation-core come with remote-creation; listed because the code here uses them directly.
    implementation(libs.androidx.collection)
    implementation(libs.remote.core)
    implementation(libs.remote.creation)
    implementation(libs.remote.creation.core)
    implementation(libs.remote.creation.expression)
    implementation(libs.jb.compose.runtime)
    implementation(libs.jb.compose.foundation)
    implementation(libs.jb.compose.ui)
    implementation(libs.jb.compose.ui.text)
    implementation(libs.jb.compose.material.icons)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.org.json)

    testImplementation(libs.junit)
}

// ./gradlew render (gradlew.bat render on Windows): the Kotlin half of go run ./cmd/demo render (backend/), without the iOS step.
tasks.register<JavaExec>("render") {
    group = "brew"
    description = "Renders the sections for backend/stage.json into backend/documents and writes the iOS app's catalog."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("brew.tools.MainKt")
    jvmArgs(utf8)
    val repo = rootDir.parentFile
    args(
        "render",
        "--stage",
        repo.resolve("backend/stage.json").path,
        "--out",
        repo.resolve("backend/documents").path,
        "--swift",
        repo.resolve("ios/Brew/Catalog.generated.swift").path,
    )
}

tasks.test {
    jvmArgs(utf8)
    // The tests compare the committed files (backend/documents, the iOS catalog) with a fresh
    // render.
    workingDir = projectDir
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// kotlinlang style (4-space indent), 140 columns. ./gradlew ktfmtFormat formats, ktfmtCheck checks.
ktfmt {
    kotlinLangStyle()
    maxWidth.set(140)
}

// detekt: the same setup for every Kotlin module (config/detekt.gradle). ./gradlew detekt checks,
// detektBaseline rewrites this module's detekt-baseline.xml (on purpose only; review its diff).
apply(from = rootProject.file("../config/detekt.gradle"))
