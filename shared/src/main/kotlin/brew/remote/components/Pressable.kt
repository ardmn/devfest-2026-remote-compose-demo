package brew.remote.components

import androidx.compose.remote.creation.compose.action.RemoteAction
import androidx.compose.remote.creation.compose.action.combinedAction
import androidx.compose.remote.creation.compose.action.valueChangeAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.RemoteRole
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.onTouchCancel
import androidx.compose.remote.creation.compose.modifier.onTouchDown
import androidx.compose.remote.creation.compose.modifier.onTouchUp
import androidx.compose.remote.creation.compose.shapes.RemoteShape
import androidx.compose.remote.creation.compose.state.MutableRemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteTimeVariables
import androidx.compose.remote.creation.compose.state.clamp
import androidx.compose.remote.creation.compose.state.lerp
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * A tappable surface of [color] in [shape] with press feedback built from document state only:
 * - while the finger is down, [pressed] = 1 shades it;
 * - a release stores the document clock in [releasedAt]; fade = clamp(1 - (now - releasedAt) * 4) is 1 at release and 0 a quarter second
 *   later, so a quick tap (down and up in the same frame — what iOS delivers) still flashes. It is a function of the clock, not an animated
 *   float: players re-evaluate an animation only where it is used directly, not in expressions built on top of it, while a clock expression
 *   is re-evaluated every frame. The shade colour is lerp(clear, shade, max(pressed, fade)). [onClick] runs on release.
 */
@RemoteComposable
@Composable
internal fun Pressable(
    modifier: RemoteModifier,
    shape: RemoteShape,
    color: Color,
    onClick: RemoteAction,
    content: @Composable @RemoteComposable () -> Unit,
) {
    val pressed = remember { MutableRemoteFloat(0f) }
    val releasedAt = remember { MutableRemoteFloat(-100f) }
    // The clock as a variable of its own (an expression with an id, re-evaluated every frame):
    // valueChangeAction needs an expression id — a bare system variable is rejected by rc-players
    // and ignored by the androidx player.
    val now = remember { MutableRemoteFloat(RemoteTimeVariables.animationTime) }
    val fade = clamp(value = 1f.rf - (now - releasedAt) * 4f.rf, min = 0f.rf, max = 1f.rf)
    val shade = max(pressed, fade)
    RemoteBox(
        modifier = modifier.clip(shape).background(color.rc),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteBox(
            modifier =
                RemoteModifier.fillMaxSize().background(lerp(from = Color.Transparent.rc, to = Color(0x38000000).rc, fraction = shade))
        )
        content()
        // Touch and click on a transparent overlay written AFTER the shade: the capture writes an
        // expression where
        // it is first used, and an action container may only hold actions (rc-players rejects a
        // declaration
        // nested in TouchUp). Written after the shade, the clock expression already exists and the
        // action reuses it.
        RemoteBox(
            modifier =
                RemoteModifier.fillMaxSize()
                    .onTouchDown(valueChangeAction(remoteState = pressed, updatedValue = 1f.rf))
                    .onTouchUp(
                        combinedAction(
                            valueChangeAction(remoteState = pressed, updatedValue = 0f.rf),
                            valueChangeAction(remoteState = releasedAt, updatedValue = now),
                        )
                    )
                    .onTouchCancel(valueChangeAction(remoteState = pressed, updatedValue = 0f.rf))
                    // role: alpha23's clickable wrote Button semantics by default; alpha24's writes none
                    // unless asked, so every clickable in the sections names its role (TalkBack: "button").
                    .clickable(onClick = onClick, role = RemoteRole.Button)
        )
    }
}
