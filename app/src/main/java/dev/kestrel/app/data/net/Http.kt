package dev.kestrel.app.data.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Client with long read timeout for media and downloads. */
    val mediaClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
