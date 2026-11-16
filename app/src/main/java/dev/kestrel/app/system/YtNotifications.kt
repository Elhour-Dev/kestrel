package dev.kestrel.app.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.kestrel.app.Graph
import dev.kestrel.app.R
import dev.kestrel.app.data.innertube.InnerTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/**
 * YouTube's own notifications (the bell) for a signed-in account: the unread count for the app's bell,
 * and phone notifications for new entries, checked in the background every 30 minutes.
 */
object YtNotifications {
    private const val CHANNEL = "youtube"
    private const val WORK = "yt-notifications"
    private const val PREFS = "yt_notices"
    private const val KEY_SEEN = "notified_ids"

    private val _unseen = MutableStateFlow(0)
    /** Number for the bell in the top bar. */
    val unseen: StateFlow<Int> = _unseen

    /** Updates the bell count (signed in only). */
    suspend fun refreshCount() {
        if (!Graph.account.isSignedIn) { _unseen.value = 0; return }
        runCatching { Graph.innerTube.unseenNotifications() }.onSuccess { _unseen.value = it }
    }

    /** The inbox was opened: no phone notification for these, and the bell goes to 0 here. */
    fun markRead(items: List<InnerTube.Notice>) {
        _unseen.value = 0
        remember(Graph.app, items.map { it.id })
    }

    /** Starts or stops the background check to match the setting. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!Graph.settings.ytNotifications.value) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<Worker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "YouTube notifications", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Posts phone notifications for inbox entries not shown before. The first run only records what is there. */
    suspend fun check(ctx: Context) {
        if (!Graph.account.isSignedIn || !Graph.settings.ytNotifications.value) return
        val page = Graph.innerTube.notifications()
        refreshCount()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = prefs.getStringSet(KEY_SEEN, null)
        val fresh = page.items.filter { !it.read && (known == null || it.id !in known) }
        if (known != null && canPost(ctx)) {
            fresh.take(5).forEach { post(ctx, it) }
        }
        remember(ctx, page.items.map { it.id })
    }

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun remember(ctx: Context, ids: List<String>) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
        // Keep the newest few hundred ids only.
        val all = (ids + old).distinct().take(300).toSet()
        prefs.edit().putStringSet(KEY_SEEN, all).apply()
    }

    @android.annotation.SuppressLint("MissingPermission") // checked in canPost()
    private fun post(ctx: Context, n: InnerTube.Notice) {
        // Opening goes through the app's link handler, like a shared YouTube link.
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(n.videoId?.let { "https://www.youtube.com/watch?v=$it" } ?: "https://www.youtube.com/"))
            .setPackage(ctx.packageName)
        val pi = PendingIntent.getActivity(ctx, n.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("YouTube")
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(n.id.hashCode(), notif)
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = runCatching { check(applicationContext) }
            .fold({ Result.success() }, { Result.retry() })
    }
}
