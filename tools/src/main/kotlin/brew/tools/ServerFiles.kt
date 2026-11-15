package brew.tools

import brew.remote.BrewCatalog
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * What `rc render` hands the Go server, in backend/documents: *.rc every section document, rendered from stage.json manifest.json request
 * path → file, the banner count, and the stage.json it was rendered from (its SHA-256, so the server can say "stage.json changed: run go
 * run ./cmd/demo render") catalog.json the drinks (id, name, emoji, price in cents) — the server checks ids and builds the promo from it
 */
object ServerFiles {
    fun manifest(docs: Map<String, ByteArray>, banners: Int, stageJson: ByteArray): String =
        JSONObject()
            .put("banners", banners)
            .put("stageSha256", sha256(stageJson))
            .put(
                "documents",
                JSONObject().also { o -> docs.keys.forEach { o.put(it, Render.fileName(it)) } },
            )
            .toString(2) + "\n"

    fun catalog(): String =
        JSONObject()
            .put(
                "drinks",
                JSONArray(
                    BrewCatalog.DRINKS.map { d ->
                        JSONObject()
                            .put("id", d.id)
                            .put("name", d.name)
                            .put("emoji", d.emoji)
                            .put("priceCents", kotlin.math.round(d.price * 100f).toInt())
                    }
                ),
            )
            .toString(2) + "\n"

    /** Writes everything into [dir]; .rc files no longer rendered are removed. */
    fun write(dir: File, docs: Map<String, ByteArray>, banners: Int, stageJson: ByteArray) {
        dir.mkdirs()
        dir.listFiles { f -> f.extension == "rc" }?.forEach { it.delete() }
        docs.forEach { (path, bytes) -> File(dir, Render.fileName(path)).writeBytes(bytes) }
        File(dir, "manifest.json").writeText(manifest(docs, banners, stageJson))
        File(dir, "catalog.json").writeText(catalog())
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
