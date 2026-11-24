package brew.remote.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.RemoteRole
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.state.rc
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
    val fill = rememberNamedRemoteColor(name = "fav_$id", defaultValue = Color.Transparent.rc)
    val line = rememberNamedRemoteColor(name = "favline_$id", defaultValue = BrewColors.Ink.rc)
    RemoteBox(
        modifier =
            RemoteModifier.size(sizeDp.rdp)
                .clip(RemoteCircleShape)
                .clickable(onClick = hostAction(name = "toggle_favorite:$id".rs), role = RemoteRole.Button)
    ) {
        RemoteBox(
            modifier =
                RemoteModifier.fillMaxSize()
                    .background(painterRemoteVector(vector = Icons.Filled.Favorite.toRemoteImageVector(), tintColor = fill))
        )
        RemoteBox(
            modifier =
                RemoteModifier.fillMaxSize()
                    .background(painterRemoteVector(vector = Icons.Filled.FavoriteBorder.toRemoteImageVector(), tintColor = line))
        )
    }
}
