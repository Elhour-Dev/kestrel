package dev.kestrel.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.kestrel.app.data.LocalStore
import dev.kestrel.app.data.Settings
import dev.kestrel.app.data.account.AccountStore
import dev.kestrel.app.data.innertube.InnerTube
import dev.kestrel.app.data.net.Http
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.downloads.Downloads
import dev.kestrel.app.player.PlayerManager

/** App-wide singletons. Simple manual wiring, no DI framework. */
object Graph {
    lateinit var app: Application
    lateinit var settings: Settings
    lateinit var store: LocalStore
    lateinit var account: AccountStore
    lateinit var innerTube: InnerTube
    lateinit var downloads: Downloads
}

class KestrelApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Graph.app = this
        Graph.settings = Settings(this)
        Graph.store = LocalStore(filesDir)
        Graph.account = AccountStore(this)
        Graph.innerTube = InnerTube(Graph.account).apply {
            gl = Graph.settings.region.value
            hl = Graph.settings.language.value
        }
        NpRepo.init(Graph.settings.region.value, Graph.settings.language.value)
        dev.kestrel.app.player.PoTokens.install(this)
        dev.kestrel.app.player.JsSolver.install(this)
        dev.kestrel.app.player.PotCapture.install(this)
        createChannels()
        PlayerManager.init(this)
        Graph.downloads = Downloads(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_POPUP, "Popup player", NotificationManager.IMPORTANCE_LOW))
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .diskCache {
                DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build()
            }
            .build()

    companion object {
        const val CH_DOWNLOADS = "downloads"
        const val CH_POPUP = "popup"
    }
}
