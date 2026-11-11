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
    private const val PAGE_JS = """
(function(){
  if (!document.getElementById('kestrel-css')) {
    var s = document.createElement('style'); s.id = 'kestrel-css';
    s.textContent = 'html,body{background:#000!important;overflow:hidden!important}' +
      'ytm-mobile-topbar-renderer,header,ytm-pivot-bar-renderer,.watch-below-the-player,ytm-single-column-watch-next-results-renderer,ytm-item-section-renderer,ytm-engagement-panel{display:none!important}' +
      '#player-container-id,.player-container,#player{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:auto!important;height:auto!important;max-height:none!important;padding:0!important;margin:0!important;z-index:9999!important;background:#000!important}' +
      'video{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100%!important;height:100%!important;object-fit:contain!important;z-index:10000!important}';
    document.head.appendChild(s);
  }
  if (window.kestrelLoop) return;
  window.kestrelWant = true;
  window.kestrelLoop = setInterval(function(){
    var v = document.querySelector('video');
    window.kestrelN = (window.kestrelN || 0) + 1;
    if (window.kestrelN % 10 == 1) console.log('KSTAT vis=' + document.visibilityState + ' video=' + !!v + (v ? ' t=' + v.currentTime + ' rs=' + v.readyState + ' paused=' + v.paused + ' src=' + (v.src || '').slice(0, 30) + ' err=' + (v.error && v.error.code) : '') + ' url=' + location.href.slice(0, 60));
    if (!v) return;
    if (!v.kestrelHooked) {
      v.kestrelHooked = true;
      v.addEventListener('ended', function(){ KWeb.ended(); });
    }
    if (v.muted) { v.muted = false; var m = document.querySelector('.ytp-unmute,.ytmUnmuteButton,ytm-unmute-button,button[aria-label*="nmute"]'); m && m.click(); }
    if (window.kestrelWant && v.paused && !v.ended && v.play) { var pr = v.play(); pr && pr.catch && pr.catch(function(e){ console.log('KPLAY ' + e); }); }
    if (!window.kestrelWant && !v.paused) v.pause();
    KWeb.progress(Math.round(v.currentTime * 1000), Math.round((v.duration || 0) * 1000), !v.paused);
  }, 500);
})();
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context): WebView {
        val cm = CookieManager.getInstance()
        Graph.account.session.value?.cookies?.forEach { (k, v) ->
            cm.setCookie("https://m.youtube.com", "$k=$v; Domain=.youtube.com; Path=/; Secure")
        }
        cm.flush()
        return WebView(ctx).apply {
            cm.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = UA
            setBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d("WebHost", m.message().take(300))
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(PAGE_JS, null)
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface
                fun progress(pos: Double, dur: Double, isPlaying: Boolean) {
                    positionMs = pos.toLong()
                    durationMs = dur.toLong()
                    playing = isPlaying
                    main.post { onProgress?.invoke(positionMs, durationMs) }
                }
                @JavascriptInterface
                fun ended() {
                    main.post { onEnded?.invoke() }
                }
            }, "KWeb")
        }
    }

    /** Starts [id] in the web player (creates the WebView on first use). */
    fun play(ctx: Context, id: String, startSec: Long) {
        val w = view ?: create(ctx.applicationContext).also { view = it }
        videoId = id
        positionMs = startSec * 1000
        durationMs = 0
        playing = true
        val t = if (startSec > 0) "&t=${startSec}s" else ""
        w.loadUrl("https://m.youtube.com/watch?v=$id$t")
    }

    fun resume() = js("window.kestrelWant=true;var v=document.querySelector('video');v&&v.play();").also { playing = true }
    fun pause() = js("window.kestrelWant=false;var v=document.querySelector('video');v&&v.pause();").also { playing = false }
    fun seekTo(ms: Long) = js("var v=document.querySelector('video');if(v)v.currentTime=${ms / 1000.0};")

    private fun js(code: String) {
        main.post { view?.evaluateJavascript("(function(){$code})();", null) }
    }

    /** Puts the WebView into [parent] (moving it out of any old one). */
    fun attach(parent: ViewGroup) {
        val w = view ?: return
        if (w.parent === parent) return
        (w.parent as? ViewGroup)?.removeView(w)
        parent.addView(w, ViewGroup.LayoutParams(-1, -1))
    }

    fun detach(parent: ViewGroup) {
        view?.let { if (it.parent === parent) parent.removeView(it) }
    }

    /** Stops playback and frees the WebView. */
    fun release() {
        val w = view ?: return
        view = null
        videoId = null
        playing = false
        (w.parent as? ViewGroup)?.removeView(w)
        w.stopLoading()
        w.loadUrl("about:blank")
        w.destroy()
    }
}
