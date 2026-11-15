package brew.tools

import brew.document.DocumentValidator
import brew.document.InvalidDocumentException
import brew.remote.BrewCatalog
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validator (shared/…/brew/document, run by the Android app on every download) stands between the network and the player, because the
 * library itself accepts a truncated document at any length (rechecked on alpha23). Real section documents, rendered the way the server's
 * are.
 */
class DocumentValidatorTest {
    private val docs =
        Render.all(BrewCatalog.Stage()).let { all ->
            mapOf(
                "hero" to all.getValue("hero?i=0"),
                "favorites" to all.getValue("favorites"),
                "details" to all.getValue("details?id=caramel"),
                "row" to all.getValue("row?id=beans"),
            )
        }

    @Test
    fun `whole documents pass`() {
        for ((name, bytes) in docs) {
            val doc = DocumentValidator.validate(bytes)
            assertTrue("$name has operations", doc.operations.isNotEmpty())
        }
    }

    @Test
    fun `a document cut anywhere is rejected`() {
        var structural = 0
        for ((name, bytes) in docs) {
            for (keep in
                listOf(
                    bytes.size - 1,
                    bytes.size - 8,
                    bytes.size * 3 / 4,
                    bytes.size / 2,
                    bytes.size / 10,
                    1,
                )) {
                val e =
                    assertThrows(
                        "$name cut to $keep of ${bytes.size} bytes",
                        InvalidDocumentException::class.java,
                    ) {
                        DocumentValidator.validate(bytes.copyOf(keep))
                    }
                // A cut inside an operation fails the parser; a cut between operations parses and
                // must be caught
                // by the structural checks (open/close balance, nothing-to-draw).
                val m = e.message!!
                assertTrue(
                    "$name/$keep: $m",
                    m.startsWith("truncated") || m.startsWith("header only") || m.startsWith("cannot parse"),
                )
                if (!m.startsWith("cannot parse")) structural++
            }
        }
        // Without the structural checks most cuts would get through: they must catch the majority
        // here.
        assertTrue("structural checks caught only $structural cuts", structural >= docs.size * 4)
    }

    @Test
    fun `empty and foreign bytes are rejected`() {
        val empty = assertThrows(InvalidDocumentException::class.java) { DocumentValidator.validate(ByteArray(0)) }
        assertEquals("empty body", empty.message)
        // An error page instead of a document: rejected by the first-byte check, before any
        // parsing.
        val html = assertThrows(InvalidDocumentException::class.java) { DocumentValidator.validate("<html>oops</html>".toByteArray()) }
        assertTrue(html.message, html.message!!.startsWith("not a Remote Compose document"))
    }

    @Test
    fun `a body that does not match the published hash is rejected`() {
        val bytes = docs.getValue("hero")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        DocumentValidator.validate(bytes, sha256 = sha) // matches
        val other = docs.getValue("favorites")
        val e = assertThrows(InvalidDocumentException::class.java) { DocumentValidator.validate(other, sha256 = sha) }
        assertTrue(e.message!!.startsWith("sha256 mismatch"))
        val etag = "\"${sha.take(32)}\""
        DocumentValidator.validate(
            bytes,
            etag = etag,
        ) // a hex etag is checked as a prefix of the SHA-256
        val bad = assertThrows(InvalidDocumentException::class.java) { DocumentValidator.validate(other, etag = etag) }
        assertTrue(
            bad.message,
            bad.message!!.startsWith("content hash does not match manifest etag"),
        )
    }

    @Test
    fun `the library alone would have accepted the cut documents`() {
        // The reason the validator exists: decode a cut document straight with the library — no
        // exception.
        val bytes = docs.getValue("details")
        val cut = bytes.copyOf(bytes.size / 2)
        val doc =
            androidx.compose.remote.core.CoreDocument().apply {
                initFromBuffer(androidx.compose.remote.core.RemoteComposeBuffer.fromInputStream(cut.inputStream()))
            }
        assertEquals(
            true,
            doc.canBeDisplayed(
                androidx.compose.remote.core.CoreDocument.MAJOR_VERSION,
                androidx.compose.remote.core.CoreDocument.MINOR_VERSION,
                0L,
            ),
        )
    }
}
