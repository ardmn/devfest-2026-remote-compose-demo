package brew.tools

import brew.document.DocumentValidator
import java.io.File
import java.nio.charset.Charset
import kotlin.system.exitProcess

private const val USAGE =
    """rc — Remote Compose documents for the Brew demo, on a plain JVM.

  rc render --stage <stage.json> --out <dir> [--swift <Catalog.generated.swift>]
      Renders every @RemoteComposable section for that stage.json into <dir>
      (*.rc, manifest.json, catalog.json) and, with --swift, the iOS app's generated catalog.
  rc json2rc
      Reads a document's JSON representation on stdin, writes the binary .rc to stdout (validated).
  rc validate <file.rc>...
      Checks documents with the validator the Android app runs."""

fun main(args: Array<String>) {
    // See build.gradle.kts: with another default charset the library writes "?" for every non-ASCII character.
    if (Charset.defaultCharset() != Charsets.UTF_8) {
        fail(
            "rc: the JVM's default charset is ${Charset.defaultCharset()}, not UTF-8 — run it with -Dfile.encoding=UTF-8 (build/install/rc/bin/rc does)"
        )
    }
    try {
        when (args.firstOrNull()) {
            "render" -> render(options(args.drop(1)))
            "json2rc" -> json2rc()
            "validate" -> validate(args.drop(1))
            else -> fail(USAGE)
        }
    } catch (e: IllegalArgumentException) {
        fail("rc ${args.firstOrNull().orEmpty()}: ${e.message}")
    }
    // Compose's coroutines may leave non-daemon threads behind: a command-line tool ends when its
    // work is done.
    exitProcess(0)
}

private fun render(o: Map<String, String>) {
    val stageFile = File(o["stage"] ?: fail("render needs --stage <stage.json>"))
    val out = File(o["out"] ?: fail("render needs --out <dir>"))
    require(stageFile.isFile) { "no stage file at ${stageFile.path}" }
    val stage = StageFile.read(stageFile)
    val docs = Render.all(stage)
    docs.forEach { (path, bytes) -> DocumentValidator.validate(bytes).also { check(it.operations.isNotEmpty()) { path } } }
    ServerFiles.write(out, docs, stage.banners.size, stageFile.readBytes())
    System.err.println("rc: ${docs.size} documents from ${stageFile.path} -> ${out.path}")
    o["swift"]?.let {
        File(it).writeText(SwiftCatalog.render())
        System.err.println("rc: iOS catalog -> $it")
    }
}

/** stdin JSON → stdout .rc. The Go server runs this for the JSON-built promo. */
private fun json2rc() {
    val bytes = JsonDocument.toRc(System.`in`.readBytes().decodeToString())
    System.out.write(bytes)
    System.out.flush()
}

private fun validate(files: List<String>) {
    if (files.isEmpty()) fail("validate needs at least one .rc file")
    var bad = 0
    files.forEach { f ->
        runCatching { DocumentValidator.validate(File(f).readBytes()) }
            .onSuccess { System.err.println("ok   $f") }
            .onFailure {
                bad++
                System.err.println("FAIL $f: ${it.message}")
            }
    }
    if (bad > 0) exitProcess(1)
}

private fun options(args: List<String>): Map<String, String> =
    args.chunked(2).associate { pair ->
        require(pair.size == 2 && pair[0].startsWith("--")) { "expected --name value, got ${pair.joinToString(" ")}\n\n$USAGE" }
        pair[0].removePrefix("--") to pair[1]
    }

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
