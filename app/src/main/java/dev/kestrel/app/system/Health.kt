package dev.kestrel.app.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.kestrel.app.Graph
import dev.kestrel.app.data.innertube.SessionExpiredException
import dev.kestrel.app.data.net.Http
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.data.streams.Fallback
import dev.kestrel.app.data.streams.Resolved
import dev.kestrel.app.data.streams.Streams
import dev.kestrel.app.player.YtDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

enum class Feature(val label: String) { Search("Search & browsing"), Streams("Video playback"), Account("Google account") }

data class CheckResult(val feature: Feature, val ok: Boolean, val message: String)

/**
 * Startup health check. Each check tries to fix itself at runtime
 * (refresh client config, clear player caches, switch to a fallback client) before giving up.
 */
object Health {
    private const val TEST_VIDEO = "jNQXAC9IVRw"

    private val _results = MutableStateFlow<Map<Feature, CheckResult>>(emptyMap())
    val results: StateFlow<Map<Feature, CheckResult>> get() = _results

    /** Uses InnerTube search instead of NewPipe when NewPipe search is broken. */
    @Volatile var searchFallback = false

    val broken get() = _results.value.values.filter { !it.ok }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun record(r: CheckResult) {
        _results.value = _results.value + (r.feature to r)
    }

    fun clearAccountIssue() {
        _results.value = _results.value - Feature.Account
    }

    private fun heal() {
        runCatching { YoutubeJavaScriptPlayerManager.clearAllCaches() }
        runCatching { YoutubeParsingHelper.resetClientVersion() }
        NpRepo.init(Graph.settings.region.value, Graph.settings.language.value)
    }

    suspend fun runAll(progress: (String) -> Unit) = coroutineScope {
        progress("Checking YouTube…")
        val s = async { guarded(Feature.Search) { checkSearch() } }
        val p = async { guarded(Feature.Streams) { checkStreams() } }
        val a = async { if (Graph.account.session.value != null) guarded(Feature.Account) { checkAccount() } else null }
        listOfNotNull(s.await(), p.await(), a.await()).forEach(::record)
    }

    private suspend fun guarded(f: Feature, block: suspend () -> CheckResult): CheckResult = try {
        withTimeout(12_000) { block() }
    } catch (e: Exception) {
        CheckResult(f, false, "Check timed out or failed: ${e.message ?: e.javaClass.simpleName}")
    }

    private suspend fun checkSearch(): CheckResult {
        suspend fun once() = NpRepo.search("music").items.isNotEmpty()
        if (runCatching { once() }.getOrDefault(false)) return CheckResult(Feature.Search, true, "OK")
        heal()
        if (runCatching { once() }.getOrDefault(false)) return CheckResult(Feature.Search, true, "Fixed after refresh")
        Graph.innerTube.refreshConfig()
        val ok = runCatching { Graph.innerTube.search("music").items.isNotEmpty() }.getOrDefault(false)
        if (ok) {
            searchFallback = true
            return CheckResult(Feature.Search, true, "Using backup search")
        }
        return CheckResult(Feature.Search, false, "Search isn't working right now. Library and downloads still work.")
    }

    private fun canFetch(r: Resolved): Boolean {
        val url = r.pickAudio()?.url ?: r.pickVideo(360)?.url ?: return r.hls != null
        val req = YtDataSource.rangedRequest(url, 0, 4095, 1)
        return Http.client.newCall(req).execute().use { it.isSuccessful && it.body.bytes().isNotEmpty() }
    }

    private suspend fun checkStreams(): CheckResult {
        suspend fun np() = canFetch(Streams.fromNewPipe(NpRepo.stream(TEST_VIDEO)))
        if (runCatching { np() }.getOrDefault(false)) {
            Streams.preferFallback = false
            return CheckResult(Feature.Streams, true, "OK")
        }
        heal()
        if (runCatching { np() }.getOrDefault(false)) return CheckResult(Feature.Streams, true, "Fixed after refresh")
        if (runCatching { canFetch(Fallback.resolve(TEST_VIDEO)) }.getOrDefault(false)) {
            Streams.preferFallback = true
            return CheckResult(Feature.Streams, true, "Using backup video source")
        }
        return CheckResult(Feature.Streams, false, "Videos can't be played right now. Downloads you already have still play.")
    }

    private suspend fun checkAccount(): CheckResult {
        return try {
            val info = Graph.innerTube.accountInfo()
            if (info.name.isNotBlank()) Graph.account.update { it.copy(name = info.name, handle = info.handle, avatar = info.avatar) }
            CheckResult(Feature.Account, true, "OK")
        } catch (e: SessionExpiredException) {
            CheckResult(Feature.Account, false, "Your Google session expired. Sign in again to see your feed.")
        } catch (e: Exception) {
            Graph.innerTube.refreshConfig()
            try {
                Graph.innerTube.accountInfo()
                CheckResult(Feature.Account, true, "Fixed after refresh")
            } catch (e2: SessionExpiredException) {
                CheckResult(Feature.Account, false, "Your Google session expired. Sign in again to see your feed.")
            } catch (e2: Exception) {
                CheckResult(Feature.Account, false, "Account features aren't working right now.")
            }
        }
    }
}
