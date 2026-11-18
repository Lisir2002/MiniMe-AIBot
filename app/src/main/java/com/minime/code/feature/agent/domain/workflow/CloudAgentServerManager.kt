package com.minime.code.feature.agent.domain.workflow

import com.minime.code.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.minime.code.feature.agent.domain.container.SshHostKeyStore
import com.minime.code.feature.agent.domain.container.SshPrivateKeyStore
import com.minime.code.feature.agent.domain.container.friendlySshError
import com.minime.code.feature.agent.domain.container.sshHostKeyFingerprint
import com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
import com.minime.code.feature.workspace.domain.model.RemoteConnection
import com.minime.code.feature.workspace.domain.remote.RemoteAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端 agent-server 的部署 / 升级 / 版本查询管理器。
 *
 * 复用「连接与同步」基座的认证存储（[SshHostKeyStore]/[SshPrivateKeyStore]）在用户已保存的
 * 服务器上执行部署脚本，避免为云端 Agent 另起一套连接配置。职责：
 * 1. [latestRelease] 从 GitHub Releases 取最新 tag + jar 下载 URL；
 * 2. [deployOrUpgrade] SSH 到服务器下载 jar、写 systemd 服务、启动、health/version 校验；
 * 3. [queryVersion] 读取服务端当前版本，供设置页「当前/期望版本」对比。
 *
 * 部署脚本幂等：重复执行只会更新 jar 并重启服务；失败时保留上一版 jar（下载到临时文件后 mv）。
 */
