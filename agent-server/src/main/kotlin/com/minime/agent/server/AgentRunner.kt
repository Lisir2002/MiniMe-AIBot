package com.minime.agent.server

import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import com.minime.code.feature.agent.data.remote.anthropic.AnthropicApi
import com.minime.agent.server.modules.BrowserDriver
import com.minime.agent.server.modules.BrowserDriverRouter
import com.minime.agent.server.modules.BrowserLoginSignal
import com.minime.agent.server.modules.RemoteBrowserDriver
import com.minime.agent.server.modules.ServerAskUserTool
import com.minime.agent.server.modules.ServerBrowserTool
import com.minime.agent.server.modules.ServerFileTools
import com.minime.agent.server.modules.ServerGenerateImageTool
import com.minime.agent.server.modules.ServerManageMcpTool
import com.minime.agent.server.modules.ServerLoadSkillTool
import com.minime.agent.server.modules.ServerMemoryRepository
import com.minime.agent.server.modules.ServerMemoryTool
import com.minime.agent.server.modules.ServerPromptBuilder
import com.minime.agent.server.modules.ServerGoalStatusTool
import com.minime.agent.server.modules.ServerMessageParentTool
import com.minime.agent.server.modules.ServerPlanModeTool
import com.minime.agent.server.modules.ServerSearchTool
import com.minime.agent.server.modules.ServerSendFileTool
import com.minime.agent.server.modules.ServerShizukuTool
import com.minime.agent.server.modules.ServerSkillRepository
import com.minime.agent.server.modules.ServerTerminalTool
import com.minime.agent.server.modules.ServerViewImageTool
import com.minime.agent.server.modules.ServerTaskTool
import com.minime.agent.server.modules.ServerTodoTool
import com.minime.agent.server.modules.ServerWebFetchTool
import com.minime.agent.server.modules.ServerWebSearchTool
import com.minime.code.feature.agent.domain.model.AgentContext
import com.minime.code.feature.agent.domain.model.AgentMessage
import com.minime.code.feature.agent.domain.provider.AIProvider
import com.minime.code.feature.agent.domain.provider.AnthropicAdapter
import com.minime.code.feature.agent.domain.provider.ToolCallTextParser
import com.minime.code.feature.agent.domain.tool.AgentTool
import com.minime.code.feature.agent.domain.tool.modelToolResultText
import com.minime.code.feature.agent.domain.tool.toTransportString
import com.minime.code.feature.agent.domain.tool.ParameterType
import com.minime.code.feature.agent.domain.tool.ToolCall
import com.minime.code.feature.agent.domain.tool.ToolParameter
import com.minime.code.feature.agent.domain.tool.ToolPermissionPolicy
import com.minime.code.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive as KJsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 云端 Agent 运行时：在服务端协程里跑完整的 LLM + 工具循环，与 App 生命周期无关。
 * 复用 :agent-core 的 AIProvider / 领域模型，工具层在服务端用本地进程执行（服务就跑在用户服务器/容器里）。
 */
