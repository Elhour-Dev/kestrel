package dev.kestrel.app.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.kestrel.app.Graph
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.newpipe.NpRepo
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Smart downloads (off by default): on Wi-Fi, every 12 hours, saves a few recommended videos at 360p
 * so there is something to watch offline. Keeps at most [KEEP] of them; the oldest go first.
 */
object SmartDownloads {
    private const val WORK = "smart-downloads"
    private const val PER_RUN = 5
    const val KEEP = 10
    private const val QUALITY = 360
    private const val MAX_LENGTH_SEC = 30 * 60

    /** Starts or stops the background job to match the setting. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!Graph.settings.smartDownloads.value) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<Worker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Picks new recommended videos, queues them, and removes the oldest smart downloads past [KEEP]. */
    suspend fun run() {
        if (!Graph.settings.smartDownloads.value) return
        val dl = Graph.downloads
        val feed = if (Graph.account.isSignedIn) Graph.innerTube.home().items else NpRepo.kiosk().items
        val have = dl.items.value.map { it.video.id }.toHashSet()
        val picks = feed.filterIsInstance<Video>()
            .filter { !it.isLive && it.id !in have && it.durationSec in 1..MAX_LENGTH_SEC.toLong() }
            .take(PER_RUN)
        picks.forEach { dl.enqueue(it, audioOnly = false, quality = QUALITY, smart = true) }
        // Oldest smart downloads beyond the limit (the list is newest first).
        dl.items.value.filter { it.smart && it.status == DlStatus.Done }.drop(KEEP).forEach { dl.delete(it.id) }
        // Stay alive while they download (the app may be in the background); WorkManager stops us after ~10 min.
        while (dl.items.value.any { it.smart && it.status in setOf(DlStatus.Queued, DlStatus.Running, DlStatus.Merging) }) delay(5_000)
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = runCatching { run() }.fold({ Result.success() }, { Result.retry() })
    }
}
