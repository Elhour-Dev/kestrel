package dev.kestrel.app.downloads

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import dev.kestrel.app.Graph
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.net.Http
import dev.kestrel.app.data.streams.Resolved
import dev.kestrel.app.data.streams.StreamOption
import dev.kestrel.app.data.streams.Streams
import dev.kestrel.app.player.YtDataSource
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.coroutineContext

@Serializable
enum class DlStatus { Queued, Running, Paused, Merging, Done, Failed }

@Serializable
data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val video: Video,
    val audioOnly: Boolean,
    val quality: Int,
    val status: DlStatus = DlStatus.Queued,
    val done: Long = 0,
    val total: Long = 0,
    val location: String? = null,
    val fileName: String? = null,
    val error: String? = null,
    val videoItag: Int = 0,
    val audioItag: Int = 0,
    val created: Long = System.currentTimeMillis(),
) {
    val progress get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Download queue with several parallel downloads, pause/resume/cancel, and offline files. */
class Downloads(private val context: Context) {
    private val file = File(context.filesDir, "downloads.json")
    private val tmpDir = File(context.cacheDir, "dl").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadItem>> get() = _items

    init {
        // Downloads interrupted by the app closing are paused, not lost.
        _items.update { list -> list.map { if (it.status == DlStatus.Running || it.status == DlStatus.Merging) it.copy(status = DlStatus.Paused) else it } }
        save()
    }

    private fun load(): List<DownloadItem> = try {
        if (file.exists()) json.decodeFromString(ListSerializer(DownloadItem.serializer()), file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save() {
        runCatching { file.writeText(json.encodeToString(ListSerializer(DownloadItem.serializer()), _items.value)) }
    }

    private fun edit(id: String, f: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    val activeCount get() = _items.value.count { it.status == DlStatus.Running || it.status == DlStatus.Merging || it.status == DlStatus.Queued }

    fun enqueue(video: Video, audioOnly: Boolean, quality: Int) {
        _items.update { listOf(DownloadItem(video = video, audioOnly = audioOnly, quality = quality)) + it }
        save()
        schedule()
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        edit(id) { it.copy(status = DlStatus.Paused) }
        save()
        schedule()
    }

    fun resume(id: String) {
        edit(id) { it.copy(status = DlStatus.Queued, error = null) }
        save()
        schedule()
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        tmpFiles(id).forEach { it.delete() }
        _items.update { list -> list.filterNot { it.id == id } }
        save()
        schedule()
    }

    /** Removes a finished download and deletes its file. */
    fun delete(id: String) {
        val item = get(id) ?: return
        item.location?.let { loc ->
            runCatching {
                if (loc.startsWith("content://")) context.contentResolver.delete(Uri.parse(loc), null, null)
                else File(loc).delete()
            }
        }
        cancel(id)
    }

    private fun tmpFiles(id: String) = listOf(File(tmpDir, "$id.v"), File(tmpDir, "$id.a"), File(tmpDir, "$id.out"))

    @Synchronized
    private fun schedule() {
        val max = Graph.settings.maxParallelDownloads.value.coerceIn(1, 5)
        val running = _items.value.count { it.status == DlStatus.Running || it.status == DlStatus.Merging }
        var slots = max - running
        for (item in _items.value.reversed()) {
            if (slots <= 0) break
            if (item.status == DlStatus.Queued && item.id !in jobs) {
                edit(item.id) { it.copy(status = DlStatus.Running) }
                jobs[item.id] = scope.launch { run(item.id) }
                slots--
            }
        }
        if (activeCount > 0) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }

    private suspend fun run(id: String) {
        try {
            val item = get(id) ?: return
            val r = Streams.resolve(item.video.id)
            val (v, a) = choose(r, item)
            edit(id) { it.copy(videoItag = v?.itag ?: 0, audioItag = a?.itag ?: 0, video = it.video.copy(durationSec = r.video.durationSec, title = r.video.title.ifBlank { it.video.title })) }
            val sizes = listOfNotNull(v, a).map { clen(it.url) }
            edit(id) { it.copy(total = sizes.sum()) }
            val vf = File(tmpDir, "$id.v"); val af = File(tmpDir, "$id.a")
            v?.let { fetch(id, it.url, vf, sizes[0]) }
            a?.let { fetch(id, it.url, af, sizes.last()) }
            edit(id) { it.copy(status = DlStatus.Merging) }
            val webm = v != null && v.ext == "webm"
            val out = File(tmpDir, "$id.out")
            when {
                v != null && a != null -> mux(listOf(vf, af), out, webm)
                a != null -> mux(listOf(af), out, false)
                v != null -> vf.copyTo(out, overwrite = true)
            }
            val ext = when { item.audioOnly -> "m4a"; webm -> "webm"; else -> "mp4" }
            val name = safeName(get(id)!!.video.title) + "." + ext
            val location = publish(out, name, item.audioOnly, ext)
            tmpFiles(id).forEach { it.delete() }
            edit(id) { it.copy(status = DlStatus.Done, location = location, fileName = name, done = it.total) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            edit(id) { it.copy(status = DlStatus.Failed, error = friendlyError(e)) }
        } finally {
            jobs.remove(id)
            save()
            schedule()
        }
    }

    private fun choose(r: Resolved, item: DownloadItem): Pair<StreamOption?, StreamOption?> {
        val audio = r.audio.firstOrNull { it.itag == item.audioItag }
            ?: r.audio.filter { it.ext == "m4a" }.maxByOrNull { it.bitrate } ?: r.audio.maxByOrNull { it.bitrate }
        if (item.audioOnly) return null to (audio ?: error("No audio stream"))
        r.videoOnly.firstOrNull { it.itag == item.videoItag }?.let { v ->
            return v to (if (v.ext == "webm") r.audio.filter { it.ext == "webm" }.maxByOrNull { it.bitrate } ?: audio else audio)
        }
        val limit = if (item.quality <= 0) Int.MAX_VALUE else item.quality
        val fit = r.videoOnly.filter { it.height <= limit }
        val bestH = fit.maxOfOrNull { it.height }
        val avc = fit.filter { it.isAvc && it.height == bestH }.firstOrNull()
        val vp9 = fit.filter { it.ext == "webm" && it.height == bestH }.firstOrNull()
        val v = avc ?: vp9 ?: r.videoOnly.filter { it.isAvc }.minByOrNull { it.height }
        if (v == null) {
            val muxed = r.muxed.maxByOrNull { it.height } ?: error("No downloadable stream")
            return muxed to null
        }
        val a = if (v.ext == "webm") r.audio.filter { it.ext == "webm" }.maxByOrNull { it.bitrate } else audio
        return v to (a ?: error("No audio stream"))
    }

    private fun clen(url: String): Long = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private suspend fun fetch(id: String, url: String, dest: File, size: Long) {
        val chunk = 4L * 1024 * 1024
        var pos = if (dest.exists()) dest.length() else 0L
        var rn = 1
        FileOutputStream(dest, true).use { out ->
            val buf = ByteArray(64 * 1024)
            var lastUi = 0L
            while (size <= 0 || pos < size) {
                coroutineContext.ensureActive()
                val last = if (size > 0) minOf(pos + chunk, size) - 1 else pos + chunk - 1
                var attempt = 0
                var got = 0L
                while (true) {
                    try {
                        Http.mediaClient.newCall(YtDataSource.rangedRequest(url, pos, last, rn++)).execute().use { resp ->
                            if (resp.code == 403) error("Blocked by YouTube. Try another network.")
                            if (!resp.isSuccessful) error("Server returned HTTP ${resp.code}")
                            resp.body.byteStream().use { input ->
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    pos += n; got += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastUi > 400) {
                                        lastUi = now
                                        updateDone(id)
                                    }
                                }
                            }
                        }
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (++attempt >= 4) throw e
                        delay(1500L * attempt)
                    }
                }
                if (got == 0L || (size <= 0 && got < chunk)) break
            }
        }
        updateDone(id)
    }

    private fun updateDone(id: String) {
        val done = File(tmpDir, "$id.v").length() + File(tmpDir, "$id.a").length()
        edit(id) { it.copy(done = done) }
    }

    private fun mux(inputs: List<File>, out: File, webm: Boolean) {
        out.delete()
        val muxer = MediaMuxer(out.path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val tracks = inputs.map { f ->
            val ex = MediaExtractor()
            ex.setDataSource(f.path)
            ex.selectTrack(0)
            val fmt = ex.getTrackFormat(0)
            Triple(ex, muxer.addTrack(fmt), fmt)
        }
        muxer.start()
        try {
            val size = tracks.maxOf { (_, _, f) -> if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }
                .coerceAtLeast(8 * 1024 * 1024)
            val buf = ByteBuffer.allocate(size)
            val info = MediaCodec.BufferInfo()
            for ((ex, idx, _) in tracks) {
                while (true) {
                    info.offset = 0
                    info.size = ex.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(idx, buf, info)
                    ex.advance()
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
            tracks.forEach { it.first.release() }
        }
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(120).ifBlank { "video" }

    /** Moves the finished file to the chosen folder. Returns a path or content:// URI. */
    private fun publish(src: File, name: String, audio: Boolean, ext: String): String {
        val mime = when (ext) { "m4a" -> "audio/mp4"; "webm" -> "video/webm"; else -> "video/mp4" }
        val folder = Graph.settings.downloadFolder.value
        if (folder.isNotBlank()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder))
            if (tree != null && tree.canWrite()) {
                val doc = tree.createFile(mime, name) ?: error("Can't create file in the download folder")
                context.contentResolver.openOutputStream(doc.uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
                src.delete()
                return doc.uri.toString()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kestrel")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Can't save file")
            resolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            src.delete()
            return uri.toString()
        }
        val dir = context.getExternalFilesDir(if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)!!
        val dest = File(dir, name)
        src.copyTo(dest, overwrite = true)
        src.delete()
        return dest.path
    }
}
