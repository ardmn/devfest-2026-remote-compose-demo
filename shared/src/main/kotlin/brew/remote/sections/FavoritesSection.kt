package brew.remote.sections

import androidx.compose.remote.creation.compose.action.*
import androidx.compose.remote.creation.compose.layout.*
import androidx.compose.remote.creation.compose.modifier.*
import androidx.compose.remote.creation.compose.shapes.*
import androidx.compose.remote.creation.compose.state.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import brew.remote.BrewCatalog
import brew.remote.BrewCatalog.Stage
import brew.remote.BrewColors
import brew.remote.components.AddButton
import brew.remote.components.Emoji
import brew.remote.components.FavoriteHeart

/** Two featured product cards (heart, "+", tap to open). */
@RemoteComposable
@Composable
internal fun FavoritesSection(s: Stage) {
    RemoteRow(
        modifier = RemoteModifier.fillMaxSize().padding(start = 20.rdp, end = 20.rdp),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
    ) {
        s.favorites.take(2).map(BrewCatalog::drink).forEach { ProductCard(d = it) }
    }
}

@RemoteComposable
@Composable
private fun ProductCard(d: BrewCatalog.Drink) {
    val open = hostAction(name = "open_details:${d.id}".rs)
    RemoteColumn(modifier = RemoteModifier.width(170.rdp)) {
        RemoteBox(modifier = RemoteModifier.size(width = 170.rdp, height = 160.rdp)) {
            RemoteBox(
                modifier =
                    RemoteModifier.fillMaxSize()
                        .clip(RemoteRoundedCornerShape(size = 24.rdp))
                        .background(Color(d.tile).rc)
                        .clickable(action = open),
                contentAlignment = RemoteAlignment.Center,
            ) {
                Emoji(emoji = d.emoji, sizeSp = 76)
            }
            RemoteColumn(
                modifier = RemoteModifier.fillMaxSize().padding(top = 10.rdp, end = 10.rdp),
                horizontalAlignment = RemoteAlignment.End,
            ) {
                FavoriteHeart(id = d.id, sizeDp = 30)
                AddButton(id = d.id, sizeDp = 30, modifier = RemoteModifier.padding(top = 10.rdp))
            }
        }
        RemoteColumn(modifier = RemoteModifier.fillMaxWidth().padding(top = 10.rdp).clickable(action = open)) {
            RemoteText(
                text = d.name.rs,
                color = BrewColors.Ink.rc,
                fontSize = 17.rsp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RemoteText(
                text = d.desc.rs,
                modifier = RemoteModifier.padding(top = 2.rdp),
                color = BrewColors.Muted.rc,
                fontSize = 13.rsp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            RemoteText(
                text = BrewCatalog.price(d.price).rs,
                modifier = RemoteModifier.padding(top = 6.rdp),
                color = BrewColors.Ink.rc,
                fontSize = 16.rsp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
