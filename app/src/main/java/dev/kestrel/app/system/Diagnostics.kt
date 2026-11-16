package dev.kestrel.app.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.FileProvider
import dev.kestrel.app.BuildConfig
import dev.kestrel.app.Graph
import dev.kestrel.app.data.streams.Streams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Error reports and the app log, so users can send what went wrong. No login data is included. */
object Diagnostics {
    /** This app's own log lines (Android only lets an app read its own). */
    private fun log(lines: Int): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", lines.toString(), "--pid", android.os.Process.myPid().toString())
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }.getOrElse { "(log unavailable: $it)" }
        // Signed-in requests can log cookie or token values in exceptions; keep them out.
        .replace(Regex("(?i)(cookie|authorization|sapisid|token)[=:]\\s*\\S+"), "$1=…")

    private fun network(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        val kinds = listOfNotNull(
            "Wi-Fi".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
            "mobile".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
            "VPN".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
        )
        return kinds.joinToString(" + ").ifEmpty { "other" }
    }

    private fun header(ctx: Context, error: String?): String = buildString {
        appendLine("Plover ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Network: ${network(ctx)}")
        val s = Graph.settings
        appendLine("App proxy: ${if (s.proxy.value.isBlank()) "off" else "on"}, DNS over HTTPS: ${s.dnsOverHttps.value}")
        appendLine("Signed in: ${Graph.account.session.value != null}, links blocked: ${Streams.linksBlocked()}")
        Health.results.value.values.forEach { appendLine("Check ${it.feature.label}: ${if (it.ok) "ok" else it.message}") }
        dev.kestrel.app.player.PlayerManager.now.value?.let { appendLine("Video: ${it.video.id}${if (it.web) " (web player)" else ""}") }
        if (error != null) appendLine("Error: $error")
    }

    /** Copies the error, app/device info and the recent app log to the clipboard. */
    suspend fun copyDetails(ctx: Context, error: String?) {
        val text = withContext(Dispatchers.IO) { header(ctx, error) + "\n--- recent log ---\n" + log(300) }
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Plover error details", text))
    }

    /** Writes the full app log to a file and opens the share sheet. */
    suspend fun shareLog(ctx: Context) {
        val file = withContext(Dispatchers.IO) {
            File(ctx.cacheDir, "logs").apply { mkdirs() }.resolve("kestrel-log.txt").apply {
                writeText(header(ctx, null) + "\n--- log ---\n" + log(5000))
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share app log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
