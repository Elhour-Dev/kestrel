package dev.kestrel.app.ui

import dev.kestrel.app.data.innertube.NotSignedInException
import dev.kestrel.app.data.innertube.SessionExpiredException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Turns exceptions into short messages a person can act on. */
fun friendlyError(e: Throwable): String {
    val root = generateSequence(e) { it.cause }.toList()
    fun has(c: Class<*>) = root.any { c.isInstance(it) }
    return when {
        has(SessionExpiredException::class.java) -> "Your Google session expired. Sign in again."
        has(NotSignedInException::class.java) -> "Sign in with Google to use this."
        has(UnknownHostException::class.java) -> "No internet connection."
        has(SocketTimeoutException::class.java) -> "The connection timed out. Try again."
        root.any { it.javaClass.simpleName.contains("NotBot") || it.message?.contains("not a bot") == true } ->
            "YouTube is blocking video on this network (common with VPN servers). Try another VPN server or try again later."
        has(ReCaptchaException::class.java) -> "YouTube is rate-limiting this network. Wait a bit and try again."
        has(AgeRestrictedContentException::class.java) -> "This video is age-restricted. Sign in with Google to watch it."
        has(GeographicRestrictionException::class.java) -> "This video isn't available in your country."
        has(PrivateContentException::class.java) -> "This video is private."
        has(PaidContentException::class.java) -> "This video needs a paid membership."
        // NewPipe messages look like: Got error UNPLAYABLE: "reason". Show just the reason.
        has(ContentNotAvailableException::class.java) -> root.firstNotNullOfOrNull { c -> c.message?.let { Regex("\"(.+)\"").find(it)?.groupValues?.get(1) } }
            ?: e.message?.takeIf { it.length < 140 } ?: "This content isn't available."
        has(IOException::class.java) -> "Network error. Check your connection and try again."
        else -> (e.message ?: e.javaClass.simpleName).take(160)
    }
}
