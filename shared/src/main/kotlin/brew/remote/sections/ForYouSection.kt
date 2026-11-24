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
import brew.remote.BrewCatalog.Stage
import brew.remote.BrewColors
import brew.remote.components.Emoji
import brew.remote.components.FavoriteHeart

/** Three small recommended products. */
@RemoteComposable
@Composable
internal fun ForYouSection(s: Stage) {
    RemoteRow(
        modifier = RemoteModifier.fillMaxSize().padding(start = 16.rdp, end = 16.rdp),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
    ) {
        s.forYou.take(3).map(BrewCatalog::drink).forEach { d ->
            RemoteColumn(modifier = RemoteModifier.width(108.rdp)) {
                RemoteBox(modifier = RemoteModifier.size(width = 108.rdp, height = 100.rdp)) {
                    RemoteBox(
                        modifier =
                            RemoteModifier.fillMaxSize()
                                .clip(RemoteRoundedCornerShape(size = 20.rdp))
                                .background(Color(d.tile).rc)
                                .clickable(onClick = hostAction(name = "open_details:${d.id}".rs), role = RemoteRole.Button),
                        contentAlignment = RemoteAlignment.Center,
                    ) {
                        Emoji(emoji = d.emoji, sizeSp = 48)
                    }
                    RemoteColumn(
                        modifier = RemoteModifier.fillMaxSize().padding(top = 6.rdp, end = 6.rdp),
                        horizontalAlignment = RemoteAlignment.End,
                    ) {
                        FavoriteHeart(id = d.id, sizeDp = 24)
                    }
                }
                RemoteText(
                    text = d.name.rs,
                    modifier = RemoteModifier.padding(top = 8.rdp),
                    color = BrewColors.Ink.rc,
                    fontSize = 14.rsp,
                    fontWeight = RemoteFontWeight.SemiBold,
                    maxLines = 1,
                    overflow = RemoteTextOverflow.Ellipsis,
                )
                RemoteText(
                    text = BrewCatalog.price(d.price).rs,
                    color = BrewColors.Muted.rc,
                    fontSize = 13.rsp,
                )
            }
        }
    }
}
