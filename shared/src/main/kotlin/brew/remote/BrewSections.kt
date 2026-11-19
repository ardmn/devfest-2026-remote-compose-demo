package brew.remote

import brew.remote.BrewCatalog.drink

/**
 * The contract between the Brew sections and the apps that host them: the height each section is laid out for and the names of the values
 * it reports. The sections themselves are the @RemoteComposable functions in brew.remote.sections (built from brew.remote.components);
 * turning them into document bytes is the job of brew.capture (on Android, with a Context) or tools/ (on a plain JVM, for the server).
 *
 * Interaction that stays inside a section (radio buttons, toppings, quantity) is document state; anything the app owns (cart,
 * favourites, navigation, filters) is a host action or a named value the app pushes.
 */
object BrewSections {
    /** Width every section is laid out for, in dp (players scale it to the real width). */
    const val WIDTH = 400

    // Heights the apps reserve for each section, in dp (the documents are laid out for them).
    const val HERO_H = 200
    const val CATEGORIES_H = 104
    const val FAVORITES_H = 268
    const val FOR_YOU_H = 150
    /** One product row (RowSection); lists are the app's own, made of rows. */
    const val ROW_H = 104
    const val DETAILS_H = 1010
    const val DETAILS_FOOD_H = 560
    /** The promo card; the backend writes it as JSON (backend/promo.json), not with these composables. */
    const val PROMO_H = 330

    fun detailsHeight(id: String) = if (drink(id).hasOptions) DETAILS_H else DETAILS_FOOD_H

    // Named host actions the product page sends after every change, each with a Float value. The
    // app shows its own
    // price (BrewCatalog.unitCents) from QTY / LARGE / TOPPINGS; TOTAL is the document's own sum,
    // for display checks.
    const val QTY_ACTION = "qty"
    const val LARGE_ACTION = "large"
    /** Selected toppings as a bit mask over BrewCatalog.TOPPINGS (bit k = topping k). */
    const val TOPPINGS_ACTION = "toppings"
}
