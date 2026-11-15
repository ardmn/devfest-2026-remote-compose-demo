package brew.lab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The lenticular card's picture layers, drawn once with the Android canvas at 2x and embedded in the document as images the shader samples.
 * Each side has two transparent layers over a background the shader draws itself: a middle layer (small emoji, scattered) and a front layer
 * (the hero emoji and the title). The shader shifts the layers by different amounts as the card turns — that parallax is the depth.
 */
internal object LenticularArt {
    const val SCALE = 2

    class Side(val mid: Bitmap, val front: Bitmap)

    fun hot(wDp: Int, hDp: Int) =
        Side(
            mid =
                mid(
                    wDp = wDp,
                    hDp = hDp,
                    items =
                        listOf(
                            "🔥" to (0.20f to 0.24f),
                            "✨" to (0.80f to 0.20f),
                            "🥐" to (0.83f to 0.62f),
                            "🍫" to (0.17f to 0.64f),
                        ),
                ),
            front = front(wDp = wDp, hDp = hDp, hero = "☕", title = "HOT ESPRESSO"),
        )

    fun iced(wDp: Int, hDp: Int) =
        Side(
            mid =
                mid(
                    wDp = wDp,
                    hDp = hDp,
                    items =
                        listOf(
                            "🧊" to (0.19f to 0.25f),
                            "❄️" to (0.81f to 0.19f),
                            "🧊" to (0.84f to 0.60f),
                            "💧" to (0.16f to 0.63f),
                        ),
                ),
            front = front(wDp = wDp, hDp = hDp, hero = "🧋", title = "ICED LATTE"),
        )

    private fun canvas(wDp: Int, hDp: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(wDp * SCALE, hDp * SCALE, Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    private fun mid(wDp: Int, hDp: Int, items: List<Pair<String, Pair<Float, Float>>>): Bitmap {
        val (bmp, c) = canvas(wDp = wDp, hDp = hDp)
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = bmp.width * 0.14f
            }
        items.forEach { (e, pos) -> c.drawText(e, bmp.width * pos.first, bmp.height * pos.second, p) }
        return bmp
    }

    private fun front(wDp: Int, hDp: Int, hero: String, title: String): Bitmap {
        val (bmp, c) = canvas(wDp = wDp, hDp = hDp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        p.textSize = w * 0.50f
        p.setShadowLayer(w * 0.03f, 0f, w * 0.02f, 0x66000000)
        val fm = p.fontMetrics
        c.drawText(hero, w / 2f, h * 0.44f - (fm.ascent + fm.descent) / 2f, p)
        p.color = Color.WHITE
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textSize = w * 0.105f
        p.setShadowLayer(w * 0.015f, 0f, w * 0.008f, 0x88000000)
        c.drawText(title, w / 2f, h * 0.86f, p)
        return bmp
    }
}
