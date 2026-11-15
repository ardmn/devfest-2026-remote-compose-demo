@file:Suppress("RestrictedApiAndroidX", "RestrictedApi")

package brew.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import brew.capture.BrewDocuments
import brew.document.DocumentValidator
import brew.document.InvalidDocumentException
import brew.remote.BrewCatalog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "Brew"

/** A document bigger than this is refused before it is read in full (the player's own limit is 32 MB unpacked). */
private const val MAX_DOCUMENT_BYTES = 32L * 1024 * 1024

/** Banner indices the app accepts from the server (the pool in stage.json is far smaller). */
private const val MAX_BANNER_POOL = 100

/** Where a section's bytes came from: the server, or built on the device because the server was unreachable. */
enum class Origin {
    LIVE,
    OFFLINE,
}

/**
 * A validated section document. Only the bytes are kept: every player decodes its own CoreDocument (a player mutates the document it plays,
 * so two players must never share one). [refresh] is the pull-to-refresh it was loaded for.
 */
class Section(val bytes: ByteArray, val origin: Origin, val refresh: Int) {
    fun decode(): CoreDocument = DocumentValidator.validate(bytes)
}

/**
 * Loads one section document ("hero", "favorites", "details?id=caramel" …) from the backend. Offline it captures the very
 * same @RemoteComposable section on the device (from :shared), so the app never shows a hole.
 */
class SectionSource(private val baseUrl: String, private val context: android.content.Context) {
    /** Last document per section: coming back to a screen shows it at once, without a new download. */
    val cache = java.util.concurrent.ConcurrentHashMap<String, Section>()

    private val http =
        OkHttpClient.Builder()
            .connectTimeout(timeout = 1500, unit = TimeUnit.MILLISECONDS)
            .readTimeout(timeout = 3, unit = TimeUnit.SECONDS)
            .build()

    /**
     * The section from the backend; if the backend is unreachable, the same section built on the device — or null if only the server makes
     * it. A document the server sends but that fails validation is rejected (and logged as such), never played.
     */
    suspend fun load(path: String, refresh: Int): Section? =
        withContext(Dispatchers.IO) {
            val downloaded = runCatching {
                download(path)
            }
                .onSuccess { if (warned.getAndSet(false)) android.util.Log.i(TAG, "Backend reachable again at $baseUrl") }
                .onFailure { e -> if (!warned.getAndSet(true)) android.util.Log.w(TAG, unreachable(e)) }
                .getOrNull()
            val live = downloaded?.let { bytes ->
                try {
                    DocumentValidator.validate(bytes)
                    Section(bytes = bytes, origin = Origin.LIVE, refresh = refresh)
                } catch (e: InvalidDocumentException) {
                    android.util.Log.e(TAG, "Rejected /sections/$path from the backend: ${e.message}. Using the copy built on the device.")
                    null
                }
            }
            (live ?: local(path)?.let { Section(bytes = it, origin = Origin.OFFLINE, refresh = refresh) })?.also { cache[path] = it }
        }

    /** The bytes of /sections/[path], refusing anything over [MAX_DOCUMENT_BYTES]. */
    private fun download(path: String): ByteArray {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/sections/" + path).build()
        return http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} for /sections/$path" }
            check(r.body.contentLength() <= MAX_DOCUMENT_BYTES) { "document over $MAX_DOCUMENT_BYTES bytes" }
            val source = r.body.source()
            check(!source.request(MAX_DOCUMENT_BYTES + 1)) { "document over $MAX_DOCUMENT_BYTES bytes" }
            source.readByteArray()
        }
    }

    private val warned = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Logged once when the backend can't be reached: what happened and how to fix it. */
    private fun unreachable(e: Throwable) =
        """
        |Backend not reachable at $baseUrl (${e.javaClass.simpleName}: ${e.message}).
        |The app keeps working: it builds the sections on the device; the JSON promo card is hidden.
        |To get live sections from the server:
        |  1. Start it:  cd backend && go run ./cmd/demo serve   (the first start builds the JSON converter, about a minute)
        |  2. Emulator or USB phone:  adb reverse tcp:8080 tcp:8080   (cmd/demo serve does it; repeat after an emulator restart)
        |  3. Phone over Wi-Fi: build with  ./gradlew :app:installDebug -Pbrew.baseUrl=http://<your-computer-ip>:8080
        |  4. Check on the computer:  curl http://127.0.0.1:8080/sections/promo.json
        |Then pull to refresh on Home.
        """
            .trimMargin()

    /**
     * Which banners the carousel shows, in order: the server picks them from its pool, a new random choice on every request (every pull to
     * refresh), and answers a JSON array of indices, e.g. [3,0,4] → hero?i=3, hero?i=0, hero?i=4. Untrusted input: non-negative distinct
     * ints only, at most 6. Offline: the banners the app can build itself.
     */
    suspend fun bannerIds(): List<Int> =
        withContext(Dispatchers.IO) {
            val offline = BrewCatalog.Stage().banners.indices.toList()
            runCatching {
                val body =
                    http.newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/sections/banners").build()).execute().use { r ->
                        check(r.isSuccessful) { "HTTP ${r.code}" }
                        r.body.string()
                    }
                val array = org.json.JSONArray(body)
                List(array.length()) { array.getInt(it) }.filter { it in 0..<MAX_BANNER_POOL }.distinct().take(6)
            }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: offline
        }

    /** The same section built on the device; null for sections only the server makes (the JSON promo). */
    private suspend fun local(path: String): ByteArray? {
        val name = path.substringBefore('?')
        val id = path.substringAfter(delimiter = "id=", missingDelimiterValue = "americano")
        return when (name) {
            "hero" ->
                BrewDocuments.hero(
                    context = context,
                    i = path.substringAfter(delimiter = "i=", missingDelimiterValue = "0").toIntOrNull() ?: 0,
                )
            "categories" -> BrewDocuments.categories(context = context)
            "favorites" -> BrewDocuments.favorites(context = context)
            "for-you" -> BrewDocuments.forYou(context = context)
            "details" -> BrewDocuments.details(context = context, id = id)
            "row" -> BrewDocuments.row(context = context, id = id)
            else -> null
        }
    }
}

