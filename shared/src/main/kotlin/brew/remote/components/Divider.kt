package brew.remote.components

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import brew.remote.BrewColors

/** A hairline between option groups. */
@RemoteComposable
@Composable
internal fun Divider() {
    RemoteBox(modifier = RemoteModifier.fillMaxWidth().padding(top = 16.rdp, bottom = 16.rdp).height(1.rdp).background(BrewColors.Line.rc))
}
