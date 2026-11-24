// The Remote Compose player APIs (StateUpdater, RemoteDocumentPlayer) are @RestrictTo(LIBRARY_GROUP) in
// 1.0.0-alpha24: there is no public way to play a document in an app yet. Suppressing the lint check is deliberate.
@file:Suppress("RestrictedApiAndroidX", "RestrictedApi")

package brew.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import brew.remote.BrewCatalog
import brew.remote.BrewSections
import brew.remote.BrewTheme
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Tan = Color(BrewTheme.TAN)
private val TanDark = Color(BrewTheme.TAN_DARK)
private val TanSoft = Color(BrewTheme.TAN_SOFT)
private val Ink = Color(BrewTheme.INK)
private val Muted = Color(BrewTheme.MUTED)

private val Colors =
    lightColorScheme(
        primary = TanDark,
        onPrimary = Color.White,
        secondaryContainer = TanSoft,
        background = Color.White,
        surface = Color.White,
        onSurface = Ink,
        surfaceContainer = Color.White,
    )

/** One line in the cart. [note] is what the product page chose ("Large"), [unitCents] the price of one, in cents. */
data class CartLine(val id: String, val qty: Int, val unitCents: Int, val note: String)

/**
 * The app's own state. Documents never own it: they ask through host actions (add_to_cart:<id>, toggle_favorite:<id>, order:<id>) and show
 * it through named values the app pushes (USER:fav_<id>). A ViewModel, so the cart survives rotation.
 */
class AppState : androidx.lifecycle.ViewModel() {
    val cart = mutableStateListOf<CartLine>()
    val favorites = mutableStateMapOf("caramel" to true, "cookie" to true)
    /** The Menu tab's filter (null = All). Home's category circles are shortcuts into it. */
    var category by mutableStateOf<String?>(null)
    val cartCount
        get() = cart.sumOf { it.qty }

    val cartTotalCents
        get() = cart.sumOf { it.unitCents * it.qty }

    fun add(id: String, qty: Int, unitCents: Int, note: String = "") {
        val i = cart.indexOfFirst { it.id == id && it.note == note && it.unitCents == unitCents }
        if (i >= 0) {
            cart[i] = cart[i].copy(qty = cart[i].qty + qty)
        } else {
            cart += CartLine(id = id, qty = qty, unitCents = unitCents, note = note)
        }
    }

    /** Takes back exactly what [add] put in (the Undo of the "added to cart" snackbar). */
    fun undoAdd(id: String, qty: Int, unitCents: Int, note: String) {
        val i = cart.indexOfFirst { it.id == id && it.note == note && it.unitCents == unitCents }
        if (i < 0) return
        val left = cart[i].qty - qty
        if (left > 0) cart[i] = cart[i].copy(qty = left) else cart.removeAt(i)
    }

    fun toggleFavorite(id: String) {
        if (favorites[id] == true) favorites.remove(id) else favorites[id] = true
    }

