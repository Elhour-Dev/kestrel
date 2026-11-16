package dev.kestrel.app.ui.screens

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.kestrel.app.MainActivity
import dev.kestrel.app.player.NowPlaying
import dev.kestrel.app.player.PlayerManager
import dev.kestrel.app.player.PopupService
import dev.kestrel.app.player.WebHost
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.theme.Sand
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Dim = Color(0x73000000)

/**
 * Our own controls over the video, for both the direct player and YouTube's web player.
 * Tap: show/hide. Double tap left/right third: -10 s / +10 s. Hold: 2x while held.
 * Portrait: swipe up = fullscreen, swipe down = minimize. Fullscreen: swipe left half = brightness, right half = volume.
 */
@UnstableApi
@Composable
fun PlayerControls(np: NowPlaying, activity: MainActivity, fullscreen: Boolean, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var visible by remember { mutableStateOf(false) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var seekBubble by remember { mutableIntStateOf(0) } // -1 back, 1 forward, 0 none
    var bubbleAt by remember { mutableLongStateOf(0L) }
    var holding by remember { mutableStateOf(false) }
    var levelText by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val speed by PlayerManager.speed.collectAsStateWithLifecycle()

    fun poke() { touchedAt = System.currentTimeMillis() }

    LaunchedEffect(np.video.id) {
        // Show once playback really starts, then hide after 3 s like before.
        var shownOnce = false
        while (true) {
            pos = PlayerManager.positionMs()
            dur = PlayerManager.durationMs().takeIf { it > 0 } ?: 0
            playing = PlayerManager.isPlayingOrWill()
            buffering = PlayerManager.isBuffering() || PlayerManager.now.value?.loading == true
            if (!shownOnce && PlayerManager.isPlaying()) { shownOnce = true; visible = true; poke() }
            delay(250)
        }
    }
    LaunchedEffect(visible, touchedAt, playing, dragFraction, showSettings) {
        if (visible && playing && dragFraction == null && !showSettings) {
            delay(3000)
            visible = false
        }
    }
    LaunchedEffect(bubbleAt) { if (bubbleAt > 0) { delay(650); seekBubble = 0 } }
    LaunchedEffect(levelText) { if (levelText != null) { delay(700); levelText = null } }

    if (MainActivity.Ui.inPip || np.error != null) return

    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    BoxWithConstraints(
        modifier
            .semantics { contentDescription = "Show player controls" }
            .pointerInput(np.video.id) {
                var holdJob: Job? = null
                var before = 1f
                detectTapGestures(
                    onPress = {
                        holdJob = scope.launch {
                            delay(600)
                            before = PlayerManager.speed.value
                            holding = true
                            PlayerManager.setSpeed(2f)
                        }
                        tryAwaitRelease()
                        holdJob?.cancel()
                        // Back to the speed the user had chosen.
                        if (holding) { holding = false; PlayerManager.setSpeed(before) }
                    },
                    onTap = { visible = !visible; poke() },
                    onDoubleTap = { o ->
                        when {
                            o.x < size.width / 3f -> { PlayerManager.seekTo(PlayerManager.positionMs() - 10_000); seekBubble = -1; bubbleAt = System.currentTimeMillis() }
                            o.x > size.width * 2 / 3f -> { PlayerManager.seekTo(PlayerManager.positionMs() + 10_000); seekBubble = 1; bubbleAt = System.currentTimeMillis() }
                            else -> { PlayerManager.toggle(); visible = true; poke() }
                        }
                    },
                )
            }
            .pointerInput(fullscreen) {
                var total = 0f
                var mode = 0 // 1 brightness, 2 volume
                var start = 0f
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        total = 0f
                        mode = if (!fullscreen) 0 else if (o.x < size.width / 2f) 1 else 2
                        start = if (mode == 1) currentBrightness(activity)
                        else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        total += dy
                        if (mode != 0) {
                            val v = (start - total / (size.height * 0.8f)).coerceIn(0f, 1f)
                            if (mode == 1) {
                                val lp = activity.window.attributes
                                lp.screenBrightness = v.coerceAtLeast(0.01f)
                                activity.window.attributes = lp
                                levelText = "Brightness ${(v * 100).toInt()}%"
                            } else {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt(), 0)
                                levelText = "Volume ${(v * 100).toInt()}%"
                            }
                        }
                    },
                    onDragEnd = {
                        val limit = with(density) { 60.dp.toPx() }
                        if (mode == 0) when {
                            total < -limit -> activity.setFullscreen(true)
                            total > limit -> nav.back()
                        }
                    },
                )
            },
    ) {
        // Dark veil and buttons.
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (fullscreen) OverlayIcon(Icons.AutoMirrored.Filled.ArrowBack, "Exit fullscreen") { activity.setFullscreen(false) }
                    else OverlayIcon(Icons.Default.KeyboardArrowDown, "Minimize") { nav.back() }
                    if (fullscreen) Text(np.video.title, Modifier.weight(1f).padding(horizontal = 4.dp), color = Color.White,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    else Spacer(Modifier.weight(1f))
                    if (!np.web && textTracks().isNotEmpty()) {
                        val on = captionsOn()
                        OverlayIcon(if (on) Icons.Default.ClosedCaption else Icons.Outlined.ClosedCaption, if (on) "Turn captions off" else "Turn captions on") {
                            toggleCaptions(); poke()
                        }
                    }
                    OverlayIcon(Icons.Outlined.Settings, "Settings") { showSettings = true }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.Default.SkipPrevious, "Previous", 48, enabled = true) { PlayerManager.previous(); poke() }
                    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        if (buffering) CircularProgressIndicator(Modifier.size(48.dp), color = Color.White, strokeWidth = 3.dp)
                        else RoundButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", 64, enabled = true) {
                            PlayerManager.toggle(); poke()
                        }
                    }
                    RoundButton(Icons.Default.SkipNext, "Next", 48, enabled = PlayerManager.hasNext()) { PlayerManager.next(); poke() }
                }
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = if (fullscreen) 36.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val shown = dragFraction?.let { (it * dur).toLong() } ?: pos
                    val timeText = if (np.video.isLive && dur <= 0) "LIVE" else "${fmt(shown)} / ${fmt(dur)}"
                    Text(timeText, Modifier.clip(RoundedCornerShape(50)).background(Dim).padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color.White, style = MaterialTheme.typography.labelLarge)
                    if (speed != 1f) Text("${trim(speed)}x", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(Dim).padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    OverlayIcon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, if (fullscreen) "Exit fullscreen" else "Fullscreen") {
                        activity.setFullscreen(!fullscreen)
                    }
                }
            }
        }

        // Seek bar: full controls when visible, a thin line on the bottom edge otherwise (portrait).
        if (dur > 0 && (visible || !fullscreen)) {
            SeekBar(
                fraction = dragFraction ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
                segments = np.segments.map { (it.startMs.toFloat() / dur) to (it.endMs.toFloat() / dur) },
                active = visible,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (fullscreen) 20.dp else 0.dp),
                onDrag = { dragFraction = it; visible = true; poke() },
                onRelease = { f -> PlayerManager.seekTo((f * dur).toLong()); dragFraction = null; poke() },
            )
        }

        // Feedback bubbles.
        if (seekBubble != 0) Text(if (seekBubble < 0) "« 10 s" else "10 s »",
            Modifier.align(if (seekBubble < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(50)).background(Dim).padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (holding) Text("2x speed", Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(50)).background(Dim)
            .padding(horizontal = 14.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
        levelText?.let {
            Text(it, Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Dim).padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }

    if (showSettings) PlayerSettingsSheet(np, activity) { showSettings = false; poke() }
}

@Composable
private fun OverlayIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(26.dp), tint = Color.White)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Dim).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size((size * 0.6f).dp), tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)) }
}

