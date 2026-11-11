package dev.kestrel.app.data

import dev.kestrel.app.data.model.SponsorSegment
import dev.kestrel.app.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.security.MessageDigest

/** SponsorBlock client. Uses the hash-prefix API so the server never learns the exact video. */
object SponsorBlock {
    private const val API = "https://sponsor.ajay.app/api/skipSegments/"

    fun hashPrefix(videoId: String): String =
        MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(4)

    /** Returns skip segments for [videoId]. Empty when there are none. Throws on network errors. */
    suspend fun segments(videoId: String, categories: Collection<String>): List<SponsorSegment> =
        withContext(Dispatchers.IO) {
            if (categories.isEmpty()) return@withContext emptyList()
            val url = (API + hashPrefix(videoId)).toHttpUrl().newBuilder()
                .addQueryParameter("categories", categories.joinToString(",", "[", "]") { "\"$it\"" })
                .build()
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.code == 404) return@withContext emptyList()
                if (!resp.isSuccessful) error("SponsorBlock HTTP ${resp.code}")
                val root = Json.parseToJsonElement(resp.body.string()).jsonArray
                root.map { it.jsonObject }
                    .filter { it["videoID"]?.jsonPrimitive?.content == videoId }
                    .flatMap { it["segments"]!!.jsonArray.map { s -> s.jsonObject } }
                    .filter { (it["actionType"]?.jsonPrimitive?.content ?: "skip") == "skip" }
                    .map {
                        val seg = it["segment"]!!.jsonArray
                        SponsorSegment(
                            (seg[0].jsonPrimitive.double * 1000).toLong(),
                            (seg[1].jsonPrimitive.double * 1000).toLong(),
                            it["category"]!!.jsonPrimitive.content,
                            it["UUID"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    }
                    .sortedBy { it.startMs }
            }
        }
}