    /** Pushed into every section; reading the state here makes each player update when it changes. */
    fun push(u: androidx.compose.remote.player.core.state.StateUpdater) {
        // Named colors (not integers) so the very same documents work with the iOS player's controller too.
        // StateUpdater is a Java interface: its arguments cannot be named.
        for (d in BrewCatalog.DRINKS) {
            val fav = favorites[d.id] == true
            u.setUserLocalColor("fav_${d.id}", if (fav) BrewTheme.RED else 0x00000000)
            u.setUserLocalColor("favline_${d.id}", if (fav) 0x00000000 else BrewTheme.INK)
        }
        // Home's category circles are shortcuts, not a filter: never highlighted (the Menu tab's chips show the filter).
        for (c in BrewCatalog.CATEGORIES) {
            u.setUserLocalColor("cat_${c.key}", BrewTheme.TAN_SOFT)
            u.setUserLocalColor("catline_${c.key}", 0x00000000)
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home(label = "Home", icon = Icons.Filled.Home),
    Menu(label = "Menu", icon = Icons.Filled.Menu),
    Favorites(label = "Favorites", icon = Icons.Filled.Favorite),
    Lab(label = "Lab", icon = Icons.Filled.Star),
    Profile(label = "Profile", icon = Icons.Filled.Person),
}

/**
 * The native skeleton: tabs, top bar, cart, banner pager, pull to refresh, snackbars — ordinary Compose. The content (banners, categories,
 * product cards, lists, the product page) is Remote Compose, served by the server.
 */
@Composable
fun ShowcaseApp(source: SectionSource) {
    MaterialTheme(colorScheme = Colors) {
        val snack = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val app: AppState = androidx.lifecycle.viewmodel.compose.viewModel()
        var tab by rememberSaveable { mutableStateOf(Tab.Home) }
        // The product page and the cart open ON TOP of the tabs (the tabs stay composed and keep their state).
        var details by rememberSaveable { mutableStateOf<String?>(null) }
        var cartOpen by rememberSaveable { mutableStateOf(false) }
        // The options the product page reported last. The page's document does its own arithmetic for display; the
        // price the app charges is worked out here from the catalog — a document is untrusted input.
        var pageQty by rememberSaveable { mutableIntStateOf(1) }
        var pageLarge by rememberSaveable { mutableStateOf(false) }
        var pageToppings by rememberSaveable { mutableIntStateOf(0) }
        val pageUnitCents = details?.let { BrewCatalog.unitCents(id = it, large = pageLarge, toppings = pageToppings) } ?: 0

        fun say(text: String) = scope.launch {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(message = text)
        }

        /** Adds to the cart and offers Undo, like any shopping app. */
        fun addWithUndo(id: String, qty: Int, unitCents: Int, note: String, text: String) {
            app.add(id = id, qty = qty, unitCents = unitCents, note = note)
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch {
                snack.currentSnackbarData?.dismiss()
                val r = snack.showSnackbar(message = text, actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) app.undoAdd(id = id, qty = qty, unitCents = unitCents, note = note)
            }
        }

        /** A host action from a document. Untrusted input: unknown ids and categories are logged and ignored. */
        fun onAction(name: String) {
            android.util.Log.i("Brew", "action $name · cart=${app.cartCount} favs=${app.favorites.keys.sorted()}")
            val verb = name.substringBefore(delimiter = ':')
            val arg = name.substringAfter(delimiter = ':', missingDelimiterValue = "")
            val drink = BrewCatalog.find(arg)
            when {
                verb == "category" && BrewCatalog.isCategory(arg) -> {
                    app.category = arg
                    details = null
                    tab = Tab.Menu
                }
                drink == null -> android.util.Log.w("Brew", "ignored action \"$name\": unknown action or id")
                verb == "open_details" -> {
                    pageQty = 1
                    pageLarge = false
                    pageToppings = 0
                    cartOpen = false
                    details = drink.id
                }
                verb == "add_to_cart" ->
                    addWithUndo(
                        id = drink.id,
                        qty = 1,
                        unitCents = BrewCatalog.unitCents(id = drink.id, large = false, toppings = 0)!!,
                        note = "",
                        text = "${drink.name} added to cart",
                    )
                verb == "toggle_favorite" -> app.toggleFavorite(drink.id)
                verb == "order" -> {
                    val qty = pageQty
                    val unit = BrewCatalog.unitCents(id = drink.id, large = pageLarge, toppings = pageToppings)!!
                    val note =
                        if (!drink.hasOptions) {
                            ""
                        } else {
                            buildList {
                                    add(if (pageLarge) "Large" else "Regular")
                                    BrewCatalog.TOPPINGS.forEachIndexed { k, t -> if (pageToppings and (1 shl k) != 0) add(t.name) }
                                }
                                .joinToString(separator = " · ")
                        }
                    details = null
                    addWithUndo(id = drink.id, qty = qty, unitCents = unit, note = note, text = "$qty × ${drink.name} added to cart")
                }
                else -> android.util.Log.w("Brew", "ignored action \"$name\": unknown action")
            }
        }

        /** A value the product page reports. Untrusted: only finite numbers, clamped to what the page can produce. */
        fun onValue(name: String, value: Any?) {
            val f = (value as? Float)?.takeIf { it.isFinite() } ?: return
            when (name) {
                BrewSections.QTY_ACTION -> pageQty = f.toInt().coerceIn(minimumValue = 1, maximumValue = 9)
                BrewSections.LARGE_ACTION -> pageLarge = f > 0.5f
                BrewSections.TOPPINGS_ACTION ->
                    pageToppings = f.toInt().coerceIn(minimumValue = 0, maximumValue = (1 shl BrewCatalog.TOPPINGS.size) - 1)
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
            Scaffold(
                containerColor = Color.White,
                snackbarHost = { SnackbarHost(hostState = snack) { Snackbar(snackbarData = it, actionColor = Color(BrewTheme.BUTTER)) } },
                // Home has no app bar (its greeting scrolls away, iOS-style); the other tabs get a plain titled bar.
                topBar = { if (tab != Tab.Home) TopBar(tab = tab) },
                bottomBar = { BottomBar(selected = tab) { tab = it } },
            ) { pad ->
                // Home draws under the status bar (its top edge fades, iOS-style); the other tabs sit below their bar.
                Box(modifier = if (tab == Tab.Home) Modifier.padding(bottom = pad.calculateBottomPadding()) else Modifier.padding(pad)) {
                    when (tab) {
                        Tab.Home ->
                            HomeTab(source = source, app = app, onAction = ::onAction) { t ->
                                if (t == Tab.Menu) app.category = null
                                tab = t
                            }
                        Tab.Menu -> MenuTab(source = source, app = app, onAction = ::onAction)
                        Tab.Favorites ->
                            ListTab(
                                source = source,
                                app = app,
                                ids = BrewCatalog.DRINKS.map { it.id }.filter { app.favorites[it] == true },
                                empty = "No favorites yet. Tap the heart on any drink.",
                                onAction = ::onAction,
                            )
                        Tab.Lab -> LabTab()
                        Tab.Profile -> ProfileTab(app = app)
                    }
                }
            }
            // The cart: a round floating button, pinned top-right over everything, on every tab (as on iOS).
            CartButton(
                count = app.cartCount,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 12.dp),
            ) {
                android.util.Log.i("Brew", "cart opened")
                cartOpen = true
            }
            Overlay(visible = cartOpen, last = if (cartOpen) "cart" else null) {
                CartScreen(app = app, onBack = { cartOpen = false }, onOpen = { onAction("open_details:$it") }) {
                    val n = app.cartCount
                    app.cart.clear()
                    cartOpen = false
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    say("Order placed — $n item${if (n == 1) "" else "s"}. Thank you!")
                }
            }
            Overlay(visible = details != null, last = details) { id ->
                DetailsScreen(
                    id = id,
                    source = source,
                    totalCents = pageUnitCents * pageQty,
                    onBack = { details = null },
                    onValue = ::onValue,
                    onAction = ::onAction,
                )
            }
            BackHandler(enabled = details != null || cartOpen) { if (details != null) details = null else cartOpen = false }
        }
    }
}

/** Slides a full-screen page in from the right; keeps showing the last key while it slides out. */
@Composable
private fun Overlay(visible: Boolean, last: String?, content: @Composable (String) -> Unit) {
    var shown by remember { mutableStateOf(last ?: "") }
    LaunchedEffect(last) { last?.let { shown = it } }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        key(shown) { content(shown) }
    }
}

@OptIn(ExperimentalMaterial3Api::class) // TopAppBar is still experimental in Material 3
@Composable
private fun TopBar(tab: Tab) {
    TopAppBar(
        title = { Text(text = tab.label, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
    )
}

/** Round white cart button with a soft shadow and the count inside its bounds. */
@Composable
private fun CartButton(count: Int, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier =
            modifier
                .size(48.dp)
                .shadow(elevation = 8.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The badge hangs off the icon's top-right corner (as on iOS), well inside the round button.
        Box(modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Filled.ShoppingCart,
                contentDescription = "Cart, $count items",
                modifier = Modifier.size(24.dp),
                tint = Ink,
            )
            if (count > 0) {
                Box(
                    modifier =
                        Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-5).dp).size(16.dp).clip(CircleShape).background(TanDark),
                    contentAlignment = Alignment.Center,
                ) {
                    // No font padding and a line box trimmed to the glyphs, so the digit sits in the optical center.
                    Text(
                        text = "$count",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        style =
                            TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 10.sp,
                                lineHeightStyle =
                                    LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = t == selected,
                onClick = { onSelect(t) },
                icon = { Icon(imageVector = t.icon, contentDescription = t.label) },
                label = { Text(text = t.label) },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = TanDark,
                        selectedTextColor = TanDark,
                        indicatorColor = TanSoft,
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted,
                    ),
            )
        }
    }
}

@Composable
private fun HomeTab(source: SectionSource, app: AppState, onAction: (String) -> Unit, openTab: (Tab) -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val loaded = remember { mutableStateMapOf<String, Section>() }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            refreshKey++
            delay(600.milliseconds)
            refreshing = false
        }
    }

    // One section of the page: the same source, refresh and callbacks for all of them.
    @Composable
    fun HomeSection(path: String, heightDp: Int) =
        Rc(path = path, heightDp = heightDp, source = source, refreshKey = refreshKey, loaded = loaded, app = app, onAction = onAction)

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true }) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = statusBar + 8.dp, bottom = 16.dp)) {
            // The greeting is content: it scrolls away with the page (the cart button stays pinned).
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 72.dp, top = 8.dp, bottom = 12.dp)) {
                    Text(text = "Good morning", color = Muted, fontSize = 15.sp)
                    Text(text = "Brew · Yerevan", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item { Banners(source = source, refreshKey = refreshKey, loaded = loaded, app = app, onAction = onAction) }
            item { Header(title = "Categories") { openTab(Tab.Menu) } }
            item { HomeSection(path = "categories", heightDp = BrewSections.CATEGORIES_H) }
            item(key = "fav-header") { Header(title = "Favorites") { openTab(Tab.Favorites) } }
            item(key = "favorites") { HomeSection(path = "favorites", heightDp = BrewSections.FAVORITES_H) }
            item(key = "foryou-header") { Header(title = "For you") { openTab(Tab.Menu) } }
            item(key = "for-you") { HomeSection(path = "for-you", heightDp = BrewSections.FOR_YOU_H) }
            // Written by the backend as JSON (backend/promo.json), turned into the same kind of document on the server.
            item(key = "promo") {
                Box(modifier = Modifier.padding(top = 20.dp)) { HomeSection(path = "promo", heightDp = BrewSections.PROMO_H) }
            }
            item { Provenance(sections = loaded.values) }
        }
        // Scroll edge: content fades out under the status bar and the floating cart button instead of being cut.
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(statusBar + 64.dp)
                    .background(
                        // colorStops is a vararg: passed positionally.
                        Brush.verticalGradient(
                            0f to Color.White,
                            0.45f to Color.White.copy(alpha = 0.9f),
                            1f to Color.White.copy(alpha = 0f),
                        )
                    )
        )
    }
}

