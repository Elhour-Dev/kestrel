package dev.kestrel.app.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Session(
    val cookies: Map<String, String>,
    val name: String = "",
    val handle: String = "",
    val avatar: String = "",
    val dataSyncId: String = "",
    val visitorData: String = "",
)

/** AES-GCM with a non-exportable key in the Android Keystore. */
private object Crypto {
    private const val ALIAS = "kestrel_session_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(data: String): String {
        val raw = Base64.decode(data, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }
}

enum class AccountState { SignedOut, SignedIn, Expired }

/** Holds the Google session. Cookies only ever touch disk encrypted. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> get() = _session

    private val _state = MutableStateFlow(if (_session.value != null) AccountState.SignedIn else AccountState.SignedOut)
    val state: StateFlow<AccountState> get() = _state

    val isSignedIn get() = _session.value != null && _state.value == AccountState.SignedIn

    private fun load(): Session? = try {
        prefs.getString("blob", null)?.let { json.decodeFromString(Session.serializer(), Crypto.decrypt(it)) }
    } catch (_: Exception) {
        null
    }

    private fun persist(s: Session?) {
        if (s == null) prefs.edit().remove("blob").apply()
        else prefs.edit().putString("blob", Crypto.encrypt(json.encodeToString(Session.serializer(), s))).apply()
    }

    fun signIn(s: Session) {
        _session.value = s
        _state.value = AccountState.SignedIn
        persist(s)
    }

    fun update(f: (Session) -> Session) {
        val cur = _session.value ?: return
        val next = f(cur)
        if (next != cur) {
            _session.value = next
            persist(next)
        }
    }

    /** Merge rotated cookies from Set-Cookie headers so the session lasts longer. */
    fun mergeCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        update { s ->
            val map = s.cookies.toMutableMap()
            for (line in setCookies) {
                val pair = line.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty()) continue
                val expired = line.contains("Max-Age=0", true) || line.contains("1970", false)
                if (expired || value.isEmpty() || value == "EXPIRED") continue
                if (name in map) map[name] = value
            }
            s.copy(cookies = map)
        }
    }

    fun markExpired() {
        if (_session.value != null) _state.value = AccountState.Expired
    }

    fun signOut() {
        _session.value = null
        _state.value = AccountState.SignedOut
        persist(null)
    }

    companion object {
        fun parseCookieHeader(header: String): Map<String, String> =
            header.split(';').mapNotNull {
                val p = it.trim()
                val i = p.indexOf('=')
                if (i <= 0) null else p.substring(0, i) to p.substring(i + 1)
            }.toMap()

        /** Accepts a raw "a=b; c=d" header or a Netscape cookies.txt export. */
        fun parseAnyCookies(text: String): Map<String, String> {
            val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            val netscape = lines.mapNotNull { l ->
                val f = l.split('\t')
                if (f.size >= 7 && f[0].contains("youtube.com")) f[5] to f[6].trim() else null
            }
            return if (netscape.isNotEmpty()) netscape.toMap() else parseCookieHeader(text.trim())
        }

        fun hasAuth(c: Map<String, String>) =
            c.containsKey("SAPISID") || c.containsKey("__Secure-3PAPISID")
    }
}
