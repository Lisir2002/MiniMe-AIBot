package com.minime.code.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minime.code.feature.agent.data.remote.cloudagent.CloudAgentApi
import com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager
import com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
import com.minime.code.feature.workspace.domain.model.RemoteConnection
import com.minime.code.feature.workspace.domain.model.RemoteProtocol
import com.minime.code.feature.workspace.domain.repository.RemoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 连接选择项：复用「连接与同步」已保存的 SFTP 服务器。 */
data class CloudServerChoice(val id: String, val name: String, val host: String, val serverUrl: String)

/** 服务端版本对比状态。 */
data class ServerVersionState(
    val current: String? = null,
    val expected: String = "",
    val protocol: Int? = null,
    val checking: Boolean = false
)

/** 完全云端 Agent 设置页的状态。 */
data class CloudAgentUiState(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val token: String = "",
    val testing: Boolean = false,
    val testResult: String? = null,
    /** 已保存的服务器连接（供选择，复用连接与同步基座）。 */
    val serverChoices: List<CloudServerChoice> = emptyList(),
    val selectedConnectionId: String? = null,
    val version: ServerVersionState = ServerVersionState(),
    /** 部署/升级进度日志（为空表示未在部署）。 */
    val deployLog: String = "",
    val deploying: Boolean = false,
    val deployResult: String? = null
)

@HiltViewModel
class CloudAgentSettingsViewModel @Inject constructor(
    private val settings: CloudAgentSettingsRepository,
    private val api: CloudAgentApi,
    private val connectionRepository: RemoteRepository,
    private val serverManager: CloudAgentServerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudAgentUiState())
    val uiState: StateFlow<CloudAgentUiState> = _uiState.asStateFlow()

    /** 当前已保存连接快照（含解密凭据），供部署时取完整 RemoteConnection。 */
    private var connectionsSnapshot: List<RemoteConnection> = emptyList()

    init {
        viewModelScope.launch {
            settings.enabledFlow.collect { enabled -> _uiState.value = _uiState.value.copy(enabled = enabled) }
        }
        viewModelScope.launch {
            settings.serverUrlFlow.collect { url ->
                _uiState.value = _uiState.value.copy(
                    serverUrl = url,
                    // 依据 serverUrl 反推选中的连接（若 host 匹配）
                    selectedConnectionId = _uiState.value.serverChoices.firstOrNull { it.serverUrl == url }?.id
                )
            }
        }
        viewModelScope.launch {
            settings.tokenFlow.collect { token -> _uiState.value = _uiState.value.copy(token = token) }
        }
        // 复用基座：列出已保存的 SFTP 服务器作为部署/升级目标。
        viewModelScope.launch {
            connectionRepository.getConnections().collect { list ->
                connectionsSnapshot = list
                val choices = list.filter { it.protocol == RemoteProtocol.SFTP }
                    .map { CloudServerChoice(it.id, it.name, it.host, "http://${it.host}:8080") }
                _uiState.value = _uiState.value.copy(
                    serverChoices = choices,
                    selectedConnectionId = _uiState.value.selectedConnectionId
                        ?.takeIf { id -> choices.any { it.id == id } }
                )
            }
        }
        // 进入设置页时探测服务端版本 + 记录期望版本。
        checkServerVersion()
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { settings.setEnabled(enabled) }

    fun setServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url, selectedConnectionId = null)
        viewModelScope.launch { settings.setServerUrl(url) }
    }

    fun setToken(token: String) {
        _uiState.value = _uiState.value.copy(token = token)
        viewModelScope.launch { settings.setToken(token) }
    }

    /** 选中已保存连接：自动推导 serverUrl 并回填，消除手填重复配置。 */
    fun selectServer(choice: CloudServerChoice) {
        _uiState.value = _uiState.value.copy(
            selectedConnectionId = choice.id,
            serverUrl = choice.serverUrl,
            testResult = null
        )
        viewModelScope.launch { settings.setServerUrl(choice.serverUrl) }
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
        if (ok) checkServerVersion()
    }

    /** 读取服务端 /version，与 App 期望版本对比。 */
    fun checkServerVersion() = viewModelScope.launch {
        val url = _uiState.value.serverUrl.trim()
        _uiState.value = _uiState.value.copy(
            version = _uiState.value.version.copy(
                expected = serverManager.expectedVersion,
                checking = true
            )
        )
        if (url.isBlank()) {
            _uiState.value = _uiState.value.copy(version = _uiState.value.version.copy(checking = false))
            return@launch
        }
        val info = serverManager.queryVersion(url).getOrNull()
        _uiState.value = _uiState.value.copy(
            version = ServerVersionState(
                current = info?.version,
                expected = serverManager.expectedVersion,
                protocol = info?.protocol,
                checking = false
            )
        )
    }

    /** 一键部署 / 升级选中连接的 agent-server。 */
    fun deployOrUpgrade() = viewModelScope.launch {
        val choice = _uiState.value.serverChoices.firstOrNull { it.id == _uiState.value.selectedConnectionId }
        if (choice == null) {
            _uiState.value = _uiState.value.copy(deployResult = "no_connection")
            return@launch
        }
        _uiState.value = _uiState.value.copy(deploying = true, deployLog = "", deployResult = null)
        val conn = connectionsSnapshot.firstOrNull { it.id == choice.id }
        if (conn == null) {
            _uiState.value = _uiState.value.copy(deploying = false, deployResult = "fail:连接不存在")
            return@launch
        }
        val result = serverManager.deployOrUpgrade(conn) { line ->
            // 进度回显到 UI（launch 在主线程，直接更新 state）
            _uiState.value = _uiState.value.copy(deployLog = (_uiState.value.deployLog + line).takeLast(8000))
        }.getOrElse {
            CloudAgentServerManager.DeployResult(false, "", it.message ?: "未知错误", "")
        }
        _uiState.value = _uiState.value.copy(
            deploying = false,
            deployResult = if (result.ok) "ok:${result.version}" else "fail:${result.message}",
            serverUrl = if (result.ok) "http://${choice.host}:8080" else _uiState.value.serverUrl
        )
        if (result.ok) checkServerVersion()
    }

    fun clearDeployResult() { _uiState.value = _uiState.value.copy(deployResult = null) }
}