/**
 * The banner carousel: a native HorizontalPager (swipe, auto-advance every 4 s, loops) with a native page indicator. Each page is a Remote
 * Compose banner from the server, and the server also decides which banners there are: on every pull to refresh it sends a new random
 * choice from its pool (SectionSource.bannerIds).
 */
@Composable
private fun Banners(
    source: SectionSource,
    refreshKey: Int,
    loaded: MutableMap<String, Section>,
    app: AppState,
    onAction: (String) -> Unit,
) {
    var ids by remember { mutableStateOf(BrewCatalog.Stage().banners.indices.toList()) }
    LaunchedEffect(refreshKey) { ids = source.bannerIds() }
    val count = ids.size
    // A large virtual page count, started in the middle, makes swiping and auto-advance loop in both directions.
    val pager = rememberPagerState(initialPage = 500 * count) { 1000 * count }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(key1 = count, key2 = dragged) {
        if (dragged) return@LaunchedEffect
        while (true) {
            delay(4.seconds)
            pager.animateScrollToPage(page = pager.currentPage + 1)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().height(BrewSections.HERO_H.dp),
            beyondViewportPageCount = 1,
        ) { page ->
            Rc(
                path = "hero?i=${ids[page % count]}",
                heightDp = BrewSections.HERO_H,
                source = source,
                refreshKey = refreshKey,
                loaded = loaded,
                app = app,
                onAction = onAction,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(count) { i ->
                val on = pager.currentPage % count == i
                val w by animateDpAsState(targetValue = if (on) 22.dp else 8.dp, label = "dot")
                val c by animateColorAsState(targetValue = if (on) TanDark else TanSoft, label = "dotColor")
                Box(modifier = Modifier.padding(horizontal = 3.dp).height(8.dp).width(w).clip(CircleShape).background(c))
            }
        }
    }
}

/** Menu: native filter chips (All + categories) over the app's list of the matching products. */
@Composable
private fun MenuTab(source: SectionSource, app: AppState, onAction: (String) -> Unit) {
    val ids = BrewCatalog.DRINKS.filter { app.category == null || it.category == app.category }.map { it.id }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val chips = listOf<Pair<String?, String>>(null to "All") + BrewCatalog.CATEGORIES.map { it.key to "${it.emoji} ${it.label}" }
            chips.forEach { (key, label) ->
                FilterChip(
                    selected = app.category == key,
                    onClick = { app.category = key },
                    label = { Text(text = label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TanSoft, selectedLabelColor = Ink),
                )
            }
        }
        Text(
            text = "${ids.size} item${if (ids.size == 1) "" else "s"}",
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            color = Muted,
            fontSize = 13.sp,
        )
        ListTab(source = source, app = app, ids = ids, empty = "Nothing here yet.", onAction = onAction)
    }
}

