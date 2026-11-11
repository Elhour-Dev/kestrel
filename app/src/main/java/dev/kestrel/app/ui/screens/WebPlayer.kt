package dev.kestrel.app.ui.screens

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.kestrel.app.player.WebHost

/** Shows the shared web player (see [WebHost]) in this spot. */
@Composable
fun WebPlayer(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK); WebHost.attach(this) } },
        update = { WebHost.attach(it) },
        onRelease = { WebHost.detach(it) },
        modifier = modifier,
    )
}