@Singleton
class CloudAgentServerManager @Inject constructor(
    private val hostKeyStore: SshHostKeyStore,
    private val privateKeyStore: SshPrivateKeyStore,
    private val settings: CloudAgentSettingsRepository
) {
    private companion object {
        const val TAG = "CloudAgentServerManager"
        const val REPO_OWNER = "Lisir2002"
        const val REPO_NAME = "MiniMe-AIBot"
        const val SERVER_PORT = 8080
        const val INSTALL_DIR = ".minimecode/agent-server"
        const val SERVICE_NAME = "minime-agent-server"
        /**
         * 合并基座镜像（Java + 完整工具链）：一期起替代薄 JRE 镜像。
         * 推到 GitHub Container Registry（CI 用 GITHUB_TOKEN，无需额外账号）；国内 pull 失败时兜底 Release tar。
         * 业务 jar 仍从 GitHub Release 下载后挂载（见 buildDownloadCommand），因此基座镜像低频更新。
         */
        const val BASE_IMAGE = "ghcr.io/lisir2002/minimecode-agent-server:base"
        /** 兜底镜像 tar 的 Release asset 名（CI 随每次发版上传，供 docker pull 失败时 load）。 */
        const val BASE_IMAGE_TAR = "agent-server-base.tar.gz"
    }

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** GitHub Release 的 tag 与 jar 下载地址。 */
    data class ReleaseInfo(val tag: String, val version: String, val jarUrl: String, val htmlUrl: String)

    /** 部署/升级结果：成功时携带服务端实际版本。 */
    data class DeployResult(val ok: Boolean, val version: String, val message: String, val log: String)

    /** 服务端版本信息。 */
    data class ServerVersionInfo(val version: String, val protocol: Int)

    /** 容器检测结果：部署前先探测服务器上的专属 AIAgent 容器状态。 */
    data class Inspection(
        val containerExists: Boolean,   // minime-agent-server 容器是否存在
        val running: Boolean,           // 容器是否运行中
        val currentVersion: String?,    // /version 读到的当前版本（null=读不到）
        val latestVersion: String?,     // GitHub 最新版本（null=取不到）
        val action: Action              // 建议动作
    ) {
        enum class Action {
            INSTALL,        // 无容器 → 装最新版
            UPGRADE,        // 有容器但版本旧/读不到 → 升级
            UP_TO_DATE,     // 有容器且已是最新 → 跳过
            UNKNOWN         // 连不上/取不到版本 → 由调用方决定是否强制部署
        }

        /** 是否已运行最新版本（无需任何部署动作）。 */
        val isUpToDate: Boolean get() = action == Action.UP_TO_DATE
    }

    private data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("assets") val assets: List<GitHubAsset> = emptyList()
    )

    private data class GitHubAsset(@SerializedName("name") val name: String)

    /**
     * 期望服务端版本：与 App 版本同源（发布 tag）。
     * CI 里 GITHUB_REF_NAME=v1.0.0-rc10 → BuildConfig.VERSION_NAME=1.0.0-rc10。
     * 本地/无 tag 构建为 1.0.0，此时不做强校验（避免误报）。
     */
    val expectedVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * 从 GitHub Releases API 获取最新版本，构造 jar 下载地址。
     * 注意：本项目所有 rc 版本都是 prerelease，GitHub 的 /releases/latest 不含 prerelease（返回 404），
     * 因此必须用 /releases 列表取第一条（按创建时间倒序，即最新），它包含 prerelease。
     */
    suspend fun latestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=10")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MiniMe-Agent")
                .get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("GitHub Releases HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val type = object : com.google.gson.reflect.TypeToken<ArrayList<GitHubRelease>>() {}.type
                val list = gson.fromJson<ArrayList<GitHubRelease>>(body, type)
                    ?: throw IllegalStateException("解析 GitHub Release 失败")
                // 只取带 agent-server-*.jar 资产的最新版本：rc9 及之前无 jar 资产需跳过，
                // GitHub 按创建时间倒序，列表里第一个含 jar 的即最新可用版本。
                val rel = list.firstOrNull { r -> r.assets.any { it.name.startsWith("agent-server-") && it.name.endsWith(".jar") } }
                    ?: throw IllegalStateException("暂无包含 agent-server.jar 的 Release，请先发布服务端版本")
                val tag = rel.tagName
                val version = tag.removePrefix("v")
                // CI 里资产命名用完整 tag（含 v）：agent-server-v1.0.0-rc10.jar。
                // 必须从 Release 的实际 assets 名取，不能用去 v 的 version 拼（会 404）。
                val assetName = rel.assets.first { it.name.startsWith("agent-server-") && it.name.endsWith(".jar") }.name
                ReleaseInfo(
                    tag = tag,
                    version = version,
                    jarUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tag/$assetName",
                    htmlUrl = rel.htmlUrl
                )
            }
        }
    }

    /** 读取服务端版本（HTTP /version），供设置页对比。 */
    suspend fun queryVersion(baseUrl: String): Result<ServerVersionInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = baseUrl.trim().trimEnd('/')
            val req = Request.Builder().url("$url/version").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val obj = gson.fromJson(body, Map::class.java) ?: emptyMap<String, Any?>()
                ServerVersionInfo(
                    version = (obj["version"] as? String) ?: "unknown",
                    protocol = (obj["protocol"] as? Number)?.toInt() ?: -1
                )
            }
        }
    }

    /**
     * 部署前探测：SSH 到服务器检查专属 AIAgent 容器是否存在、是否运行、当前版本，
     * 并从 GitHub 取最新版本，得出 [Inspection.action] 决策。
     *
     * 不修改服务器任何状态，可安全重复调用；连接/解析失败时返回 [Inspection.Action.UNKNOWN]，
     * 由调用方决定是否继续强制部署。
     */
    suspend fun inspect(
        conn: RemoteConnection,
        onProgress: (String) -> Unit = {}
    ): Result<Inspection> = withContext(Dispatchers.IO) {
        var client: SSHClient? = null
        try {
            onProgress("读取最新服务端版本…\n")
            val latest = latestRelease().getOrNull()?.version
            onProgress("连接 ${conn.host}:${conn.port} 检测容器…\n")
            client = connect(conn)
            val r = exec(client, buildInspectCommand(), onProgress)
            val containerLine = r.output.lineSequence()
                .firstOrNull { it.startsWith("INSPECT_CONTAINER=") }
                ?.removePrefix("INSPECT_CONTAINER=").orEmpty()
            val versionJson = r.output.lineSequence()
                .firstOrNull { it.startsWith("INSPECT_VERSION_JSON=") }
                ?.removePrefix("INSPECT_VERSION_JSON=")?.takeIf { it.isNotBlank() }
            val current = versionJson?.let { parseVersionFromJson(it) }

            val inspection = decideInspection(
                containerRaw = containerLine,
                currentVersion = current,
                latestVersion = latest
            )
            onProgress(
                when (inspection.action) {
                    Inspection.Action.INSTALL -> "检测结果：未发现专属容器，将安装最新版 ${latest ?: "?"}\n"
                    Inspection.Action.UPGRADE -> "检测结果：当前 ${current ?: "未知"}，最新 ${latest ?: "?"}，需升级\n"
                    Inspection.Action.UP_TO_DATE -> "检测结果：已是最新版本 ${current}\n"
                    Inspection.Action.UNKNOWN -> "检测结果：无法确认容器/版本状态，将尝试部署\n"
                }
            )
            Result.success(inspection)
        } catch (e: Exception) {
            Result.success(
                Inspection(
                    containerExists = false,
                    running = false,
                    currentVersion = null,
                    latestVersion = null,
                    action = Inspection.Action.UNKNOWN
                )
            )
        } finally {
            runCatching { client?.disconnect() }
        }
    }

    /**
     * 在已保存连接上部署或升级 agent-server。
     *
     * 部署前先 [inspect]：容器已是最新且运行时直接跳过重装（幂等升级）；否则下载最新 jar
     * 并原子替换重启。UNKNOWN（探测失败）时仍尝试部署，避免探测能力故障阻断正常升级。
     *
     * @param token 服务端 MINIME_TOKEN 与客户端 Bearer 共用的访问令牌；写入容器环境变量并用于部署后鉴权校验。
     *              为空时服务端不校验（兼容旧行为）。
     * @param onProgress 流式回显每一步日志（运行在 IO 线程）；日志经过 token 脱敏，不会回显明文。
     */
    suspend fun deployOrUpgrade(
        conn: RemoteConnection,
        token: String = "",
        onProgress: (String) -> Unit = {}
    ): Result<DeployResult> = withContext(Dispatchers.IO) {
        var client: SSHClient? = null
        // 所有进度/日志回显统一经 token 脱敏，避免令牌出现在远端回显或 UI 部署日志中。
        val safeProgress: (String) -> Unit = { line -> onProgress(if (token.isBlank()) line else line.replace(token, "****")) }
        fun mask(text: String) = if (token.isBlank()) text else text.replace(token, "****")
        try {
            safeProgress("解析最新服务端版本…\n")
            val rel = latestRelease().getOrElse {
                return@withContext Result.success(
                    DeployResult(false, "", "获取最新版本失败：${it.message ?: "未知错误"}（请检查网络）", "")
                )
            }
            safeProgress("最新版本 ${rel.version}，准备连接 ${conn.host}:${conn.port} …\n")

            client = connect(conn)
            val log = StringBuilder()

            // 0) 部署前检测：已有最新容器且运行中则跳过重装（幂等，避免每次点都重启服务）
            safeProgress("检测服务器现有容器…\n")
            val probe = exec(client, buildInspectCommand(), safeProgress)
            log.append(mask(probe.output))
            val containerLine = probe.output.lineSequence()
                .firstOrNull { it.startsWith("INSPECT_CONTAINER=") }
                ?.removePrefix("INSPECT_CONTAINER=").orEmpty()
            val versionJson = probe.output.lineSequence()
                .firstOrNull { it.startsWith("INSPECT_VERSION_JSON=") }
                ?.removePrefix("INSPECT_VERSION_JSON=")?.takeIf { it.isNotBlank() }
            val existing = decideInspection(containerLine, versionJson?.let { parseVersionFromJson(it) }, rel.version)
            if (existing.action == Inspection.Action.UP_TO_DATE) {
                safeProgress("已是最新版本 ${existing.currentVersion}，无需重装。\n")
                settings.setServerUrl("http://${conn.host}:$SERVER_PORT")
                settings.setServerUrlFor(conn.id, "http://${conn.host}:$SERVER_PORT")
                return@withContext Result.success(
                    DeployResult(true, existing.currentVersion ?: rel.version, "已是最新版本，无需重装", log.toString())
                )
            }

            // 1) 下载 jar 到临时文件（失败不影响旧版）
            safeProgress("下载 agent-server-${rel.version}.jar …\n")
            val downloadCmd = buildDownloadCommand(rel.jarUrl)
            val r1 = exec(client, downloadCmd, safeProgress)
            log.append(mask(r1.output))
            if (r1.exitCode != null && r1.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, "", "下载 jar 失败（退出码 ${r1.exitCode}）", log.toString())
                )
            }

            // 2) 原子替换 jar + 写 systemd 服务（无 systemd 时降级 nohup）+ 启动
            safeProgress("安装并启动服务…\n")
            val installCmd = buildInstallCommand(rel.tag, token)
            val r2 = exec(client, installCmd, safeProgress)
            log.append(mask(r2.output))
            if (r2.exitCode != null && r2.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, "", "安装/启动失败（退出码 ${r2.exitCode}）", log.toString())
                )
            }

            // 3) 等待 health + version 校验（服务端启动需要几秒）；带 token 调受保护端点确认鉴权真生效
            safeProgress("校验服务健康状态…\n")
            val verifyCmd = buildVerifyCommand(token)
            val r3 = exec(client, verifyCmd, safeProgress)
            log.append(mask(r3.output))
            val deployedVersion = parseVersionFromOutput(r3.output) ?: "unknown"

            if (r3.exitCode != null && r3.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, deployedVersion, "服务启动后健康检查/鉴权校验未通过（token 是否正确写入？）", log.toString())
                )
            }

            // 4) 回填 serverUrl（复用基座连接 host，端口固定 8080）；按 connectionId 存一份供多服务器解析
            settings.setServerUrl("http://${conn.host}:$SERVER_PORT")
            settings.setServerUrlFor(conn.id, "http://${conn.host}:$SERVER_PORT")
            Result.success(DeployResult(true, deployedVersion, "部署成功", log.toString()))
        } catch (e: Exception) {
            Result.success(DeployResult(false, "", friendlySshError(e), ""))
        } finally {
            runCatching { client?.disconnect() }
        }
    }

    // ---- SSH 连接（复用基座认证存储）----

    private fun connect(conn: RemoteConnection): SSHClient {
        val verifier = object : HostKeyVerifier {
            override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val saved = hostKeyStore.get(hostname, port)
                val fp = sshHostKeyFingerprint(key)
                // 已保存指纹才放行；未确认的连接应先在「连接与同步」页测试连通性完成确认。
                return saved != null && saved == fp
            }
        }
        return SSHClient().apply {
            setConnectTimeout(20_000)
            setTimeout(60_000)
            addHostKeyVerifier(verifier)
            connect(conn.host, conn.port)
            when (val a = resolveAuth(conn)) {
                is RemoteAuth.Password -> authPassword(conn.username, a.password)
                is RemoteAuth.PrivateKey -> {
                    val pem = privateKeyStore.readPem(a.privateKeyPath)
                    val pf = a.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    authPublickey(conn.username, loadKeys(pem, null, pf))
                }
            }
        }
    }

    private fun resolveAuth(conn: RemoteConnection): RemoteAuth = when {
        conn.authType == "key" && conn.authData.isNotBlank() ->
            RemoteAuth.PrivateKey(conn.authData, conn.password.ifBlank { null })
        conn.password.isNotBlank() -> RemoteAuth.Password(conn.password)
        conn.authData.isNotBlank() -> RemoteAuth.PrivateKey(conn.authData, conn.password.ifBlank { null })
        else -> throw IllegalStateException("连接缺少认证凭据，请先在「连接与同步」页更新")
    }

    private data class ExecOutput(val output: String, val exitCode: Int?)

    private fun exec(client: SSHClient, command: String, onProgress: (String) -> Unit): ExecOutput {
        val session = client.startSession()
        val cmd = session.exec("export LANG=C LC_ALL=C; $command")
        runCatching { cmd.outputStream.close() }
        val sb = StringBuilder()
        try {
            BufferedReader(InputStreamReader(cmd.inputStream)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    sb.append(line).append('\n')
                    onProgress(line + "\n")
                }
            }
            BufferedReader(InputStreamReader(cmd.errorStream)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    sb.append(line).append('\n')
                    onProgress(line + "\n")
                }
            }
            // 关键：exit-status 是 SSH 独立消息包，到达可能晚于 stdout EOF。
            // 必须 join 等待命令真正结束并收齐 exit-status，否则直接读 exitStatus 会得到 null，
            // 导致「安装脚本明明执行到最后一行、却被误判为退出码 null 失败」的竞态。
            runCatching { cmd.join(30, java.util.concurrent.TimeUnit.SECONDS) }
            return ExecOutput(sb.toString(), cmd.exitStatus)
        } finally {
            runCatching { session.close() }
        }
    }

    // ---- 远程脚本 ----

    // 注意：普通 Kotlin 字符串里 \$ = 字面 $（交给远程 shell 展开）；裸 $XXX 是 Kotlin 编译期插值。
    private fun buildDownloadCommand(jarUrl: String): String {
        val q = shellQuote(jarUrl)
        return listOf(
            "set -e",
            "D=\"\$HOME/$INSTALL_DIR\"",
            "mkdir -p \"\$D\"",
            "curl -fsSL --retry 3 --connect-timeout 15 $q -o \"\$D/agent-server.jar.new\"",
            "test -s \"\$D/agent-server.jar.new\"",
            "mv -f \"\$D/agent-server.jar.new\" \"\$D/agent-server.jar\"",
            "echo \"jar downloaded: \$(wc -c < \"\$D/agent-server.jar\") bytes\""
        ).joinToString("\n")
    }

    /**
     * 用合并基座镜像跑 agent-server（Java + 完整工具链）。
     * 镜像获取策略（用户选定的 a 主 + b 兜底）：
     *   1) 本地已有该镜像 → 直接用；
     *   2) 否则 docker pull（ghcr.io，最快、分层增量）；
     *   3) pull 失败（国内网络）→ 从 GitHub Release 下载镜像 tar + docker load 兜底。
     * 业务 jar 由 [buildDownloadCommand] 单独从 Release 下载后挂载，故基座镜像低频更新。
     */
    private fun buildInstallCommand(releaseTag: String, token: String = ""): String {
        val tarUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$releaseTag/$BASE_IMAGE_TAR"
        val q = shellQuote(tarUrl)
        // token 为空时不写环境变量 → 服务端保持不校验（兼容旧行为/本地测试）；非空则 shell 引号包裹后注入，防注入。
        val tokenEnv = if (token.isBlank()) "" else " -e MINIME_TOKEN=${shellQuote(token)}"
        return listOf(
            "set -e",
            "D=\"\$HOME/$INSTALL_DIR\"",
            "WS=\"\$HOME/minimecode-workspace\"",
            "mkdir -p \"\$D\" \"\$WS\"",
            "if ! command -v docker >/dev/null 2>&1; then echo '未检测到 Docker，请先安装 Docker'; exit 1; fi",
            // 基座镜像：本地 → pull → tar 兜底
            "if ! docker image inspect $BASE_IMAGE >/dev/null 2>&1; then",
            "  echo '拉取云端运行环境镜像（$BASE_IMAGE）…'",
            "  if docker pull $BASE_IMAGE >/dev/null 2>&1; then",
            "    echo '镜像拉取成功'",
            "  else",
            "    echo '镜像仓库不可达，改用 Release 镜像包兜底…'",
            "    curl -fsSL --retry 3 --connect-timeout 15 $q -o \"\$D/base.tar.new\"",
            "    test -s \"\$D/base.tar.new\" || { echo '镜像包下载失败'; exit 1; }",
            "    mv -f \"\$D/base.tar.new\" \"\$D/base.tar\"",
            "    docker load -i \"\$D/base.tar\" >/dev/null",
            "    echo '镜像包载入成功'",
            "  fi",
            "fi",
            "docker rm -f $SERVICE_NAME >/dev/null 2>&1 || true",
            "docker run -d --name $SERVICE_NAME --restart unless-stopped -p $SERVER_PORT:$SERVER_PORT " +
                "-v \"\$D:/data/agent-server\" -v \"\$WS:/workspace\" " +
                "-e MINIME_DB=/data/agent-server/events.db -e MINIME_WORKSPACE=/workspace -e MINIME_PORT=$SERVER_PORT$tokenEnv " +
                "$BASE_IMAGE java -jar /data/agent-server/agent-server.jar >/dev/null",
            "echo \"service=docker container=$SERVICE_NAME image=$BASE_IMAGE\""
        ).joinToString("\n")
    }

    private fun buildVerifyCommand(token: String = ""): String {
        // 鉴权校验：token 非空时必须能带 Bearer 读到 /policy（受保护端点），否则说明 token 没写进服务端。
        // token 为空时只探测无需鉴权的 /version，保持向后兼容。
        val authFlag = if (token.isBlank()) "" else " -H \"Authorization: Bearer ${token.replace("\"", "")}\""
        return listOf(
            "OK=0",
            "P=000",
            "for i in \$(seq 1 20); do",
            "  V=\$(curl -fsS --max-time 2$authFlag http://127.0.0.1:$SERVER_PORT/version 2>/dev/null || true)",
            if (token.isNotBlank())
                "  P=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 2$authFlag http://127.0.0.1:$SERVER_PORT/policy 2>/dev/null || true)"
            else "  P=200",
            "  if [ -n \"\$V\" ] && [ \"\$P\" = \"200\" ]; then echo \"VERSION_JSON=\$V\"; OK=1; break; fi",
            "  sleep 1",
            "done",
            "if [ \"\$OK\" = \"1\" ]; then echo 'health-ok'; else echo 'health-failed'; exit 1; fi"
        ).joinToString("\n")
    }

    /**
     * 检测脚本：输出两类标记行供 App 解析——
     *  INSPECT_CONTAINER=<name|EMPTY>  容器是否存在（docker ps -a 含已停止的）
     *  INSPECT_VERSION_JSON=<json>     从容器内 /version 读到的版本（容器运行时才有）
     * docker 不可用时两项都为空，交给 decideInspection 判为 UNKNOWN。
     */
    private fun buildInspectCommand(): String = listOf(
        "if command -v docker >/dev/null 2>&1; then",
        "  C=\$(docker ps -a --filter 'name=^/$SERVICE_NAME\$' --format '{{.Names}}' 2>/dev/null | head -n1)",
        "  echo \"INSPECT_CONTAINER=\${C:-EMPTY}\"",
        "  V=\$(curl -fsS --max-time 3 http://127.0.0.1:$SERVER_PORT/version 2>/dev/null || true)",
        "  if [ -n \"\$V\" ]; then echo \"INSPECT_VERSION_JSON=\$V\"; fi",
        "else",
        "  echo 'INSPECT_CONTAINER=EMPTY'",
        "fi"
    ).joinToString("\n")

    private fun parseVersionFromOutput(output: String): String? {
        val line = output.lineSequence().firstOrNull { it.startsWith("VERSION_JSON=") } ?: return null
        return parseVersionFromJson(line.removePrefix("VERSION_JSON="))
    }

    internal fun parseVersionFromJson(json: String): String? = runCatching {
        val obj = gson.fromJson(json, Map::class.java) ?: emptyMap<String, Any?>()
        obj["version"] as? String
    }.getOrNull()

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/**
 * 纯决策函数（无副作用，供单测）：根据部署前探测到的容器名、当前版本、最新版本得出动作。
 *
 * @param containerRaw docker 输出的容器名；"EMPTY"/空白表示无容器
 * @param currentVersion 容器内 /version 读到的版本；null 表示读不到（容器未运行/服务未就绪）
 * @param latestVersion GitHub 最新版本；null 表示取不到（网络问题）
 */
