// Used only by ci/check.py osv: `./gradlew --init-script <this> -PosvLockDir=<dir> --write-locks osvLocks`.
// Configuration-cache safe.
//
// Turns on dependency locking for the configurations whose dependencies end up in what this repo ships, and only
// those: the Android app's runtime classpaths (which include :shared's dependencies, a composite build) and the rc
// tool's runtime (its runtime classpath, plus the remote-creation-compose AAR it unpacks). Build tools resolved into
// their own configurations (Android Lint, ktfmt, the Kotlin compiler) are not locked, so OSV-Scanner never sees them.
// The gradle.lockfile files go to <osvLockDir>/<build>[-<project>]/, outside the repo; check.py passes each one to
// OSV-Scanner and deletes the directory. A configuration listed here that does not exist fails the task.
val shipped =
    mapOf(
        "brew-android" to mapOf(":app" to listOf("debugRuntimeClasspath", "releaseRuntimeClasspath")),
        "rc" to mapOf(":" to listOf("runtimeClasspath", "aar")),
    )

allprojects {
    val names = shipped[rootProject.name]?.get(path) ?: return@allprojects
    // Read here, not at the top: the init script also runs for the included build (shared), which does not get -P.
    val lockDir =
        gradle.startParameter.projectProperties["osvLockDir"]
            ?: error("dependency-locks.init.gradle.kts: pass -PosvLockDir=<directory> (ci/check.py osv does)")
    val dir = rootProject.name + if (path == ":") "" else path.replace(':', '-')
    dependencyLocking { lockFile.set(File(lockDir, "$dir/gradle.lockfile")) }
    configurations.matching { it.name in names }.configureEach { resolutionStrategy.activateDependencyLocking() }
    // Resolving the graphs (no artifacts downloaded) is what writes the locks under --write-locks. The task gets them
    // as providers when it is configured and never touches the project while it runs (Task.project at execution time
    // is deprecated and breaks the configuration cache).
    tasks.register("osvLocks") {
        val graphs = names.map { configurations.getByName(it).incoming.resolutionResult.rootComponent }
        doLast { graphs.forEach { it.get() } }
    }
}
