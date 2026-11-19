package brew.capture

import android.content.Context
import brew.remote.BrewCatalog.Stage
import brew.remote.BrewSections
import brew.remote.sections.CategoriesSection
import brew.remote.sections.DetailsSection
import brew.remote.sections.FavoritesSection
import brew.remote.sections.ForYouSection
import brew.remote.sections.HeroSection
import brew.remote.sections.RowSection

/**
 * Captures each Brew section (brew.remote.sections) into the Remote Compose document the server sends and the Android and iOS apps play.
 * Capturing needs an Android Context (captureSingleRemoteDocument resolves resources and density with it), so it lives here and not next to
 * the sections.
 */
object BrewDocuments {
    /** Banner [i] of the top carousel; the app pages through them (native pager, dots, auto-advance). */
    suspend fun hero(context: Context, s: Stage = Stage(), i: Int = 0): ByteArray =
        captureSection(context = context, heightDp = BrewSections.HERO_H) { HeroSection(s = s, i = i) }

    /** Category circles: shortcuts the app turns into the Menu tab's filter ("category:<key>"). */
    suspend fun categories(context: Context): ByteArray =
        captureSection(context = context, heightDp = BrewSections.CATEGORIES_H) { CategoriesSection() }

    /** Two featured product cards (heart, "+", open). */
    suspend fun favorites(context: Context, s: Stage = Stage()): ByteArray =
        captureSection(context = context, heightDp = BrewSections.FAVORITES_H) { FavoritesSection(s = s) }

    /** Three small recommended products. */
    suspend fun forYou(context: Context, s: Stage = Stage()): ByteArray =
        captureSection(context = context, heightDp = BrewSections.FOR_YOU_H) { ForYouSection(s = s) }

    /** One product row; the app stacks rows into its lists (the Menu filter, its favourites). */
    suspend fun row(context: Context, id: String): ByteArray =
        captureSection(context = context, heightDp = BrewSections.ROW_H) { RowSection(id = id) }

    /** The whole product page: header, options and quantity; the document reports the choice, the app prices it. */
    suspend fun details(context: Context, id: String): ByteArray =
        captureSection(context = context, heightDp = BrewSections.detailsHeight(id)) { DetailsSection(id = id) }
}
