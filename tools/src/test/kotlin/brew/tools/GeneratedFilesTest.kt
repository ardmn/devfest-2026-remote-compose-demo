package brew.tools

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The files the pipeline writes and the repo commits — backend/documents (what the Go server hands out) and the iOS app's catalog — must be
 * what the current Kotlin renders. When this fails: run `go run ./cmd/demo render` in backend/ and commit.
 */
class GeneratedFilesTest {
    private val stageFile = File("../backend/stage.json")
    private val dir = File("../backend/documents")
    private val fix = "out of date — run `go run ./cmd/demo render` in backend/"

    @Test
    fun `backend documents are up to date`() {
        val docs = Render.all(StageFile.read(stageFile))
        docs.forEach { (path, bytes) ->
            val file = File(dir, Render.fileName(path))
            assertArrayEquals("${file.path} $fix", bytes, file.takeIf { it.isFile }?.readBytes())
        }
        val committed = dir.listFiles { f -> f.extension == "rc" }!!.map { it.name }.sorted()
        assertEquals(
            "backend/documents holds exactly the rendered documents ($fix)",
            docs.keys.map(Render::fileName).sorted(),
            committed,
        )
        val stage = StageFile.read(stageFile)
        assertEquals(
            "manifest.json $fix",
            ServerFiles.manifest(docs, stage.banners.size, stageFile.readBytes()),
            File(dir, "manifest.json").readText(),
        )
        assertEquals(
            "catalog.json $fix",
            ServerFiles.catalog(),
            File(dir, "catalog.json").readText(),
        )
    }

    @Test
    fun `the iOS catalog is up to date`() {
        assertEquals(
            "ios/Brew/Catalog.generated.swift $fix",
            SwiftCatalog.render(),
            File("../ios/Brew/Catalog.generated.swift").readText(),
        )
    }
}
