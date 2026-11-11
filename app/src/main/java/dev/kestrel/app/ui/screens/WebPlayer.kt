package dev.kestrel.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.kestrel.app.Graph

/** Backup player: YouTube's own mobile web player, used when our player's links get blocked. */
private const val WEB_UA = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

// Hide everything but the player, make it fill the view, and start playback.
private const val ONLY_PLAYER_JS = """
(function(){
  if (!document.getElementById('kestrel-css')) {
    var s = document.createElement('style'); s.id = 'kestrel-css';
    s.textContent = 'html,body{background:#000!important;overflow:hidden!important}' +
      'ytm-mobile-topbar-renderer,header,ytm-pivot-bar-renderer,.watch-below-the-player,ytm-single-column-watch-next-results-renderer,ytm-item-section-renderer,ytm-engagement-panel{display:none!important}' +
      '#player-container-id,.player-container,#player{position:fixed!important;top:0!important;left:0!important;top:0!important;right:0!important;bottom:0!important;width:auto!important;height:auto!important;max-height:none!important;padding:0!important;margin:0!important;z-index:9999!important;background:#000!important}' +
      'video{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100%!important;height:100%!important;object-fit:contain!important;z-index:10000!important}';
    document.head.appendChild(s);
  }
  if (!window.kstat) window.kstat = setInterval(function(){ // TEMP DIAG
    var v = document.querySelector('video');
    console.log('KSTAT url=' + location.href.slice(0,80) + ' title=' + document.title.slice(0,40) + ' video=' + !!v +
      (v ? ' t=' + v.currentTime.toFixed(1) + ' rs=' + v.readyState + ' paused=' + v.paused + ' muted=' + v.muted + ' err=' + (v.error && v.error.code) + ' src=' + (v.src||'').slice(0,40) + ' w=' + v.videoWidth + ' rect=' + JSON.stringify(v.getBoundingClientRect()) + ' vis=' + getComputedStyle(v).visibility + '/' + getComputedStyle(v).opacity + ' top=' + (function(e){return e ? e.tagName + '.' + (e.className||'').toString().slice(0,60) : 'none'})(document.elementFromPoint(innerWidth/2, innerHeight/2)) + ' vw=' + innerWidth + 'x' + innerHeight : '') +
      ' err=' + ((document.querySelector('.ytp-error,.player-error,ytm-player-error-message-renderer')||{}).innerText||'').slice(0,120).replace(/\s+/g,' '));
  }, 3000);
  var tries = 0, t = setInterval(function(){
    var v = document.querySelector('video');
    if (v && v.paused) v.play && v.play();
    if (v && v.muted) { v.muted = false; var m = document.querySelector('.ytp-unmute,.ytmUnmuteButton,ytm-unmute-button,button[aria-label*="nmute"]'); m && m.click(); }
    if ((v && !v.paused && !v.muted) || ++tries > 40) clearInterval(t);
  }, 500);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPlayer(videoId: String, startSec: Long, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            val cm = CookieManager.getInstance()
            Graph.account.session.value?.cookies?.forEach { (k, v) ->
                cm.setCookie("https://m.youtube.com", "$k=$v; Domain=.youtube.com; Path=/; Secure")
            }
            cm.flush()
            WebView(ctx).apply {
                cm.setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString = WEB_UA
                setBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.w("WebPlayer", "DIAG ${m.message().take(300)}") // TEMP DIAG
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(ONLY_PLAYER_JS, null)
                    }
                }
                val t = if (startSec > 0) "&t=${startSec}s" else ""
                loadUrl("https://m.youtube.com/watch?v=$videoId$t")
                tag = videoId
            }
        },
        update = { w ->
            if (w.tag != videoId) {
                w.tag = videoId
                w.loadUrl("https://m.youtube.com/watch?v=$videoId")
            }
        },
        onRelease = { w ->
            w.stopLoading()
            w.loadUrl("about:blank")
            w.destroy()
        },
        modifier = modifier,
    )
}
