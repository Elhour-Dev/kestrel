package dev.kestrel.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

private const val MAX_CLIP_MS = 60_000L
private const val MIN_CLIP_MS = 5_000L

/** Cuts 5-60 s out of a downloaded video on the phone and shares the file. Nothing is posted to YouTube. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipDialog(source: String, title: String, positionMs: Long, durationMs: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val dur = durationMs.coerceAtLeast(MIN_CLIP_MS)
    val start0 = positionMs.coerceIn(0, (dur - MIN_CLIP_MS).coerceAtLeast(0))
    var range by remember { mutableStateOf(start0.toFloat()..(start0 + 30_000).coerceAtMost(dur).toFloat()) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    DisposableEffect(Unit) { onDispose { transformer?.cancel() } }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Clip") },
        text = {
            Column {
                Text("${fmtClip(range.start.toLong())} – ${fmtClip(range.endInclusive.toLong())}  (${((range.endInclusive - range.start) / 1000).toInt()} s)",
                    style = MaterialTheme.typography.bodyLarge)
                RangeSlider(
                    value = range,
                    onValueChange = { r ->
                        // Keep it between 5 and 60 seconds, moving the other end along.
                        var a = r.start; var b = r.endInclusive
                        if (b - a > MAX_CLIP_MS) { if (a != range.start) b = a + MAX_CLIP_MS else a = b - MAX_CLIP_MS }
                        if (b - a < MIN_CLIP_MS) { if (a != range.start) a = b - MIN_CLIP_MS else b = a + MIN_CLIP_MS }
                        range = a.coerceIn(0f, dur.toFloat())..b.coerceIn(0f, dur.toFloat())
                    },
                    valueRange = 0f..dur.toFloat(),
                    enabled = !working,
                )
                Text("Made on your phone from the downloaded file, then shared. Nothing is posted to YouTube.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (working) LinearProgressIndicator(Modifier.padding(top = 12.dp))
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                working = true
                error = null
                transformer = exportClip(ctx, source, range.start.toLong(), range.endInclusive.toLong(),
                    onDone = { file -> working = false; shareClip(ctx, file, title); onDismiss() },
                    onError = { working = false; error = "Couldn't make the clip: $it" })
            }) { Text("Share clip") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(UnstableApi::class)
private fun exportClip(ctx: Context, source: String, fromMs: Long, toMs: Long, onDone: (File) -> Unit, onError: (String) -> Unit): Transformer {
    val dir = File(ctx.cacheDir, "clips").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val out = File(dir, "clip.mp4")
    val uri = if (source.startsWith("content:") || source.startsWith("file:")) Uri.parse(source) else Uri.fromFile(File(source))
    val item = MediaItem.Builder().setUri(uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(fromMs).setEndPositionMs(toMs).build())
        .build()
    val t = Transformer.Builder(ctx)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) = onDone(out)
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                onError(exportException.errorCodeName)
        })
        .build()
    t.start(EditedMediaItem.Builder(item).build(), out.absolutePath)
    return t
}

private fun shareClip(ctx: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, "Clip: $title")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share clip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun fmtClip(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
