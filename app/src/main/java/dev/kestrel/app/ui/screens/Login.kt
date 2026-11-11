package dev.kestrel.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.kestrel.app.Graph
import dev.kestrel.app.data.account.AccountStore
import dev.kestrel.app.data.account.Session
import dev.kestrel.app.system.Health
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
    "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26hl%3Den%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
private const val CHROME = "142"
private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROME.0.0.0 Mobile Safari/537.36"
private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROME.0.0.0 Safari/537.36"

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** Pulls account subscriptions into the local list after sign-in. */
fun syncSubscriptions() {
    appScope.launch {
        runCatching { Graph.store.mergeSubscriptions(Graph.innerTube.subscribedChannels()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var desktop by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var finishing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun complete(cookies: Map<String, String>, dataSyncId: String, visitor: String) {
        finishing = true
        val session = Session(cookies, dataSyncId = dataSyncId, visitorData = visitor)
        Graph.account.signIn(session)
        try {
            val info = Graph.innerTube.accountInfo()
            Graph.account.update { it.copy(name = info.name, handle = info.handle, avatar = info.avatar) }
            Health.clearAccountIssue()
            syncSubscriptions()
            nav.message(if (info.name.isNotBlank()) "Signed in as ${info.name}" else "Signed in")
            nav.back()
        } catch (e: Exception) {
            Graph.account.signOut()
            finishing = false
            error = "Sign-in didn't work: ${friendlyError(e)}"
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sign in with Google") },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (desktop) "Use mobile sign-in page" else "Use desktop sign-in page") }, onClick = { menu = false; desktop = !desktop })
                    DropdownMenuItem(text = { Text("Paste cookies instead") }, onClick = { menu = false; paste = true })
                }
            },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            "If Google says the browser isn't secure, open the ⋮ menu and try the desktop page, or paste cookies from a computer.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        Box(Modifier.weight(1f)) {
            key(desktop) {
                LoginWebView(desktop, onLoading = { loading = it }) { cookies, ds, vd ->
                    if (!finishing) scope.launch { complete(cookies, ds, vd) }
                }
            }
            if (finishing) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    if (paste) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { paste = false },
            title = { Text("Paste cookies") },
            text = {
                Column {
                    Text("Sign in to youtube.com on a computer, export cookies (Netscape cookies.txt or a Cookie header) and paste them here.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp).padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = AccountStore.parseAnyCookies(text)
                    if (!AccountStore.hasAuth(c)) error = "Those cookies don't contain a YouTube login (SAPISID missing)."
                    else scope.launch { complete(c, "", "") }
                    paste = false
                }) { Text("Sign in") }
            },
            dismissButton = { TextButton(onClick = { paste = false }) { Text("Cancel") } },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(desktop: Boolean, onLoading: (Boolean) -> Unit, onDone: (Map<String, String>, String, String) -> Unit) {
    val cm = CookieManager.getInstance()
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
        }
    }
    AndroidView(factory = { ctx ->
        // Start clean so an old half-finished login can't interfere.
        cm.removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        WebView(ctx).apply {
            webView = this
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = if (desktop) DESKTOP_UA else MOBILE_UA
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = desktop
            // Look like regular Chrome, not an embedded WebView, so Google allows the login.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                val brands = listOf(
                    UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion(CHROME).setFullVersion("$CHROME.0.0.0").build(),
                    UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(CHROME).setFullVersion("$CHROME.0.0.0").build(),
                    UserAgentMetadata.BrandVersion.Builder().setBrand("Not_A Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build(),
                )
                WebSettingsCompat.setUserAgentMetadata(
                    settings,
                    UserAgentMetadata.Builder()
                        .setBrandVersionList(brands)
                        .setFullVersion("$CHROME.0.0.0")
                        .setPlatform(if (desktop) "Windows" else "Android")
                        .setPlatformVersion(if (desktop) "10.0.0" else "10.0.0")
                        .setMobile(!desktop)
                        .setModel("")
                        .build(),
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) = onLoading(true)
                // Don't crash the app if the WebView's renderer dies; the user can just retry.
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean = true
                override fun onPageFinished(view: WebView, url: String?) {
                    onLoading(false)
                    val u = url ?: return
                    if (!u.contains("youtube.com") || u.contains("accounts.google")) return
                    val raw = cm.getCookie("https://www.youtube.com") ?: return
                    val cookies = AccountStore.parseCookieHeader(raw)
                    if (!AccountStore.hasAuth(cookies)) return
                    view.evaluateJavascript(
                        "(function(){try{return JSON.stringify({d:ytcfg.get('DATASYNC_ID')||'',v:ytcfg.get('VISITOR_DATA')||''})}catch(e){return '{}'}})()"
                    ) { result ->
                        var ds = ""; var vd = ""
                        runCatching {
                            val inner = Json.parseToJsonElement(result).jsonPrimitive.content
                            val o = Json.parseToJsonElement(inner).jsonObject
                            ds = o["d"]?.jsonPrimitive?.content.orEmpty()
                            vd = o["v"]?.jsonPrimitive?.content.orEmpty()
                        }
                        // Session now lives only in encrypted storage; wipe the WebView copy.
                        cm.removeAllCookies(null)
                        cm.flush()
                        WebStorage.getInstance().deleteAllData()
                        view.stopLoading()
                        onDone(cookies, ds, vd)
                    }
                }
            }
            loadUrl(LOGIN_URL)
        }
    }, modifier = Modifier.fillMaxSize())
}
