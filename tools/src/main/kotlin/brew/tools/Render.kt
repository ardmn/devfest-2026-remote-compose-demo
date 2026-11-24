// The JVM capture. The public capture takes an Android Context. The writer profile without Android is @RestrictTo,
// and since alpha24 the Context-free captureSingleRemoteDocument is `internal` (it was @RestrictTo up to alpha23):
// Kotlin's visibility check is suppressed for it (the compiler warns that such a suppression is unspecified
// behaviour). It compiles to a public static method, so it links and runs; a release that renames or removes it
// fails this build (and RenderTest), not silently. The sections themselves stay public-API only.
@file:Suppress("RestrictedApi", "INVISIBLE_REFERENCE")

package brew.tools

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RcProfiles
import androidx.compose.remote.creation.JvmRcPlatformServices
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensityBehavior
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.runtime.Composable
import brew.remote.BrewCatalog
import brew.remote.BrewCatalog.Stage
import brew.remote.BrewSections
import brew.remote.sections.CategoriesSection
import brew.remote.sections.DetailsSection
import brew.remote.sections.FavoritesSection
import brew.remote.sections.ForYouSection
import brew.remote.sections.HeroSection
import brew.remote.sections.RowSection
import kotlinx.coroutines.runBlocking

/**
 * Turns the Brew sections (shared/…/brew/remote/sections, @RemoteComposable) into Remote Compose documents on a plain JVM. The output is
 * byte for byte what the Android app's own capture (brew.capture, with a Context) writes.
 */
object Render {
    /** For the androidx player (the operations it supports), written by the platform-free writer. */
    private val profile =
        Profile(
            CoreDocument.DOCUMENT_API_LEVEL,
            RcProfiles.PROFILE_ANDROIDX,
            JvmRcPlatformServices(),
        ) { info, p, callback ->
            RemoteComposeWriter(info, null, p, callback)
        }

    /** One section laid out for [BrewSections.WIDTH] x [heightDp] dp (sizes in dp: players scale to the device). */
    fun section(heightDp: Int, content: @Composable @RemoteComposable () -> Unit): ByteArray = runBlocking {
        captureSingleRemoteDocument(
            creationDisplayInfo =
                RemoteCreationDisplayInfo(
                    width = BrewSections.WIDTH,
                    height = heightDp,
                    densityDpi = 160,
                    densityBehavior = RemoteDensityBehavior.Dp,
                ),
            profile = profile,
            content = content,
        )
    }

    /**
     * Every document the server hands out, by the path the apps ask for ("hero?i=0", "details?id=caramel", …). All of them are fixed for a
     * given stage.json: lists are the apps' own, made of rows.
     */
    fun all(stage: Stage): Map<String, ByteArray> = buildMap {
        stage.banners.indices.forEach { i -> put("hero?i=$i", section(BrewSections.HERO_H) { HeroSection(stage, i) }) }
        put("categories", section(BrewSections.CATEGORIES_H) { CategoriesSection() })
        put("favorites", section(BrewSections.FAVORITES_H) { FavoritesSection(stage) })
        put("for-you", section(BrewSections.FOR_YOU_H) { ForYouSection(stage) })
        BrewCatalog.DRINKS.forEach { d ->
            put(
                "details?id=${d.id}",
                section(BrewSections.detailsHeight(d.id)) { DetailsSection(d.id) },
            )
            put("row?id=${d.id}", section(BrewSections.ROW_H) { RowSection(d.id) })
        }
    }

    /** "hero?i=0" -> "hero_i-0.rc": the file name on disk (the same one the iOS app looks up in its bundle). */
    fun fileName(path: String) = path.replace("?", "_").replace("=", "-").replace(",", "+") + ".rc"
}
