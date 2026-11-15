package brew.remote.sections

import androidx.compose.remote.creation.compose.action.*
import androidx.compose.remote.creation.compose.layout.*
import androidx.compose.remote.creation.compose.modifier.*
import androidx.compose.remote.creation.compose.shapes.*
import androidx.compose.remote.creation.compose.state.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import brew.remote.BrewCatalog.Stage
import brew.remote.BrewColors
import brew.remote.components.Emoji
import brew.remote.components.Pressable

/** Banner [i] of the carousel; the app pages through them (native pager, dots, auto-advance). */
@RemoteComposable
@Composable
internal fun HeroSection(s: Stage, i: Int) {
    val b = s.banners[i.coerceIn(0, s.banners.lastIndex)]
    RemoteBox(modifier = RemoteModifier.fillMaxSize().padding(start = 16.rdp, end = 16.rdp)) {
        RemoteRow(
            modifier = RemoteModifier.fillMaxSize().clip(RemoteRoundedCornerShape(size = 32.rdp)).background(Color(b.color).rc),
            verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
            RemoteBox(
                modifier = RemoteModifier.width(150.rdp).fillMaxHeight(),
                contentAlignment = RemoteAlignment.Center,
            ) {
                Emoji(emoji = b.emoji, sizeSp = 92)
            }
            RemoteColumn(
                modifier = RemoteModifier.fillMaxSize().padding(end = 16.rdp),
                verticalArrangement = RemoteArrangement.Center,
            ) {
                RemoteText(
                    text = b.headline.rs,
                    color = BrewColors.Ink.rc,
                    fontSize = 32.rsp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Pressable(
                    modifier = RemoteModifier.padding(top = 14.rdp).size(width = 140.rdp, height = 36.rdp),
                    shape = RemoteRoundedCornerShape(size = 18.rdp),
                    color = BrewColors.Butter,
                    onClick = hostAction(name = "open_details:${b.target}".rs),
                ) {
                    RemoteText(
                        text = b.cta.rs,
                        color = BrewColors.Ink.rc,
                        fontSize = 13.rsp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
