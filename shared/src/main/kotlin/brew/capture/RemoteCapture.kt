package brew.capture

import android.content.Context
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensityBehavior
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.runtime.Composable
import brew.remote.BrewSections

/**
 * Records [content] — @RemoteComposable composables — into a Remote Compose document of [heightDp] dp. Sizes in the document are dp
 * (RemoteDensityBehavior.Dp): the player scales them to the device.
 */
suspend fun captureSection(
    context: Context,
    heightDp: Int,
    content: @Composable @RemoteComposable () -> Unit,
): ByteArray =
    captureSingleRemoteDocument(
            context = context,
            creationDisplayInfo =
                RemoteCreationDisplayInfo(
                    width = BrewSections.WIDTH,
                    height = heightDp,
                    densityDpi = 160,
                    densityBehavior = RemoteDensityBehavior.Dp,
                ),
            content = content,
        )
        .bytes
