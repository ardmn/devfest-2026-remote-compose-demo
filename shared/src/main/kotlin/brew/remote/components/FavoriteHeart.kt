package brew.remote.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteColor
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.vector.painterRemoteVector
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import brew.remote.BrewColors

/**
 * Favourite toggle: Material's Favorite (fill) and FavoriteBorder (outline) icons. The app owns favourites and pushes their colours as
 * named values — "fav_<id>" red or clear, "favline_<id>" ink or clear (colours, not ints, so the iOS player's controller can set them too).
 * A tap asks the app to toggle.
 */
@RemoteComposable
@Composable
internal fun FavoriteHeart(id: String, sizeDp: Int) {
    val fill = rememberNamedRemoteColor(name = "fav_$id", initialValue = Color.Transparent)
    val line = rememberNamedRemoteColor(name = "favline_$id", initialValue = BrewColors.Ink)
    RemoteBox(
        modifier = RemoteModifier.size(sizeDp.rdp).clip(RemoteCircleShape).clickable(action = hostAction(name = "toggle_favorite:$id".rs))
    ) {
        RemoteBox(modifier = RemoteModifier.fillMaxSize().background(painterRemoteVector(image = Icons.Filled.Favorite, tintColor = fill)))
        RemoteBox(
            modifier = RemoteModifier.fillMaxSize().background(painterRemoteVector(image = Icons.Filled.FavoriteBorder, tintColor = line))
        )
    }
}
