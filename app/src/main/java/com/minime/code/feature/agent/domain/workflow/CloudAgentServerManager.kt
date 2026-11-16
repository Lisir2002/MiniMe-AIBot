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
        /** 基础镜像：服务器无需预装 JRE，挂载 jar 运行。与 agent-server/Dockerfile 的 FROM 一致。 */
        const val JAVA_IMAGE = "eclipse-temurin:17-jre"
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
                ReleaseInfo(
                    tag = tag,
                    version = version,
                    jarUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tag/agent-server-$version.jar",
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
     * 在已保存连接上部署或升级 agent-server。
     * @param onProgress 流式回显每一步日志（运行在 IO 线程）。
     */
    suspend fun deployOrUpgrade(
        conn: RemoteConnection,
        onProgress: (String) -> Unit = {}
    ): Result<DeployResult> = withContext(Dispatchers.IO) {
        var client: SSHClient? = null
        try {
            onProgress("解析最新服务端版本…\n")
            val rel = latestRelease().getOrElse {
                return@withContext Result.success(
                    DeployResult(false, "", "获取最新版本失败：${it.message ?: "未知错误"}（请检查网络）", "")
                )
            }
            onProgress("最新版本 ${rel.version}，准备连接 ${conn.host}:${conn.port} …\n")

            client = connect(conn)
            val log = StringBuilder()

            // 1) 下载 jar 到临时文件（失败不影响旧版）
            onProgress("下载 agent-server-${rel.version}.jar …\n")
            val downloadCmd = buildDownloadCommand(rel.jarUrl)
            val r1 = exec(client, downloadCmd, onProgress)
            log.append(r1.output)
            if (r1.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, "", "下载 jar 失败（退出码 ${r1.exitCode}）", log.toString())
                )
            }

            // 2) 原子替换 jar + 写 systemd 服务（无 systemd 时降级 nohup）+ 启动
            onProgress("安装并启动服务…\n")
            val installCmd = buildInstallCommand()
            val r2 = exec(client, installCmd, onProgress)
            log.append(r2.output)
            if (r2.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, "", "安装/启动失败（退出码 ${r2.exitCode}）", log.toString())
                )
            }

            // 3) 等待 health + version 校验（服务端启动需要几秒）
            onProgress("校验服务健康状态…\n")
            val verifyCmd = buildVerifyCommand()
            val r3 = exec(client, verifyCmd, onProgress)
            log.append(r3.output)
            val deployedVersion = parseVersionFromOutput(r3.output) ?: "unknown"

            if (r3.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, deployedVersion, "服务启动后健康检查未通过", log.toString())
                )
            }

            // 4) 回填 serverUrl（复用基座连接 host，端口固定 8080）
            settings.setServerUrl("http://${conn.host}:$SERVER_PORT")
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

    // 用 Docker 跑 agent-server：服务器通常没装 java，但既然已用 Docker 跑 ai-runtime，
    // 复用 Docker + eclipse-temurin:17-jre 镜像挂载 jar 运行，无需宿主 JRE、隔离且自动重启。
    private fun buildInstallCommand(): String = listOf(
        "set -e",
        "D=\"\$HOME/$INSTALL_DIR\"",
        "WS=\"\$HOME/minimecode-workspace\"",
        "mkdir -p \"\$D\" \"\$WS\"",
        "if ! command -v docker >/dev/null 2>&1; then echo '未检测到 Docker，请先安装 Docker'; exit 1; fi",
        "docker image inspect $JAVA_IMAGE >/dev/null 2>&1 || docker pull $JAVA_IMAGE",
        "docker rm -f $SERVICE_NAME >/dev/null 2>&1 || true",
        "docker run -d --name $SERVICE_NAME --restart unless-stopped -p $SERVER_PORT:$SERVER_PORT " +
            "-v \"\$D:/data/agent-server\" -v \"\$WS:/workspace\" " +
            "-e MINIME_DB=/data/agent-server/events.db -e MINIME_WORKSPACE=/workspace -e MINIME_PORT=$SERVER_PORT " +
            "$JAVA_IMAGE java -jar /data/agent-server/agent-server.jar >/dev/null",
        "echo \"service=docker container=$SERVICE_NAME\""
    ).joinToString("\n")

    private fun buildVerifyCommand(): String = listOf(
        "OK=0",
        "for i in \$(seq 1 20); do",
        "  V=\$(curl -fsS --max-time 2 http://127.0.0.1:$SERVER_PORT/version 2>/dev/null || true)",
        "  if [ -n \"\$V\" ]; then echo \"VERSION_JSON=\$V\"; OK=1; break; fi",
        "  sleep 1",
        "done",
        "if [ \"\$OK\" = \"1\" ]; then echo 'health-ok'; else echo 'health-failed'; exit 1; fi"
    ).joinToString("\n")

    private fun parseVersionFromOutput(output: String): String? {
        val line = output.lineSequence().firstOrNull { it.startsWith("VERSION_JSON=") } ?: return null
        val json = line.removePrefix("VERSION_JSON=")
        return runCatching {
            val obj = gson.fromJson(json, Map::class.java) ?: emptyMap<String, Any?>()
            obj["version"] as? String
        }.getOrNull()
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
