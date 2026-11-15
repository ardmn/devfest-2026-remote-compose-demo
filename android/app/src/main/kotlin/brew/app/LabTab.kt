@file:Suppress("RestrictedApiAndroidX", "RestrictedApi", "ClickableViewAccessibility")

// RemoteDocumentPlayer and RemoteComposePlayer.setShaderControl are @RestrictTo(LIBRARY_GROUP) in alpha23.

package brew.app

import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import brew.document.DocumentValidator
import brew.lab.LabDocuments
import brew.lab.LabShaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A built card: its bytes and the validated document. */
private class Built(val bytes: ByteArray, val doc: CoreDocument)

/**
 * The Lab: Remote Compose documents that are GPU shaders. Each card is its own document and its own player: a card is built in the
 * background when it first scrolls into view (a placeholder of its size until then) and kept for the next time; a lazy list keeps only the
 * visible cards alive, so off-screen shaders don't run. Shaders are off in the player by default; this screen allows exactly the sources in
 * LabShaders and nothing else.
 */
@Composable
fun LabTab() {
    val built = remember { mutableStateMapOf<String, Built>() }
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B0F))) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items = LabDocuments.all, key = { it.title }) { lab ->
                val card by
                    produceState<Result<Built>?>(initialValue = built[lab.title]?.let { Result.success(it) }, key1 = lab.title) {
                        if (value == null) {
                            value =
                                withContext(Dispatchers.Default) {
                                        runCatching {
                                            val bytes = lab.build()
                                            // Debug builds only: a copy for the iOS app's bundle (adb pull …/files/lab, see README).
                                            if (BuildConfig.DEBUG)
                                                runCatching {
                                                    context
                                                        .getExternalFilesDir("lab")
                                                        ?.resolve("lab_i-${LabDocuments.all.indexOf(lab)}.rc")
                                                        ?.writeBytes(bytes)
                                                }
                                            Built(bytes = bytes, doc = DocumentValidator.validate(bytes))
                                        }
                                            .onFailure { android.util.Log.e("Brew", "Lab card ${lab.title} failed", it) }
                                    }
                                    .also { r -> r.getOrNull()?.let { built[lab.title] = it } }
                        }
                    }
                val shape = Modifier.fillMaxWidth().aspectRatio(LabDocuments.W.toFloat() / lab.heightDp).clip(RoundedCornerShape(24.dp))
                Column {
                    val c = card?.getOrNull()
                    if (card?.isFailure == true) {
                        Box(modifier = shape.background(Color(0xFF2A1416)), contentAlignment = Alignment.Center) {
                            Text(
                                text = "This card failed to build: ${card?.exceptionOrNull()?.message}",
                                modifier = Modifier.padding(24.dp),
                                color = Color(0xFFFF9C9C),
                                fontSize = 13.sp,
                            )
                        }
                    } else if (c == null) {
                        Box(modifier = shape.background(Color(0xFF16161C)))
                    } else {
                        RemoteDocumentPlayer(
                            document = c.doc,
                            documentWidth = c.doc.width,
                            documentHeight = c.doc.height,
                            modifier = shape,
                            init = { p ->
                                p.setShaderControl { source -> source in LabShaders.ALL }
                                // A tap must not focus the view: the list would jump to bring it into view.
                                p.isFocusable = false
                                (0 until p.childCount).forEach {
                                    p.getChildAt(it).apply {
                                        isFocusable = false
                                        isFocusableInTouchMode = false
                                    }
                                }
                                // A card you drag across: while the finger is on it, the list must not take the gesture.
                                if (lab.drag)
                                    p.getChildAt(0)?.setOnTouchListener { v, e ->
                                        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN)
                                            v.parent?.requestDisallowInterceptTouchEvent(true)
                                        false
                                    }
                            },
                        )
                    }
                    Text(
                        text = "${lab.title} · ${c?.bytes?.size?.let { "%,d bytes".format(it) } ?: "building…"} · GPU",
                        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                        color = Color(0xFF8A8F98),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        FpsMeter(modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp))
    }
}

/** Frames per second of the whole window, counted with Choreographer — the honest number on stage. */
@Composable
private fun FpsMeter(modifier: Modifier) {
    var fps by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        var frames = 0
        var since = 0L
        var running = true
        val cb =
            object : Choreographer.FrameCallback {
                override fun doFrame(now: Long) {
                    if (since == 0L) since = now
                    frames++
                    if (now - since >= 1_000_000_000L) {
                        fps = frames
                        frames = 0
                        since = now
                    }
                    if (running) choreographer.postFrameCallback(this)
                }
            }
        choreographer.postFrameCallback(cb)
        onDispose {
            running = false
            choreographer.removeFrameCallback(cb)
        }
    }
    Text(
        text = "$fps fps",
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = if (fps >= 55) Color(0xFF6EE7A8) else Color(0xFFFFB454),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
}
