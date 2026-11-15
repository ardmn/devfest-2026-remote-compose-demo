package brew.remote.sections

import androidx.compose.remote.creation.compose.action.*
import androidx.compose.remote.creation.compose.layout.*
import androidx.compose.remote.creation.compose.modifier.*
import androidx.compose.remote.creation.compose.shapes.*
import androidx.compose.remote.creation.compose.state.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import brew.remote.BrewCatalog.CATEGORIES
import brew.remote.BrewColors
import brew.remote.components.Emoji

/**
 * Category circles: shortcuts the app turns into the Menu tab's filter ("category:<key>"). The selected one is app state, pushed as named
 * colours: "cat_<key>" (circle) and "catline_<key>" (bar under the label).
 */
@RemoteComposable
@Composable
internal fun CategoriesSection() {
    RemoteRow(
        modifier = RemoteModifier.fillMaxSize().padding(start = 12.rdp, end = 12.rdp),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
    ) {
        CATEGORIES.forEach { (key, emoji, label) ->
            val circle = rememberNamedRemoteColor(name = "cat_$key", initialValue = BrewColors.TanSoft)
            val bar = rememberNamedRemoteColor(name = "catline_$key", initialValue = Color.Transparent)
            RemoteColumn(
                modifier =
                    RemoteModifier.width(68.rdp)
                        .clip(RemoteRoundedCornerShape(size = 16.rdp))
                        .clickable(action = hostAction(name = "category:$key".rs)),
                horizontalAlignment = RemoteAlignment.CenterHorizontally,
            ) {
                RemoteBox(
                    modifier = RemoteModifier.size(62.rdp).clip(RemoteCircleShape).background(circle),
                    contentAlignment = RemoteAlignment.Center,
                ) {
                    Emoji(emoji = emoji, sizeSp = 28)
                }
                RemoteText(
                    text = label.rs,
                    modifier = RemoteModifier.padding(top = 6.rdp),
                    color = BrewColors.Text.rc,
                    fontSize = 14.rsp,
                )
                RemoteBox(
                    modifier =
                        RemoteModifier.padding(top = 3.rdp)
                            .size(width = 18.rdp, height = 3.rdp)
                            .clip(RemoteRoundedCornerShape(size = 1.5f.rdp))
                            .background(bar)
                )
            }
        }
    }
}
