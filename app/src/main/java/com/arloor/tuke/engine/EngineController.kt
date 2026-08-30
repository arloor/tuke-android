package com.arloor.tuke.engine

import android.content.Context
import com.arloor.tuke.core.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject

class EngineController(
    context: Context,
    private val settingsStore: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimeFile = File(appContext.filesDir, "engine-runtime.json")
    private val _state = MutableStateFlow(EngineState(hasApiKey = settingsStore.current().deepSeekApiKey.isNotBlank()))
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private var pollJob: Job? = null

    init {
        scope.launch {
            settingsStore.settings.drop(1).collectLatest { settings ->
                val hasKey = settings.deepSeekApiKey.isNotBlank()
                if (!hasKey) {
                    stop()
                    _state.value = EngineState(hasApiKey = false)
                } else {
                    start(settings.deepSeekApiKey, settings.deepSeekBaseUrl, settings.internalApiKey)
                }
            }
        }
    }

    fun ensureStarted() {
        if (_state.value.ready || _state.value.starting) return
        val settings = settingsStore.current()
        if (settings.deepSeekApiKey.isNotBlank()) {
            scope.launch { start(settings.deepSeekApiKey, settings.deepSeekBaseUrl, settings.internalApiKey) }
        }
    }

    fun endpoint(): EngineEndpoint? {
        val snapshot = _state.value
        val base = snapshot.baseUrl ?: return null
        val token = settingsStore.current().internalApiKey
        if (!snapshot.ready || token.isBlank()) return null
        return EngineEndpoint(baseUrl = base, token = token)
    }

    fun notifyRunBegin() = TukeEngineService.runBegin(appContext)

    fun notifyRunEnd() = TukeEngineService.runEnd(appContext)

    private fun start(apiKey: String, baseUrl: String, internalKey: String) {
        _state.value = EngineState(hasApiKey = true, starting = true, error = null)
        writeConfig(apiKey, baseUrl, internalKey)
        runtimeFile.delete()
        TukeEngineService.start(appContext)
        pollJob?.cancel()
        pollJob = scope.launch { pollRuntime() }
    }

    private fun stop() {
        pollJob?.cancel()
        TukeEngineService.stop(appContext)
        runtimeFile.delete()
    }

    private fun writeConfig(apiKey: String, baseUrl: String, internalKey: String) {
        val dataDir = File(appContext.filesDir, "tuke").apply { mkdirs() }
        File(dataDir, "sessions").mkdirs()
        val json = JSONObject()
            .put("dataDir", dataDir.absolutePath)
            .put("apiKey", apiKey)
            .put("baseURL", baseUrl)
            .put("internalAPIKey", internalKey)
            .put("runtimePath", runtimeFile.absolutePath)
        File(appContext.filesDir, "engine-config.json").writeText(json.toString())
    }

    private suspend fun pollRuntime() {
        var attempts = 0
        while (true) {
            val parsed = runCatching {
                if (runtimeFile.exists()) JSONObject(runtimeFile.readText()) else null
            }.getOrNull()
            if (parsed != null) {
                val status = parsed.optString("status")
                val port = parsed.optInt("port", 0)
                val error = parsed.optString("error").ifBlank { null }
                if (status == "running" && port > 0) {
                    _state.value = EngineState(hasApiKey = true, starting = false, ready = true, port = port)
                    return
                }
                if (status == "error") {
                    _state.value = EngineState(
                        hasApiKey = true,
                        starting = false,
                        ready = false,
                        error = error ?: "引擎启动失败",
                    )
                    return
                }
            }
            attempts += 1
            if (attempts == 80) {
                _state.value = EngineState(
                    hasApiKey = true,
                    starting = true,
                    ready = false,
                    error = "本地引擎启动较慢，仍在等待…",
                )
            }
            delay(250)
        }
    }
}
