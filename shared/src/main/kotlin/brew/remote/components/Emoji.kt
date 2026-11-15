package brew.remote.components

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign

/** An emoji centred in its box: the text is as wide as the box and centred (a glyph's advance is not its width). */
@RemoteComposable
@Composable
internal fun Emoji(emoji: String, sizeSp: Int) {
    RemoteText(
        text = emoji.rs,
        modifier = RemoteModifier.fillMaxWidth(),
        fontSize = sizeSp.rsp,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}
