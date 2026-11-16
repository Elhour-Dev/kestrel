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
  if (!window.kestrelNoAds) {
    window.kestrelNoAds = true;
    var strip = function(o){
      if (!o || typeof o !== 'object') return o;
      ['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'].forEach(function(k){ if (k in o) delete o[k]; });
      if (o.playerResponse) strip(o.playerResponse);
      return o;
    };
    var parse = JSON.parse;
    JSON.parse = function(){ var o = parse.apply(this, arguments); try { strip(o); } catch(e){} return o; };
    try {
      var ipr = window.ytInitialPlayerResponse;
      Object.defineProperty(window, 'ytInitialPlayerResponse', { configurable: true,
        get: function(){ return ipr; }, set: function(v){ ipr = strip(v); } });
      if (ipr) strip(ipr);
    } catch(e){}
  }
  if (window.kestrelLoop) return;
  window.kestrelWant = true;
  // The video the app loaded; YouTube's own autoplay may move the page to another one later.
  window.kestrelId = new URLSearchParams(location.search).get('v');
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
    // YouTube's bot check page ("Sign in to confirm you're not a bot"): reloading won't help.
    if (!window.kestrelErr && (!v || v.readyState < 2) && document.body && /confirm you.{0,3}re not a bot/i.test(document.body.textContent || '')) {
      window.kestrelErr = true; KWeb.botCheck();
    }
    if (!window.kestrelErr && document.querySelector('.ytp-error,.ytp-error-content-wrap,ytm-player-error-message-renderer,.player-error-message')) {
      window.kestrelErr = true; KWeb.error();
    }
    if (!v) return;
    var pv = new URLSearchParams(location.search).get('v') || '';
    if (window.kestrelId && pv && pv !== window.kestrelId) {
      // Not ours (YouTube's autoplay after the end): keep it paused and tell the app.
      if (!v.paused) v.pause();
      KWeb.progress(0, 0, false, pv);
      return;
    }
    // An ad that still got through plays in the same <video>: skip it, and keep it out of position/ended.
    var ad = document.querySelector('.ad-showing,.ad-interrupting');
    if (ad) {
      var skip = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');
      if (skip) skip.click();
      v.muted = true;
      if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.2) v.currentTime = v.duration - 0.1;
      window.kestrelInAd = true;
      return;
    }
    if (window.kestrelInAd) { window.kestrelInAd = false; v.playbackRate = 1; }
    // Playback speed chosen in the app.
    try { var rate = KWeb.rate(); if (rate > 0 && v.playbackRate !== rate) v.playbackRate = rate; } catch(e) {}
    // Quality: report the levels YouTube offers and apply the one the app wants.
    // Re-applied if YouTube drifts off it (ads, network), at most 3 tries per request, 4 s apart.
    try {
      var mp = document.getElementById('movie_player');
      if (mp && mp.getAvailableQualityLevels) {
        var lv = (mp.getAvailableQualityLevels() || []).join(',');
        var cur = (mp.getPlaybackQuality && mp.getPlaybackQuality()) || '';
        if (lv !== window.kestrelLv || cur !== window.kestrelCur) { window.kestrelLv = lv; window.kestrelCur = cur; KWeb.quality(lv, cur); }
        var want = String(KWeb.wanted()).split('|'), w = want[0];
        if (want[1] !== window.kestrelQReq) { window.kestrelQReq = want[1]; window.kestrelQTries = 0; window.kestrelQAt = 0; if (!w && window.kestrelQSet && mp.setPlaybackQualityRange) { window.kestrelQSet = false; mp.setPlaybackQualityRange('auto', 'auto'); } }
        if (cur === w) window.kestrelQTries = 0;
        if (w && lv && cur !== w && mp.setPlaybackQualityRange && window.kestrelQTries < 3 && !(Date.now() - (window.kestrelQAt || 0) < 4000)) {
          window.kestrelQTries++; window.kestrelQAt = Date.now(); window.kestrelQSet = true;
          mp.setPlaybackQualityRange(w, w); mp.setPlaybackQuality && mp.setPlaybackQuality(w);
        }
      }
    } catch(e) {}
    if (!v.kestrelHooked) {
      v.kestrelHooked = true;
      v.addEventListener('ended', function(){ if (!document.querySelector('.ad-showing,.ad-interrupting')) KWeb.ended(); });
    }
    if (v.muted) { v.muted = false; var m = document.querySelector('.ytp-unmute,.ytmUnmuteButton,ytm-unmute-button,button[aria-label*="nmute"]'); m && m.click(); }
    if (window.kestrelWant && v.paused && !v.ended && v.play) { var pr = v.play(); pr && pr.catch && pr.catch(function(){}); }
    if (!window.kestrelWant && !v.paused) v.pause();
    KWeb.progress(Math.round(v.currentTime * 1000), Math.round((v.duration || 0) * 1000), !v.paused, new URLSearchParams(location.search).get('v') || '');
  }, 500);
})();
"""

    // YouTube's page pauses when it thinks it's hidden; keep it "visible" so background play works.
    private const val KEEP_VISIBLE_JS = """
