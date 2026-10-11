package fr.appartfinder.freetube.settings

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

const val DEFAULT_SERVER_URL = "https://appartfinder.fr/"

/** A device token. toString never prints it; only ServerClient reads [value]. */
@JvmInline
value class DeviceToken private constructor(val value: String) {
    override fun toString() = "DeviceToken(***)"

    companion object {
        private val PATTERN = Regex("[A-Za-z0-9_-]{32,128}") // server: secrets.token_urlsafe(32) = 43 chars

        fun parse(raw: String): DeviceToken? = raw.trim().takeIf(PATTERN::matches)?.let(::DeviceToken)
    }
}

data class ServerConfig(val baseUrl: HttpUrl, val token: DeviceToken)

/** What the Settings screen shows. The token itself never leaves this class except through [server]. */
data class SettingsSnapshot(val serverUrl: String, val hasToken: Boolean)

private val Context.settingsStore by preferencesDataStore("settings")
private val SERVER_URL = stringPreferencesKey("server_url")
private val DEVICE_TOKEN = stringPreferencesKey("device_token") // base64 of the Tink AEAD ciphertext
private val TOKEN_AD = "device_token".toByteArray() // associated data: the ciphertext only decrypts as a token

/**
 * Sole owner of the "settings" DataStore. The token is encrypted with Tink AES256-GCM; the keyset is
 * itself wrapped by a master key kept in the Android Keystore (EncryptedSharedPreferences is deprecated).
 */
@Singleton
class Settings @Inject constructor(@ApplicationContext private val context: Context) {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "freetube_keyset", "freetube_tink")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://freetube_master")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /** Decrypting on every authenticated request would be wasteful: setters invalidate this. */
    @Volatile private var cached: ServerConfig? = null

    val snapshot: Flow<SettingsSnapshot> = context.settingsStore.data.map {
        SettingsSnapshot(it[SERVER_URL] ?: DEFAULT_SERVER_URL, it[DEVICE_TOKEN] != null)
    }

    /** Base URL for server thumbnails of tracks with no local file. */
    val serverUrl: Flow<HttpUrl?> = snapshot.map { it.serverUrl.toHttpUrlOrNull() }

    /** https only. Returns false (nothing saved) otherwise. Normalised to end with "/" for Retrofit. */
    suspend fun setServerUrl(raw: String): Boolean {
        val url = raw.trim().toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return false
        context.settingsStore.edit { it[SERVER_URL] = url.newBuilder().encodedPath("/").build().toString() }
        cached = null
        return true
    }

    /** Returns false (nothing saved) if the format is invalid. */
    suspend fun setToken(raw: String): Boolean {
        val token = DeviceToken.parse(raw) ?: return false
        val sealed = withContext(Dispatchers.IO) { aead.encrypt(token.value.toByteArray(), TOKEN_AD) }
        context.settingsStore.edit { it[DEVICE_TOKEN] = Base64.encodeToString(sealed, Base64.NO_WRAP) }
        cached = null
        return true
    }

    /** null when no token. If the Keystore key is lost, the token is dropped: the user pastes it again. */
    suspend fun server(): ServerConfig? {
        cached?.let { return it }
        val prefs = context.settingsStore.data.first()
        val sealed = prefs[DEVICE_TOKEN] ?: return null
        val token = try {
            withContext(Dispatchers.IO) { String(aead.decrypt(Base64.decode(sealed, Base64.NO_WRAP), TOKEN_AD)) }
        } catch (e: GeneralSecurityException) {
            context.settingsStore.edit { it.remove(DEVICE_TOKEN) }
            return null
        }
        val url = (prefs[SERVER_URL] ?: DEFAULT_SERVER_URL).toHttpUrlOrNull() ?: return null
        return DeviceToken.parse(token)?.let { ServerConfig(url, it) }.also { cached = it }
    }
}
