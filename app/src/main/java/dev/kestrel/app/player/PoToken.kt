/*
 * PO token generation through YouTube's BotGuard, run inside a hidden WebView.
 * Adapted from NewPipe (app/src/main/java/org/schabi/newpipe/util/potoken), GPL-3.0-or-later,
 * Copyright (C) NewPipe contributors. Rewritten with coroutines instead of RxJava.
 */
package dev.kestrel.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.kestrel.app.data.net.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.Base64

class PoTokenException(message: String) : Exception(message)

/** One BotGuard session in a WebView. Mints PO tokens until its integrity token expires. */
@SuppressLint("SetJavaScriptEnabled")
private class PoTokenWebView(context: Context) {
    private val webView = WebView(context)
    private val ready = CompletableDeferred<Unit>()
    private val pending = HashMap<String, CompletableDeferred<String>>()
    private var expiresAt = 0L

    companion object {
        private const val TAG = "PoToken"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS = "PoTokenWebView"
    }

    init {
        webView.settings.javaScriptEnabled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, false)
        }
        webView.settings.userAgentString = UA
        webView.settings.blockNetworkLoads = true
        webView.addJavascriptInterface(this, JS)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) fail(PoTokenException("WebView JS error: ${m.message()}"))
                return super.onConsoleMessage(m)
            }
        }
    }

    val isExpired get() = System.currentTimeMillis() > expiresAt

    fun start(html: String) {
        webView.loadDataWithBaseURL(
            "https://www.youtube.com",
            html.replaceFirst("</script>", "\n$JS.downloadAndRunBotguard()</script>"),
            "text/html", "utf-8", null,
        )
    }

    suspend fun awaitReady() = ready.await()

    private fun fail(e: Throwable) {
        if (!ready.isCompleted) ready.completeExceptionally(e)
        synchronized(pending) { pending.values.forEach { it.completeExceptionally(e) }; pending.clear() }
    }

    private fun post(url: String, body: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("x-goog-api-key", GOOGLE_API_KEY)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .post(body.toRequestBody("application/json+protobuf".toMediaType()))
            .build()
        return Http.client.newCall(req).execute().use {
            if (it.code != 200) throw PoTokenException("BotGuard HTTP ${it.code}")
            it.body.string()
        }
    }

    private fun bg(block: () -> String, then: (String) -> Unit) {
        Thread {
            try {
                val r = block()
                Handler(Looper.getMainLooper()).post { then(r) }
            } catch (e: Exception) {
                fail(e)
            }
        }.start()
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        bg({ post("https://www.youtube.com/api/jnn/v1/Create", "[ \"$REQUEST_KEY\" ]") }) { body ->
            val data = try { parseChallengeData(body) } catch (e: Exception) { fail(e); return@bg }
            webView.evaluateJavascript(
                """try {
                    data = $data
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS.onRunBotguardResult(result.botguardResponse)
                    }, function (error) { $JS.onJsInitializationError(error + "\n" + error.stack) })
                } catch (error) { $JS.onJsInitializationError(error + "\n" + error.stack) }""",
                null,
            )
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) = fail(PoTokenException("BotGuard init: $error"))

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        bg({ post("https://www.youtube.com/api/jnn/v1/GenerateIT", "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]") }) { body ->
            val arr = Json.parseToJsonElement(body).jsonArray
            val token = base64ToU8(arr[0].jsonPrimitive.content)
            val seconds = arr[1].jsonPrimitive.long
            expiresAt = System.currentTimeMillis() + (seconds - 600) * 1000
            webView.evaluateJavascript("this.integrityToken = $token") { ready.complete(Unit) }
        }
    }

    suspend fun mint(identifier: String): String {
        val d = CompletableDeferred<String>()
        synchronized(pending) { pending[identifier] = d }
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(
                """try {
                    identifier = "$identifier"
                    u8Identifier = ${u8(identifier.toByteArray())}
                    poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                    poTokenU8String = ""
                    for (i = 0; i < poTokenU8.length; i++) { if (i != 0) poTokenU8String += ","; poTokenU8String += poTokenU8[i] }
                    $JS.onObtainPoTokenResult(identifier, poTokenU8String)
                } catch (error) { $JS.onObtainPoTokenError(identifier, error + "\n" + error.stack) }""",
            ) {}
        }
        return d.await()
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        synchronized(pending) { pending.remove(identifier) }?.completeExceptionally(PoTokenException(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val d = synchronized(pending) { pending.remove(identifier) } ?: return
        try {
            val bytes = poTokenU8.split(",").map { it.trim().toInt().toByte() }.toByteArray()
            d.complete(Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_'))
        } catch (e: Exception) {
            d.completeExceptionally(e)
        }
    }

    fun close() {
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    // ---- helpers (from NewPipe's JavaScriptUtil) ----
    private fun u8(bytes: ByteArray) = "new Uint8Array([" + bytes.joinToString(",") { (it.toInt() and 0xff).toString() } + "])"

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/').replace('.', '='))

    private fun base64ToU8(s: String) = u8(b64(s))

    private fun parseChallengeData(raw: String): String {
        val scrambled = Json.parseToJsonElement(raw).jsonArray
        val challenge = if (scrambled.size > 1 && (scrambled[1] as? JsonPrimitive)?.isString == true) {
            val descrambled = b64(scrambled[1].jsonPrimitive.content).map { (it + 97).toByte() }.toByteArray().decodeToString()
            Json.parseToJsonElement(descrambled).jsonArray
        } else {
            scrambled[0].jsonArray
        }
        fun str(i: Int) = (challenge.getOrNull(i) as? JsonPrimitive)?.contentOrNull
        fun firstString(i: Int) = (challenge.getOrNull(i) as? JsonArray)?.firstOrNull { (it as? JsonPrimitive)?.isString == true }?.jsonPrimitive?.content
        return buildJsonObject {
            put("messageId", str(0))
            putJsonObject("interpreterJavascript") {
                put("privateDoNotAccessOrElseSafeScriptWrappedValue", firstString(1))
                put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", firstString(2))
            }
            put("interpreterHash", str(3))
            put("program", str(4))
            put("globalName", str(5))
            put("clientExperimentsStateBlob", str(7))
        }.toString()
    }
}

/** Gives NewPipeExtractor PO tokens for the WEB client, so playback works on networks YouTube flags. */
object PoTokens : PoTokenProvider {
    private lateinit var appContext: Context
    private val lock = Any()
    private var generator: PoTokenWebView? = null
    private var visitorData: String? = null
    private var streamingPot: String? = null
    @Volatile var broken = false
        private set

    /** Forces a new BotGuard session and visitor ID next time. */
    fun reset() = synchronized(lock) { generator?.let { g -> Handler(Looper.getMainLooper()).post { g.close() } }; generator = null }

    fun install(context: Context) {
        appContext = context.applicationContext
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setPoTokenProvider(this)
    }

    private suspend fun newGenerator(): PoTokenWebView {
        val html = withContext(Dispatchers.IO) { appContext.assets.open("po_token.html").bufferedReader().use { it.readText() } }
        val wv = withContext(Dispatchers.Main) { PoTokenWebView(appContext).also { it.start(html) } }
        try {
            withTimeout(20_000) { wv.awaitReady() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { wv.close() }
            throw e
        }
        return wv
    }

    private fun fresh(force: Boolean): Triple<PoTokenWebView, String, String> = synchronized(lock) {
        val g = generator
        if (g == null || force || g.isExpired) {
            visitorData = dev.kestrel.app.data.streams.Fallback.freshVisitor() ?: run {
                val info = InnertubeClientRequestInfo.ofWebClient()
                info.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
                YoutubeParsingHelper.getVisitorDataFromInnertube(
                    info, NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(), YoutubeParsingHelper.YOUTUBEI_V1_URL, null, false,
                )
            }
            g?.let { old -> Handler(Looper.getMainLooper()).post { old.close() } }
            val created = runBlocking { newGenerator() }
            generator = created
            // The streaming token must be minted once, before any player token.
            streamingPot = runBlocking { withTimeout(10_000) { created.mint(visitorData!!) } }
        }
        Triple(generator!!, visitorData!!, streamingPot!!)
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        Log.i("PoToken", "requested for $videoId broken=$broken")
        if (broken) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null // never block the UI thread
        return try {
            tokenFor(videoId, force = false)
        } catch (e: Exception) {
            Log.w("PoToken", "PO token failed: $e")
            try {
                tokenFor(videoId, force = true)
            } catch (e2: Exception) {
                if (e2.message?.contains("WebView JS error") == true) broken = true
                null
            }
        }
    }

    /** PO token for any identifier, or null when BotGuard is unavailable. Never call on the main thread. */
    fun mint(identifier: String): String? {
        if (broken || Looper.myLooper() == Looper.getMainLooper()) return null
        return runCatching { val (gen, _, _) = fresh(false); runBlocking { withTimeout(10_000) { gen.mint(identifier) } } }.getOrNull()
    }

    private fun tokenFor(videoId: String, force: Boolean): PoTokenResult {
        val (gen, vd, spot) = fresh(force)
        val playerPot = runBlocking { withTimeout(10_000) { gen.mint(videoId) } }
        Log.i("PoToken", "minted for $videoId")
        return PoTokenResult(vd, playerPot, spot)
    }
}
