package com.arloor.tuke.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arloor.tuke.core.settings.SettingsStore
import com.arloor.tuke.core.update.AppReleaseCheckResult
import com.arloor.tuke.core.update.AppUpdateChecker
import com.arloor.tuke.engine.EngineController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val apiKey: String = "",
    val baseUrl: String = "",
    val saved: Boolean = false,
    val engineReady: Boolean = false,
    val engineError: String? = null,
    val checkingUpdate: Boolean = false,
    val updateInfo: AppReleaseCheckResult? = null,
    val updateError: String? = null,
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    engineController: EngineController,
    private val appUpdateChecker: AppUpdateChecker,
) : ViewModel() {
    private val current = settingsStore.current()
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            apiKey = current.deepSeekApiKey,
            baseUrl = current.deepSeekBaseUrl,
            engineReady = engineController.state.value.ready,
            engineError = engineController.state.value.error,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appUpdateChecker.state.collect { updateState ->
                _uiState.update {
                    it.copy(
                        checkingUpdate = updateState.checking,
                        updateInfo = updateState.info,
                        updateError = updateState.error,
                    )
                }
            }
        }
    }

    fun setApiKey(value: String) {
        _uiState.update { it.copy(apiKey = value, saved = false) }
    }

    fun setBaseUrl(value: String) {
        _uiState.update { it.copy(baseUrl = value, saved = false) }
    }

    fun save() {
        val state = _uiState.value
        settingsStore.saveApiKey(state.apiKey, state.baseUrl)
        _uiState.update { it.copy(saved = true) }
    }

    fun checkUpdate() {
        appUpdateChecker.checkNow()
    }
}
