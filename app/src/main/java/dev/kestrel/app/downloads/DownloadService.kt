package dev.kestrel.app.downloads

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.kestrel.app.Graph
import dev.kestrel.app.KestrelApp
import dev.kestrel.app.MainActivity
import dev.kestrel.app.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Foreground service that keeps downloads running and shows their progress. */
class DownloadService : LifecycleService() {
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!started) {
            started = true
            ServiceCompat.startForeground(
                this, 7, build(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            lifecycleScope.launch {
                Graph.downloads.items.collectLatest {
                    if (Graph.downloads.activeCount == 0) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(android.app.NotificationManager::class.java).notify(7, build())
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(): android.app.Notification {
        val items = Graph.downloads.items.value
        val active = items.filter { it.status == DlStatus.Running || it.status == DlStatus.Merging }
        val queued = items.count { it.status == DlStatus.Queued }
        val done = active.sumOf { it.done }
        val total = active.sumOf { it.total }
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DOWNLOADS),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (active.size) {
            0 -> "Preparing downloads"
            1 -> active[0].video.title
            else -> "Downloading ${active.size} videos"
        }
        return NotificationCompat.Builder(this, KestrelApp.CH_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(if (queued > 0) "$queued waiting" else if (active.any { it.status == DlStatus.Merging }) "Merging…" else "")
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
