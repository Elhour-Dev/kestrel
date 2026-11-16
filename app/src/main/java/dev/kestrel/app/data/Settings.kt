package dev.kestrel.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Small preference holder: every setting is a StateFlow the UI can observe. */
class Pref<T>(
    private val prefs: SharedPreferences,
    val key: String,
    default: T,
    private val read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    private val state = MutableStateFlow(prefs.read(key, default))
    val flow: StateFlow<T> get() = state
    var value: T
        get() = state.value
        set(v) {
            state.value = v
            prefs.edit().apply { write(key, v) }.apply()
        }
}

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val all = mutableListOf<Pref<*>>()
    private fun <T> reg(pref: Pref<T>) = pref.also { all += it }
    private fun bool(k: String, d: Boolean) = reg(Pref(p, k, d, { a, b -> getBoolean(a, b) }, { a, b -> putBoolean(a, b) }))
    private fun int(k: String, d: Int) = reg(Pref(p, k, d, { a, b -> getInt(a, b) }, { a, b -> putInt(a, b) }))
    private fun str(k: String, d: String) = reg(Pref(p, k, d, { a, b -> getString(a, b) ?: b }, { a, b -> putString(a, b) }))
    private fun set(k: String, d: Set<String>) =
        reg(Pref(p, k, d, { a, b -> getStringSet(a, b)?.toSet() ?: b }, { a, b -> putStringSet(a, b) }))

    /** Not backed up: a folder permission doesn't carry over to another install. */
    private val notBackedUp = setOf("download_folder")

    /** Every setting as JSON, for backups. */
    fun export(): JsonObject = JsonObject(all.filter { it.key !in notBackedUp }.associate { pref ->
        pref.key to when (val v = pref.value) {
            is Boolean -> JsonPrimitive(v)
            is Int -> JsonPrimitive(v)
            is Set<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) })
            else -> JsonPrimitive(v.toString())
        }
    })

    /** Restores settings from [export]'s JSON; unknown keys and wrong types are skipped. Returns how many were set. */
    @Suppress("UNCHECKED_CAST")
    fun restore(o: JsonObject): Int = all.filter { it.key !in notBackedUp }.count { pref ->
        val e: JsonElement = o[pref.key] ?: return@count false
        runCatching {
            when (pref.value) {
                is Boolean -> (pref as Pref<Boolean>).value = e.jsonPrimitive.booleanOrNull ?: return@count false
                is Int -> (pref as Pref<Int>).value = e.jsonPrimitive.intOrNull ?: return@count false
                is Set<*> -> (pref as Pref<Set<String>>).value = (e as JsonArray).map { it.jsonPrimitive.content }.toSet()
                else -> (pref as Pref<String>).value = e.jsonPrimitive.content
            }
            true
        }.getOrDefault(false)
    }

    /** "system", "light" or "dark". */
    val theme = str("theme", "system")
    /** Preferred max video height. 0 = best available. */
    val defaultQuality = int("default_quality", 720)
    val sponsorBlockEnabled = bool("sb_enabled", true)
    val sponsorBlockCategories = set("sb_categories", SponsorCategories.defaults)
    val sponsorBlockToast = bool("sb_toast", true)
    /** SAF tree URI, or "" for the app's own Movies/Music folder. */
    val downloadFolder = str("download_folder", "")
    val maxParallelDownloads = int("max_parallel_downloads", 3)
    val region = str("region", "US")
    val language = str("language", "en")
    val backgroundPlay = bool("background_play", true)
    val autoPip = bool("auto_pip", true)
    val resumePlayback = bool("resume_playback", true)
    val onboardingDone = bool("onboarding_done", false)
    val loginPromptDone = bool("login_prompt_done", false)
    val skippedUpdate = int("skipped_update", 0)
    val sendHistory = bool("send_history", true)
    /** App-only proxy, "socks5://host:port" or "http://host:port"; "" = use the phone's network/VPN. */
    val proxy = str("proxy", "")
    val dnsOverHttps = bool("dns_over_https", false)
}

object SponsorCategories {
    val all = linkedMapOf(
        "sponsor" to "Sponsor",
        "selfpromo" to "Self-promotion",
        "intro" to "Intro",
        "outro" to "Outro / end cards",
        "interaction" to "Interaction reminder",
        "preview" to "Preview / recap",
        "music_offtopic" to "Non-music in music videos",
        "filler" to "Filler / tangents",
    )
    val defaults = setOf("sponsor", "selfpromo", "intro", "outro", "interaction", "preview")
}