/** Thin bar on the video's bottom edge; thicker with a knob while the controls show. SponsorBlock parts in sand. */
@Composable
private fun SeekBar(
    fraction: Float,
    segments: List<Pair<Float, Float>>,
    active: Boolean,
    modifier: Modifier,
    onDrag: (Float) -> Unit,
    onRelease: (Float) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    var last by remember { mutableFloatStateOf(fraction) }
    Box(
        modifier.fillMaxWidth().height(if (active) 28.dp else 10.dp)
            .semantics { contentDescription = "Seek bar" }
            .pointerInput(Unit) {
                detectTapGestures { o -> onRelease((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> last = (o.x / size.width).coerceIn(0f, 1f); onDrag(last) },
                    onHorizontalDrag = { c, _ -> c.consume(); last = (c.position.x / size.width).coerceIn(0f, 1f); onDrag(last) },
                    onDragEnd = { onRelease(last) },
                    onDragCancel = { onRelease(last) },
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxWidth().height(if (active) 14.dp else 3.dp)) {
            val y = size.height - 1.5.dp.toPx()
            val h = if (active) 3.dp.toPx() else 2.5.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), h)
            segments.forEach { (a, b) -> drawLine(Sand, Offset(a * size.width, y), Offset(b.coerceAtMost(1f) * size.width, y), h) }
            drawLine(accent, Offset(0f, y), Offset(fraction * size.width, y), h)
            if (active) drawCircle(accent, 6.dp.toPx(), Offset(fraction * size.width, y))
        }
    }
}