Object.defineProperty(document, 'hidden', { get: function(){ return false; } });
Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; } });
document.addEventListener('visibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
"""

    private val AD_HOSTS = listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com", "imasdk.googleapis.com")
    private val AD_PATHS = listOf("/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info")

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

    /** Heights YouTube's web player offers for the current video (highest first) and the one playing (0 = unknown). */
    data class Quality(val heights: List<Int> = emptyList(), val current: Int = 0)
    private val _quality = kotlinx.coroutines.flow.MutableStateFlow(Quality())
    val quality: kotlinx.coroutines.flow.StateFlow<Quality> = _quality

    /** Playback speed the app wants; the page applies it every half second. */
    @Volatile var speed = 1f

    /** Height the app wants (0 = let YouTube choose). The page asks for it every half second. */
    @Volatile var wantHeight = 0
        set(value) { field = value; wantRequest++ }
    /** Bumped on every request, so picking the same level again retries it. */
    @Volatile private var wantRequest = 0

    private val LEVELS = mapOf(
        "highres" to 4320, "hd2880" to 2880, "hd2160" to 2160, "hd1440" to 1440, "hd1080" to 1080,
        "hd720" to 720, "large" to 480, "medium" to 360, "small" to 240, "tiny" to 144,
    )
    @Volatile private var levels: List<String> = emptyList()

    /** YouTube level for [wantHeight]: the highest one not above it, else the lowest. Null = auto. */
    internal fun pickLevel(available: List<String>, want: Int): String? {
        val known = available.filter { it in LEVELS }.sortedByDescending { LEVELS[it] }
        if (known.isEmpty() || want <= 0) return null
        return known.firstOrNull { LEVELS.getValue(it) <= want } ?: known.last()
    }

    /** Called on the main thread with (positionMs, durationMs). */
    var onProgress: ((Long, Long) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    /** Called on the main thread when the WebView's renderer died and the WebView was released. */
    var onCrash: (() -> Unit)? = null
    /** Called on the main thread when YouTube's page shows its own player error ("Something went wrong"). */
    var onError: (() -> Unit)? = null

    /** Called on the main thread when the page shows YouTube's bot check instead of the video. */
    var onBotCheck: (() -> Unit)? = null
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
                // Ad and ad-tracking requests get an empty answer.
                override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    val u = request.url
                    val host = u.host.orEmpty()
                    val path = u.path.orEmpty()
                    val ad = AD_HOSTS.any { host == it || host.endsWith(".$it") } ||
                        (host.endsWith("youtube.com") && AD_PATHS.any { path.startsWith(it) })
                    return if (ad) android.webkit.WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))) else null
                }
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(PAGE_JS, null)
                }
                // The renderer died (e.g. out of memory): drop the WebView instead of crashing the app.
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    main.post { release(); onCrash?.invoke() }
                    return true
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface
                fun progress(pos: Double, dur: Double, isPlaying: Boolean, page: String) {
                    pageVideo = page.ifBlank { null }
                    // YouTube's page moved on to its own next video (its autoplay; the page pauses it): not ours.
                    if (page.isNotBlank() && videoId != null && page != videoId) {
                        if (playing) { playing = false; changed() }
                        return
                    }
                    val stateChanged = isPlaying != playing || dur.toLong() != durationMs
                    positionMs = pos.toLong()
                    durationMs = dur.toLong()
                    playing = isPlaying
                    main.post { onProgress?.invoke(positionMs, durationMs) }
                    if (stateChanged) changed()
                }
                @JavascriptInterface
                fun quality(lv: String, cur: String) {
                    levels = lv.split(',').filter { it.isNotBlank() }
                    _quality.value = Quality(levels.mapNotNull { LEVELS[it] }.distinct().sortedDescending(), LEVELS[cur] ?: 0)
                    android.util.Log.i("Kestrel", "web quality: levels=$lv current=$cur")
                }
                @JavascriptInterface
                fun rate(): Double = speed.toDouble()
                @JavascriptInterface
                fun wanted(): String = (pickLevel(levels, wantHeight) ?: "") + "|" + wantRequest
                @JavascriptInterface
                fun error() {
                    main.post { onError?.invoke() }
                }
                @JavascriptInterface
                fun botCheck() {
                    main.post { onBotCheck?.invoke() }
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
        pageVideo = null
        positionMs = startSec * 1000
        durationMs = 0
        playing = true
        levels = emptyList()
        _quality.value = Quality()
        val t = if (startSec > 0) "&t=${startSec}s" else ""
        w.loadUrl("https://m.youtube.com/watch?v=$id$t")
        changed()
    }

    /** Video the page itself shows (differs from [videoId] after YouTube's own autoplay). */
    @Volatile private var pageVideo: String? = null

    fun resume() {
        val id = videoId
        if (id != null && pageVideo != null && pageVideo != id) {
            // The page went on to another video: load ours again from the start.
            pageVideo = null
            positionMs = 0
            durationMs = 0
            playing = true
            main.post { view?.loadUrl("https://m.youtube.com/watch?v=$id") }
            changed()
            return
        }
        resumeJs()
    }

    private fun resumeJs() = js("window.kestrelWant=true;var v=document.querySelector('video');v&&v.play();").also { playing = true; changed() }
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