/**
 * Menu and Favorites: the app's own list, one Remote Compose row per product. The app decides which ids it shows; each row is a fixed
 * document ("row?id=…"), so the server can build every row ahead of time.
 */
@Composable
private fun ListTab(source: SectionSource, app: AppState, ids: List<String>, empty: String, onAction: (String) -> Unit) {
    if (ids.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(imageVector = Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(72.dp), tint = TanSoft)
            Text(text = empty, modifier = Modifier.padding(top = 16.dp), color = Muted, fontSize = 16.sp)
        }
        return
    }
    val loaded = remember { mutableStateMapOf<String, Section>() }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)) {
        items(items = ids, key = { it }) { id ->
            Rc(
                path = "row?id=${android.net.Uri.encode(id)}",
                heightDp = BrewSections.ROW_H,
                source = source,
                refreshKey = 0,
                loaded = loaded,
                app = app,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun ProfileTab(app: AppState) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape).background(TanSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "☕", fontSize = 44.sp)
        }
        Text(
            text = "DevFest Yerevan",
            modifier = Modifier.padding(top = 16.dp),
            color = Ink,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = "${app.favorites.size} favorites · ${app.cartCount} in cart", modifier = Modifier.padding(top = 4.dp), color = Muted)
        Text(
            text = "This tab is plain Compose. Home, Menu, Favorites and the product page are Remote Compose sections.",
            modifier = Modifier.padding(top = 24.dp),
            color = Muted,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun Rc(
    path: String,
    heightDp: Int,
    source: SectionSource,
    refreshKey: Int,
    loaded: MutableMap<String, Section>,
    app: AppState,
    onAction: (String) -> Unit,
) {
    RemoteSection(
        path = path,
        source = source,
        refreshKey = refreshKey,
        modifier = Modifier.fillMaxWidth().height(heightDp.dp),
        onLoaded = { p, s -> loaded[p] = s },
        push = app::push,
        onAction = onAction,
    )
}

@Composable
private fun Header(title: String, action: String = "See all", onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, modifier = Modifier.weight(1f), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = onAction) { Text(text = action, color = TanDark) }
    }
}

