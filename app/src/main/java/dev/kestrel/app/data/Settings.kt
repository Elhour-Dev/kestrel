package dev.kestrel.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small preference holder: every setting is a StateFlow the UI can observe. */
class Pref<T>(
    private val prefs: SharedPreferences,
    private val key: String,
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

    private fun bool(k: String, d: Boolean) = Pref(p, k, d, { a, b -> getBoolean(a, b) }, { a, b -> putBoolean(a, b) })
    private fun int(k: String, d: Int) = Pref(p, k, d, { a, b -> getInt(a, b) }, { a, b -> putInt(a, b) })
    private fun str(k: String, d: String) = Pref(p, k, d, { a, b -> getString(a, b) ?: b }, { a, b -> putString(a, b) })
    private fun set(k: String, d: Set<String>) =
        Pref(p, k, d, { a, b -> getStringSet(a, b)?.toSet() ?: b }, { a, b -> putStringSet(a, b) })

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
