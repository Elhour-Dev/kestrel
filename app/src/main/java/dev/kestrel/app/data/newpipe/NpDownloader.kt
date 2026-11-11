package dev.kestrel.app.data.newpipe

import dev.kestrel.app.data.net.Http
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** NewPipeExtractor HTTP bridge on top of OkHttp. Anonymous: never sends account cookies. */
object NpDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = OkRequest.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", Http.BROWSER_UA)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        Http.client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) {
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }
            val text = resp.body.string()
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                text,
                resp.request.url.toString(),
            )
        }
    }
}
