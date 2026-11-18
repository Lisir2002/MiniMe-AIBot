package com.minime.code.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minime.code.feature.agent.data.remote.cloudagent.CloudAgentApi
import com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager
import com.minime.code.feature.agent.domain.workflow.CloudTargetDiscovery
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
    /** 用户自定义的服务端端口（字符串，直接绑定输入框）。 */
    val port: String = "8080",
    val token: String = "",
    val testing: Boolean = false,
    val testResult: String? = null,
    /** 端口可用性探测状态。 */
    val portProbing: Boolean = false,
    val portProbeResult: CloudAgentServerManager.PortProbeResult? = null,
    /** 已保存的服务器连接（供选择，复用连接与同步基座）。 */
    val serverChoices: List<CloudServerChoice> = emptyList(),
    val selectedConnectionId: String? = null,
    val version: ServerVersionState = ServerVersionState(),
    /** 部署/升级进度日志（为空表示未在部署）。 */
    val deployLog: String = "",
    val deploying: Boolean = false,
    val deployResult: String? = null,
    /** 部署前容器探测结果（null 表示尚未探测）。 */
    val inspection: CloudAgentServerManager.Inspection? = null,
    val inspecting: Boolean = false
)

@HiltViewModel
class CloudAgentSettingsViewModel @Inject constructor(
    private val settings: CloudAgentSettingsRepository,
    private val api: CloudAgentApi,
    private val connectionRepository: RemoteRepository,
    private val serverManager: CloudAgentServerManager,
    private val cloudTargetDiscovery: CloudTargetDiscovery
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
            settings.portFlow.collect { port ->
                _uiState.value = _uiState.value.copy(port = port)
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
                    .map { conn ->
                        val p = settings.portFor(conn.id)
                        CloudServerChoice(conn.id, conn.name, conn.host, "http://${conn.host}:$p")
                    }
                _uiState.value = _uiState.value.copy(
                    serverChoices = choices,
                    selectedConnectionId = _uiState.value.selectedConnectionId
                        ?.takeIf { id -> choices.any { it.id == id } }
                )
            }
        }
        // 首次进入确保有一个强随机 token（服务端 MINIME_TOKEN 与客户端 Bearer 共用同一值）。
        // tokenFlow 的 collect 会自动把生成结果刷进 UI 状态，无需手动 copy。
        viewModelScope.launch { settings.ensureToken() }
        // 进入设置页时探测服务端版本 + 记录期望版本。
        checkServerVersion()
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { settings.setEnabled(enabled) }

    fun setServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url, selectedConnectionId = null)
        viewModelScope.launch { settings.setServerUrl(url) }
    }

    fun setToken(token: String) {
        val trimmed = token.trim()
        _uiState.value = _uiState.value.copy(token = trimmed)
        viewModelScope.launch { settings.setToken(trimmed) }
    }

    /**
     * 用户输入端口：只更新输入态（允许中间态如空串/"8"），不立即落库。
     * 点「检测端口」或部署时用 [commitPort] 校验后持久化，避免边输入边写导致误存非法值。
     */
    fun setPort(port: String) {
        _uiState.value = _uiState.value.copy(port = port, portProbeResult = null)
    }

    /** 校验并持久化当前输入端口；合法返回端口值，非法返回 null 并清空探测结果。 */
    private suspend fun commitPort(): Int? {
        val raw = _uiState.value.port
        val valid = CloudAgentSettingsRepository.validatePort(raw)
        if (valid == null) {
            _uiState.value = _uiState.value.copy(portProbeResult = null)
            return null
        }
        settings.setPort(valid)
        _uiState.value.selectedConnectionId?.let { settings.setPortFor(it, valid) }
        // serverUrl 用新端口重建（host 复用当前选中连接或现有 URL 的 host）
        val host = _uiState.value.serverChoices
            .firstOrNull { it.id == _uiState.value.selectedConnectionId }?.host
            ?: runCatching { java.net.URI(_uiState.value.serverUrl).host }.getOrNull()
        if (!host.isNullOrBlank()) {
            val url = "http://$host:$valid"
            settings.setServerUrl(url)
            _uiState.value.selectedConnectionId?.let { settings.setServerUrlFor(it, url) }
            _uiState.value = _uiState.value.copy(serverUrl = url)
        }
        return valid
    }

    /** 通过 SSH 在服务器上实际尝试绑定端口，测试可用性并回显结果。 */
    fun probePort() = viewModelScope.launch {
        val choice = _uiState.value.serverChoices.firstOrNull { it.id == _uiState.value.selectedConnectionId }
        if (choice == null) {
            _uiState.value = _uiState.value.copy(
                portProbeResult = CloudAgentServerManager.PortProbeResult(
                    false, "ERROR", "请先选择一个已保存的服务器", ""
                )
            )
            return@launch
        }
        val conn = connectionsSnapshot.firstOrNull { it.id == choice.id }
        if (conn == null) return@launch
        val port = commitPort()
        if (port == null) {
            _uiState.value = _uiState.value.copy(
                portProbeResult = CloudAgentServerManager.PortProbeResult(
                    false, "ERROR", "端口不合法，请输入 1-65535 之间的数字", ""
                )
            )
            return@launch
        }
        _uiState.value = _uiState.value.copy(portProbing = true, portProbeResult = null)
        val result = serverManager.probePort(conn, port).getOrElse {
            CloudAgentServerManager.PortProbeResult(false, "ERROR", it.message ?: "探测失败", "")
        }
        _uiState.value = _uiState.value.copy(portProbing = false, portProbeResult = result)
    }

    /** 重新生成强随机 token；服务端/客户端统一，重新部署后生效。 */
    fun regenerateToken() {
        val fresh = CloudAgentSettingsRepository.generateToken()
        _uiState.value = _uiState.value.copy(token = fresh)
        viewModelScope.launch { settings.setToken(fresh) }
    }

    /** 选中已保存连接：自动推导 serverUrl（含该连接自定义端口）并回填，随后立即探测容器状态。 */
    fun selectServer(choice: CloudServerChoice) {
        _uiState.value = _uiState.value.copy(
            selectedConnectionId = choice.id,
            serverUrl = choice.serverUrl,
            port = CloudAgentSettingsRepository.portFromUrl(choice.serverUrl).toString(),
            testResult = null,
            portProbeResult = null,
            inspection = null
        )
        viewModelScope.launch {
            val port = settings.portFor(choice.id)
            val url = "http://${choice.host}:$port"
            _uiState.value = _uiState.value.copy(serverUrl = url, port = port.toString())
            settings.setServerUrl(url)
            settings.setPort(port)
            // 按 connectionId 存地址并设为新会话默认目标，供多服务器会话级解析使用。
            settings.setServerUrlFor(choice.id, url)
            settings.setPortFor(choice.id, port)
            settings.setDefaultConnectionId(choice.id)
            settings.setEnabled(true)
            cloudTargetDiscovery.invalidate()
            inspectContainer(port)
        }
    }

    /**
     * 选中连接后探测容器：不存在→建议安装；存在但版本旧→建议升级；已是最新→可跳过。
     * 供 UI 展示「检测到服务器尚未安装 / 可升级 / 已是最新」并提示下一步。
     */
    fun inspectContainer(portOverride: Int? = null) = viewModelScope.launch {
        val choice = _uiState.value.serverChoices.firstOrNull { it.id == _uiState.value.selectedConnectionId }
        if (choice == null) {
            _uiState.value = _uiState.value.copy(inspection = null)
            return@launch
        }
        val conn = connectionsSnapshot.firstOrNull { it.id == choice.id }
        if (conn == null) return@launch
        val port = portOverride ?: CloudAgentSettingsRepository.validatePort(_uiState.value.port)
            ?: settings.portFor(conn.id)
        _uiState.value = _uiState.value.copy(inspecting = true)
        val result = serverManager.inspect(conn, port).getOrNull()
        _uiState.value = _uiState.value.copy(inspection = result, inspecting = false)
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
        val port = commitPort()
        if (port == null) {
            _uiState.value = _uiState.value.copy(
                deploying = false,
                deployResult = "fail:端口不合法，请输入 1-65535 之间的数字"
            )
            return@launch
        }
        val result = serverManager.deployOrUpgrade(conn, _uiState.value.token, port) { line ->
            // 进度回显到 UI（launch 在主线程，直接更新 state）；token 由 serverManager 侧脱敏
            _uiState.value = _uiState.value.copy(deployLog = (_uiState.value.deployLog + line).takeLast(8000))
        }.getOrElse {
            CloudAgentServerManager.DeployResult(false, "", it.message ?: "未知错误", "")
        }
        _uiState.value = _uiState.value.copy(
            deploying = false,
            deployResult = if (result.ok) "ok:${result.version}" else "fail:${result.message}",
            serverUrl = if (result.ok) "http://${choice.host}:$port" else _uiState.value.serverUrl
        )
        if (result.ok) {
            checkServerVersion()
            inspectContainer(port)
        }
    }

    fun clearDeployResult() { _uiState.value = _uiState.value.copy(deployResult = null) }
}
