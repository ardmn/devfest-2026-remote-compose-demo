package brew.lab

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.creation.RemoteComposeWriter.HTag
import androidx.compose.remote.creation.dsl.Modifier
import androidx.compose.remote.creation.dsl.RcCanvasScope
import androidx.compose.remote.creation.dsl.RcFloat
import androidx.compose.remote.creation.dsl.RcFontWeight
import androidx.compose.remote.creation.dsl.RcHorizontalPositioning
import androidx.compose.remote.creation.dsl.RcProfile
import androidx.compose.remote.creation.dsl.RcScope
import androidx.compose.remote.creation.dsl.RcTextAlign
import androidx.compose.remote.creation.dsl.RcTouchStopMode
import androidx.compose.remote.creation.dsl.RcVerticalPositioning
import androidx.compose.remote.creation.dsl.createRcBuffer
import androidx.compose.remote.creation.dsl.fillMaxSize
import androidx.compose.remote.creation.dsl.padding
import androidx.compose.remote.creation.dsl.rf
import androidx.compose.remote.creation.dsl.rsp
import androidx.compose.remote.creation.dsl.touchPosX
import androidx.compose.remote.creation.dsl.touchPosY
import androidx.compose.remote.creation.profile.RcPlatformProfiles

/**
 * The Lab screen's documents: full-bleed AGSL shaders with Remote Compose text on top.
 *
 * Written with the creation DSL, not the public Compose API: in alpha23 shaders can only be authored through
 *
 * @RestrictTo API (RemoteComposeContext.createShader / the DSL's shader {}). The rest of the app (brew.remote) stays on the public API;
 *   shared's lint ignores RestrictedApi for this package only (lint.xml).
 */
object LabDocuments {
    const val W = LabCards.W
    private const val LATTE_H = LabCards.LATTE_H
    private const val RIPPLE_H = LabCards.RIPPLE_H
    private const val HOLO_H = LabCards.HOLO_H
    private const val LENT_H = LabCards.LENT_H
    private const val LAVA_H = LabCards.LAVA_H
    private const val AURORA_H = LabCards.AURORA_H
    private const val HYPER_H = LabCards.HYPER_H
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val SOFT = 0xCCFFFFFF.toInt()

    private val PROFILE = RcProfile(RcPlatformProfiles.ANDROIDX)

    // HTag is a Java class: its arguments cannot be named.
    private fun doc(h: Int, content: RcScope.() -> Unit): ByteArray =
        createRcBuffer(
            profile = PROFILE,
            HTag(Header.DOC_WIDTH, W),
            HTag(Header.DOC_HEIGHT, h),
            HTag(Header.DOC_DENSITY_BEHAVIOR, CoreDocument.DENSITY_BEHAVIOR_DP),
            content = content,
        )

