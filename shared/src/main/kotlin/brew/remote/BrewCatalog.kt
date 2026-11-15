package brew.remote

import brew.remote.BrewTheme.TAN

/** What Brew sells and what the stage server can change: products, categories, banners, toppings, prices. */
object BrewCatalog {
    class Drink(
        val id: String,
        val name: String,
        val desc: String,
        val price: Float,
        val emoji: String,
        val tile: Int,
        val category: String = "coffee",
    ) {
        /** Drinks have sugar / size / ice / toppings on their page; cakes and beans do not. */
        val hasOptions
            get() = category == "coffee" || category == "tea" || category == "bottled"
    }

    val DRINKS =
        listOf(
            Drink(
                id = "americano",
                name = "Americano",
                desc = "Espresso and hot water",
                price = 3.5f,
                emoji = "☕",
                tile = 0xFFF7F1EC.toInt(),
            ),
            Drink(
                id = "caramel",
                name = "Caramel Macchiato",
                desc = "Espresso, steamed milk and caramel sauce",
                price = 4.8f,
                emoji = "🧋",
                tile = 0xFFFBF1E4.toInt(),
            ),
            Drink(
                id = "cookie",
                name = "Cookie Latte",
                desc = "Espresso, milk, vanilla and cookies",
                price = 5.2f,
                emoji = "🥤",
                tile = 0xFFF1ECEA.toInt(),
            ),
            Drink(
                id = "matcha",
                name = "Matcha Latte",
                desc = "Ceremonial matcha and oat milk",
                price = 4.6f,
                emoji = "🍵",
                tile = 0xFFEEF4E8.toInt(),
                category = "tea",
            ),
            Drink(
                id = "mocha",
                name = "Iced Mocha",
                desc = "Espresso, chocolate and cold milk",
                price = 4.9f,
                emoji = "🍫",
                tile = 0xFFF4ECE6.toInt(),
            ),
            Drink(
                id = "chai",
                name = "Chai Latte",
                desc = "Black tea, cinnamon, cardamom and milk",
                price = 4.2f,
                emoji = "🫖",
                tile = 0xFFF6EEE4.toInt(),
                category = "tea",
            ),
            Drink(
                id = "cheesecake",
                name = "Cheesecake",
                desc = "New York style, berry sauce",
                price = 5.5f,
                emoji = "🍰",
                tile = 0xFFFBEFF0.toInt(),
                category = "cake",
            ),
            Drink(
                id = "croissant",
                name = "Butter Croissant",
                desc = "Baked this morning",
                price = 3.2f,
                emoji = "🥐",
                tile = 0xFFFBF3E3.toInt(),
                category = "cake",
            ),
            Drink(
                id = "coldbrew",
                name = "Cold Brew Bottle",
                desc = "18-hour steep, 330 ml",
                price = 4.4f,
                emoji = "🧃",
                tile = 0xFFEFF3EA.toInt(),
                category = "bottled",
            ),
            Drink(
                id = "beans",
                name = "House Blend Beans",
                desc = "Brazil and Ethiopia, 250 g",
                price = 12.0f,
                emoji = "🫘",
                tile = 0xFFF3ECE8.toInt(),
                category = "beans",
            ),
        )

    /** For the sections, which only ever use known ids. */
    fun drink(id: String) = find(id) ?: DRINKS[0]

    /** For ids that come from a document (host actions): null when unknown — the app ignores the action. */
    fun find(id: String): Drink? = DRINKS.firstOrNull { it.id == id }

    fun isCategory(key: String) = CATEGORIES.any { it.key == key }

    /** Extra cost of the large size, in cents. */
    const val LARGE_CENTS = 60

    /**
     * The price of one item, in cents, worked out by the app from the catalog and the options the product page reported — never taken from
     * the document (a document is untrusted input). [toppings] is a bit mask over [TOPPINGS]. Null for an unknown drink.
     */
    fun unitCents(id: String, large: Boolean, toppings: Int): Int? {
        val d = find(id) ?: return null
        if (!d.hasOptions) return cents(d.price)
        val extras = TOPPINGS.indices.filter { toppings and (1 shl it) != 0 }.sumOf { cents(TOPPINGS[it].price) }
        return cents(d.price) + (if (large) LARGE_CENTS else 0) + extras
    }

    private fun cents(p: Float) = kotlin.math.round(p * 100f).toInt()

    /** One banner of the top carousel. [target] is the drink its button opens. */
    class Banner(
        val headline: String,
        val cta: String,
        val color: Int,
        val emoji: String,
        val target: String,
    )

    /** What the server lets you change live on stage (stage.json): no app release involved. */
    class Stage(
        val banners: List<Banner> =
            listOf(
                Banner(
                    headline = "Coffee\ntime?",
                    cta = "ORDER NOW",
                    color = TAN,
                    emoji = "☕",
                    target = "americano",
                ),
                Banner(
                    headline = "Matcha\nseason",
                    cta = "TRY IT",
                    color = 0xFFA8C3A0.toInt(),
                    emoji = "🍵",
                    target = "matcha",
                ),
            ),
        val favorites: List<String> = listOf("americano", "caramel"),
        val forYou: List<String> = listOf("cookie", "matcha", "mocha"),
    )

    /** A menu category; [key] is what the app filters by. */
    data class Category(val key: String, val emoji: String, val label: String)

    val CATEGORIES =
        listOf(
            Category(key = "coffee", emoji = "☕", label = "Coffee"),
            Category(key = "tea", emoji = "🍵", label = "Tea"),
            Category(key = "cake", emoji = "🥐", label = "Bakery"),
            Category(key = "bottled", emoji = "🧃", label = "Bottled"),
            Category(key = "beans", emoji = "🫘", label = "Beans"),
        )

    class Topping(val emoji: String, val name: String, val price: Float)

    val TOPPINGS =
        listOf(
            Topping(emoji = "☕", name = "Espresso", price = 0.8f),
            Topping(emoji = "🍯", name = "Honey", price = 0.5f),
            Topping(emoji = "⚫", name = "Boba", price = 0.7f),
            Topping(emoji = "🍮", name = "Caramel", price = 0.5f),
            Topping(emoji = "🍪", name = "Cookie", price = 0.7f),
        )

    /** "$3.50", in plain Kotlin (no String.format, no Locale: the same output on every device and in the backend). */
    fun price(p: Float): String = priceCents(cents(p))

    fun priceCents(cents: Int): String = "\$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
}
