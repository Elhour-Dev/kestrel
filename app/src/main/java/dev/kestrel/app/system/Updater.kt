package dev.kestrel.app.system

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.kestrel.app.BuildConfig
import dev.kestrel.app.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Request
import java.io.File

data class Release(val versionCode: Int, val name: String, val apkUrl: String, val notes: String, val size: Long)

/** Checks GitHub releases and installs the new APK directly (no browser). */
object Updater {
    private const val API = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"

    /** Tag format: v1.<versionCode>. */
    fun codeFromTag(tag: String): Int? = Regex("v?\\d+\\.(\\d+)").find(tag)?.groupValues?.get(1)?.toIntOrNull()

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Kestrel-Updater")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) error("Update server HTTP ${resp.code}")
            val o = Json.parseToJsonElement(resp.body.string()) as JsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return@withContext null
            val code = codeFromTag(tag) ?: return@withContext null
            val asset = o["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } ?: return@withContext null
            Release(
                code, tag,
                asset["browser_download_url"]!!.jsonPrimitive.content,
                o["body"]?.jsonPrimitive?.content.orEmpty(),
                asset["size"]?.jsonPrimitive?.long ?: 0,
            )
        }
    }

    /** Newer release than the installed one, or null. */
    suspend fun check(): Release? = latest()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    suspend fun download(context: Context, r: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "kestrel-${r.versionCode}.apk")
        val req = Request.Builder().url(r.apkUrl).header("User-Agent", "Kestrel-Updater").build()
        Http.mediaClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}")
            val total = resp.body.contentLength().takeIf { it > 0 } ?: r.size
            resp.body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        }
        out
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
