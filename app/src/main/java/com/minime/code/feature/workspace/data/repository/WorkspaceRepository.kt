package com.minime.code.feature.workspace.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.minime.code.core.datastore.preferencesCorruptionHandler
import com.minime.code.R
import com.minime.code.core.util.FileLogger
import com.minime.code.feature.agent.domain.container.ConnectionState
import com.minime.code.feature.agent.domain.container.ContainerProfile
import com.minime.code.feature.agent.domain.container.RemoteSshConnection
import com.minime.code.feature.agent.domain.session.SessionUseCase
import com.minime.code.feature.settings.data.repository.ContainerSettingsRepository
import com.minime.code.feature.credentials.data.CredentialInjectSettingsRepository
import com.minime.code.feature.credentials.domain.repository.CredentialRepository
import com.minime.code.feature.settings.data.repository.ExecutionMode
import com.minime.code.feature.settings.data.repository.ExecutionModeHolder
import com.minime.code.feature.settings.data.repository.GeneralSettingsRepository
import com.minime.code.feature.workspace.domain.PathHomeResolver
import com.minime.code.feature.workspace.domain.UriPathResolver
import com.minime.code.feature.workspace.domain.model.Workspace
import com.minime.code.feature.workspace.domain.model.WorkspaceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.workspaceDataStore by preferencesDataStore(
    name = "workspace_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 管理 App 内的"工作区/项目"。
 *
 * **本地模式**：所有项目放在内部私有目录 `filesDir/projects/<name>` 下——ext4 真实路径，
 * [java.io.File] 工具与 PRoot 容器挂载都能直接使用，无需运行时存储权限，且支持 symlink。
 *
 * **远程模式**：工作区 = 远程 SSH 服务器上 `remoteWorkspacePath` 下的子文件夹。
 * 列表/新建/删除通过 SFTP 操作远程目录，[Workspace.path] 为远程绝对路径。
 *
 * 当前选中的工作区名持久化在 DataStore 中，重启后保留（本地/远程共用同一份名字）。
 */
@Singleton
class WorkspaceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val pathHomeResolver: PathHomeResolver,
    private val sessionUseCase: SessionUseCase,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val credentialInjectSettings: CredentialInjectSettingsRepository,
    private val credentialRepository: CredentialRepository,
    private val containerSettingsRepository: ContainerSettingsRepository
) {
    /** 校验失败时给 UI 的提示文案（如目录不可写/无法解析），消费后置 null。 */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    /** UI 消费添加失败提示后调用，清除待展示的文案。 */
    fun consumeAddError() {
        _addError.value = null
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val DEFAULT_WORKSPACE = "default"
        private val json = Json { ignoreUnknownKeys = true }

        /** 外部工作区记录 → 工作区列表；[isDir] 判定目录当前是否存在，决定 available。 */
        internal fun mapExternalWorkspaces(
            records: List<ExternalWorkspaceRecord>,
            isDir: (String) -> Boolean
        ): List<Workspace> = records.map { rec ->
            Workspace(
                name = rec.name,
                path = rec.path,
                type = WorkspaceType.EXTERNAL_LOCAL,
                available = isDir(rec.path)
            )
        }

        /** 生成不与现有工作区重名的目录名，冲突时追加 " (n)" 后缀。 */
        internal fun uniqueName(base: String, existing: Set<String>): String {
            if (base !in existing) return base
            var i = 2
            while ("$base ($i)" in existing) i++
            return "$base ($i)"
        }

        /**
         * 本地工作区根目录名（按容器作用域隔离）。内置容器沿用旧 `projects` 保证存量升级不丢；
         * 自定义容器用 `projects_local_<清洗后的id>`，不同容器物理目录不同 → 工作区整组隔离。
         */
        internal fun projectsDirNameFor(profileId: String): String =
            if (profileId == ContainerProfile.BUILTIN_ID || profileId.isBlank()) {
                "projects"
            } else {
                "projects_local_${profileId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}"
            }

        /** 当前工作区选中态 key：内置容器用旧 key（存量兼容），自定义容器按 profileId 分开。 */
        internal fun currentNameKeySuffixFor(profileId: String): String =
            if (profileId == ContainerProfile.BUILTIN_ID || profileId.isBlank()) "" else "_local_$profileId"
    }

    private val currentNameKey = stringPreferencesKey("current_workspace_name")

    /**
     * 当前本地容器 profile id（作用域）。仅由 [initialize] 刷新，决定本地工作区的物理目录与选中态 key。
     * 内置 Alpine 恒为 [ContainerProfile.BUILTIN_ID]，沿用旧路径/旧 key 保证存量升级不丢工作区与会话。
     */
    @Volatile
    private var currentLocalProfileId: String = ContainerProfile.BUILTIN_ID

    /**
     * 当前工作区名按"容器作用域"分开存，实现「工作区归容器所有、切容器整组替换」：
     * - 内置 Alpine：沿用原 key（存量兼容）；
     * - 自定义本地容器：`current_workspace_name_local_<profileId>`，各容器记住自己的选择；
     * - 远程 SSH：按服务器（host:port）单独存。
     */
    private fun currentNameKeyForMode(): androidx.datastore.preferences.core.Preferences.Key<String> {
        if (isLocal()) {
            return stringPreferencesKey("current_workspace_name${currentNameKeySuffixFor(currentLocalProfileId)}")
        }
        val cfg = remoteSshConnection.config
        val suffix = if (cfg != null) "${cfg.host}:${cfg.port}" else ""
        return stringPreferencesKey("current_workspace_name_remote_$suffix")
    }
    private val externalWarningDismissedKey = booleanPreferencesKey("external_workspace_warning_dismissed")
    val externalWarningDismissed = context.workspaceDataStore.data
        .map { it[externalWarningDismissedKey] ?: false }

    suspend fun setExternalWarningDismissed(dismissed: Boolean) {
        context.workspaceDataStore.edit { it[externalWarningDismissedKey] = dismissed }
    }

    /** 外部本地工作区列表（用户所选设备目录），JSON 序列化持久化，重启保留。 */
    private val externalWorkspacesKey = stringPreferencesKey("external_workspaces")

    /**
     * 所有项目的父目录，固定用内部 filesDir（app 私有 ext4）。
     *
     * 必须是 ext4：外部私有目录（getExternalFilesDir）落在 emulated/FUSE 存储，内核拒绝
     * symlink()，npm/pnpm/yarn/git 建软链时会 `EACCES symlink` 而失败。filesDir 是 ext4，
     * symlink 原生可用，所有工具链零配置即可跑。对外可见性由 DocumentsProvider 暴露，不依赖物理位置。
     */
    /**
     * 所有项目的父目录，按【本地容器作用域】物理隔离：
     * - 内置 Alpine 沿用旧 `filesDir/projects`（存量工作区与会话路径不变，升级不丢）；
     * - 每个自定义本地容器用 `filesDir/projects_local_<profileId>`——切容器即整组换成该容器的工作区，
     *   同名工作区在不同容器下是不同物理目录，会话 workspacePath 因路径不同而天然隔离。
     *
     * 仍是 ext4（filesDir 内），symlink 与工具链行为不变。目录名对 profileId 做安全化，避免路径穿越。
     */
    private val projectsRoot: File
        get() = File(context.filesDir, projectsDirNameFor(currentLocalProfileId)).apply { mkdirs() }

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    /** 移除外部本地工作区时是否一并删除其聊天记录（供删除确认文案判断）。 */
    val deleteExternalWorkspaceSessionsFlow: Flow<Boolean> =
        generalSettingsRepository.deleteExternalWorkspaceSessionsFlow

    private val _current = MutableStateFlow<Workspace?>(null)
    val current: StateFlow<Workspace?> = _current.asStateFlow()

    /** 远程工作区初始化失败（根路径/默认工作区创建失败）的提示文案；UI 消费后置 null。 */
    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    /** UI 消费错误提示后调用，清除待展示的文案。 */
    fun consumeInitError() {
        _initError.value = null
    }

    /** 扫描并恢复上次选中的工作区；首次启动本地模式会创建默认工作区。应在 App/ViewModel 启动时调用一次。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        // 本地模式：刷新容器作用域，工作区根目录与选中态 key 随之切换，实现「切容器=整组换工作区」。
        // 必须在 refreshWorkspaces 之前——后者要用 currentLocalProfileId 计算 projectsRoot。
        if (isLocal()) {
            currentLocalProfileId = containerSettingsRepository.activeProfileIdFlow.first()
        }
        // 远程模式：等 SSH 连接就绪后再列工作区，避免启动时序竞争
        if (!isLocal()) {
            waitForConnection()
            ensureRemoteWorkspaceRoot()
        }
        // 清掉上一个模式/容器遗留的当前工作区：它不属于本作用域的列表，留着会让下面的可达性校验
        // 误判为“当前工作区失效”而回退，并把本作用域已保存的选择覆盖掉。
        _current.value = null
        refreshWorkspaces()

        // 没有可用工作区时创建默认工作区（本地/远程一致），保证 AI 始终有可用目录；
        // 全部工作区失联（如唯一外部目录被删除）也走这里，避免 currentPath 回退到父目录。
        if (_workspaces.value.none { it.available }) {
            val created = if (isLocal()) createLocalFallbackWorkspace() else {
                val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
                createWorkspace(fallbackName)
            }
            refreshWorkspaces()
            if (!isLocal() && created == null && remoteSshConnection.isConnected()) {
                _initError.value = context.getString(R.string.workspace_remote_default_create_failed)
            }
        }

        val savedName = context.workspaceDataStore.data.first()[currentNameKeyForMode()]
        val saved = savedName?.let { name -> _workspaces.value.firstOrNull { it.name == name } }
        val target = saved?.takeIf { it.available }
            ?: _workspaces.value.firstOrNull { it.available }
        if (saved != null && !saved.available) {
            _initError.value = target?.let {
                context.getString(R.string.workspace_unavailable_fallback, saved.name, it.name)
            } ?: context.getString(R.string.workspace_unavailable_current, saved.name)
            FileLogger.w(TAG, "上次工作区不可用，回退: ${saved.name} -> ${target?.name}")
        }
        _current.value = target
        val location = if (isLocal()) projectsRoot.absolutePath else remoteSshConnection.config?.remoteWorkspacePath ?: ""
        FileLogger.i(TAG, "工作区初始化完成，当前: ${target?.name}，根目录: $location")
        // 远程模式：选中工作区后更新符号链接，让 Bash 的 ~/workspace 指向当前工作区
        if (!isLocal() && target != null) {
            remoteSshConnection.updateWorkspaceSymlink(target.path)
            syncRemoteCredentials(target.path)
        }
    }

    private suspend fun syncRemoteCredentials(workspacePath: String) {
        val config = remoteSshConnection.config ?: return
        val key = credentialInjectSettings.serverKey(config.host, config.port)
        if (!credentialInjectSettings.isAutoInjectEnabled(key)) return
        remoteSshConnection.uploadGitCredentialConfig(credentialRepository.getAll().first(), workspacePath)
    }

    private fun isLocal(): Boolean =
        executionModeHolder.currentMode() != ExecutionMode.REMOTE_SSH

    /** 远程模式下等待 SSH 连接就绪（最多 5 秒），避免启动时序竞争。 */
    /** 远程模式下挂起等待 SSH 连接就绪（CONNECTED）；连接失败（FAILED）则提前返回，保持空工作区。 */
    private suspend fun waitForConnection() {
        val state = remoteSshConnection.connectionState.first {
            it == ConnectionState.CONNECTED || it == ConnectionState.FAILED
        }
        if (state == ConnectionState.FAILED) {
            FileLogger.w(TAG, "SSH 连接失败，工作区保持空")
        }
    }

    /** 远程模式：确保 remoteWorkspacePath 存在（用户填写的路径可能尚不存在），不存在则 mkdir -p 创建。
     *  @return 是否成功；连接不可用时不提示直接返回 false。 */
    private suspend fun ensureRemoteWorkspaceRoot(): Boolean {
        val cfg = remoteSshConnection.config ?: return false
        if (!remoteSshConnection.isConnected()) return false
        val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
        if (wsRoot.isEmpty()) return false
        val exit = execRemoteExit("mkdir -p ${shellQuote(wsRoot)}")
        if (exit != 0) {
            FileLogger.w(TAG, "创建远程工作区根目录失败: $wsRoot (exit=$exit)")
            _initError.value = context.getString(R.string.workspace_remote_root_create_failed)
            return false
        }
        return true
    }

    /** 重新扫描工作区可用性（打开面板、拔插存储后调用），失联项置灰并在必要时回退当前工作区。 */
    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        refreshWorkspaces()
    }

    /** 重新读取工作区目录列表。本地扫 projectsRoot + 外部本地工作区，远程 exec ls remoteWorkspacePath。 */
    private suspend fun refreshWorkspaces() {
        _workspaces.value = if (isLocal()) refreshLocalWorkspaces() else refreshRemoteWorkspaces()
        ensureCurrentReachable()
    }

    /**
     * 刷新后校验当前工作区：已被移除或不可用（目录被移动/删除）时回退到第一个可用工作区，
     * 并在实际发生切换时通过 [_initError] 提示用户。
     */
    private suspend fun ensureCurrentReachable() {
        val current = _current.value ?: return
        val matched = _workspaces.value.firstOrNull { it.name == current.name }
        if (matched != null && matched.available) return
        var fallback = _workspaces.value.firstOrNull { it.available }
        if (fallback == null && isLocal()) {
            fallback = createLocalFallbackWorkspace()
        }
        _current.value = fallback
        context.workspaceDataStore.edit { prefs ->
            val key = currentNameKeyForMode()
            if (fallback != null) prefs[key] = fallback.name else prefs.remove(key)
        }
        if (fallback != null && fallback.name != current.name) {
            _initError.value = context.getString(R.string.workspace_unavailable_fallback, current.name, fallback.name)
        }
        FileLogger.w(TAG, "当前工作区不可用，回退: ${current.name} -> ${fallback?.name}")
    }

    private suspend fun createLocalFallbackWorkspace(): Workspace? {
        val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
        val fallbackDir = File(projectsRoot, fallbackName)
        if (!fallbackDir.isDirectory && !fallbackDir.mkdirs()) return null
        _workspaces.value = refreshLocalWorkspaces()
        return _workspaces.value.firstOrNull { it.name == fallbackName && it.available }
    }

    /**
     * 当前容器作用域的可读名，用于工作区 UI 呈现归属绑定。
     * 内置 Alpine 用资源名；自定义容器用 profile.name；远程 SSH 用 host:port。
     */
    private suspend fun currentOwnerLabel(): String {
        if (!isLocal()) {
            val cfg = remoteSshConnection.config
            return cfg?.let { "${it.username}@${it.host}:${it.port}" } ?: ""
        }
        if (currentLocalProfileId == ContainerProfile.BUILTIN_ID || currentLocalProfileId.isBlank()) {
            return context.getString(R.string.container_builtin_name)
        }
        return containerSettingsRepository.customProfilesFlow.first()
            .firstOrNull { it.id == currentLocalProfileId }?.name ?: ""
    }

    private suspend fun refreshLocalWorkspaces(): List<Workspace> {
        val owner = currentOwnerLabel()
        val internal = projectsRoot.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Workspace(name = it.name, path = it.absolutePath, ownerLabel = owner) }
            ?: emptyList()
        // 外部本地工作区：失联（目录被移动/删除）时保留记录并标记不可用，目录恢复后自动重新可用
        val external = mapExternalWorkspaces(readExternalWorkspaces()) { File(it).isDirectory }
        return internal + external
    }

    /** exec 列出 remoteWorkspacePath 下的子目录作为工作区（不用 SFTP，避免 sshj Buffer bug）。 */
    private suspend fun refreshRemoteWorkspaces(): List<Workspace> {
        val cfg = remoteSshConnection.config ?: run {
            FileLogger.w(TAG, "远程工作区列表失败：SSH 未配置")
            return emptyList()
        }
        val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
        return runCatching {
            // 目录名可含空格，不能交给 xargs/basename 按空白分词（会把 "new api" 拆成两条），
            // 用 glob 逐项原样输出目录名。
            val output = execRemote(
                "cd ${shellQuote(wsRoot)} 2>/dev/null || exit 0; " +
                    "for d in */; do [ -d \"\$d\" ] && printf '%s\\n' \"\${d%/}\"; done"
            )
            if (output.isBlank()) emptyList()
            else output.lines().filter { it.isNotBlank() }
                .sortedBy { it.lowercase() }
                .map { Workspace(name = it, path = "$wsRoot/$it", type = WorkspaceType.REMOTE) }
        }.getOrElse {
            FileLogger.w(TAG, "远程工作区列表失败: $wsRoot", it)
            emptyList()
        }
    }

    /** 同步执行远程命令并返回 stdout（供工作区列表/新建/删除用）。 */
    private suspend fun execRemote(command: String): String =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                val output = java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                output
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }

    /** 同步执行远程命令并返回退出码。 */
    private suspend fun execRemoteExit(command: String): Int =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                session.exitStatus ?: -1
            } catch (e: Exception) {
                runCatching { session.close() }
                -1
            }
        }

    /** 切换当前工作区并持久化。 */
    suspend fun selectWorkspace(name: String) = withContext(Dispatchers.IO) {
        val target = _workspaces.value.firstOrNull { it.name == name && it.available } ?: return@withContext
        _current.value = target
        context.workspaceDataStore.edit { it[currentNameKeyForMode()] = name }
        FileLogger.i(TAG, "切换工作区: $name")
        // 远程模式：切换后更新符号链接指向新工作区
        if (!isLocal()) {
            remoteSshConnection.updateWorkspaceSymlink(target.path)
            syncRemoteCredentials(target.path)
        }
    }

    /**
     * 新建工作区目录。名称会被清洗为安全的文件夹名。
     * 本地模式 mkdirs projectsRoot/name；远程模式 SFTP mkdirs remoteWorkspacePath/name。
     * @return 创建成功的 [Workspace]；名称非法或已存在返回 null。
     */
    suspend fun createWorkspace(rawName: String): Workspace? = withContext(Dispatchers.IO) {
        val name = sanitize(rawName)
        if (name.isEmpty()) {
            FileLogger.w(TAG, "新建工作区失败：名称非法 '$rawName'")
            return@withContext null
        }
        if (isLocal()) {
            val dir = File(projectsRoot, name)
            if (dir.exists() || _workspaces.value.any { it.name == name }) {
                FileLogger.w(TAG, "新建工作区失败：已存在 '$name'")
                return@withContext null
            }
            if (!dir.mkdirs()) {
                FileLogger.e(TAG, "新建工作区失败：无法创建目录 ${dir.absolutePath}")
                return@withContext null
            }
            refreshWorkspaces()
            FileLogger.i(TAG, "新建工作区: $name")
            Workspace(name = name, path = dir.absolutePath)
        } else {
            val cfg = remoteSshConnection.config ?: return@withContext null
            val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
            val remotePath = "$wsRoot/$name"
            runCatching {
                if (execRemoteExit("test -d ${shellQuote(remotePath)}") == 0) {
                    FileLogger.w(TAG, "新建工作区失败：已存在 '$name'")
                    return@withContext null
                }
                execRemoteExit("mkdir -p ${shellQuote(remotePath)}")
            }.getOrElse {
                FileLogger.e(TAG, "远程新建工作区失败: $remotePath", it)
                return@withContext null
            }
            refreshWorkspaces()
            FileLogger.i(TAG, "新建工作区(远程): $name")
            Workspace(name = name, path = remotePath, type = WorkspaceType.REMOTE)
        }
    }

    /**
     * 注册用户所选设备目录为外部本地工作区（双向直读该目录，不复制）。
     * 校验：能解析为真实路径、是目录、可写。与现有工作区同名时自动加后缀。
     * @return 注册成功的 [Workspace]；校验失败返回 null（同时通过 [addError] 给出提示文案）。
     */
    suspend fun addExternalWorkspace(uri: Uri): Workspace? = withContext(Dispatchers.IO) {
        val uriFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val path = UriPathResolver.toFilePath(context, uri)
        if (path == null) {
            _addError.value = context.getString(R.string.workspace_external_unresolvable)
            FileLogger.w(TAG, "添加本地工作区失败：无法解析 uri")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        // 实际读写走 targetSdk 28 的 legacy storage + java.io.File；SAF grant 仅记录目录选择授权，不是文件后端。
        if (path.contains(':')) {
            _addError.value = context.getString(R.string.workspace_external_unsupported_path)
            FileLogger.w(TAG, "添加本地工作区失败：路径包含不支持的冒号 $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val dir = File(path)
        val existing = readExternalWorkspaces()
        if (existing.any { it.path == path }) {
            _addError.value = context.getString(R.string.workspace_external_exists)
            FileLogger.w(TAG, "添加本地工作区失败：目录已是工作区 $path")
            return@withContext null
        }
        if (!dir.isDirectory) {
            _addError.value = context.getString(R.string.workspace_external_invalid)
            FileLogger.w(TAG, "添加本地工作区失败：不是目录 $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val writable = runCatching {
            val probe = File.createTempFile(".minimecode_write_probe_", ".tmp", dir)
            try {
                probe.writeText("ok")
                if (!probe.delete()) error("probe delete failed")
                true
            } catch (e: Exception) {
                runCatching { probe.delete() }
                throw e
            }
        }.getOrDefault(false)
        if (!writable) {
            _addError.value = context.getString(R.string.workspace_external_unwritable)
            FileLogger.w(TAG, "添加本地工作区失败：目录不可写 $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val allNames = (_workspaces.value.map { it.name } + existing.map { it.name }).toSet()
        val baseName = sanitize(dir.name).ifBlank { DEFAULT_WORKSPACE }
        val name = uniqueName(baseName, allNames)
        writeExternalWorkspaces(existing + ExternalWorkspaceRecord(name, path, uri.toString()))
        refreshWorkspaces()
        val created = _workspaces.value.firstOrNull { it.path == path }
        FileLogger.i(TAG, "添加本地工作区: $name ($path)")
        created
    }

    private fun releaseUriGrant(uri: Uri, flags: Int) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    /** 删除工作区。内部/远程工作区连同文件与会话记录删除；外部本地工作区只解除关联，不删除物理目录，
     *  其会话记录是否一并删除由「偏好设置」中的开关决定。
     *  若删的是当前工作区，则自动切到剩余的第一个。 */
    suspend fun deleteWorkspace(name: String) = withContext(Dispatchers.IO) {
        val target = _workspaces.value.firstOrNull { it.name == name }
        if (isLocal() && target?.type == WorkspaceType.EXTERNAL_LOCAL) {
            val records = readExternalWorkspaces()
            val removed = records.firstOrNull { it.name == name }
            writeExternalWorkspaces(records.filterNot { it.name == name })
            removed?.let { record ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(record.uri),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
            if (generalSettingsRepository.deleteExternalWorkspaceSessions()) {
                sessionUseCase.deleteSessionsByWorkspace(target.path)
            }
            FileLogger.i(TAG, "移除外部工作区关联: $name")
        } else if (isLocal()) {
            File(projectsRoot, name).deleteRecursively()
            target?.let { sessionUseCase.deleteSessionsByWorkspace(it.path) }
        } else {
            val cfg = remoteSshConnection.config
            if (cfg != null) {
                val remotePath = "${pathHomeResolver.expandHome(cfg.remoteWorkspacePath).trimEnd('/')}/$name"
                runCatching { execRemoteExit("rm -rf ${shellQuote(remotePath)}") }
                    .onFailure { FileLogger.e(TAG, "远程删除工作区失败: $remotePath", it) }
            }
            target?.let { sessionUseCase.deleteSessionsByWorkspace(it.path) }
        }
        refreshWorkspaces()
        FileLogger.i(TAG, "删除工作区: $name")
    }

    /** 外部本地工作区持久化记录。 */
    @Serializable
    internal data class ExternalWorkspaceRecord(
        val name: String,
        val path: String,
        val uri: String
    )

    private suspend fun readExternalWorkspaces(): List<ExternalWorkspaceRecord> {
        val raw = context.workspaceDataStore.data.first()[externalWorkspacesKey] ?: return emptyList()
        return runCatching { json.decodeFromString<List<ExternalWorkspaceRecord>>(raw) }
            .getOrElse {
                FileLogger.w(TAG, "外部工作区记录解析失败，已忽略", it)
                emptyList()
            }
    }

    private suspend fun writeExternalWorkspaces(list: List<ExternalWorkspaceRecord>) {
        context.workspaceDataStore.edit { it[externalWorkspacesKey] = json.encodeToString(list) }
    }

    /** 单引号转义，保证 shell 命令安全。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 当前工作区的路径，供 projectRoot / 命令执行目录使用。
     * 本地模式返回宿主工作区绝对路径；远程模式返回选中工作区的远程绝对路径（命令 cd 到此）。
     * 无选中时本地回退到项目根目录，远程回退到配置的 remoteWorkspacePath。 */
    fun currentPath(): String {
        if (!isLocal()) {
            return _current.value?.path
                ?: remoteSshConnection.config?.remoteWorkspacePath
                    ?.let { pathHomeResolver.expandHome(it) }
                    ?.takeIf { it.isNotBlank() }
                ?: "/"
        }
        return _current.value?.path ?: projectsRoot.absolutePath
    }

    /** 仅保留字母数字、下划线、连字符、点和空格，去掉路径分隔符等危险字符。 */
    private fun sanitize(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9 ._\\u4e00-\\u9fa5-]"), "")
            .trim()
            .take(64)
}
