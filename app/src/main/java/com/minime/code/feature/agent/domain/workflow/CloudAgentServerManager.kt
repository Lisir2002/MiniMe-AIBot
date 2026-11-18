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
    companion object {
        const val TAG = "CloudAgentServerManager"
        const val REPO_OWNER = "Lisir2002"
        const val REPO_NAME = "MiniMe-AIBot"
        const val SERVER_PORT = 8080
        const val INSTALL_DIR = ".minimecode/agent-server"
        const val SERVICE_NAME = "minime-agent-server"
        /**
         * 合并基座镜像（Java + 完整工具链）：一期起替代薄 JRE 镜像。
         * 推到 GitHub Container Registry（CI 用 GITHUB_TOKEN，无需额外账号）；国内 pull 失败时兜底 Release tar。
         *
         * 镜像 tag 必须**随版本变化**（`:base-<version>`，如 :base-1.0.0-rc19），
         * 绝不能用固定 :base —— 否则老用户本地已有同名 tag，`docker image inspect` 命中后
         * 升级脚本会跳过 pull，导致 jar 更新了、镜像（含 iptables 等系统依赖）永远是旧版。
         * 见 [buildInstallCommand]。
         */
        const val BASE_IMAGE_REPO = "ghcr.io/lisir2002/minimecode-agent-server"
        /** 兜底镜像 tar 的 Release asset 名（CI 随每次发版上传，供 docker pull 失败时 load）。 */
        const val BASE_IMAGE_TAR = "agent-server-base.tar.gz"

        /** 由 Release tag（含 v）推导版本化镜像全名：v1.0.0-rc19 → ...:base-1.0.0-rc19。 */
        fun baseImageFor(releaseTag: String): String =
            "$BASE_IMAGE_REPO:base-${releaseTag.removePrefix("v")}"

        /**
         * 从 releases.atom 解析最新 tag：取第一个 /releases/tag/<tag> 链接（feed 按时间倒序，第一条最新）。
         *  purified 为纯函数便于单测；atom 含 prerelease（所有 rc），已实测确认。
         */
        fun parseLatestTagFromAtom(atomXml: String): String? =
            Regex("""/releases/tag/([^"<>\s]+)""").find(atomXml)?.groupValues?.get(1)

        /** 403/429 限流的可读文案（方案 D）：区分限流与封禁，给出可操作建议。 */
        fun rateLimitMessage(code: Int, retryAfter: String?, resetAt: String?, rawBody: String): String {
            val isRateLimit = rawBody.contains("rate limit", ignoreCase = true) || code == 429
            return if (isRateLimit) {
                val whenStr = retryAfter?.let { "${it}秒后" }
                    ?: resetAt?.let { "约 $it" }
                    ?: "稍后"
                "GitHub API 请求过于频繁（未认证配额 60 次/小时，共享网络/VPN 出口 IP 会被共同计入）。" +
                    "请 $whenStr 重试，或关闭 VPN 后重试。"
            } else {
                "GitHub 拒绝访问（HTTP $code），可能是网络或代理限制。"
            }
        }

        /**
         * 前置环境准备脚本（新服务器一键安装的关键步骤，纯函数便于单测）：
         * 在下载 jar / 启动容器之前，先探测容器运行依赖是否齐全，缺失则**自动安装**，全程幂等。
         *
         * 覆盖：
         *  1) 提权：root 直连；非 root 且有 sudo 则借 sudo 安装系统依赖，否则给出明确指引后退出。
         *  2) 包管理器探测：apt / dnf / yum / apk。
         *  3) curl：下载 jar 与镜像 tar 依赖，缺失即装。
         *  4) Docker：缺失即装（apk 用 apk add，其余用官方 get.docker.com 脚本，覆盖主流发行版）。
         *  5) 启动 daemon：systemd → service → 直接 dockerd 三级降级。
         *  6) 探活：docker info 轮询（最多 40 秒），失败退出码非 0 阻断后续部署。
         *
         * 为什么不只报「请先安装 Docker」：用户从一开始就要求新服务器先自动补齐前置环境再部署，
         * 旧逻辑把检测压到最后一步且只报错，导致一键安装对全新服务器必然失败。
         */
        fun buildBootstrapCommand(): String = listOf(
            "set -e",
            "SUDO=''",
            "if [ \"\$(id -u)\" != \"0\" ]; then",
            "  if command -v sudo >/dev/null 2>&1; then SUDO='sudo';",
            "  else echo '安装前置依赖需要 root 或 sudo 权限，请用 root 账户连接'; exit 1; fi",
            "fi",
            "PKG=''",
            "if command -v apt-get >/dev/null 2>&1; then PKG=apt",
            "elif command -v dnf >/dev/null 2>&1; then PKG=dnf",
            "elif command -v yum >/dev/null 2>&1; then PKG=yum",
            "elif command -v apk >/dev/null 2>&1; then PKG=apk",
            "fi",
            "echo \"包管理器：\${PKG:-未识别}\"",
            "if ! command -v curl >/dev/null 2>&1; then",
            "  echo '未检测到 curl，正在安装…'",
            "  case \"\$PKG\" in",
            "    apt) \$SUDO apt-get update -qq && \$SUDO apt-get install -y curl ;;",
            "    dnf) \$SUDO dnf install -y curl ;;",
            "    yum) \$SUDO yum install -y curl ;;",
            "    apk) \$SUDO apk add --no-cache curl ;;",
            "    *) echo '无法识别包管理器，请手动安装 curl 后重试'; exit 1 ;;",
            "  esac",
            "else echo 'curl 已就绪'; fi",
            "if command -v docker >/dev/null 2>&1; then",
            "  echo 'Docker 已安装'",
            "else",
            "  echo '未检测到 Docker，正在自动安装…'",
            "  if [ \"\$PKG\" = 'apk' ]; then \$SUDO apk add --no-cache docker",
            "  else curl -fsSL --retry 3 --connect-timeout 15 https://get.docker.com | \$SUDO sh; fi",
            "fi",
            "if ! \$SUDO docker info >/dev/null 2>&1; then",
            "  echo '启动 Docker 服务…'",
            "  if command -v systemctl >/dev/null 2>&1; then \$SUDO systemctl enable --now docker >/dev/null 2>&1 || true; fi",
            "  if ! \$SUDO docker info >/dev/null 2>&1 && command -v service >/dev/null 2>&1; then \$SUDO service docker start >/dev/null 2>&1 || true; fi",
            "  if ! \$SUDO docker info >/dev/null 2>&1; then \$SUDO nohup dockerd >/dev/null 2>&1 & fi",
            "fi",
            "OK=0",
            "for i in \$(seq 1 20); do if \$SUDO docker info >/dev/null 2>&1; then OK=1; break; fi; sleep 2; done",
            "if [ \"\$OK\" != '1' ]; then echo 'Docker 安装后仍无法启动，请查看 journalctl -u docker 或重启服务器后重试'; exit 1; fi",
            "echo \"前置环境就绪：\$(\$SUDO docker --version)\""
        ).joinToString("\n")

        /**
         * 端口占用清理脚本（纯函数便于单测）：安装前释放指定端口，解决
         * 「docker rm -f 只删固定名容器，删不掉占端口的旧服务 → docker run 报 address already in use（退出码 125）」。
         *
         * 背景：旧版一键安装只 `docker rm -f minime-agent-server`，若该端口被历史容器（名字/镜像不同）
         * 或宿主机直接起的旧 agent 进程占用，就清不掉，新容器绑端口必然失败。
         *
         * 安全原则（绝不误杀用户其它服务）：
         *  1) docker 容器：按 `publish=$port` 找出占用者，名字/镜像/启动命令含
         *     minime|minimecode|agent-server 特征才删，否则仅提示；
         *  2) 宿主机进程：ss/lsof/fuser 探到 PID 后读 /proc/<pid>/cmdline，含 agent-server 特征才 kill，
         *     否则仅提示；
         *  3) 复核：若端口仍被占用则 exit 1 阻断部署并给出明确指引，不带着冲突往下走。
         */
        fun buildPortCleanupCommand(port: Int = SERVER_PORT): String = listOf(
            "SUDO=''",
            "if [ \"\$(id -u)\" != \"0\" ] && command -v sudo >/dev/null 2>&1; then SUDO='sudo'; fi",
            "echo '检查端口 $port 占用…'",
            // 1) docker 容器：按发布端口找，特征匹配才删
            "if command -v docker >/dev/null 2>&1; then",
            "  for cid in \$(\$SUDO docker ps -a --filter 'publish=$port' -q 2>/dev/null); do",
            "    INFO=\$(\$SUDO docker inspect --format '{{.Name}} {{.Config.Image}} {{.Config.Cmd}}' \"\$cid\" 2>/dev/null)",
            "    case \"\$INFO\" in",
            "      *minime*|*minimecode*|*agent-server*|*agent_server*)",
            "        echo \"移除占用 $port 的旧 agent 容器：\$INFO\"",
            "        \$SUDO docker rm -f \"\$cid\" >/dev/null 2>&1 || true",
            "        ;;",
            "      *) echo \"端口 $port 被其它容器占用（\$INFO），未自动删除，请先手动停止\" ;;",
            "    esac",
            "  done",
            "fi",
            // 2) 宿主机进程：按端口探 PID，特征匹配才 kill
            "PIDS=''",
            "if command -v ss >/dev/null 2>&1; then",
            "  PIDS=\$(\$SUDO ss -ltnp 'sport = :$port' 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)",
            "elif command -v lsof >/dev/null 2>&1; then",
            "  PIDS=\$(lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null)",
            "elif command -v fuser >/dev/null 2>&1; then",
            "  PIDS=\$(\$SUDO fuser -n tcp $port 2>/dev/null)",
            "fi",
            "for pid in \$PIDS; do",
            "  CMD=\$(tr '\\0' ' ' < /proc/\$pid/cmdline 2>/dev/null || true)",
            "  case \"\$CMD\" in",
            "    *agent-server*|*minimecode*|*minime*)",
            "      echo \"停止占用 $port 的旧 agent 进程（pid=\$pid）\"",
            "      kill -9 \"\$pid\" 2>/dev/null || true",
            "      ;;",
            "    *) echo \"端口 $port 被其它进程占用（pid=\$pid: \$(echo \"\$CMD\" | cut -c1-80)），未自动停止\" ;;",
            "  esac",
            "done",
            // 3) 复核端口，仍被占则阻断
            "STILL=0",
            "if command -v ss >/dev/null 2>&1; then",
            "  if [ -n \"\$(ss -ltn 'sport = :$port' 2>/dev/null | grep LISTEN)\" ]; then STILL=1; fi",
            "elif command -v lsof >/dev/null 2>&1; then",
            "  if lsof -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1; then STILL=1; fi",
            "fi",
            "if [ \"\$STILL\" != '0' ]; then echo '端口 $port 仍被占用，请先手动停止占用程序后重试'; exit 1; fi",
            "echo '端口 $port 已释放'"
        ).joinToString("\n")

        /**
         * 端口可用性探测脚本（纯函数便于单测）：在服务器上**实际尝试绑定**指定端口，
         * 判断能否被 agent-server 监听（比只看 ss/lsof 更准，能发现 TIME_WAIT / 权限 / 占用等真实绑定失败）。
         *
         * 尝试顺序：
         *  1) python3：bind('',port) + listen 后立即关闭 → 成功输出 PORT_PROBE=AVAILABLE，占用/权限输出 =BUSY/=DENIED；
         *  2) 无 python3 时用 nc -l 带超时试绑（nc 各不相同，尽力而为）；
         *  3) 都不可用时输出 PORT_PROBE=UNKNOWN 并附 ss/lsof 静态占用判断，由 UI 提示用户手动确认。
         *
         * 该脚本不部署、不改防火墙规则：真正的公网可达还需云服务器安全组/系统防火墙放行对应端口。
         */
        fun buildPortProbeCommand(port: Int = SERVER_PORT): String = listOf(
            "SUDO=''",
            "if [ \"\$(id -u)\" != \"0\" ] && command -v sudo >/dev/null 2>&1; then SUDO='sudo'; fi",
            "echo '尝试在 $port 端口建立测试监听…'",
            "if command -v python3 >/dev/null 2>&1; then",
            "  RESULT=\$(python3 -c \"import socket;s=socket.socket();s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('',$port));s.listen(1);s.close();print('OK')\" 2>&1 || true)",
            "  case \"\$RESULT\" in",
            "    *OK*) echo 'PORT_PROBE=AVAILABLE' ;;",
            "    *13*|*ermission*|*enied*) echo 'PORT_PROBE=DENIED' ;;",
            "    *) echo 'PORT_PROBE=BUSY' ;;",
            "  esac",
            "elif command -v nc >/dev/null 2>&1; then",
            "  if timeout 2 nc -l $port >/dev/null 2>&1; then echo 'PORT_PROBE=AVAILABLE'; else echo 'PORT_PROBE=UNKNOWN'; fi",
            "else",
            "  OCC=0",
            "  if command -v ss >/dev/null 2>&1 && [ -n \"\$(ss -ltn 'sport = :$port' 2>/dev/null | grep LISTEN)\" ]; then OCC=1; fi",
            "  if [ \"\$OCC\" = '1' ]; then echo 'PORT_PROBE=BUSY'; else echo 'PORT_PROBE=UNKNOWN'; fi",
            "fi"
        ).joinToString("\n")

        /** 从探测脚本回显解析 [PortProbeResult]（纯函数便于单测）。 */
        fun parsePortProbe(output: String, port: Int): PortProbeResult {
            val status = output.lineSequence()
                .firstOrNull { it.startsWith("PORT_PROBE=") }
                ?.removePrefix("PORT_PROBE=")?.trim() ?: "UNKNOWN"
            val message = when (status) {
                "AVAILABLE" -> "端口 $port 可用：已在服务器上成功绑定并监听（部署时可直接用）。"
                "BUSY" -> "端口 $port 已被占用或无法绑定，建议换一个端口或先停止占用程序。"
                "DENIED" -> "权限不足，无法绑定 $port（1024 以下端口需 root/sudo），建议换用 1024 以上端口。"
                "UNKNOWN" -> "无法自动确认 $port（服务器缺少探测工具），请手动检查后部署。"
                else -> "端口探测失败：$status"
            }
            return PortProbeResult(status == "AVAILABLE", status, message, output)
        }
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

    /** 端口可用性探测结果。 */
    data class PortProbeResult(
        val available: Boolean,   // 能否绑定监听
        val status: String,       // AVAILABLE / BUSY / DENIED / UNKNOWN / ERROR
        val message: String,      // 给 UI 的可读说明
        val log: String           // 远程回显
    )

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
     * 获取最新版本，构造 jar 下载地址。双通道 + 友好降级：
     *
     * 主通道 [latestReleaseFromAtom]：抓 github.com 的 releases.atom（RSS）。
     *   - 不走 api.github.com 的 60 次/小时未认证限流池（共享 VPN 出口 IP 极易被打满 → 403）；
     *   - atom 包含 prerelease（所有 rc 都在），第一条即最新，实测确认。
     * 兜底 [latestReleaseFromApi]：atom 失败（网络/解析）才用 Releases API，能拿到精确资产名。
     *
     * 本项目所有 rc 都是 prerelease，/releases/latest 不含 prerelease（404），故 API 通道用列表接口。
     */
    suspend fun latestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        // A：主通道 atom，无 API 限流；成功直接返回
        val atom = latestReleaseFromAtom()
        if (atom.isSuccess) return@withContext atom
        // 兜底 D：atom 失败才用 API（403 有可读限流提示）
        latestReleaseFromApi()
    }

    /** 主通道：解析 releases.atom。 jar 资产名按 CI 固定规则 agent-server-<tag>.jar 推导。 */
    private fun latestReleaseFromAtom(): Result<ReleaseInfo> = runCatching {
        val url = "https://github.com/$REPO_OWNER/$REPO_NAME/releases.atom"
        val body = httpGetWithRetry(url, accept = "application/atom+xml")
        val tag = parseLatestTagFromAtom(body)
            ?: throw IllegalStateException("Atom 中未找到 Release tag")
        val jarName = "agent-server-$tag.jar" // CI 固定命名：dist/agent-server-${ver}.jar（ver 含 v）
        ReleaseInfo(
            tag = tag,
            version = tag.removePrefix("v"),
            jarUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tag/$jarName",
            htmlUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/tag/$tag"
        )
    }

    /** 兜底通道：Releases API，能拿到精确资产名；403 限流给可读提示。 */
    private fun latestReleaseFromApi(): Result<ReleaseInfo> = runCatching {
        val url = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=10"
        val resp = httpGetWithRetryRaw(url, accept = "application/vnd.github+json")
        resp.use { r ->
            if (r.code == 403 || r.code == 429) {
                val retryAfter = r.header("Retry-After")
                val msg = r.body?.string().orEmpty()
                val resetAt = r.header("X-RateLimit-Reset")?.toLongOrNull()?.let { sec ->
                    runCatching { java.time.Instant.ofEpochSecond(sec).toString() }.getOrNull()
                }
                throw IllegalStateException(rateLimitMessage(r.code, retryAfter, resetAt, msg))
            }
            if (!r.isSuccessful) throw IllegalStateException("GitHub Releases HTTP ${r.code}")
            val body = r.body?.string().orEmpty()
            val type = object : com.google.gson.reflect.TypeToken<ArrayList<GitHubRelease>>() {}.type
            val list = gson.fromJson<ArrayList<GitHubRelease>>(body, type)
                ?: throw IllegalStateException("解析 GitHub Release 失败")
            val rel = list.firstOrNull { it.assets.any { a -> a.name.startsWith("agent-server-") && a.name.endsWith(".jar") } }
                ?: throw IllegalStateException("暂无包含 agent-server.jar 的 Release，请先发布服务端版本")
            val tag = rel.tagName
            val assetName = rel.assets.first { it.name.startsWith("agent-server-") && it.name.endsWith(".jar") }.name
            ReleaseInfo(
                tag = tag,
                version = tag.removePrefix("v"),
                jarUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tag/$assetName",
                htmlUrl = rel.htmlUrl
            )
        }
    }

    /** 带重试的 GET（返回字符串）；网络抖动/5xx 最多重试 2 次。 */
    private fun httpGetWithRetry(url: String, accept: String, attempts: Int = 3): String {
        var lastErr: Throwable? = null
        repeat(attempts) { i ->
            try {
                httpGetWithRetryRaw(url, accept).use { r ->
                    if (r.isSuccessful) return r.body?.string().orEmpty()
                    // 4xx（如 403 限流）重试无意义，直接抛给调用方解析
                    if (r.code in 400..499) throw IllegalStateException("HTTP ${r.code}")
                    lastErr = IllegalStateException("HTTP ${r.code}")
                }
            } catch (e: IllegalStateException) {
                throw e // 4xx 不重试
            } catch (e: Exception) {
                lastErr = e
            }
            if (i < attempts - 1) Thread.sleep(500L * (i + 1))
        }
        throw lastErr ?: IllegalStateException("请求失败：$url")
    }

    /** 单次 GET，返回原始 Response（调用方负责 close）。 */
    private fun httpGetWithRetryRaw(url: String, accept: String): okhttp3.Response {
        val req = Request.Builder().url(url)
            .header("Accept", accept)
            .header("User-Agent", "MiniMe-Agent")
            .get().build()
        return http.newCall(req).execute()
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
     * 端口可用性探测：SSH 到服务器实际尝试绑定端口，判断能否作为 agent-server 监听端口。
     * 不部署、不改防火墙；公网可达仍需云服务器安全组放行该端口。
     */
    suspend fun probePort(
        conn: RemoteConnection,
        port: Int = SERVER_PORT,
        onProgress: (String) -> Unit = {}
    ): Result<PortProbeResult> = withContext(Dispatchers.IO) {
        var client: SSHClient? = null
        try {
            client = connect(conn)
            val r = exec(client, buildPortProbeCommand(port), onProgress)
            Result.success(parsePortProbe(r.output, port))
        } catch (e: Exception) {
            Result.success(
                PortProbeResult(false, "ERROR", friendlySshError(e), "")
            )
        } finally {
            runCatching { client?.disconnect() }
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
        port: Int = SERVER_PORT,
        onProgress: (String) -> Unit = {}
    ): Result<Inspection> = withContext(Dispatchers.IO) {
        var client: SSHClient? = null
        try {
            onProgress("读取最新服务端版本…\n")
            val latest = latestRelease().getOrNull()?.version
            onProgress("连接 ${conn.host}:${conn.port} 检测容器…\n")
            client = connect(conn)
            val r = exec(client, buildInspectCommand(port), onProgress)
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
        port: Int = SERVER_PORT,
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
                    DeployResult(false, "", "获取最新版本失败：${it.message ?: "未知错误"}", "")
                )
            }
            safeProgress("最新版本 ${rel.version}，准备连接 ${conn.host}:${conn.port} …\n")

            client = connect(conn)
            val log = StringBuilder()

            // 0) 前置环境：新服务器先补齐容器运行依赖（curl + Docker），缺失则自动安装并启动。
            //    必须在探测/下载之前——旧逻辑把「未检测到 Docker」压到最后安装步骤才报错，
            //    导致新服务器下载完 jar 才失败（用户反复反馈的一键安装问题）。
            safeProgress("检查服务器前置环境（curl / Docker）…\n")
            val bootstrapCmd = buildBootstrapCommand()
            val rBoot = exec(client, bootstrapCmd, safeProgress)
            log.append(mask(rBoot.output))
            if (rBoot.exitCode != null && rBoot.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, "", "前置环境准备失败（缺少 Docker 且无法自动安装）", log.toString())
                )
            }

            // 0.5) 端口清理：按端口释放 $port（只清本服务的旧容器/旧进程，不误杀其它服务）。
            //      必须在 docker run 之前——旧逻辑只删固定名容器，旧服务占端口会导致退出码 125。
            safeProgress("检查并释放端口 $port…\n")
            val portCmd = buildPortCleanupCommand(port)
            val rPort = exec(client, portCmd, safeProgress)
            log.append(mask(rPort.output))
            if (rPort.exitCode != null && rPort.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, "", "端口 $port 释放失败（被其它服务占用，请手动停止）", log.toString())
                )
            }

            // 1) 部署前检测：已有最新容器且运行中则跳过重装（幂等，避免每次点都重启服务）
            safeProgress("检测服务器现有容器…\n")
            val probe = exec(client, buildInspectCommand(port), safeProgress)
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
                settings.setServerUrl("http://${conn.host}:$port")
                settings.setServerUrlFor(conn.id, "http://${conn.host}:$port")
                return@withContext Result.success(
                    DeployResult(true, existing.currentVersion ?: rel.version, "已是最新版本，无需重装", log.toString())
                )
            }

            // 2) 下载 jar 到临时文件（失败不影响旧版）
            safeProgress("下载 agent-server-${rel.version}.jar …\n")
            val downloadCmd = buildDownloadCommand(rel.jarUrl)
            val r1 = exec(client, downloadCmd, safeProgress)
            log.append(mask(r1.output))
            if (r1.exitCode != null && r1.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, "", "下载 jar 失败（退出码 ${r1.exitCode}）", log.toString())
                )
            }

            // 3) 原子替换 jar + 写 systemd 服务（无 systemd 时降级 nohup）+ 启动
            safeProgress("安装并启动服务…\n")
            val installCmd = buildInstallCommand(rel.tag, token, port)
            val r2 = exec(client, installCmd, safeProgress)
            log.append(mask(r2.output))
            if (r2.exitCode != null && r2.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, "", "安装/启动失败（退出码 ${r2.exitCode}）", log.toString())
                )
            }

            // 4) 等待 health + version 校验（服务端启动需要几秒）；带 token 调受保护端点确认鉴权真生效
            safeProgress("校验服务健康状态…\n")
            val verifyCmd = buildVerifyCommand(token, port)
            val r3 = exec(client, verifyCmd, safeProgress)
            log.append(mask(r3.output))
            val deployedVersion = parseVersionFromOutput(r3.output) ?: "unknown"

            if (r3.exitCode != null && r3.exitCode != 0) {
                return@withContext Result.success(
                    DeployResult(false, deployedVersion, "服务启动后健康检查/鉴权校验未通过（token 是否正确写入？）", log.toString())
                )
            }

            // 5) 回填 serverUrl（复用基座连接 host + 用户自定义端口）；按 connectionId 存一份供多服务器解析
            val serverUrl = "http://${conn.host}:$port"
            settings.setServerUrl(serverUrl)
            settings.setServerUrlFor(conn.id, serverUrl)
            settings.setPort(port)
            settings.setPortFor(conn.id, port)
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
     *
     * 镜像获取策略（版本化 tag + 强制更新）：
     *   1) 镜像名带版本 `:base-<version>`，每次发版必然是新 tag，避免命中本地旧缓存；
     *   2) **无条件 docker pull**（分层增量很快），确保拿到该版本对应的镜像；
     *   3) pull 失败（国内网络）→ 从 GitHub Release 下载镜像 tar + docker load 兜底。
     *
     * 为什么必须强制 pull：基座镜像内含 iptables 等系统依赖（防火墙工具要用），
     * 若沿用固定 :base 且仅在本地缺失时拉取，老用户升级只会更新外挂 jar，
     * 镜像永远停在首次安装版本 → 新系统能力无法生效（rc18 的真实故障）。
     *
     * 业务 jar 由 [buildDownloadCommand] 单独从 Release 下载后挂载。
     */
    private fun buildInstallCommand(releaseTag: String, token: String = "", port: Int = SERVER_PORT): String {
        val image = baseImageFor(releaseTag)
        val tarUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$releaseTag/$BASE_IMAGE_TAR"
        val q = shellQuote(tarUrl)
        // token 为空时不写环境变量 → 服务端保持不校验（兼容旧行为/本地测试）；非空则 shell 引号包裹后注入，防注入。
        val tokenEnv = if (token.isBlank()) "" else " -e MINIME_TOKEN=${shellQuote(token)}"
        return listOf(
            "set -e",
            "SUDO=''",
            "if [ \"\$(id -u)\" != \"0\" ] && command -v sudo >/dev/null 2>&1; then SUDO='sudo'; fi",
            "D=\"\$HOME/$INSTALL_DIR\"",
            "WS=\"\$HOME/minimecode-workspace\"",
            "mkdir -p \"\$D\" \"\$WS\"",
            // 防御性兜底：正常情况下前置 bootstrap 已自动安装并探活 Docker；此处仅防绕过部署流程直调本命令。
            "if ! \$SUDO docker info >/dev/null 2>&1; then echo 'Docker 不可用，请重新执行一键部署以自动安装前置环境'; exit 1; fi",
            // 基座镜像：无条件 pull（版本化 tag）→ 失败则 tar 兜底。绝不在本地命中时跳过。
            "echo '拉取云端运行环境镜像（$image）…'",
            "if ! \$SUDO docker pull $image >/dev/null 2>&1; then",
            "  echo '镜像仓库不可达，改用 Release 镜像包兜底…'",
            "  curl -fsSL --retry 3 --connect-timeout 15 $q -o \"\$D/base.tar.new\"",
            "  test -s \"\$D/base.tar.new\" || { echo '镜像包下载失败'; exit 1; }",
            "  mv -f \"\$D/base.tar.new\" \"\$D/base.tar\"",
            "  \$SUDO docker load -i \"\$D/base.tar\" >/dev/null",
            "  echo '镜像包载入成功'",
            "fi",
            "\$SUDO docker rm -f $SERVICE_NAME >/dev/null 2>&1 || true",
            "\$SUDO docker run -d --name $SERVICE_NAME --restart unless-stopped --cap-add NET_ADMIN -p $port:$port " +
                "-v \"\$D:/data/agent-server\" -v \"\$WS:/workspace\" " +
                "-e MINIME_DB=/data/agent-server/events.db -e MINIME_WORKSPACE=/workspace -e MINIME_PORT=$port$tokenEnv " +
                "$image java -jar /data/agent-server/agent-server.jar >/dev/null",
            "echo \"service=docker container=$SERVICE_NAME image=$image\""
        ).joinToString("\n")
    }

    private fun buildVerifyCommand(token: String = "", port: Int = SERVER_PORT): String {
        // 鉴权校验：token 非空时必须能带 Bearer 读到 /policy（受保护端点），否则说明 token 没写进服务端。
        // token 为空时只探测无需鉴权的 /version，保持向后兼容。
        val authFlag = if (token.isBlank()) "" else " -H \"Authorization: Bearer ${token.replace("\"", "")}\""
        return listOf(
            "OK=0",
            "P=000",
            "for i in \$(seq 1 20); do",
            "  V=\$(curl -fsS --max-time 2$authFlag http://127.0.0.1:$port/version 2>/dev/null || true)",
            if (token.isNotBlank())
                "  P=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 2$authFlag http://127.0.0.1:$port/policy 2>/dev/null || true)"
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
    private fun buildInspectCommand(port: Int = SERVER_PORT): String = listOf(
        "SUDO=''",
        "if [ \"\$(id -u)\" != \"0\" ] && command -v sudo >/dev/null 2>&1; then SUDO='sudo'; fi",
        "if command -v docker >/dev/null 2>&1; then",
        "  C=\$(\$SUDO docker ps -a --filter 'name=^/$SERVICE_NAME\$' --format '{{.Names}}' 2>/dev/null | head -n1)",
        "  echo \"INSPECT_CONTAINER=\${C:-EMPTY}\"",
        "  V=\$(curl -fsS --max-time 3 http://127.0.0.1:$port/version 2>/dev/null || true)",
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
