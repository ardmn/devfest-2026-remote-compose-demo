package brew.remote.sections

import androidx.compose.remote.creation.compose.action.*
import androidx.compose.remote.creation.compose.layout.*
import androidx.compose.remote.creation.compose.modifier.*
import androidx.compose.remote.creation.compose.shapes.*
import androidx.compose.remote.creation.compose.state.*
import androidx.compose.remote.creation.compose.text.RemoteFontWeight
import androidx.compose.remote.creation.compose.text.RemoteTextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import brew.remote.BrewCatalog
import brew.remote.BrewColors
import brew.remote.components.AddButton
import brew.remote.components.Emoji
import brew.remote.components.FavoriteHeart

/**
 * One product row of a list (the Menu tab, Favorites). The app stacks the rows it wants in its own list — so every row is a fixed document
 * the server can build ahead of time, whatever the filter or the favourites are.
 */
@RemoteComposable
@Composable
internal fun RowSection(id: String) {
    val d = BrewCatalog.drink(id)
    RemoteRow(
        modifier = RemoteModifier.fillMaxSize().padding(start = 16.rdp, top = 6.rdp, end = 16.rdp, bottom = 6.rdp),
        verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
        RemoteRow(
            modifier =
                RemoteModifier.width(280.rdp)
                    .fillMaxHeight()
                    .clip(RemoteRoundedCornerShape(size = 20.rdp))
                    .clickable(onClick = hostAction(name = "open_details:${d.id}".rs), role = RemoteRole.Button),
            verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
            RemoteBox(
                modifier = RemoteModifier.size(84.rdp).clip(RemoteRoundedCornerShape(size = 20.rdp)).background(Color(d.tile).rc),
                contentAlignment = RemoteAlignment.Center,
            ) {
                Emoji(emoji = d.emoji, sizeSp = 42)
            }
            RemoteColumn(modifier = RemoteModifier.padding(start = 14.rdp).width(180.rdp)) {
                RemoteText(
                    text = d.name.rs,
                    color = BrewColors.Ink.rc,
                    fontSize = 17.rsp,
                    fontWeight = RemoteFontWeight.SemiBold,
                    maxLines = 1,
                    overflow = RemoteTextOverflow.Ellipsis,
                )
                RemoteText(
                    text = d.desc.rs,
                    modifier = RemoteModifier.padding(top = 2.rdp),
                    color = BrewColors.Muted.rc,
                    fontSize = 13.rsp,
                    maxLines = 1,
                    overflow = RemoteTextOverflow.Ellipsis,
                )
                RemoteText(
                    text = BrewCatalog.price(d.price).rs,
                    modifier = RemoteModifier.padding(top = 6.rdp),
                    color = BrewColors.Ink.rc,
                    fontSize = 16.rsp,
                    fontWeight = RemoteFontWeight.SemiBold,
                )
            }
        }
        RemoteColumn(
            modifier = RemoteModifier.fillMaxWidth(),
            horizontalAlignment = RemoteAlignment.End,
        ) {
            FavoriteHeart(id = d.id, sizeDp = 28)
            AddButton(id = d.id, sizeDp = 30, modifier = RemoteModifier.padding(top = 10.rdp))
        }
    }
}
