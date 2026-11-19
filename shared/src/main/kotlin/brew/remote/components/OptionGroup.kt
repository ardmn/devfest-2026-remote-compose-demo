package brew.remote.components

import androidx.compose.remote.creation.compose.action.RemoteAction
import androidx.compose.remote.creation.compose.action.combinedAction
import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
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
import androidx.compose.ui.text.font.FontWeight
import brew.remote.BrewColors

/**
 * A radio group: one MutableRemoteFloat per option (1 = selected). A tap sets its own to 1 and the others to 0, plus [extra] (e.g.
 * reporting the new size to the app). The dot's colour is tween(clear, tan, selected) — drawn from a float, which both the androidx and the iOS
 * player redraw on change.
 */
@RemoteComposable
@Composable
internal fun OptionGroup(
    title: String,
    options: List<String>,
    selected: List<MutableRemoteFloat>,
    extra: (Int) -> List<RemoteAction> = { emptyList() },
) {
    RemoteColumn(modifier = RemoteModifier.fillMaxWidth()) {
        RemoteText(
            text = title.rs,
            color = BrewColors.Ink.rc,
            fontSize = 20.rsp,
            fontWeight = FontWeight.SemiBold,
        )
        RemoteText(
            text = "Required · pick 1".rs,
            modifier = RemoteModifier.padding(top = 2.rdp, bottom = 10.rdp),
            color = BrewColors.TanDark.rc,
            fontSize = 13.rsp,
        )
        RemoteRow(modifier = RemoteModifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val select =
                    selected.mapIndexed { j, v -> valueChange(remoteState = v, updatedValue = (if (j == i) 1f else 0f).rf) } + extra(i)
                RemoteRow(
                    modifier =
                        RemoteModifier.padding(end = 10.rdp)
                            .height(36.rdp)
                            .clip(RemoteRoundedCornerShape(size = 18.rdp))
                            .clickable(action = combinedAction(*select.toTypedArray()))
                            .padding(end = 8.rdp),
                    verticalAlignment = RemoteAlignment.CenterVertically,
                ) {
                    RemoteBox(
                        modifier =
                            RemoteModifier.size(22.rdp)
                                .border(
                                    width = 1.6f.rdp,
                                    color = BrewColors.TanDark.rc,
                                    shape = RemoteCircleShape,
                                ),
                        contentAlignment = RemoteAlignment.Center,
                    ) {
                        RemoteBox(
                            modifier =
                                RemoteModifier.size(12.rdp)
                                    .clip(RemoteCircleShape)
                                    .background(
                                        tween(
                                            from = Color.Transparent.rc,
                                            to = BrewColors.TanDark.rc,
                                            tween = selected[i],
                                        )
                                    )
                        )
                    }
                    RemoteText(
                        text = label.rs,
                        modifier = RemoteModifier.padding(start = 8.rdp),
                        color = BrewColors.Text.rc,
                        fontSize = 15.rsp,
                    )
                }
            }
        }
    }
}
