// The Remote Compose player/core APIs are @RestrictTo(LIBRARY_GROUP) in 1.0.0-alpha23: there is no
// public way to decode or play a document yet. Suppressing the lint check is deliberate for this
// sample.
@file:Suppress("RestrictedApiAndroidX", "RestrictedApi")

package brew.document

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.Operations
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.layout.Container
import androidx.compose.remote.core.operations.layout.ContainerEnd
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class InvalidDocumentException(message: String) : Exception(message)

/**
 * Checks bytes BEFORE they reach a player. From alpha19 through alpha23 (rechecked on alpha23, 2026-11-15) the library alone accepts
 * truncated input without complaint: CoreDocument.initFromBuffer returns normally for a document cut at any length (even 0 bytes),
 * canBeDisplayed() stays true, and the player draws whatever prefix survived.
 */
object DocumentValidator {

    /**
     * @param sha256 full hex SHA-256 from the manifest, if the server published one
     * @param etag the manifest's etag for a static document; when it looks like hex (a prefix of the SHA-256), it is checked as a prefix
     */
    fun validate(bytes: ByteArray, sha256: String? = null, etag: String? = null): CoreDocument {
        if (bytes.isEmpty()) throw InvalidDocumentException("empty body")
        if (sha256 != null || etag != null) {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (sha256 != null && !actual.equals(sha256, ignoreCase = true)) {
                throw InvalidDocumentException("sha256 mismatch: manifest ${sha256.take(12)}…, body ${actual.take(12)}…")
            }
            val hexTag =
                etag?.trim('"')?.removeSuffix("-gzip")?.takeIf {
                    it.length >= 16 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }
                }
            if (hexTag != null && !actual.startsWith(hexTag.lowercase())) {
                throw InvalidDocumentException(
                    "content hash does not match manifest etag ${hexTag.take(12)}… " + "(body ${actual.take(12)}…)"
                )
            }
        }
        if ((bytes[0].toInt() and 0xFF) != Operations.HEADER) {
            throw InvalidDocumentException("not a Remote Compose document (first byte 0x%02x, expected HEADER op)".format(bytes[0]))
        }

        // 1) Flat pass over the wire operations: every container that is opened must be closed.
        //    A body cut anywhere inside the layout tree leaves unclosed containers.
        val flat = ArrayList<Operation>()
        try {
            RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(bytes)).inflateFromBuffer(flat)
        } catch (t: Throwable) {
            throw InvalidDocumentException("cannot parse operations: ${t.javaClass.simpleName}: ${t.message}")
        }
        val opened = flat.count { it is Container }
        val closed = flat.count { it is ContainerEnd }
        if (opened != closed)
            throw InvalidDocumentException(
                "truncated: $opened containers opened, $closed closed " + "(${flat.size} ops, ${bytes.size} bytes)"
            )

        // 2) The real decode, the same call the player makes.
        val doc =
            try {
                CoreDocument().apply { initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(bytes))) }
            } catch (t: Throwable) {
                throw InvalidDocumentException("decode failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        if (!doc.canBeDisplayed(CoreDocument.MAJOR_VERSION, CoreDocument.MINOR_VERSION, 0L)) {
            throw InvalidDocumentException(
                "document version is newer than this player " + "(${CoreDocument.MAJOR_VERSION}.${CoreDocument.MINOR_VERSION})"
            )
        }
        if (doc.operations.none { it !is Header }) {
            throw InvalidDocumentException("header only, nothing to draw (${doc.operations.size} top-level ops)")
        }
        return doc
    }
}
