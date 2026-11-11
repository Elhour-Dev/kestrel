package dev.kestrel.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.kestrel.app.data.account.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gets the streaming token ("pot") that YouTube's own mobile web player uses for the signed-in
 * account: a hidden, muted WebView opens one video page and the token is read from the player's
 * first media request. The token is kept for a few hours.
 */
object PotCapture {
    private const val TAG = "PotCapture"
    private const val TTL = 4 * 60 * 60 * 1000L
    private const val UA = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    private var app: Context? = null
    private val mutex = Mutex()
    @Volatile private var token: Pair<String, Long>? = null
    @Volatile private var owner: String? = null

    /** Copies the body of the player's media requests to the app. */
    private const val HOOK = """
(function () {
  function send(b) {
    try {
      var u8 = b instanceof Uint8Array ? b : (b instanceof ArrayBuffer ? new Uint8Array(b) : null);
      if (!u8) return;
      var s = '';
      for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
      KPot.body(btoa(s));
    } catch (e) {}
  }
  try { KPot.log('hook installed at ' + location.href); } catch (e) {}
  var f = window.fetch;
  window.fetch = function (input, init) {
    try {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      if (String(url).indexOf('videoplayback') >= 0) KPot.log('fetch ' + (typeof input) + ' body=' + (init && init.body ? Object.prototype.toString.call(init.body) : 'none') + ' method=' + ((init && init.method) || (input && input.method)));
      if (String(url).indexOf('videoplayback') >= 0) {
        if (init && init.body) send(init.body);
        else if (input && typeof input.clone === 'function') input.clone().arrayBuffer().then(send, function () {});
      }
    } catch (e) {}
    return f.apply(this, arguments);
  };
  var open = XMLHttpRequest.prototype.open, sendX = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (m, u) { this._k = String(u); return open.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function (b) {
    try { if (this._k && this._k.indexOf('videoplayback') >= 0 && b) send(b); } catch (e) {}
    return sendX.apply(this, arguments);
  };
})();
"""

    /** Reads streamerContext (field 19) -> poToken (field 2) from a SABR request, as base64url. */
    fun tokenFromSabrRequest(b: ByteArray): String? {
        val ctx = field(b, 19) ?: return null
        val tok = field(ctx, 2)?.takeIf { it.size >= 60 } ?: return null // short = placeholder before attestation
        return android.util.Base64.encodeToString(tok, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    }

    private fun layout(b: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        fun varint(): Long { var r = 0L; var sh = 0; while (i < b.size) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl sh); if (x < 0x80) return r; sh += 7 }; return r }
        while (i < b.size) {
            val key = varint(); val n = key ushr 3
            when ((key and 7).toInt()) {
                0 -> out.append("$n:v=${varint()} ")
                1 -> { out.append("$n:f64 "); i += 8 }
                2 -> { val len = varint().toInt(); out.append("$n:len$len "); i += len }
                5 -> { out.append("$n:f32 "); i += 4 }
                else -> { out.append("?"); break }
            }
        }
        return out.toString()
    }

    /** First length-delimited protobuf field with this number, or null. */
    private fun field(b: ByteArray, num: Int): ByteArray? {
        var i = 0
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (i < b.size) {
                val x = b[i++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x < 0x80) return r
                shift += 7
            }
            return r
        }
        while (i < b.size) {
            val key = varint()
            val n = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> varint()
                1 -> i += 8
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || i + len > b.size) return null
                    if (n == num) return b.copyOfRange(i, i + len)
                    i += len
                }
                5 -> i += 4
                else -> return null
            }
        }
        return null
    }

    fun install(context: Context) {
        app = context.applicationContext
    }

    fun clear() {
        token = null
    }

    /** Streaming token for this session, or null when it couldn't be read. */
    suspend fun get(session: Session, videoId: String): String? = mutex.withLock {
        val key = session.dataSyncId
        val now = System.currentTimeMillis()
        token?.takeIf { now - it.second < TTL && owner == key }?.let { return it.first }
        val ctx = app ?: return null
        val found = CompletableDeferred<String>()
        var web: WebView? = null
        val t0 = now
        try {
            withContext(Dispatchers.Main) { web = open(ctx, session, videoId, found) }
            val pot = withTimeoutOrNull(25_000) { found.await() }
            Log.i(TAG, if (pot != null) "token read in ${System.currentTimeMillis() - t0} ms" else "no token found")
            if (pot != null) {
                token = pot to System.currentTimeMillis()
                owner = key
            }
            pot
        } finally {
            withContext(Dispatchers.Main) {
                web?.run {
                    stopLoading()
                    loadUrl("about:blank")
                    destroy()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(ctx: Context, session: Session, videoId: String, found: CompletableDeferred<String>): WebView {
        val cm = CookieManager.getInstance()
        session.cookies.forEach { (k, v) ->
            cm.setCookie("https://m.youtube.com", "$k=$v; Domain=.youtube.com; Path=/; Secure")
        }
        cm.flush()
        val w = WebView(ctx)
        cm.setAcceptThirdPartyCookies(w, true)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.userAgentString = UA
        w.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun body(b64: String) {
                val bytes = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull() ?: return
                val pot = tokenFromSabrRequest(bytes)
                Log.i(TAG, "media request body ${bytes.size} bytes, token=${pot?.length}")
                pot?.let { found.complete(it) }
            }
            @android.webkit.JavascriptInterface
            fun log(m: String) { Log.d(TAG, m) }
        }, "KPot")
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, HOOK, setOf("https://m.youtube.com"))
        } else {
            Log.w(TAG, "document start scripts not supported")
        }
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Keep it silent and start playback so the player requests media.
                view.evaluateJavascript(
                    "setInterval(function(){var v=document.querySelector('video');if(v){v.muted=true;v.play&&v.play();}},500)",
                    null,
                )
            }
        }
        w.loadUrl("https://m.youtube.com/watch?v=$videoId")
        return w
    }
}
