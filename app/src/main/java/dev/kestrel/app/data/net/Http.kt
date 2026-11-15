package dev.kestrel.app.data.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

object Http {
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    @Volatile private var proxy: Proxy? = null
    @Volatile private var doh = false

    /**
     * Optional app-only network settings. Called once at start, before the first request;
     * changes apply after the app restarts.
     */
    fun configure(proxyUrl: String, dnsOverHttps: Boolean) {
        proxy = parseProxy(proxyUrl)
        doh = dnsOverHttps
    }

    /** "socks5://host:port" or "http://host:port", else null. Host names resolve on the proxy. */
    fun parseProxy(url: String): Proxy? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = u.host ?: return null
        if (u.port !in 1..65535) return null
        val type = when (u.scheme?.lowercase()) {
            "socks", "socks5", "socks5h" -> Proxy.Type.SOCKS
            "http" -> Proxy.Type.HTTP
            else -> return null
        }
        return Proxy(type, InetSocketAddress.createUnresolved(host, u.port))
    }

    val client: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        proxy?.let { b.proxy(it) }
        // DNS over HTTPS skips a poisoned local resolver. Not needed with a SOCKS proxy (it resolves names).
        if (doh && proxy?.type() != Proxy.Type.SOCKS) {
            val bootstrap = OkHttpClient.Builder().apply { proxy?.let { proxy(it) } }.build()
            b.dns(
                DnsOverHttps.Builder().client(bootstrap)
                    .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
                    .build(),
            )
        }
        b.build()
    }

    /** Client with long read timeout for media and downloads. */
    val mediaClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