/**
 * A Remote Compose section inside a normal Compose layout. While the first copy loads it shows a quiet placeholder of the same size; on
 * refresh it keeps the old document on screen until the new one is ready (no flicker).
 */
@Composable
fun RemoteSection(
    path: String,
    source: SectionSource,
    refreshKey: Int,
    modifier: Modifier,
    /** false: always a fresh document (a product page should not remember the last visit's quantity). */
    reuse: Boolean = true,
    onLoaded: (path: String, Section) -> Unit = { _, _ -> },
    onNamedValue: (name: String, value: Any?) -> Unit = { _, _ -> },
    /** Host-owned values pushed into the document (favorites). Compose state read here re-runs it on change. */
    push: (StateUpdater) -> Unit = {},
    onAction: (String) -> Unit,
) {
    var section by remember(key1 = path) { mutableStateOf(if (reuse) source.cache[path] else null) }
    var missing by remember(key1 = path) { mutableStateOf(false) }
    LaunchedEffect(key1 = path, key2 = refreshKey) {
        // Already loaded for this pull-to-refresh (e.g. a list item scrolled back into view): no new download.
        if (section?.let { it.refresh >= refreshKey } == true) return@LaunchedEffect
        val s = source.load(path = path, refresh = refreshKey)
        if (s == null) {
            missing = true
            return@LaunchedEffect
        }
        missing = false
        section = s
        onLoaded(path, s)
    }
    // A section only the server makes (the JSON promo) while the server is away: show nothing, not a hole.
    if (missing && section == null) return
    val s = section
    if (s == null) {
        Box(modifier = modifier.clip(RoundedCornerShape(size = 24.dp)).background(Color(0xFFF6F1ED)))
    } else {
        // key: a new document instance replaces the player instead of mutating the old one.
        androidx.compose.runtime.key(s) {
            val doc = remember { s.decode() }
            // The player must NOT recompose once it shows a document: every recomposition runs its update block,
            // which calls setDocument() -> initializeContext() on the live document. That zeroes the component
            // sizes the layout filled in (no new layout follows, the view size did not change), so everything
            // derived from them breaks: RemoteCircleShape (radius = min(w, h) / 2) turns square, vector icons draw
            // at 0x0. So: stable callbacks (they read the latest lambdas), and host values pushed by an effect.
            val player = remember { mutableStateOf<RemoteComposePlayer?>(null) }
            val latestAction = rememberUpdatedState(onAction)
            val latestValue = rememberUpdatedState(onNamedValue)
            val latestPush = rememberUpdatedState(push)
            val init = remember {
                { p: RemoteComposePlayer ->
                    guardDrags(p)
                    player.value = p
                }
            }
            val onNamed = remember {
                { name: String, value: Any?, _: StateUpdater ->
                    if (value != null) latestValue.value(name, value) else latestAction.value(name)
                }
            }
            RemoteDocumentPlayer(
                document = doc,
                documentWidth = doc.width,
                documentHeight = doc.height,
                modifier = modifier,
                init = init,
                onNamedAction = onNamed,
            )
            val p = player.value
            if (p != null) {
                // Re-runs push whenever the Compose state it reads changes (favourites, category), then redraws.
                LaunchedEffect(p) {
                    snapshotFlow {
                        latestPush.value(p.stateUpdater)
                        Any()
                    }
                        .collect {
                            p.invalidate()
                            (0 until p.childCount).forEach { i -> p.getChildAt(i).invalidate() }
                        }
                }
            }
        }
    }
}

/**
 * A drag that starts on a section must scroll the list, not click. The player decides "click" on ACTION_UP when it did not see the finger
 * move, but once the list (or pull-to-refresh) takes the gesture the player may never see the moves. So: past touch slop, send the player
 * ACTION_CANCEL (it resets pressed states, no click) and swallow the rest of the gesture. Compose still gets every event for its own
 * scrolling.
 */
// Not a click detector: taps still reach the player's own onTouchEvent, which calls performClick.
@android.annotation.SuppressLint("ClickableViewAccessibility")
private fun guardDrags(player: RemoteComposePlayer) {
    val inner = (0 until player.childCount).map { player.getChildAt(it) }.firstOrNull() ?: return
    val slop = android.view.ViewConfiguration.get(player.context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var cancelled = false
    inner.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                cancelled = false
                false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!cancelled && kotlin.math.hypot(x = e.x - downX, y = e.y - downY) > slop) {
                    cancelled = true
                    val c = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    v.onTouchEvent(c)
                    c.recycle()
                }
                cancelled
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                val swallow = cancelled
                cancelled = false
                swallow
            }
            else -> cancelled
        }
    }
}