internal fun decideInspection(
    containerRaw: String?,
    currentVersion: String?,
    latestVersion: String?
): CloudAgentServerManager.Inspection {
    val hasContainer = !containerRaw.isNullOrBlank() && containerRaw.trim() != "EMPTY"
    val running = currentVersion != null
    return when {
        latestVersion == null -> CloudAgentServerManager.Inspection(
            hasContainer, running, currentVersion, null,
            CloudAgentServerManager.Inspection.Action.UNKNOWN
        )
        !hasContainer -> CloudAgentServerManager.Inspection(
            false, false, null, latestVersion,
            CloudAgentServerManager.Inspection.Action.INSTALL
        )
        currentVersion == null -> CloudAgentServerManager.Inspection(
            true, false, null, latestVersion,
            CloudAgentServerManager.Inspection.Action.UPGRADE
        )
        currentVersion.trim().removePrefix("v") == latestVersion.trim().removePrefix("v") -> CloudAgentServerManager.Inspection(
            true, true, currentVersion, latestVersion,
            CloudAgentServerManager.Inspection.Action.UP_TO_DATE
        )
        else -> CloudAgentServerManager.Inspection(
            true, true, currentVersion, latestVersion,
            CloudAgentServerManager.Inspection.Action.UPGRADE
        )
    }
}
