package brew.tools

import brew.document.DocumentValidator
import brew.lab.LabCards
import brew.lab.LabShaders
import brew.remote.BrewCatalog
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderTest {
    @Test
    fun `every section renders on a plain JVM and validates`() {
        val docs = Render.all(BrewCatalog.Stage())
        // 2 banners, categories, favorites, for-you, and a product page and a list row per drink.
        assertEquals(5 + 2 * BrewCatalog.DRINKS.size, docs.size)
        docs.forEach { (path, bytes) -> assertTrue(path, DocumentValidator.validate(bytes).operations.isNotEmpty()) }
    }

    @Test
    fun `rendering is deterministic`() {
        // Committed documents only change when the UI does: two renders give the same bytes.
        val a = Render.all(BrewCatalog.Stage())
        val b = Render.all(BrewCatalog.Stage())
        a.forEach { (path, bytes) -> assertArrayEquals(path, bytes, b[path]) }
    }

    @Test
    fun `a mistake in stage json fails the render, not the app`() {
        val f = File.createTempFile("stage", ".json").apply { deleteOnExit() }
        f.writeText("""{ "favorites": ["americano", "espresso-tonic"] }""")
        val e = assertThrows(IllegalArgumentException::class.java) { StageFile.read(f) }
        assertTrue(e.message, "espresso-tonic" in e.message!!)
        f.writeText("""{ "banners": [{ "color": "brown" }] }""")
        assertThrows(IllegalArgumentException::class.java) { StageFile.read(f) }
    }

    @Test
    fun `every shader fits the player's 4000-byte string limit`() {
        LabShaders.ALL.forEach { assertTrue("${it.length} bytes", it.encodeToByteArray().size < 4000) }
        assertEquals(LabShaders.ALL.size, LabCards.all.size)
    }
}
