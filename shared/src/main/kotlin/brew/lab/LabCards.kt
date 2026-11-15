package brew.lab

/**
 * The Lab screen's cards as plain data: title, the height each document is laid out for (dp, at width [W]) and whether it follows a drag.
 * The documents themselves are written by LabDocuments; the iOS app's copy of this list is generated from here (tools/,
 * Catalog.generated.swift).
 */
object LabCards {
    const val W = 400
    const val LATTE_H = 320
    const val RIPPLE_H = 320
    const val LENT_H = 460
    const val HOLO_H = 460
    const val LAVA_H = 300
    const val AURORA_H = 300
    const val HYPER_H = 300

    class Card(val title: String, val heightDp: Int, val drag: Boolean = false)

    /** In screen order; LabDocuments.all builds them in the same order (lab_i-<index>.rc). */
    val all: List<Card> =
        listOf(
            Card("Liquid latte", LATTE_H),
            Card("Coffee ripple", RIPPLE_H),
            Card("Lenticular flip", LENT_H, drag = true),
            Card("Holographic foil", HOLO_H, drag = true),
            Card("Lava lamp", LAVA_H),
            Card("Aurora", AURORA_H),
            Card("Hyperspace", HYPER_H),
        )
}
