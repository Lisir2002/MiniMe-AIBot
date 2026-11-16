package com.minime.code.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minime.code.feature.agent.data.remote.cloudagent.CloudAgentApi
import com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 完全云端 Agent 设置页的状态。 */
data class CloudAgentUiState(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val token: String = "",
    val testing: Boolean = false,
    val testResult: String? = null
)

@HiltViewModel
class CloudAgentSettingsViewModel @Inject constructor(
    private val settings: CloudAgentSettingsRepository,
    private val api: CloudAgentApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudAgentUiState())
    val uiState: StateFlow<CloudAgentUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.enabledFlow.collect { enabled -> _uiState.value = _uiState.value.copy(enabled = enabled) }
        }
        viewModelScope.launch {
            settings.serverUrlFlow.collect { url -> _uiState.value = _uiState.value.copy(serverUrl = url) }
        }
        viewModelScope.launch {
            settings.tokenFlow.collect { token -> _uiState.value = _uiState.value.copy(token = token) }
        }
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { settings.setEnabled(enabled) }

    fun setServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url)
        viewModelScope.launch { settings.setServerUrl(url) }
    }

    fun setToken(token: String) {
        _uiState.value = _uiState.value.copy(token = token)
        viewModelScope.launch { settings.setToken(token) }
    }

    fun clearTestResult() { _uiState.value = _uiState.value.copy(testResult = null) }

    fun testConnection() = viewModelScope.launch {
        val url = _uiState.value.serverUrl.trim()
        if (url.isBlank()) {
            _uiState.value = _uiState.value.copy(testResult = "need_url")
            return@launch
        }
        _uiState.value = _uiState.value.copy(testing = true, testResult = null)
        val ok = runCatching { api.health(url)["status"] == "ok" }.getOrDefault(false)
        _uiState.value = _uiState.value.copy(
            testing = false,
            testResult = if (ok) "ok" else "fail:connection"
        )
    }
}
