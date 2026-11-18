package com.minime.agent.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import com.minime.agent.server.modules.LocalFileAccessProvider
import com.minime.agent.server.modules.LoginInputEvent
import com.minime.agent.server.modules.ServerSkillRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** 该服务端注册的工具清单，供 App 在切换执行目标时展示真实可用工具/授权。 */
private val SERVER_TOOL_NAMES = listOf(
    "Bash", "readFile", "writeFile", "editFile", "list", "search", "todo",
    "loadSkill", "memory", "websearch", "webfetch", "task",
    "askUserQuestion", "planMode", "goalStatus", "messageParent",
    // 镜像补全：与 App 本地工具集完全对齐
    "sendFile", "viewImage", "generateImage", "Shizuku", "terminal", "manageMcp", "browser",
    // 系统级能力：出口防火墙 + 进程生命周期管理 + 冷门端口池
    "firewall", "process", "port"
)

fun Application.configureServer(
    store: EventStore,
    runner: AgentRunner,
    token: String,
    fileAccess: LocalFileAccessProvider,
    skillRepository: ServerSkillRepository,
    settingsRepository: com.minime.agent.server.modules.ServerSettingsRepository =
        com.minime.agent.server.modules.ServerSettingsRepository(),
    firewallManager: com.minime.agent.server.modules.FirewallManager =
        com.minime.agent.server.modules.FirewallManager(),
    processManager: com.minime.agent.server.modules.ProcessLifecycleManager =
        com.minime.agent.server.modules.ProcessLifecycleManager(),
    portPool: com.minime.agent.server.modules.PortPoolManager =
        com.minime.agent.server.modules.PortPoolManager()
) {
    val log = LoggerFactory.getLogger("AgentServer")
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false })
    }
    install(CallLogging)
    // WebSocket 帧推流：用默认心跳保活；放宽帧上限（JPEG base64 单帧可达数十 KB）。
    install(WebSockets) {
        maxFrameSize = 2 * 1024 * 1024
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("请求处理失败: ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "内部错误"))
        }
    }

    routing {
        get("/health") {
            call.respond(mapOf(
                "status" to "ok",
                "service" to "minime-agent-server",
                "version" to ServerVersion.version,
                "protocol" to ServerVersion.PROTOCOL.toString()
            ))
        }

        // 版本查询：App 设置页读取后与期望版本对比，提示是否需要升级服务端。
        // 注意：/version 与 /health 一样放在鉴权之前，便于未配置 token 也能读到版本。
        get("/version") {
            call.respond(VersionResponse(version = ServerVersion.version, protocol = ServerVersion.PROTOCOL))
        }

        // 可选的 Bearer token 鉴权（第一版：环境变量 MINIME_TOKEN 为空则不校验）
        fun authorized(c: ApplicationCall): Boolean {
            if (token.isBlank()) return true
            val header = c.request.headers["Authorization"] ?: return false
            return header == "Bearer $token"
        }

        // ---- 环境数据：供 App 侧边栏文件/skill/授权按执行目标切换 --------------------
        // 文件系统读取在 IO 线程执行，避免阻塞 Netty 事件循环。
        get("/workspace/list") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            val path = call.request.queryParameters["path"] ?: "~/workspace"
            val result = runCatching { fileAccess.listFiles(path).map { e ->
                WorkspaceFileEntry(e.name, e.isDirectory, e.size, e.lastModified, e.permissions)
            } }
            call.respond(result.fold(
                onSuccess = { WorkspaceListResponse(path, it) },
                onFailure = { WorkspaceListResponse(path, emptyList(), it.message ?: "列目录失败") }
            ))
        }

        get("/workspace/read") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            val path = call.request.queryParameters["path"] ?: run {
                call.respond(HttpStatusCode.BadRequest, WorkspaceReadResponse("", "", "missing path")); return@get
            }
            val result = runCatching { fileAccess.readFile(path) }
            call.respond(result.fold(
                onSuccess = { WorkspaceReadResponse(path, it) },
                onFailure = { WorkspaceReadResponse(path, "", it.message ?: "读取失败") }
            ))
        }

        get("/skills") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            val skills = runCatching { skillRepository.listSkills().map { s ->
                CloudSkillEntry(s.name, s.description, s.requiredTools, s.dirPath ?: "")
            } }.getOrDefault(emptyList())
            call.respond(SkillsResponse(skills))
        }

        get("/policy") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            call.respond(PolicyResponse(tools = SERVER_TOOL_NAMES.map { ToolPolicyEntry(it) }))
        }

        // 云端设置：App 设置页读取/修改云端 agent 偏好（语言、迭代上限、goal 预算、sendFile 上限等）。
        // 读取无需重新加载——仓库内已缓存且 env 覆盖每次生效；修改即时落盘，下一轮 agent loop 生效。
        get("/settings") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            call.respond(settingsRepository.get())
        }

        post("/settings") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val req = call.receive<SettingsUpdateRequest>()
            val next = settingsRepository.update { cur ->
                cur.copy(
                    maxIterations = req.maxIterations?.coerceIn(1, 500) ?: cur.maxIterations,
                    goalMaxTurns = req.goalMaxTurns?.coerceAtLeast(1) ?: cur.goalMaxTurns,
                    goalMaxTokens = req.goalMaxTokens?.coerceAtLeast(1000) ?: cur.goalMaxTokens,
                    sendFileMaxSizeMb = req.sendFileMaxSizeMb?.coerceAtLeast(1) ?: cur.sendFileMaxSizeMb,
                    compactionThresholdPercent = req.compactionThresholdPercent?.coerceIn(1, 100)
                        ?: cur.compactionThresholdPercent,
                    language = req.language?.trim() ?: cur.language
                )
            }
            call.respond(next)
        }

        // ---- 系统级能力：出口防火墙 + 进程生命周期（App 侧边栏/设置可用） --------------------
        get("/firewall") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            call.respond(firewallManager.status())
        }

        post("/firewall") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val action = call.request.queryParameters["action"] ?: "status"
            val state = when (action) {
                "enable" -> firewallManager.enable()
                "disable" -> firewallManager.disable()
                "reset" -> firewallManager.reset()
                else -> firewallManager.status()
            }
            call.respond(state)
        }

        get("/processes") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            call.respond(mapOf(
                "count" to processManager.list().size,
                "processes" to processManager.list().map { r ->
                    mapOf(
                        "id" to r.id, "pid" to r.pid, "kind" to r.kind, "label" to r.label,
                        "command" to r.command.take(200), "ttlMinutes" to r.ttlMs / 60000,
                        "idleMinutes" to (System.currentTimeMillis() - r.lastHeartbeat) / 60000,
                        "persistent" to r.persistent, "alive" to (r.process?.isAlive ?: true)
                    )
                }
            ))
        }

        post("/processes/{id}/persist") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val id = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id")); return@post
            }
            val value = call.request.queryParameters["value"]?.toBooleanStrictOrNull() ?: true
            val ok = processManager.setPersistent(id, value)
            call.respond(OkResponse(ok = ok))
        }

        post("/processes/{id}/kill") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val id = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id")); return@post
            }
            val killed = processManager.kill(id) is com.minime.agent.server.modules.ProcessLifecycleManager.KillOutcome.Killed
            call.respond(OkResponse(ok = killed))
        }

        // ---- 冷门端口池：查询 / 分配 / 归还（App 侧边栏可用） --------------------
        get("/ports") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            call.respond(mapOf(
                "stats" to portPool.stats(),
                "ports" to portPool.list().records.sortedBy { it.port }.map { r ->
                    mapOf(
                        "port" to r.port, "inUse" to r.inUse, "owner" to r.owner,
                        "note" to r.note, "useCount" to r.useCount
                    )
                }
            ))
        }

        post("/ports/acquire") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val owner = call.request.queryParameters["owner"]?.trim().orEmpty()
            val note = call.request.queryParameters["note"]?.trim().orEmpty()
            val preferred = call.request.queryParameters["port"]?.toIntOrNull() ?: 0
            val r = portPool.acquire(owner = owner, note = note, preferredPort = preferred)
            if (r == null) call.respond(HttpStatusCode.Conflict, ErrorResponse("no available port"))
            else call.respond(mapOf("port" to r.port, "owner" to r.owner, "inUse" to r.inUse, "useCount" to r.useCount))
        }

        post("/ports/release") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val port = call.request.queryParameters["port"]?.toIntOrNull()
            if (port == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing port")); return@post }
            call.respond(OkResponse(ok = portPool.release(port)))
        }

        post("/sessions") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val req = call.receive<CreateSessionRequest>()
            val id = store.createSession(req.title)
            call.respond(CreateSessionResponse(id))
        }

        post("/sessions/{id}/messages") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val sessionId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing session id")); return@post
            }
            if (!store.sessionExists(sessionId)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("session not found")); return@post
            }
            val req = call.receive<SubmitMessageRequest>()
            val trustLevel = req.trustLevel.ifBlank { "basic" }
            val taskId = store.createTask(sessionId, req.provider, trustLevel)
            runner.submit(sessionId, taskId, req.content, req.provider, trustLevel)
            call.respond(HttpStatusCode.Accepted, SubmitMessageResponse(taskId))
        }

        get("/sessions/{id}/events") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            val sessionId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing session id")); return@get
            }
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val events = store.eventsAfter(sessionId, after)
            val nextSeq = events.lastOrNull()?.seq ?: store.latestSeq(sessionId)
            // 直接读任务真实状态（历史事件里永久保留 pending_decision，不能用它反推，否则会卡在 waiting_approval）
            val status = store.latestTaskStatus(sessionId) ?: "idle"
            // 挂起的审批项：App 断线/删后台/熄屏期间云端可能已挂起等审批，重连后据此恢复审批卡片
            val pending = store.pendingDecisionsFor(sessionId)
            val pendingLogins = store.pendingLoginsFor(sessionId)
            call.respond(EventsResponse(events, nextSeq, status, pending, pendingLogins))
        }

        post("/decisions/{id}") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val decisionId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing decision id")); return@post
            }
            val req = call.receive<DecisionRequest>()
            val ok = store.resolvePending(decisionId, req.approve)
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("decision not found or already resolved")); return@post
            }
            call.respond(OkResponse())
        }

        // ---- 云端登录会话：手机遥控云端浏览器完成登录，全程不搬运凭证 --------------------
        // 这些端点把画面/输入代理给 BrowserDriver（内部走 Playwright 微服务），
        // agent-server 只见截图像素与输入坐标，从不接触 Cookie/token。
        // 安全性由微服务层保证：回环绑定 + X-Browser-Token + profileId 白名单 + profile 静态加密；
        // 登录态归属由「App 只能从自己会话事件流拿到 loginId」天然隔离。

        get("/login/{id}/frame") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            val loginId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, LoginFrameResponse()); return@get
            }
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val frame = runCatching { runner.browserDriver().loginFrame(loginId, after) }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable, LoginFrameResponse()); return@get
            }
            call.respond(LoginFrameResponse(frame.seq, frame.jpegBase64, frame.width, frame.height, frame.changed))
        }

        post("/login/{id}/input") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val loginId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing login id")); return@post
            }
            val req = call.receive<LoginInputRequest>()
            val ev = LoginInputEvent(req.type, req.x, req.y, req.text, req.key, req.dx, req.dy)
            val ok = runCatching { runner.browserDriver().loginInput(loginId, ev) }.getOrDefault(false)
            call.respond(OkResponse(ok = ok))
        }

        get("/login/{id}/status") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            val loginId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, LoginStateResponse("", "error", message = "missing login id")); return@get
            }
            val state = runCatching { runner.browserDriver().loginStatus(loginId) }
                .getOrDefault(com.minime.agent.server.modules.LoginState(loginId, "error", message = "浏览器服务不可用"))
            call.respond(LoginStateResponse(state.loginId, state.state, state.url, state.message, state.detail))
        }

        /**
         * 登录完成/取消：唤醒 AgentRunner 中挂起的 deferred，任务据此恢复或收尾。
         * action=success 时同时通知微服务提交 profile（其实浏览器已自动持久化，这里只是显式收尾）。
         */
        post("/login/{id}/complete") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val loginId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing login id")); return@post
            }
            val body = runCatching { call.receive<Map<String, String>>() }.getOrDefault(emptyMap())
            val success = body["success"]?.toBoolean() ?: true
            if (success) runCatching { runner.browserDriver().finishLogin(loginId) }
            val resolved = store.resolvePending("login:$loginId", success)
            if (!resolved) {
                call.respond(OkResponse(ok = false)); return@post
            }
            call.respond(OkResponse())
        }

        // ---- 云端登录帧 WebSocket 推流（App 订阅，替代 350ms 轮询）--------------------
        // App 建连后服务端主动推送「变化帧 + 状态」，登录进入终态或客户端断开即结束。
        // 与 HTTP 端点同构：拨号仍走 /login/start，输入/收尾仍走 HTTP，只把"拉画面"换成"推画面"。
        // 消息格式：{"type":"frame",seq,jpegBase64,width,height,changed} / {"type":"status",...}
        webSocket("/login/{id}/stream") {
            if (!authorized(call)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@webSocket
            }
            val loginId = call.parameters["id"]
            if (loginId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing login id"))
                return@webSocket
            }
            // 推送间隔可调（80-1000ms，默认 150ms，低于原 350ms 轮询 → 更跟手）
            val interval = call.request.queryParameters["interval"]?.toLongOrNull()?.coerceIn(80, 1000) ?: 150L
            var lastSeq = 0L
            var lastState = ""; var lastMessage = ""; var lastDetail = ""
            val terminal = setOf("success", "failed", "timeout")
            log.info("登录帧 WS 建连: loginId=$loginId interval=${interval}ms")
            try {
                while (isActive) {
                    // 增量帧：序号前进且画面确有变化才推，静态页不产生流量
                    val frame = runCatching { runner.browserDriver().loginFrame(loginId, lastSeq) }.getOrNull()
                    if (frame != null && frame.seq > lastSeq) {
                        lastSeq = frame.seq
                        if (frame.changed && frame.jpegBase64.isNotEmpty()) {
                            send(Frame.Text(buildJsonObject {
                                put("type", "frame")
                                put("seq", frame.seq)
                                put("jpegBase64", frame.jpegBase64)
                                put("width", frame.width)
                                put("height", frame.height)
                                put("changed", true)
                            }.toString()))
                        }
                    }
                    // 状态：变化才推；终态推完即收尾关闭连接
                    val state = runCatching { runner.browserDriver().loginStatus(loginId) }.getOrNull()
                    if (state != null && (state.state != lastState || state.message != lastMessage || state.detail != lastDetail)) {
                        lastState = state.state; lastMessage = state.message; lastDetail = state.detail
                        send(Frame.Text(buildJsonObject {
                            put("type", "status")
                            put("loginId", state.loginId)
                            put("state", state.state)
                            put("url", state.url)
                            put("message", state.message)
                            put("detail", state.detail)
                        }.toString()))
                        if (state.state.lowercase() in terminal) {
                            log.info("登录帧 WS 终态收尾: loginId=$loginId state=${state.state}")
                            break
                        }
                    }
                    delay(interval)
                }
            } catch (e: Exception) {
                log.info("登录帧 WS 断开: loginId=$loginId reason=${e.message}")
            } finally {
                runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "stream-end")) }
            }
        }

        // 用户主动停止：按 taskId 精确取消运行中的任务（中断 LLM 调用、落「已停止」事件）。
        // 任务已结束或不存在时返回 ok=false，调用方据此作幂等处理。
        post("/tasks/{id}/cancel") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val taskId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing task id")); return@post
            }
            val cancelled = runner.cancelTask(taskId)
            call.respond(OkResponse(ok = cancelled))
        }
    }
}