/** A quiet line under the content: which parts came from the server. Handy on stage, invisible to a "user". */
@Composable
private fun Provenance(sections: Collection<Section>) {
    if (sections.isEmpty()) return
    val live = sections.count { it.origin == Origin.LIVE }
    val bytes = sections.sumOf { it.bytes.size }
    val text =
        if (live == sections.size) {
            "${sections.size} sections from the server · ${"%.1f".format(bytes / 1024.0)} KB"
        } else {
            "offline · ${sections.size - live} of ${sections.size} sections built on the device"
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = if (live == sections.size) Color(BrewTheme.GREEN) else Color(0xFFE5A100)
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text = text, modifier = Modifier.padding(start = 8.dp), color = Muted, fontSize = 12.sp)
    }
}

/** Total and a big button, pinned under the product page and the cart. */
@Composable
private fun TotalBar(totalCents: Int, button: String, onClick: () -> Unit) {
    Surface(shadowElevation = 12.dp, color = Color.White) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Total", color = Muted, fontSize = 14.sp)
                Text(text = BrewCatalog.priceCents(totalCents), color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onClick,
                modifier = Modifier.height(56.dp).width(180.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TanDark),
            ) {
                Text(text = button, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink) }
}

@OptIn(ExperimentalMaterial3Api::class) // TopAppBar is still experimental in Material 3
@Composable
private fun DetailsScreen(
    id: String,
    source: SectionSource,
    totalCents: Int,
    onBack: () -> Unit,
    onValue: (String, Any?) -> Unit,
    onAction: (String) -> Unit,
) {
    Scaffold(
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton(onBack = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tan),
            )
        },
        bottomBar = { TotalBar(totalCents = totalCents, button = "Add to cart") { onAction("order:$id") } },
    ) { pad ->
        // Native scrolling; the product page inside it is one Remote Compose document.
        Column(modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            RemoteSection(
                path = "details?id=${android.net.Uri.encode(id)}",
                source = source,
                refreshKey = 0,
                modifier = Modifier.fillMaxWidth().height(BrewSections.detailsHeight(id).dp),
                reuse = false,
                onNamedValue = onValue,
                onAction = onAction,
            )
        }
    }
}