class AgentRunner(
    private val store: EventStore,
    private val workspaceRoot: String,
    private val scope: CoroutineScope,
    // 云端的 skill/memory/prompt 能力（完全独立）。默认值让 Main 无需改动即可构造；
    // 也可注入自定义实例（单测用临时目录隔离）。
    private val skillRepository: ServerSkillRepository = ServerSkillRepository(
        com.minime.agent.server.modules.LocalFileAccessProvider()
    ),
    private val memoryRepository: ServerMemoryRepository = ServerMemoryRepository(),
    private val promptBuilder: ServerPromptBuilder = ServerPromptBuilder(skillRepository, memoryRepository),
    // 浏览器驱动：默认从环境变量读取微服务地址；未配置时 remote 健康检查失败，读操作自动降级本地只读。
    // 显式注入可让单测用 mock 驱动，也让 Main 统一构造并共享给登录会话端点。
    private val browserDriver: BrowserDriver = BrowserDriverRouter(
        RemoteBrowserDriver(System.getenv("MINIME_BROWSER_URL") ?: "http://127.0.0.1:8090")
    )
) {
    /** 供登录会话端点访问同一驱动实例（启动登录、拉帧、注入输入、查状态）。 */
    fun browserDriver(): BrowserDriver = browserDriver
    private val log = LoggerFactory.getLogger(AgentRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 当前在跑的任务 job，按 taskId 索引，供用户主动停止时精确取消。 */
    private val running = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * 取消指定任务：中断协程（连带中断挂起中的 LLM HTTP 请求），落「已停止」事件并置终态。
     *
     * 幂等且防竞态：
     * - 任务不在 running（已正常完成/失败或从未开始）时不重复落事件；
     * - 状态已是终态时跳过；
     * - 取消走 CancellationException，submit/resume 的 catch 会 rethrow，不会误落 TASK_FAILED。
     *
     * @return true 表示确实取消了运行中的任务；false 表示任务已结束或不存在。
     */
    fun cancelTask(taskId: String): Boolean {
        val job = running[taskId] ?: return false
        if (!job.isActive) return false
        val status = store.getTaskStatus(taskId)
        if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) return false
        val sessionId = store.getSessionIdForTask(taskId) ?: return false
        // 先置状态并落终态事件，再 cancel：cancel 会同步触发 finally（running.remove），
        // 但状态与事件不受影响；顺序颠倒也不影响幂等，只是先落更稳妥。
        store.setTaskStatus(taskId, TaskStatus.CANCELLED)
        store.append(NewEvent(sessionId, EventKind.TASK_CANCELLED, taskId, text = "用户已停止"))
        job.cancel()
        running.remove(taskId)
        log.info("任务 $taskId 已被用户取消")
        return true
    }

    fun submit(sessionId: String, taskId: String, userText: String, providerConfig: ProviderConfigDto, trustLevelWire: String = "basic") {
        store.setTaskStatus(taskId, TaskStatus.QUEUED)
        val job = scope.launch(Dispatchers.IO) {
            try {
                // 多轮：把会话已有历史接上本轮用户提问，云端自带完整上下文
                val history = store.getSessionContext(sessionId).toMutableList()
                history += AgentMessage.UserMessage(content = userText)
                store.append(NewEvent(sessionId, EventKind.USER_MESSAGE, taskId, text = userText))
                runLoop(sessionId, taskId, history, providerConfig, startIteration = 0, appendUserEvent = false, trustLevelWire = trustLevelWire)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.error("任务 $taskId 失败", e)
                store.append(NewEvent(sessionId, EventKind.TASK_FAILED, taskId, text = e.message ?: "未知错误", isError = true))
                store.setTaskStatus(taskId, TaskStatus.FAILED)
            } finally {
                running.remove(taskId)
            }
        }
        running[taskId] = job
    }

    /**
     * 服务启动后续跑：扫描非终态任务，从其 checkpoint 恢复对话与迭代轮次继续执行。
     * 进程重启会丢失挂起的审批 deferred——这类任务重新跑到该工具时会重新发 pending_decision 等待审批。
     */
    fun resumeUnfinished() {
        val tasks = store.unfinishedTasks()
        if (tasks.isEmpty()) return
        log.info("发现 ${tasks.size} 个未完成任务，开始续跑")
        for (t in tasks) {
            val cfg = store.getTaskProvider(t.taskId) ?: run {
                log.warn("任务 ${t.taskId} 缺少 provider 配置，标记失败")
                store.setTaskStatus(t.taskId, TaskStatus.FAILED)
                store.append(NewEvent(t.sessionId, EventKind.TASK_FAILED, t.taskId, text = "续跑失败：缺少模型配置", isError = true))
                continue
            }
            val cp = store.getCheckpoint(t.taskId)
            val messages = if (cp != null && cp.messagesJson != "[]") {
                runCatching { json.decodeFromString<List<AgentMessage>>(cp.messagesJson) }.getOrDefault(mutableListOf())
            } else emptyList()
            if (messages.isEmpty()) {
                // 尚无有效 checkpoint（任务刚入队就崩溃），标记失败让 App 重新提交
                store.setTaskStatus(t.taskId, TaskStatus.FAILED)
                store.append(NewEvent(t.sessionId, EventKind.TASK_FAILED, t.taskId, text = "续跑失败：无有效断点，请重新提问", isError = true))
                continue
            }
            val startIteration = cp?.iteration ?: 0
            val taskId = t.taskId
            val sessionId = t.sessionId
            val trustLevelWire = store.getTaskTrustLevel(taskId)
            val job = scope.launch(Dispatchers.IO) {
                try {
                    log.info("续跑任务 $taskId，从第 $startIteration 轮恢复")
                    runLoop(sessionId, taskId, messages.toMutableList(), cfg, startIteration, appendUserEvent = false, trustLevelWire = trustLevelWire)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.error("续跑任务 $taskId 失败", e)
                    store.append(NewEvent(sessionId, EventKind.TASK_FAILED, taskId, text = e.message ?: "续跑错误", isError = true))
                    store.setTaskStatus(taskId, TaskStatus.FAILED)
                } finally {
                    running.remove(taskId)
                }
            }
            running[taskId] = job
        }
    }

    private suspend fun runLoop(
        sessionId: String,
        taskId: String,
        initialMessages: MutableList<AgentMessage>,
        cfg: ProviderConfigDto,
        startIteration: Int,
        appendUserEvent: Boolean,
        trustLevelWire: String = "basic"
    ) {
        val trustLevel = com.minime.code.feature.agent.domain.model.TrustLevel.fromWire(trustLevelWire)
        store.setTaskStatus(taskId, TaskStatus.RUNNING)
        if (appendUserEvent) {
            store.append(NewEvent(sessionId, EventKind.USER_MESSAGE, taskId, text = initialMessages.lastOrNull()?.let {
                (it as? AgentMessage.UserMessage)?.content
            } ?: ""))
        }

        val provider = buildProvider(cfg)
        provider.logSessionId = sessionId

        val messages = initialMessages
        // 云端工具集：与 App 本地工具集对齐，产出结构化结果供 App 富卡片渲染。
        // 文件读写/编辑产出 diff 卡，搜索产出结果卡，待办产出 TodoCard；
        // 技能/记忆数据完全独立于 App，存在容器内。
        val fileTools = ServerFileTools(workspaceRoot)
        val tools: List<AgentTool> = listOf(
            BashTool(workspaceRoot),
            fileTools.writeFile,
            fileTools.editFile,
            fileTools.readFile,
            fileTools.list,
            ServerSearchTool(workspaceRoot),
            ServerTodoTool(),
            ServerLoadSkillTool(skillRepository),
            ServerMemoryTool(memoryRepository),
            // 本轮补齐的 7 个有意义工具（与 App 卡片契约对齐）
            ServerWebSearchTool(),
            ServerWebFetchTool(),
            ServerTaskTool(),
            ServerAskUserTool(),
            ServerPlanModeTool(),
            ServerGoalStatusTool(),
            ServerMessageParentTool(),
            // 镜像补全：App 有但服务端此前缺失的 7 个工具，使云端工具集与本地完全对齐
            ServerSendFileTool(workspaceRoot),
            ServerViewImageTool(workspaceRoot),
            ServerGenerateImageTool(workspaceRoot),
            ServerShizukuTool(workspaceRoot),
            ServerTerminalTool(workspaceRoot),
            ServerManageMcpTool(),
            ServerBrowserTool(browserDriver, profileId = sessionId)
        )
        // 一期不需要完整 AgentContext 能力；工具只用 projectRoot 等少量字段，给合理默认即可。
        val agentContext = AgentContext(
            currentFile = null,
            selectedCode = null,
            projectRoot = workspaceRoot,
            language = null,
            sessionId = sessionId
        )

        val maxIterations = 30
        var iteration = startIteration
        while (iteration < maxIterations) {
            // 续跑检测：若 checkpoint 恰好停在「assistant 已回填、工具未执行完」（常见于审批挂起时崩溃），
            // 直接续执行这些 toolCalls，不再调 LLM、不重复落 assistant_text 事件。
            val last = messages.lastOrNull()
            val resumeCalls = if (last is AgentMessage.AssistantMessage && last.toolCalls.isNotEmpty()) last.toolCalls else null
            if (resumeCalls == null) {
                val response = provider.complete(
                    systemPrompt = promptBuilder.build(),
                    messages = messages,
                    tools = tools
                )

                // 协议无关兜底：部分模型（如讯飞 spark / 兼容端点）不返回结构化 tool_use，
                // 而是把工具调用以 <tool_call><arg_key>... 文本吐在正文里。这里扫描并还原成
                // 标准 ToolCall，同时从正文剥离，避免「工具调用/代码被写在正文」且无法接入卡片。
                val recovered = recoverTextToolCalls(response.content, response.toolCalls)
                val cleanContent = recovered.first
                val effectiveCalls = recovered.second
                if (effectiveCalls.isNotEmpty() && response.toolCalls.isEmpty()) {
                    log.info("从正文兜底解析出 ${effectiveCalls.size} 个文本工具调用")
                }

                // 记录助手文本 + thinking（终态事件，App 拉回放）。正文已剥离工具调用片段。
                if (cleanContent.isNotBlank() || effectiveCalls.isNotEmpty()) {
                    store.append(NewEvent(
                        sessionId = sessionId, kind = EventKind.ASSISTANT_TEXT, taskId = taskId,
                        text = cleanContent, reasoning = response.reasoning ?: "",
                        thinkingBlocksJson = response.thinkingBlocksJson ?: "", signature = response.signature ?: ""
                    ))
                }

                // 把助手消息回填上下文（thinking/signature 原样保留， Anthropic 工具循环必需）
                messages += AgentMessage.AssistantMessage(
                    content = cleanContent,
                    toolCalls = effectiveCalls,
                    reasoning = response.reasoning ?: "",
                    signature = response.signature ?: "",
                    thinkingBlocksJson = response.thinkingBlocksJson ?: "",
                    inputTokens = response.inputTokens
                )

                if (effectiveCalls.isEmpty()) {
                    // 任务正常完成：写回会话上下文，下一轮提问可在云端续接多轮历史
                    store.updateSessionContext(sessionId, messages)
                    store.append(NewEvent(sessionId, EventKind.TASK_COMPLETED, taskId, text = cleanContent))
                    store.setTaskStatus(taskId, TaskStatus.COMPLETED)
                    return
                }
                // assistant 已回填、工具待执行——保存断点，供审批挂起崩溃后精确续跑
                store.saveCheckpoint(taskId, TaskCheckpoint(messagesJson = json.encodeToString(messages), iteration = iteration))
            }

            val calls = resumeCalls ?: (messages.last() as AgentMessage.AssistantMessage).toolCalls
            // 逐个执行工具（第一版串行，审批挂起更简单）
            for (call in calls) {
                val tool = tools.firstOrNull { it.name == call.name }
                if (tool == null) {
                    val err = "未知工具: ${call.name}"
                    store.append(NewEvent(sessionId, EventKind.TOOL_CALL_FINISHED, taskId, text = err, toolName = call.name, toolCallId = call.id, isError = true))
                    messages += AgentMessage.ToolResultMessage(id = call.id, toolName = call.name, result = err)
                    continue
                }

                store.append(NewEvent(
                    sessionId, EventKind.TOOL_CALL_STARTED, taskId,
                    toolName = call.name, toolArgs = compactArgs(call), toolCallId = call.id
                ))

                // 是否挂起等审批：完全信任档全部静默放行；严格档所有工具都审批；
                // 基本信任档沿用工具自身 ASK/AUTO 策略（Bash 需审批，loadSkill/memory 自动）。
                val needsApproval = TrustPolicy.needsApproval(trustLevel, tool.permissionPolicy)
                if (needsApproval) {
                    val decisionId = call.id
                    store.append(NewEvent(
                        sessionId, EventKind.PENDING_DECISION, taskId,
                        text = "请求执行 ${call.name}", toolName = call.name,
                        toolArgs = compactArgs(call), toolCallId = decisionId
                    ))
                    store.setTaskStatus(taskId, TaskStatus.WAITING_APPROVAL)
                    val deferred = store.registerPending(
                        decisionId, sessionId, taskId, call.name, compactArgs(call)
                    )
                    val approved = deferred.await()
                    store.setTaskStatus(taskId, TaskStatus.RUNNING)
                    if (!approved) {
                        val rejected = "用户拒绝了 ${call.name} 调用"
                        store.append(NewEvent(sessionId, EventKind.TOOL_CALL_FINISHED, taskId, text = rejected, toolName = call.name, toolCallId = call.id, isError = true))
                        messages += AgentMessage.ToolResultMessage(id = call.id, toolName = call.name, result = rejected)
                        continue
                    }
                }

                var result = tool.executeWithContext(call.arguments, agentContext)

                // ---- 登录墙挂起：工具报告需要登录 → 发 LOGIN_REQUIRED 事件 → 挂起等手机登录 ----
                // 复用审批的 deferred.await() 骨架，只是 id 加 "login:" 前缀、事件类型独立。
                // 登录完成后用同一 profile 重跑该工具调用（profile 已带登录态，这次能拿到内容）。
                BrowserLoginSignal.parse(result)?.let { login ->
                    val loginId = login.loginId
                    // 通知微服务为该 profile 准备可交互登录会话（同一 profile 才有登录态）
                    runCatching { browserDriver.startLogin(loginId, login.url, sessionId) }
                        .onFailure { log.warn("启动登录会话失败（App 仍可尝试拉帧）: ${it.message}") }
                    val payload = buildJsonObject {
                        put("loginId", KJsonPrimitive(loginId))
                        put("url", KJsonPrimitive(login.url))
                        put("reason", KJsonPrimitive(login.reason))
                        put("taskId", KJsonPrimitive(taskId))
                    }.toString()
                    store.append(NewEvent(
                        sessionId, EventKind.LOGIN_REQUIRED, taskId,
                        text = payload, toolName = call.name, toolCallId = call.id
                    ))
                    store.setTaskStatus(taskId, TaskStatus.WAITING_LOGIN)
                    log.info("任务 $taskId 触发登录墙，loginId=$loginId url=${login.url}")
                    // argsPreview 存 {url,reason}，供 App 断线重开恢复登录面板时还原站点与原因
                    val loginArgs = buildJsonObject {
                        put("url", KJsonPrimitive(login.url))
                        put("reason", KJsonPrimitive(login.reason))
                    }.toString()
                    val deferred = store.registerPending("login:$loginId", sessionId, taskId, "login", loginArgs)
                    val loggedIn = deferred.await()
                    store.setTaskStatus(taskId, TaskStatus.RUNNING)
                    if (!loggedIn) {
                        val msg = "用户取消登录或登录会话结束"
                        store.append(NewEvent(sessionId, EventKind.LOGIN_CANCELLED, taskId, text = msg, toolCallId = call.id))
                        store.append(NewEvent(sessionId, EventKind.TOOL_CALL_FINISHED, taskId, text = msg, toolName = call.name, toolCallId = call.id, isError = true))
                        messages += AgentMessage.ToolResultMessage(id = call.id, toolName = call.name, result = msg)
                        continue
                    }
                    store.append(NewEvent(sessionId, EventKind.LOGIN_SUCCEEDED, taskId, text = "登录成功，继续访问 ${login.url}", toolCallId = call.id))
                    // 重跑工具：这次远程驱动的同一 profile 已带登录态，能拿到登录后的内容
                    result = tool.executeWithContext(call.arguments, agentContext)
                    log.info("任务 $taskId 登录完成，已重跑工具 ${call.name}")
                }

                // 与 App 本地链路完全一致：落库/回传 UI 用标准 transport JSON
                // （{"status":"success","data":{...},"images":[]}），App 各卡片 parse 函数据此识别
                // diff / 文件 / 搜索 / 待办 / 子代理等富卡片；直接 toString() 裸文本会让所有卡片失效。
                val transport = result.toTransportString()
                store.append(NewEvent(
                    sessionId, EventKind.TOOL_CALL_FINISHED, taskId, text = transport,
                    toolName = call.name, toolArgs = compactArgs(call), toolCallId = call.id,
                    isError = result is ToolResult.Error
                ))
                // 回填给模型的是精简投影（避免把整份 diff/文件内容再喂回去浪费 token）；
                // 文件类工具用专用投影，其余工具回退到完整 transport（模型此前已产出这些内容）。
                val forModel = modelToolResultText(call.name, transport) ?: transport
                messages += AgentMessage.ToolResultMessage(
                    id = call.id, toolName = call.name, result = transport,
                    modelResult = forModel
                )
            }
            // 一轮完整结束（含所有工具结果）后保存干净边界 checkpoint：
            // 恢复时从此 messages + iteration+1 继续，不重复执行任何工具。
            iteration++
            store.saveCheckpoint(taskId, TaskCheckpoint(messagesJson = json.encodeToString(messages), iteration = iteration))
        }

        val msg = "达到最大迭代次数 ($maxIterations)"
        store.append(NewEvent(sessionId, EventKind.TASK_FAILED, taskId, text = msg, isError = true))
        store.setTaskStatus(taskId, TaskStatus.FAILED)
    }

    /**
     * 文本形态工具调用兜底：优先保留 provider 返回的结构化调用；仅当其为空时才解析正文。
     * @return Pair(剥离工具调用后的正文, 最终生效的工具调用列表)
     */
    private fun recoverTextToolCalls(
        content: String,
        structured: List<ToolCall>
    ): Pair<String, List<ToolCall>> {
        if (structured.isNotEmpty()) return content to structured
        val parsed = ToolCallTextParser.parse(content)
        if (!parsed.hasCalls) return content to structured
        val calls = parsed.toolCalls.mapIndexed { idx, c ->
            ToolCall(id = "textcall_${System.currentTimeMillis()}_$idx", name = c.name, arguments = c.arguments)
        }
        return parsed.cleanText to calls
    }

    private fun compactArgs(call: ToolCall): String = runCatching {
        json.encodeToString(JsonObject.serializer(), JsonObject(call.arguments))
    }.getOrElse { call.arguments.toString() }.take(2000)

    private fun buildProvider(cfg: ProviderConfigDto): AIProvider {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MINUTES) // 流式/长思考不设上限
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        return when (cfg.type.lowercase()) {
            "anthropic", "" -> {
                val retrofit = Retrofit.Builder().baseUrl("https://api.anthropic.com/")
                    .client(client).addConverterFactory(GsonConverterFactory.create()).build()
                val api = retrofit.create(AnthropicApi::class.java)
                AnthropicAdapter(api).apply {
                    apiKey = cfg.apiKey
                    baseUrl = cfg.baseUrl
                    useFullUrl = cfg.useFullUrl
                    model = cfg.model
                }
            }
            else -> throw IllegalArgumentException("云端 agent-server 第一版仅支持 anthropic 兼容端点，收到: ${cfg.type}")
        }
    }

}

