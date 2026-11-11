package dev.kestrel.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kestrel.app.BuildConfig
import dev.kestrel.app.Graph
import dev.kestrel.app.R
import dev.kestrel.app.system.Health
import dev.kestrel.app.system.Release
import dev.kestrel.app.system.Updater
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun Logo(size: Int = 96) {
    Box(
        Modifier.size(size.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFFE3803F), androidx.compose.ui.graphics.Color(0xFFA8461A)))),
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
    }
}

@Composable
fun StartupScreen(onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Starting…") }
    var update by remember { mutableStateOf<Release?>(null) }
    var downloading by remember { mutableStateOf<Float?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    val next = if (Graph.settings.onboardingDone.value) "home" else "onboarding"

    LaunchedEffect(Unit) {
        if (!Health.isOnline(ctx)) {
            status = "Offline — opening your library"
            onDone(next)
            return@LaunchedEffect
        }
        coroutineScope {
            val up = async { withTimeoutOrNull(6_000) { runCatching { Updater.check() }.getOrNull() } }
            val health = async { withTimeoutOrNull(15_000) { Health.runAll { status = it } } }
            status = "Checking for updates…"
            val rel = up.await()
            if (rel != null && rel.versionCode != Graph.settings.skippedUpdate.value) {
                update = rel
            }
            status = "Checking YouTube…"
            health.await()
            // A broken feature makes an update worth offering even if it was skipped before.
            if (update == null && rel != null && Health.broken.isNotEmpty()) update = rel
        }
        finished = true
    }

    Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(112)
            Spacer(Modifier.height(16.dp))
            Text("Kestrel", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(Modifier.width(160.dp))
            Spacer(Modifier.height(12.dp))
            AnimatedContent(status, label = "status") { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        }
        Text("v${BuildConfig.VERSION_NAME}", Modifier.align(Alignment.BottomCenter).padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    update?.let { rel ->
        val brokenNote = if (Health.broken.isNotEmpty()) "\n\nSome features aren't working right now; this update may fix them." else ""
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Update available") },
            text = {
                Column {
                    Text("Version ${rel.name} is ready (you have v${BuildConfig.VERSION_NAME}).$brokenNote")
                    downloading?.let {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth())
                    }
                    updateError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(enabled = downloading == null, onClick = {
                    downloading = 0f
                    scope.launch {
                        try {
                            val apk = Updater.download(ctx, rel) { downloading = it }
                            Updater.install(ctx, apk)
                            downloading = null
                        } catch (e: Exception) {
                            downloading = null
                            updateError = friendlyError(e)
                        }
                    }
                }) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Graph.settings.skippedUpdate.value = rel.versionCode
                    update = null
                }) { Text("Skip") }
            },
        )
    }
    // If the update dialog is up when checks finish, continue once it is closed.
    LaunchedEffect(finished, update) { if (finished && update == null) onDone(next) }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val nav = LocalNav.current
    var page by remember { mutableStateOf(0) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { page = 2 }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        AnimatedContent(page, label = "page") { p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (p) {
                    0 -> {
                        Logo(120)
                        Spacer(Modifier.height(20.dp))
                        Text("Welcome to Kestrel", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text("A free, ad-free way to watch videos. No ads, no tracking, sponsor segments skipped for you.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(28.dp))
                        Feature(Icons.Default.Block, "No ads", "Videos play without any ads.")
                        Feature(Icons.Default.FastForward, "Sponsor skipping", "Sponsors, intros and reminders skipped automatically.")
                        Feature(Icons.Default.PictureInPicture, "Background & popup", "Keep listening with the screen off, or watch in a floating window.")
                        Feature(Icons.Default.Download, "Downloads", "Save videos or audio to watch offline.")
                    }
                    1 -> {
                        Text("Stay in the loop", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text("Allow notifications so you can control playback and see download progress.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        Logo(80)
                        Spacer(Modifier.height(16.dp))
                        Text("Sign in with Google?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Signing in brings your recommendations, subscriptions, history, Watch later, playlists, likes and comments. " +
                                "You'll use Google's own sign-in page. Your session stays encrypted on this phone.\n\nYou can skip this and sign in later from Settings.",
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        when (page) {
            0 -> Button(onClick = { page = 1 }, Modifier.fillMaxWidth()) { Text("Get started") }
            1 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { page = 2 }, Modifier.weight(1f)) { Text("Not now") }
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else page = 2
                }, Modifier.weight(1f)) { Text("Allow") }
            }
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    Graph.settings.onboardingDone.value = true
                    Graph.settings.loginPromptDone.value = true
                    onDone()
                }, Modifier.weight(1f)) { Text("Skip") }
                Button(onClick = {
                    Graph.settings.onboardingDone.value = true
                    Graph.settings.loginPromptDone.value = true
                    onDone()
                    nav.go("login")
                }, Modifier.weight(1f)) { Text("Sign in with Google") }
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
