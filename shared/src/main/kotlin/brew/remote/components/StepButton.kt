package brew.remote.components

import androidx.compose.remote.creation.compose.action.RemoteAction
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import brew.remote.BrewColors

/** A round outlined "−" / "+" for the quantity stepper. */
@RemoteComposable
@Composable
internal fun StepButton(label: String, onClick: RemoteAction) {
    Pressable(
        modifier = RemoteModifier.size(32.rdp).border(width = 1.6f.rdp, color = BrewColors.Ink.rc, shape = RemoteCircleShape),
        shape = RemoteCircleShape,
        color = Color.Transparent,
        onClick = onClick,
    ) {
        RemoteText(text = label.rs, color = BrewColors.Ink.rc, fontSize = 20.rsp)
    }
}