    /**
     * A canvas filled by [source], fed every frame: iResolution (the canvas size), iTime (the document clock) and, for shaders that declare
     * them, iTouch (the finger), iTouchTime (when it last touched) and iAnim (the clock touchTime uses).
     */
    private fun RcScope.shaderFill(source: String, drag: Boolean = false) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = componentWidth()
            val h = componentHeight()
            // continuousSec, not animationTime: a document is redrawn every frame only while
            // something listens to
            // the continuous clock (RemoteComposeState.getOpsToUpdate); animationTime alone freezes
            // after one frame.
            val t = continuousSec()
            val anim = animationTime()
            // A touch expression makes the document a touch listener: only then does the player
            // follow a drag
            // (touchDrag → TOUCH_POS on every move); without one it records the finger only where
            // it went down.
            val tx = if (drag) dragX(w) else touchPosX()
            val ty = touchPosY()
            val tt = touchTime()
            val sh =
                shader(shaderString = source) {
                    uniform(name = "iResolution", x = w, y = h)
                    uniform(name = "iTime", value = t)
                    // Only uniforms the shader declares: setting an undeclared one aborts the app
                    // in RuntimeShader.
                    if ("iTouch;" in source) uniform(name = "iTouch", x = tx, y = ty)
                    if ("iTouchTime;" in source) uniform(name = "iTouchTime", value = tt)
                    if ("iAnim;" in source) uniform(name = "iAnim", value = anim)
                }
            paint { shader(sh) }
            drawRect(left = 0f.rf, top = 0f.rf, right = w, bottom = h)
        }
    }

    /** The finger's x, followed through a drag (a touch expression), from 0 to [width]. */
    private fun RcCanvasScope.dragX(width: RcFloat) =
        addTouch(
            defValue = 0f.rf,
            min = 0f.rf,
            max = width,
            exp = touchPosX(),
            stopMode = RcTouchStopMode.Instantly,
        )

    fun latte(): ByteArray =
        doc(h = LATTE_H) {
            Box(modifier = Modifier.fillMaxSize()) {
                shaderFill(source = LabShaders.LATTE)
                Box(
                    modifier = Modifier.fillMaxSize().padding(start = 24f, top = 24f, end = 24f, bottom = 24f),
                    horizontal = RcHorizontalPositioning.Start,
                    vertical = RcVerticalPositioning.Bottom,
                ) {
                    Text(
                        text = "Liquid latte",
                        color = 0xFFFFFFFF.toInt(),
                        fontSize = 34.rsp,
                        fontWeight = RcFontWeight.W800,
                    )
                }
            }
        }

    /** A shader card with a title and a line under it, bottom-left. */
    private fun card(h: Int, source: String, title: String, sub: String): ByteArray =
        doc(h = h) {
            Box(modifier = Modifier.fillMaxSize()) {
                shaderFill(source = source)
                Box(
                    modifier = Modifier.fillMaxSize().padding(start = 24f, top = 24f, end = 24f, bottom = 22f),
                    horizontal = RcHorizontalPositioning.Start,
                    vertical = RcVerticalPositioning.Bottom,
                ) {
                    Column {
                        Text(
                            text = title,
                            color = WHITE,
                            fontSize = 30.rsp,
                            fontWeight = RcFontWeight.W800,
                        )
                        Text(text = sub, color = SOFT, fontSize = 14.rsp)
                    }
                }
            }
        }

    fun ripple(): ByteArray =
        card(
            h = RIPPLE_H,
            source = LabShaders.RIPPLE,
            title = "Tap the coffee",
            sub = "touch → a wave, lit and refracted on the GPU",
        )

    fun lava(): ByteArray =
        card(
            h = LAVA_H,
            source = LabShaders.LAVA,
            title = "Lava lamp",
            sub = "seven metaballs, every pixel, every frame",
        )

    fun aurora(): ByteArray =
        card(
            h = AURORA_H,
            source = LabShaders.AURORA,
            title = "Aurora",
            sub = "noise curtains over a ridge",
        )

    fun hyperspace(): ByteArray =
        card(
            h = HYPER_H,
            source = LabShaders.HYPERSPACE,
            title = "Hyperspace",
            sub = "three layers of streaking stars",
        )

    /** A loyalty card in holographic foil: the light on the foil follows the finger, the text is ordinary Remote Compose. */
    fun holo(): ByteArray =
        doc(h = HOLO_H) {
            Box(modifier = Modifier.fillMaxSize()) {
                shaderFill(source = LabShaders.HOLO, drag = true)
                Box(
                    modifier = Modifier.fillMaxSize().padding(start = 24f, top = 28f, end = 24f, bottom = 26f),
                    horizontal = RcHorizontalPositioning.Center,
                    vertical = RcVerticalPositioning.Center,
                ) {
                    Column(horizontal = RcHorizontalPositioning.Center) {
                        Text(
                            text = "BREW GOLD · LOYALTY CARD",
                            color = SOFT,
                            fontSize = 13.rsp,
                            fontWeight = RcFontWeight.W600,
                        )
                        Text(text = "☕", fontSize = 110.rsp, textAlign = RcTextAlign.Center)
                        Text(
                            text = "Holographic foil",
                            color = WHITE,
                            fontSize = 30.rsp,
                            fontWeight = RcFontWeight.W800,
                        )
                        Text(
                            text = "drag across the card — the light follows your finger",
                            color = SOFT,
                            fontSize = 14.rsp,
                        )
                    }
                }
            }
        }

    /** A lenticular flip card: two embedded pictures; drag left / right and it flips between hot and iced. */
    fun lenticular(): ByteArray =
        doc(h = LENT_H) {
            val hot = LenticularArt.hot(wDp = W, hDp = LENT_H)
            val iced = LenticularArt.iced(wDp = W, hDp = LENT_H)
            val aMid = remoteBitmap(hot.mid)
            val aFront = remoteBitmap(hot.front)
            val bMid = remoteBitmap(iced.mid)
            val bFront = remoteBitmap(iced.front)
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = componentWidth()
                    val h = componentHeight()
                    // A touch expression: without one the player follows only where the finger went
                    // down, not a drag.
                    val tx = dragX(w)
                    val ty = touchPosY()
                    val sh =
                        shader(shaderString = LabShaders.LENTICULAR) {
                            uniform(name = "iResolution", x = w, y = h)
                            uniform(name = "iTouch", x = tx, y = ty)
                            uniform(name = "aMid", image = aMid)
                            uniform(name = "aFront", image = aFront)
                            uniform(name = "bMid", image = bMid)
                            uniform(name = "bFront", image = bFront)
                            uniform(
                                name = "imgSize",
                                x = (W * LenticularArt.SCALE).toFloat(),
                                y = (LENT_H * LenticularArt.SCALE).toFloat(),
                            )
                        }
                    paint { shader(sh) }
                    drawRect(left = 0f.rf, top = 0f.rf, right = w, bottom = h)
                }
                Box(
                    modifier = Modifier.fillMaxSize().padding(start = 24f, top = 18f, end = 24f, bottom = 18f),
                    horizontal = RcHorizontalPositioning.Center,
                    vertical = RcVerticalPositioning.Top,
                ) {
                    Text(
                        text = "◀  drag  ▶",
                        color = SOFT,
                        fontSize = 14.rsp,
                        fontWeight = RcFontWeight.W600,
                    )
                }
            }
        }

    /**
     * One card of the Lab screen: the document is laid out for [W] x [heightDp] dp. [build] writes it — call it off the main thread, one
     * card at a time (the lenticular card draws and embeds four images).
     */
    class LabDoc(
        val title: String,
        val heightDp: Int,
        val drag: Boolean = false,
        val build: () -> ByteArray,
    )

    /** Every Lab card (LabCards), in screen order; nothing is built until a card asks. */
    val all: List<LabDoc> =
        LabCards.all.zip(listOf(::latte, ::ripple, ::lenticular, ::holo, ::lava, ::aurora, ::hyperspace)) { card, build ->
            LabDoc(card.title, card.heightDp, card.drag) { build() }
        }
}
