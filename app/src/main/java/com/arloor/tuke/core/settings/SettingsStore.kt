package com.arloor.tuke.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val deepSeekApiKey: String = "",
    val deepSeekBaseUrl: String = "",
    val internalApiKey: String = "",
)

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "tuke_secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        appContext.getSharedPreferences("tuke_settings_fallback", Context.MODE_PRIVATE)
    }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        if (_settings.value.internalApiKey.length < 32) {
            save(_settings.value.copy(internalApiKey = randomToken()))
        }
    }

    fun current(): AppSettings = _settings.value

    fun saveApiKey(apiKey: String, baseUrl: String) {
        save(current().copy(deepSeekApiKey = apiKey.trim(), deepSeekBaseUrl = baseUrl.trim()))
    }

    private fun save(value: AppSettings) {
        prefs.edit()
            .putString(KEY_API, value.deepSeekApiKey)
            .putString(KEY_BASE, value.deepSeekBaseUrl)
            .putString(KEY_INTERNAL, value.internalApiKey)
            .apply()
        _settings.value = value
    }

    private fun read(): AppSettings = AppSettings(
        deepSeekApiKey = prefs.getString(KEY_API, "").orEmpty(),
        deepSeekBaseUrl = prefs.getString(KEY_BASE, "").orEmpty(),
        internalApiKey = prefs.getString(KEY_INTERNAL, "").orEmpty(),
    )

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_API = "deepseek_api_key"
        private const val KEY_BASE = "deepseek_base_url"
        private const val KEY_INTERNAL = "internal_api_key"
    }
}
