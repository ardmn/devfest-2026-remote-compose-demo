package brew.remote.sections

import androidx.compose.remote.creation.compose.action.*
import androidx.compose.remote.creation.compose.layout.*
import androidx.compose.remote.creation.compose.modifier.*
import androidx.compose.remote.creation.compose.shapes.*
import androidx.compose.remote.creation.compose.state.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import brew.remote.BrewCatalog
import brew.remote.BrewCatalog.TOPPINGS
import brew.remote.BrewColors
import brew.remote.BrewSections
import brew.remote.components.Divider
import brew.remote.components.Emoji
import brew.remote.components.OptionGroup
import brew.remote.components.StepButton
import brew.remote.components.ToppingChip

/**
 * The whole product page: header, options and quantity. Options, quantity and toppings are document state (MutableRemoteFloat) —
 * the player keeps it, nothing goes back to the server. After every change the document reports the choice — quantity, size and the
 * toppings bit mask — to the app (named host actions with a float value). The app prices that choice from its own catalog, draws the
 * sticky total bar and puts the line in the cart: money is never taken from a document.
 */
@RemoteComposable
@Composable
internal fun DetailsSection(id: String) {
    val d = BrewCatalog.drink(id)
    val qty = remember { MutableRemoteFloat(1f) }
    val large = remember { MutableRemoteFloat(0f) }
    val sugar = remember { listOf(MutableRemoteFloat(1f), MutableRemoteFloat(0f)) }
    val size = remember { listOf(MutableRemoteFloat(1f), MutableRemoteFloat(0f)) }
    val ice = remember { listOf(MutableRemoteFloat(1f), MutableRemoteFloat(0f), MutableRemoteFloat(0f)) }
    val tOn = remember { TOPPINGS.map { MutableRemoteFloat(0f) } }

    // The toppings as a bit mask. An expression's value refreshes on the next frame, so a tap first copies it into a plain
    // variable (evaluated now, from the values the same tap just set) and sends that.
    var mask: RemoteFloat = 0f.rf
    TOPPINGS.indices.forEach { k -> mask = mask + tOn[k] * (1 shl k).toFloat().rf }
    val maskNow = remember { MutableRemoteFloat(0f) }
    val report =
        listOf(
            valueChange(remoteState = maskNow, updatedValue = mask),
            hostAction(name = BrewSections.QTY_ACTION.rs, value = qty),
            hostAction(name = BrewSections.LARGE_ACTION.rs, value = large),
            hostAction(name = BrewSections.TOPPINGS_ACTION.rs, value = maskNow),
        )
    fun act(vararg a: RemoteAction) = combinedAction(*a, *report.toTypedArray())

    RemoteColumn(modifier = RemoteModifier.fillMaxSize().background(BrewColors.Page.rc)) {
        // Tan header continues the app's top bar.
        RemoteRow(
            modifier =
                RemoteModifier.fillMaxWidth()
                    .background(BrewColors.Tan.rc)
                    .padding(start = 24.rdp, top = 4.rdp, end = 8.rdp, bottom = 40.rdp)
        ) {
            RemoteColumn(modifier = RemoteModifier.width(220.rdp)) {
                RemoteText(
                    text = d.name.rs,
                    color = BrewColors.Ink.rc,
                    fontSize = 30.rsp,
                    fontWeight = FontWeight.Bold,
                )
                RemoteText(
                    text = d.desc.rs,
                    modifier = RemoteModifier.padding(top = 8.rdp),
                    color = BrewColors.Text.rc,
                    fontSize = 16.rsp,
                )
                RemoteText(
                    text = BrewCatalog.price(d.price).rs,
                    modifier = RemoteModifier.padding(top = 14.rdp),
                    color = BrewColors.Ink.rc,
                    fontSize = 26.rsp,
                    fontWeight = FontWeight.SemiBold,
                )
                RemoteRow(
                    modifier = RemoteModifier.padding(top = 14.rdp),
                    verticalAlignment = RemoteAlignment.CenterVertically,
                ) {
                    StepButton(
                        label = "−",
                        onClick =
                            act(
                                valueChange(
                                    remoteState = qty,
                                    updatedValue = max(qty - 1f.rf, 1f.rf),
                                )
                            ),
                    )
                    // The quantity, 1…9: nine digits on top of each other, only the one equal to
                    // qty visible.
                    // (RemoteInt.toRemoteString() would do it in one line, but in alpha23 it pulls
                    // in android.icu,
                    // and the sections could no longer be rendered on a plain JVM — see tools/.)
                    RemoteBox(
                        modifier = RemoteModifier.width(40.rdp),
                        contentAlignment = RemoteAlignment.Center,
                    ) {
                        for (k in 1..9) {
                            RemoteText(
                                text = "$k".rs,
                                modifier = RemoteModifier.alpha(1f.rf - min(abs(qty - k.toFloat().rf), 1f.rf)),
                                color = BrewColors.Ink.rc,
                                fontSize = 22.rsp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    StepButton(
                        label = "+",
                        onClick =
                            act(
                                valueChange(
                                    remoteState = qty,
                                    updatedValue = min(qty + 1f.rf, 9f.rf),
                                )
                            ),
                    )
                }
            }
            RemoteBox(
                modifier = RemoteModifier.fillMaxWidth().height(200.rdp),
                contentAlignment = RemoteAlignment.Center,
            ) {
                Emoji(emoji = d.emoji, sizeSp = 110)
            }
        }
        // White sheet with a rounded top, pulled over the header.
        RemoteColumn(modifier = RemoteModifier.fillMaxWidth().background(BrewColors.Tan.rc)) {
            RemoteColumn(
                modifier =
                    RemoteModifier.fillMaxWidth()
                        .clip(
                            RemoteRoundedCornerShape(
                                topStart = RemoteCornerSize(size = 32.rdp),
                                topEnd = RemoteCornerSize(size = 32.rdp),
                                bottomEnd = RemoteCornerSize(size = 0.rdp),
                                bottomStart = RemoteCornerSize(size = 0.rdp),
                            )
                        )
                        .background(BrewColors.Page.rc)
                        .padding(start = 24.rdp, top = 24.rdp, end = 24.rdp, bottom = 16.rdp)
            ) {
                if (!d.hasOptions) {
                    RemoteText(
                        text = "About".rs,
                        color = BrewColors.Ink.rc,
                        fontSize = 20.rsp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    RemoteText(
                        text = ("${d.desc}. Made in our kitchen in Yerevan every morning; " + "ask the barista what goes best with it.").rs,
                        modifier = RemoteModifier.padding(top = 8.rdp),
                        color = BrewColors.Text.rc,
                        fontSize = 15.rsp,
                    )
                } else {
                    OptionGroup(
                        title = "Sugar level",
                        options = listOf("Normal", "Less sugar"),
                        selected = sugar,
                    )
                    Divider()
                    OptionGroup(
                        title = "Size",
                        options = listOf("Regular", "Large (+\$0.60)"),
                        selected = size,
                    ) { i ->
                        listOf(
                            valueChange(
                                remoteState = large,
                                updatedValue = (if (i == 1) 1f else 0f).rf,
                            )
                        ) + report
                    }
                    Divider()
                    OptionGroup(
                        title = "Ice level",
                        options = listOf("Normal", "Less ice", "No ice"),
                        selected = ice,
                    )
                    Divider()
                    RemoteText(
                        text = "Topping".rs,
                        modifier = RemoteModifier.padding(top = 4.rdp),
                        color = BrewColors.Ink.rc,
                        fontSize = 20.rsp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    RemoteText(
                        text = "Optional · pick any".rs,
                        modifier = RemoteModifier.padding(top = 2.rdp, bottom = 12.rdp),
                        color = BrewColors.TanDark.rc,
                        fontSize = 13.rsp,
                    )
                    RemoteRow(
                        modifier = RemoteModifier.fillMaxWidth(),
                        horizontalArrangement = RemoteArrangement.SpaceBetween,
                    ) {
                        TOPPINGS.forEachIndexed { k, t -> ToppingChip(t = t, on = tOn[k], report = report) }
                    }
                }
            }
        }
    }
}
