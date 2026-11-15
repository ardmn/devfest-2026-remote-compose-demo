// RemoteComposeJsonParser is @RestrictTo in alpha23.
@file:Suppress("RestrictedApi")

package brew.tools

import androidx.compose.remote.creation.json.RemoteComposeJsonParser
import brew.document.DocumentValidator

/**
 * A document's JSON representation → the binary .rc the players read. Remote Compose has a JSON form of a document; any backend can build
 * or edit that JSON (the Go server does, for the promo), and this one call turns it into bytes. There is no converter the other way (binary
 * → JSON) in alpha23.
 */
object JsonDocument {
    /** @throws IllegalArgumentException when the JSON is not a document, or converts to one that does not validate */
    fun toRc(json: String): ByteArray {
        val bytes =
            try {
                val buffer = RemoteComposeJsonParser.parseToByteBuffer(json)
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "not a valid Remote Compose JSON document: " + "${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }
        try {
            DocumentValidator.validate(bytes)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "the converted document does not validate: ${e.message}",
                e,
            )
        }
        return bytes
    }
}
