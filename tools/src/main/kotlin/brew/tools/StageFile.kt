package brew.tools

import brew.remote.BrewCatalog
import brew.remote.BrewCatalog.Banner
import brew.remote.BrewCatalog.Stage
import java.io.File
import org.json.JSONObject

/** backend/stage.json → the Stage the sections are rendered for. Missing keys keep the defaults; mistakes fail. */
object StageFile {
    fun read(file: File): Stage {
        val d = Stage()
        if (!file.isFile) return d
        val o = JSONObject(file.readText())
        fun ids(key: String, def: List<String>): List<String> {
            val a = o.optJSONArray(key) ?: return def
            return List(a.length()) { a.getString(it) }
                .onEach { require(BrewCatalog.find(it) != null) { "$file: unknown drink \"$it\" in \"$key\"" } }
        }
        val banners =
            o.optJSONArray("banners")
                ?.let { a ->
                    List(a.length()) { k ->
                        val b = a.getJSONObject(k)
                        val def = d.banners.getOrElse(k) { d.banners[0] }
                        Banner(
                            headline = b.optString("headline", def.headline),
                            cta = b.optString("cta", def.cta),
                            color = b.optString("color", "").takeIf { it.isNotEmpty() }?.let { color(it, file) } ?: def.color,
                            emoji = b.optString("emoji", def.emoji),
                            target =
                                b.optString("target", def.target).also {
                                    require(BrewCatalog.find(it) != null) { "$file: banner $k opens unknown drink \"$it\"" }
                                },
                        )
                    }
                }
                ?.takeIf { it.isNotEmpty() } ?: d.banners
        return Stage(
            banners = banners,
            favorites = ids("favorites", d.favorites),
            forYou = ids("forYou", d.forYou),
        )
    }

    /** "#RRGGBB" or "#AARRGGBB" → ARGB. */
    private fun color(s: String, file: File): Int {
        val hex = s.removePrefix("#")
        require(hex.length == 6 || hex.length == 8) { "$file: colour \"$s\" is not #RRGGBB or #AARRGGBB" }
        val v = hex.toLong(16)
        return (if (hex.length == 6) v or 0xFF000000 else v).toInt()
    }
}
