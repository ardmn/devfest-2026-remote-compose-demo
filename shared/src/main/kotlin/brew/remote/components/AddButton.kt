package brew.remote.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.vector.painterRemoteVector
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import brew.remote.BrewColors

/**
 * Green round "+" (Material's Add icon): asks the app to add product [id] to the cart. Put spacing in [modifier], not in a wrapping box:
 * the androidx player hit-tests a child of a padded wrapper at the wrong place (taps on the heart above landed on this button).
 */
@RemoteComposable
@Composable
internal fun AddButton(id: String, sizeDp: Int, modifier: RemoteModifier = RemoteModifier) {
    Pressable(
        modifier = modifier.size(sizeDp.rdp),
        shape = RemoteCircleShape,
        color = BrewColors.Green,
        onClick = hostAction(name = "add_to_cart:$id".rs),
    ) {
        RemoteBox(
            modifier =
                RemoteModifier.size((sizeDp * 0.7f).rdp)
                    .background(painterRemoteVector(vector = Icons.Filled.Add.toRemoteImageVector(), tintColor = Color.White.rc))
        )
    }
}
