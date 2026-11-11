package dev.kestrel.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.kestrel.app.data.net.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes the "n" and signature values of YouTube media links using yt-dlp's EJS solver
 * (assets/ejs, public domain) on YouTube's current player script. Runs in a hidden, offline WebView.
 */
object JsSolver {
    private const val TAG = "JsSolver"
    private const val PLAYER_TTL = 60 * 60 * 1000L

    private var app: Context? = null
    private var web: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val nCache = ConcurrentHashMap<String, String>()
    private val sigCache = ConcurrentHashMap<String, String>()
    private val mutex = Mutex()

    private class Player(val path: String, val at: Long)
    private val players = ConcurrentHashMap<Boolean, Player>()
    private val sources = ConcurrentHashMap<String, String>()
    @Volatile private var current = ""

    fun install(context: Context) {
        app = context.applicationContext
    }

    val available get() = app != null

    class Result(val n: Map<String, String>, val sig: Map<String, String>)

    private fun get(url: String, ua: String): String =
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use {
            check(it.isSuccessful) { "HTTP ${it.code} for $url" }
            it.body.string()
        }

    /**
     * Path of YouTube's current player script. The mobile site uses its own player,
     * and links for the mobile client must be decoded with that one.
     */
    fun playerPath(mobile: Boolean = false): String? {
        val now = System.currentTimeMillis()
        players[mobile]?.takeIf { now - it.at < PLAYER_TTL }?.let { return it.path }
        val path = if (mobile) {
            val html = get("https://m.youtube.com/watch?v=jNQXAC9IVRw", dev.kestrel.app.data.streams.Fallback.MWEB_UA)
            Regex("\"jsUrl\":\"(/s/player/[^\"]+/base\\.js)\"").find(html)?.groupValues?.get(1)
        } else {
            val js = get("https://www.youtube.com/iframe_api", Http.BROWSER_UA)
            Regex("player\\\\?/([0-9a-zA-Z_-]{8})\\\\?/").find(js)?.groupValues?.get(1)?.let { "/s/player/$it/player_ias.vflset/en_US/base.js" }
        } ?: return null
        players[mobile] = Player(path, now)
        return path
    }

    private fun playerSource(path: String): String = sources[path] ?: get("https://www.youtube.com$path", Http.BROWSER_UA).also {
        if (sources.size > 3) sources.clear()
        sources[path] = it
    }

    /** Signature timestamp of the player the solver uses (must match the decoded links). */
    fun signatureTimestamp(mobile: Boolean = false): Int? = runCatching {
        val path = playerPath(mobile) ?: return null
        Regex("signatureTimestamp[=:](\\d+)").find(playerSource(path))?.groupValues?.get(1)?.toInt()
    }.getOrNull()

    /** Forgets the players so the next call downloads them again. */
    fun reset() {
        players.clear()
        nCache.clear()
        sigCache.clear()
    }

    suspend fun solve(n: Collection<String>, sig: Collection<String>, mobile: Boolean = false): Result = mutex.withLock {
        val id = withContext(Dispatchers.IO) { playerPath(mobile) } ?: error("No player found")
        val needN = n.filter { nCache["$id:$it"] == null }.distinct()
        val needSig = sig.filter { sigCache["$id:$it"] == null }.distinct()
        if (needN.isNotEmpty() || needSig.isNotEmpty()) {
            val t0 = System.currentTimeMillis()
            current = withContext(Dispatchers.IO) { playerSource(id) }
            Log.i(TAG, "player $id loaded in ${System.currentTimeMillis() - t0} ms")
            val types = mutableListOf<String>()
            val reqs = buildJsonArray {
                if (needN.isNotEmpty()) { types += "n"; addJsonObject { put("type", "n"); putJsonArray("challenges") { needN.forEach { add(it) } } } }
                if (needSig.isNotEmpty()) { types += "sig"; addJsonObject { put("type", "sig"); putJsonArray("challenges") { needSig.forEach { add(it) } } } }
            }
            val callId = UUID.randomUUID().toString()
            val done = CompletableDeferred<String>()
            pending[callId] = done
            val raw = try {
                withContext(Dispatchers.Main) {
                    ensureWeb()
                    web!!.evaluateJavascript("run('$callId','$id',$reqs)", null)
                }
                withTimeout(30_000) { done.await() }
            } finally {
                pending.remove(callId)
            }
            Log.i(TAG, "solved ${needN.size} n + ${needSig.size} sig in ${System.currentTimeMillis() - t0} ms")
            val out = Json.parseToJsonElement(raw) as JsonObject
            val responses = out["responses"] as? JsonArray ?: error("Solver failed: ${out["error"]}")
            responses.forEachIndexed { i, r ->
                r as JsonObject
                val data = r["data"] as? JsonObject
                if (data == null) {
                    Log.w(TAG, "${types[i]} failed: ${r["error"]?.jsonPrimitive?.content?.take(300)}")
                    return@forEachIndexed
                }
                val cache = if (types[i] == "n") nCache else sigCache
                data.forEach { (k, v) -> cache["$id:$k"] = v.jsonPrimitive.content }
            }
        }
        Result(
            n.mapNotNull { k -> nCache["$id:$k"]?.let { k to it } }.toMap(),
            sig.mapNotNull { k -> sigCache["$id:$k"]?.let { k to it } }.toMap(),
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWeb() {
        if (web == null) {
            ready = CompletableDeferred()
            web = WebView(app!!).apply {
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                addJavascriptInterface(Bridge, "KSolver")
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        Log.w(TAG, "console: ${m.message()} @${m.lineNumber()}")
                        return true
                    }
                }
                loadUrl("file:///android_asset/ejs/solver.html")
            }
        }
        withTimeout(15_000) { ready.await() }
        Log.i(TAG, "solver page ready")
    }

    private object Bridge {
        @JavascriptInterface fun player(): String = current
        @JavascriptInterface fun ready() { JsSolver.ready.complete(Unit) }
        @JavascriptInterface fun done(id: String, json: String) { pending[id]?.complete(json) }
    }

    private val nRegex = Regex("([?&])n=([^&]+)")

    fun nOf(url: String): String? = nRegex.find(url)?.groupValues?.get(2)?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    fun withN(url: String, n: String): String {
        val m = nRegex.find(url) ?: return url
        return url.replaceRange(m.range, "${m.groupValues[1]}n=${java.net.URLEncoder.encode(n, "UTF-8")}")
    }
}
