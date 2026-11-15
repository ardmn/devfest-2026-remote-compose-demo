package brew.remote.components

import androidx.compose.remote.creation.compose.action.RemoteAction
import androidx.compose.remote.creation.compose.action.combinedAction
import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.MutableRemoteFloat
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.state.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import brew.remote.BrewCatalog
import brew.remote.BrewColors

/** A topping chip: a tap toggles [on] (0 / 1); the ring's colour follows it through tween(). */
@RemoteComposable
@Composable
internal fun ToppingChip(
    t: BrewCatalog.Topping,
    on: MutableRemoteFloat,
    report: List<RemoteAction>,
) {
    val toggle = listOf(valueChange(remoteState = on, updatedValue = 1f.rf - on)) + report
    RemoteColumn(
        modifier =
            RemoteModifier.width(62.rdp)
                .clip(RemoteRoundedCornerShape(size = 16.rdp))
                .clickable(action = combinedAction(*toggle.toTypedArray())),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
        RemoteBox(
            modifier =
                RemoteModifier.size(58.rdp)
                    .clip(RemoteCircleShape)
                    .background(BrewColors.TanSoft.rc)
                    .border(
                        width = 2.5f.rdp,
                        color =
                            tween(
                                from = Color.Transparent.rc,
                                to = BrewColors.TanDark.rc,
                                tween = on,
                            ),
                        shape = RemoteCircleShape,
                    ),
            contentAlignment = RemoteAlignment.Center,
        ) {
            Emoji(emoji = t.emoji, sizeSp = 26)
        }
        RemoteText(
            text = t.name.rs,
            modifier = RemoteModifier.padding(top = 6.rdp),
            color = BrewColors.Text.rc,
            fontSize = 12.rsp,
            maxLines = 1,
        )
        RemoteText(
            text = ("+" + BrewCatalog.price(t.price)).rs,
            color = BrewColors.TanDark.rc,
            fontSize = 12.rsp,
        )
    }
}
