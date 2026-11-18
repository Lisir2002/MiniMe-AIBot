package com.minime.code.feature.workspace.presentation.remote

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minime.code.feature.agent.domain.container.ContainerProfile
import com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager
import com.minime.code.feature.agent.domain.container.RootfsSource
import com.minime.code.feature.agent.domain.container.SshLoginKey
import com.minime.code.feature.agent.domain.container.SshLoginKeyStore
import com.minime.code.feature.settings.data.repository.ContainerSettingsRepository
import com.minime.code.feature.settings.data.repository.ExecutionMode
import com.minime.code.feature.settings.data.repository.ExecutionModeHolder
import com.minime.code.feature.settings.data.repository.ExecutionModeRepository
import com.minime.code.feature.workspace.domain.model.RemoteConnection
import com.minime.code.feature.workspace.domain.model.RemoteProtocol
import com.minime.code.feature.workspace.domain.remote.CloudProvisioner
import com.minime.code.feature.workspace.domain.remote.CloudProvisionUiState
import com.minime.code.feature.workspace.domain.remote.CloudProvisioner.ProvisionParams
import com.minime.code.feature.workspace.domain.remote.RemoteAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 云端一键配置向导 ViewModel。
 *
 * 持有表单状态并驱动 [CloudProvisioner] 的三段式流程；配置成功后调用既有仓储
 * 保存连接 + 创建 REMOTE_SSH profile + 激活并切模式，复用 SettingsViewModel.applyProfile 的等价语义。
 */
@HiltViewModel
class CloudProvisionViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val provisioner: CloudProvisioner,
    private val connectionRepository: com.minime.code.feature.workspace.domain.repository.RemoteRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val executionModeRepository: ExecutionModeRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val loginKeyStore: SshLoginKeyStore,
    private val serverManager: CloudAgentServerManager,
    private val cloudAgentSettings: com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
) : ViewModel() {

    val provisionState: StateFlow<CloudProvisionUiState> = provisioner.uiState

    /** App 已导入的 SSH 登录密钥，供向导下拉选择。 */
    private val _loginKeys = MutableStateFlow<List<SshLoginKey>>(emptyList())
    val loginKeys: StateFlow<List<SshLoginKey>> = _loginKeys.asStateFlow()

    init {
        viewModelScope.launch {
            _loginKeys.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loginKeyStore.entries()
            }
        }
    }

    // 表单
    private val _form = MutableStateFlow(ProvisionForm())
    val form: StateFlow<ProvisionForm> = _form.asStateFlow()

    /** 完成并启用后回调（供页面返回并刷新）。 */
    private val _enabled = MutableStateFlow<Boolean>(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var currentParams: ProvisionParams? = null
    private var createdConnectionId: String? = null

    fun updateForm(transform: (ProvisionForm) -> ProvisionForm) {
        _form.value = transform(_form.value)
    }

    private fun buildParams(): ProvisionParams? {
        val f = _form.value
        val host = f.host.trim()
        if (host.isBlank()) return null
        val port = f.port.toIntOrNull() ?: 22
        val auth = if (f.useKey && f.keyPath.isNotBlank()) {
            RemoteAuth.PrivateKey(f.keyPath, f.keyPassphrase.ifBlank { null })
        } else {
            RemoteAuth.Password(f.password)
        }
        val ws = f.workspacePath.ifBlank { "~/minimecode-workspace" }
        val image = f.imageName.ifBlank { "minimecode/ai-runtime:latest" }
        return ProvisionParams(host, port, f.username.ifBlank { "root" }, auth, ws, image)
    }

    /** Phase 0：预检。 */
    fun runPreflight() {
        val params = buildParams() ?: run {
            provisioner.reset()
            return
        }
        currentParams = params
        viewModelScope.launch { provisioner.preflight(params) }
    }

    /** 主机指纹确认后重试。 */
    fun confirmHostKeyAndRetry() {
        val params = currentParams ?: return
        viewModelScope.launch { provisioner.confirmHostKeyAndRetry(params) }
    }

    /** Phase 1+2：执行安装。 */
    fun executePlan() {
        val params = currentParams ?: return
        viewModelScope.launch { provisioner.executePlan(params) }
    }

    fun cancel() = provisioner.cancel()

    fun reset() {
        provisioner.reset()
        createdConnectionId = null
    }

    /**
     * 完成配置后：保存 SSH 连接 + 创建 REMOTE_SSH profile + 激活 + 切全局模式。
     * 与 SettingsViewModel.applyProfile 语义一致（connectionId 由 resolver 实时解析）。
     */
    fun enableCloudEnvironment() {
        val params = currentParams ?: return
        val f = _form.value
        viewModelScope.launch {
            val connId = createdConnectionId ?: UUID.randomUUID().toString().also { createdConnectionId = it }
            val conn = RemoteConnection(
                id = connId,
                name = f.name.ifBlank { "云端 ${params.host}" },
                protocol = RemoteProtocol.SFTP,
                host = params.host,
                port = params.port,
                username = params.username
            )
            connectionRepository.addConnection(conn, params.auth)

            val profile = ContainerProfile(
                id = "cloud-" + connId,
                name = f.name.ifBlank { "云端 ${params.host}" },
                rootfsSource = RootfsSource.RemoteSsh(connId, params.workspacePath),
                shellPath = null,
                isBuiltin = false,
                mode = ExecutionMode.REMOTE_SSH,
                createdAt = System.currentTimeMillis()
            )
            containerSettingsRepository.upsertCustomProfile(profile)
            containerSettingsRepository.setActiveProfile(profile.id)
            executionModeRepository.setExecutionMode(ExecutionMode.REMOTE_SSH)
            executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)

            // 联动：回填云端 Agent 地址（host 复用同一台服务器，端口取用户自定义的全局端口）。
            val agentPort = cloudAgentSettings.port()
            val agentUrl = "http://${params.host}:$agentPort"
            cloudAgentSettings.setServerUrl(agentUrl)
            cloudAgentSettings.setPortFor(connId, agentPort)
            cloudAgentSettings.setServerUrlFor(connId, agentUrl)

            // 联动：后台顺带部署 agent-server（失败不影响向导完成；用户可在设置页重试/查看日志）。
            val authStr = when (val a = params.auth) {
                is RemoteAuth.Password -> a.password
                is RemoteAuth.PrivateKey -> a.privateKeyPath
            }
            val deployConn = conn.copy(
                password = if (params.auth is RemoteAuth.Password) authStr else "",
                authType = if (params.auth is RemoteAuth.PrivateKey) "key" else "password",
                authData = authStr
            )
            runCatching { serverManager.deployOrUpgrade(deployConn, port = agentPort) { /* 向导阶段不逐行刷 UI */ } }

            _enabled.value = true
        }
    }

    data class ProvisionForm(
        val name: String = "",
        val host: String = "",
        val port: String = "22",
        val username: String = "root",
        val useKey: Boolean = false,
        val password: String = "",
        val keyPath: String = "",
        val keyPassphrase: String = "",
        val workspacePath: String = "~/minimecode-workspace",
        val imageName: String = "minimecode/ai-runtime:latest"
    )
}