private fun currentBrightness(activity: MainActivity): Float {
    val w = activity.window.attributes.screenBrightness
    if (w >= 0) return w
    return runCatching { android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun trim(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

// ---------------- tracks (direct player only) ----------------

private data class TrackChoice(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

private fun choices(type: Int): List<TrackChoice> {
    val p = PlayerManager.player
    return p.currentTracks.groups.filter { it.type == type }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
            val f = g.getTrackFormat(i)
            val name = f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).displayName } ?: "Track ${i + 1}"
            TrackChoice(g, i, name, g.isTrackSelected(i))
        }
    }.distinctBy { it.label }
}

private fun textTracks() = choices(C.TRACK_TYPE_TEXT)
private fun captionsOn() = textTracks().any { it.selected } &&
    !PlayerManager.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

private fun select(type: Int, t: TrackChoice?) {
    val p = PlayerManager.player
    val b = p.trackSelectionParameters.buildUpon()
    if (t == null) b.setTrackTypeDisabled(type, true)
    else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
    p.trackSelectionParameters = b.build()
}

private fun toggleCaptions() {
    if (captionsOn()) select(C.TRACK_TYPE_TEXT, null)
    else select(C.TRACK_TYPE_TEXT, textTracks().firstOrNull())
}

// ---------------- settings sheet ----------------

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun PlayerSettingsSheet(np: NowPlaying, activity: MainActivity, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    var page by remember { mutableStateOf("main") }
    val speed by PlayerManager.speed.collectAsStateWithLifecycle()
    val loop by PlayerManager.loop.collectAsStateWithLifecycle()
    val sleepAt by PlayerManager.sleepAt.collectAsStateWithLifecycle()
    val webQ by WebHost.quality.collectAsStateWithLifecycle()
    val r = np.resolved
    val direct = !np.web
    val qualities: List<Int> = when {
        np.web -> if (webQ.heights.isEmpty()) emptyList() else listOf(0) + webQ.heights
        r != null && !r.isLive && np.localPath == null -> r.qualities()
        else -> emptyList()
    }
    val qualityLabel = when {
        np.web -> if (np.quality <= 0) "Auto" + (webQ.current.takeIf { it > 0 }?.let { " (${it}p)" } ?: "") else webQ.current.takeIf { it > 0 }?.let { "${it}p" } ?: "${np.quality}p"
        else -> r?.pickVideo(np.quality)?.label ?: "Auto"
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        when (page) {
            "main" -> Column(Modifier.padding(bottom = 24.dp)) {
                if (qualities.isNotEmpty()) SheetRow(Icons.Outlined.Tune, "Quality", qualityLabel) { page = "quality" }
                SheetRow(Icons.Outlined.Speed, "Playback speed", if (speed == 1f) "Normal" else "${trim(speed)}x") { page = "speed" }
                if (direct && textTracks().isNotEmpty()) SheetRow(Icons.Outlined.ClosedCaption, "Captions",
                    textTracks().firstOrNull { it.selected && captionsOn() }?.label ?: "Off") { page = "captions" }
                if (direct && choices(C.TRACK_TYPE_AUDIO).size > 1) SheetRow(Icons.Outlined.RecordVoiceOver, "Audio track",
                    choices(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.label ?: "Default") { page = "audio" }
                SheetRow(Icons.Outlined.Bedtime, "Sleep timer", sleepLabel(sleepAt)) { page = "sleep" }
                SheetSwitch(Icons.Outlined.Repeat, "Loop video", loop) { PlayerManager.setLoop(it) }
                if (direct) SheetSwitch(Icons.Outlined.Headphones, "Audio only", np.audioOnly) { PlayerManager.setAudioOnly(it) }
                SheetRow(Icons.Outlined.PictureInPicture, "Picture-in-picture", null) { onDismiss(); activity.enterPip() }
                if (direct) SheetRow(Icons.AutoMirrored.Outlined.OpenInNew, "Popup player", null) {
                    onDismiss()
                    if (PopupService.canDraw(ctx)) {
                        PopupService.start(ctx)
                        nav.back()
                        activity.moveTaskToBack(true)
                    } else {
                        nav.message("Allow \"Display over other apps\" for the popup player")
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")))
                    }
                }
            }
            "quality" -> ChoicePage("Quality for this video", qualities.map { h ->
                val label = if (h <= 0) "Auto" + (webQ.current.takeIf { it > 0 }?.let { " (${it}p)" } ?: "") else "${h}p"
                val on = if (np.web) (if (np.quality <= 0) h == 0 else h == webQ.current) else r?.pickVideo(np.quality)?.height == h
                Triple(label, on) { PlayerManager.setQuality(h); onDismiss() }
            })
            "speed" -> ChoicePage("Playback speed", listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f).map { s ->
                Triple(if (s == 1f) "Normal" else "${trim(s)}x", s == speed) { PlayerManager.setSpeed(s); onDismiss() }
            })
            "captions" -> ChoicePage("Captions", listOf(Triple("Off", !captionsOn()) { select(C.TRACK_TYPE_TEXT, null); onDismiss() }) +
                textTracks().map { t -> Triple(t.label, t.selected && captionsOn()) { select(C.TRACK_TYPE_TEXT, t); onDismiss() } })
            "audio" -> ChoicePage("Audio track", choices(C.TRACK_TYPE_AUDIO).map { t -> Triple(t.label, t.selected) { select(C.TRACK_TYPE_AUDIO, t); onDismiss() } })
            "sleep" -> ChoicePage("Sleep timer", listOf(0 to "Off", 10 to "10 minutes", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", -1 to "End of video").map { (m, label) ->
                val on = when (m) { 0 -> sleepAt == 0L; -1 -> sleepAt == PlayerManager.SLEEP_END_OF_VIDEO; else -> false }
                Triple(label, on) { PlayerManager.setSleep(m); onDismiss(); if (m != 0) nav.message("Sleep timer: $label") }
            })
        }
    }
}

private fun sleepLabel(at: Long): String = when {
    at == 0L -> "Off"
    at == PlayerManager.SLEEP_END_OF_VIDEO -> "End of video"
    else -> "${((at - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min left"
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, null) },
        trailingContent = value?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SheetSwitch(icon: ImageVector, title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(on, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!on) },
    )
}

@Composable
private fun ChoicePage(title: String, rows: List<Triple<String, Boolean, () -> Unit>>) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyColumn {
            items(rows) { (label, on, click) ->
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { Box(Modifier.width(24.dp)) { if (on) Icon(Icons.Default.Check, null) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = click),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