/**
 * 服务端 Bash 工具：在服务进程所在环境（用户服务器或容器）本地执行命令。
 * 第一版设为 ASK（高风险挂起审批），验证挂起链路；后续可按需加 AUTO 白名单。
 */
class BashTool(private val workspaceRoot: String) : AgentTool() {
    override val name = "Bash"
    override val description = "在执行环境中运行 shell 命令，返回 stdout/stderr。用于检查文件、运行脚本、安装依赖等。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val parameters = mapOf(
        "command" to ToolParameter("command", ParameterType.STRING, "要执行的 shell 命令", true)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = (args["command"] as? KJsonPrimitive)?.content
            ?: return ToolResult.Error("缺少 command 参数")
        return try {
            val pb = ProcessBuilder("/bin/bash", "-lc", command)
            pb.directory(java.io.File(workspaceRoot))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exit = proc.waitFor()
            if (exit == 0) {
                ToolResult.Success(KJsonPrimitive(output.take(MAX_OUTPUT)))
            } else {
                ToolResult.Error("exit $exit\n${output.take(MAX_OUTPUT)}")
            }
        } catch (e: Exception) {
            ToolResult.Error("执行失败: ${e.message}")
        }
    }

    private companion object { const val MAX_OUTPUT = 20_000 }
}
