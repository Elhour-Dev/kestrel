package dev.kestrel.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.kestrel.app.Graph

/**
 * Backup player: YouTube's own mobile web player in one shared WebView.
 * Used when this network blocks our stream links. Lives as long as playback, not as long as a screen,
 * so the mini player, PiP and background play keep working.
 */
object WebHost {
    private const val UA = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    // Hide everything but the video, keep it playing with sound, and report progress to the app.
    // Runs at document start and again when the page finishes; safe to run twice.
    private const val PAGE_JS = """
(function(){
  if (window.kestrelLoop) return;
  window.kestrelWant = true;
  window.kestrelLoop = setInterval(function(){
  if (document.head && !document.getElementById('kestrel-css')) {
    var s = document.createElement('style'); s.id = 'kestrel-css';
    s.textContent = 'html,body{background:#000!important;overflow:hidden!important}' +
      'ytm-mobile-topbar-renderer,header,ytm-pivot-bar-renderer,.watch-below-the-player,ytm-single-column-watch-next-results-renderer,ytm-item-section-renderer,ytm-engagement-panel{display:none!important}' +
      '#player-container-id,.player-container,#player{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:auto!important;height:auto!important;max-height:none!important;padding:0!important;margin:0!important;z-index:9999!important;background:#000!important}' +
      'video{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100%!important;height:100%!important;object-fit:contain!important;z-index:10000!important}';
    document.head.appendChild(s);
  }
    var v = document.querySelector('video');
    if (!v) return;
    if (!v.kestrelHooked) {
      v.kestrelHooked = true;
      v.addEventListener('ended', function(){ KWeb.ended(); });
    }
    if (v.muted) { v.muted = false; var m = document.querySelector('.ytp-unmute,.ytmUnmuteButton,ytm-unmute-button,button[aria-label*="nmute"]'); m && m.click(); }
    if (window.kestrelWant && v.paused && !v.ended && v.play) { var pr = v.play(); pr && pr.catch && pr.catch(function(){}); }
    if (!window.kestrelWant && !v.paused) v.pause();
    KWeb.progress(Math.round(v.currentTime * 1000), Math.round((v.duration || 0) * 1000), !v.paused);
  }, 500);
})();
"""

    // YouTube's page pauses when it thinks it's hidden; keep it "visible" so background play works.
    private const val KEEP_VISIBLE_JS = """
Object.defineProperty(document, 'hidden', { get: function(){ return false; } });
Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; } });
document.addEventListener('visibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
"""

    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    var videoId: String? = null
        private set

    @Volatile var positionMs = 0L
        private set
    @Volatile var durationMs = 0L
        private set
    @Volatile var playing = false
        private set

    /** Called on the main thread with (positionMs, durationMs). */
    var onProgress: ((Long, Long) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    /** Called on the main thread when play/pause, duration or the video changes. */
    var onChange: (() -> Unit)? = null

    private fun changed() = main.post { onChange?.invoke() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context): WebView {
        val cm = CookieManager.getInstance()
        Graph.account.session.value?.cookies?.forEach { (k, v) ->
            cm.setCookie("https://m.youtube.com", "$k=$v; Domain=.youtube.com; Path=/; Secure")
        }
        cm.flush()
        // Ignore "window hidden": otherwise Chromium pauses the video when the app goes to the background.
        return object : WebView(ctx) {
            override fun onWindowVisibilityChanged(visibility: Int) = super.onWindowVisibilityChanged(android.view.View.VISIBLE)
        }.apply {
            cm.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = UA
            setBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage) = true // keep YouTube's page logs out of logcat
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(PAGE_JS, null)
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface
                fun progress(pos: Double, dur: Double, isPlaying: Boolean) {
                    val stateChanged = isPlaying != playing || dur.toLong() != durationMs
                    positionMs = pos.toLong()
                    durationMs = dur.toLong()
                    playing = isPlaying
                    main.post { onProgress?.invoke(positionMs, durationMs) }
                    if (stateChanged) changed()
                }
                @JavascriptInterface
                fun ended() {
                    main.post { onEnded?.invoke() }
                }
            }, "KWeb")
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, KEEP_VISIBLE_JS + PAGE_JS, setOf("https://m.youtube.com"))
            }
        }
    }

    /** Starts [id] in the web player (creates the WebView on first use). */
    fun play(ctx: Context, id: String, startSec: Long) {
        val w = view ?: create(ctx.applicationContext).also { view = it; park(it) }
        videoId = id
        positionMs = startSec * 1000
        durationMs = 0
        playing = true
        val t = if (startSec > 0) "&t=${startSec}s" else ""
        w.loadUrl("https://m.youtube.com/watch?v=$id$t")
        changed()
    }

    fun resume() = js("window.kestrelWant=true;var v=document.querySelector('video');v&&v.play();").also { playing = true; changed() }
    fun pause() = js("window.kestrelWant=false;var v=document.querySelector('video');v&&v.pause();").also { playing = false; changed() }
    fun seekTo(ms: Long) = js("var v=document.querySelector('video');if(v)v.currentTime=${ms / 1000.0};").also { positionMs = ms; changed() }

    private fun js(code: String) {
        main.post { view?.evaluateJavascript("(function(){$code})();", null) }
    }

    /**
     * 1-pixel spot in the activity that holds the WebView while no screen shows it.
     * A WebView that isn't in a window stops its media, so this keeps playback going.
     */
    var parking: ViewGroup? = null
        set(value) {
            field = value
            view?.let { if (it.parent == null) park(it) }
        }

    private fun park(w: WebView) {
        (w.parent as? ViewGroup)?.removeView(w)
        parking?.addView(w, ViewGroup.LayoutParams(1, 1))
    }

    /** Puts the WebView into [parent] (moving it out of any old one). */
    fun attach(parent: ViewGroup) {
        val w = view ?: return
        if (w.parent === parent) return
        (w.parent as? ViewGroup)?.removeView(w)
        parent.addView(w, ViewGroup.LayoutParams(-1, -1))
    }

    fun detach(parent: ViewGroup) {
        view?.let { if (it.parent === parent) park(it) }
    }

    /** Called when the activity holding [p] goes away. */
    fun unpark(p: ViewGroup) {
        if (parking !== p) return
        view?.let { if (it.parent === p) p.removeView(it) }
        parking = null
    }

    /** Stops playback and frees the WebView. */
    fun release() {
        val w = view ?: return
        view = null
        videoId = null
        playing = false
        changed()
        (w.parent as? ViewGroup)?.removeView(w)
        w.stopLoading()
        w.loadUrl("about:blank")
        w.destroy()
    }
}