/** The cart is plain Compose: it is app state, not server UI. */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar is still experimental in Material 3
@Composable
private fun CartScreen(app: AppState, onBack: () -> Unit, onOpen: (String) -> Unit, onCheckout: () -> Unit) {
    Scaffold(
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text(text = "Cart", color = Ink, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackButton(onBack = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
        bottomBar = { if (app.cart.isNotEmpty()) TotalBar(totalCents = app.cartTotalCents, button = "Checkout", onClick = onCheckout) },
    ) { pad ->
        if (app.cart.isEmpty()) {
            Column(
                modifier = Modifier.padding(pad).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(imageVector = Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(72.dp), tint = TanSoft)
                Text(text = "Your cart is empty", modifier = Modifier.padding(top = 16.dp), color = Muted, fontSize = 16.sp)
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(items = app.cart, key = { "${it.id}/${it.note}/${it.unitCents}" }) { line ->
                CartRow(app = app, line = line, onOpen = onOpen)
            }
        }
    }
}

/** One cart line; a tap opens that product's page. */
@Composable
private fun CartRow(app: AppState, line: CartLine, onOpen: (String) -> Unit) {
    val d = BrewCatalog.drink(line.id)
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(line.id) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Color(d.tile)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = d.emoji, fontSize = 32.sp)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(text = d.name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (line.note.isNotEmpty()) Text(text = line.note, color = Muted, fontSize = 13.sp)
            Text(text = "${line.qty} × ${BrewCatalog.priceCents(line.unitCents)}", color = Muted, fontSize = 13.sp)
        }
        Text(
            text = BrewCatalog.priceCents(line.unitCents * line.qty),
            color = Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = { app.cart.remove(line) }) {
            Icon(imageVector = Icons.Filled.Close, contentDescription = "Remove", tint = Muted)
        }
    }
    HorizontalDivider(color = TanSoft)
}
