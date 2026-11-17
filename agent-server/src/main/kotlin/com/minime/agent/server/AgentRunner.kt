package com.minime.agent.server

import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import com.minime.code.feature.agent.data.remote.anthropic.AnthropicApi
import com.minime.agent.server.modules.ServerLoadSkillTool
import com.minime.agent.server.modules.ServerMemoryRepository
import com.minime.agent.server.modules.ServerMemoryTool
import com.minime.agent.server.modules.ServerPromptBuilder
import com.minime.agent.server.modules.ServerSkillRepository
import com.minime.code.feature.agent.domain.model.AgentContext
import com.minime.code.feature.agent.domain.model.AgentMessage
import com.minime.code.feature.agent.domain.provider.AIProvider
import com.minime.code.feature.agent.domain.provider.AnthropicAdapter
import com.minime.code.feature.agent.domain.tool.AgentTool
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
    private val promptBuilder: ServerPromptBuilder = ServerPromptBuilder(skillRepository, memoryRepository)
) {
    private val log = LoggerFactory.getLogger(AgentRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 当前在跑的任务 job，便于取消（第一版不实现取消，占位）。 */
    private val running = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

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
        // 云端工具集：Bash + 技能加载 + 长期记忆（后两者数据完全独立于 App，存在容器内）
        val tools: List<AgentTool> = listOf(
            BashTool(workspaceRoot),
            ServerLoadSkillTool(skillRepository),
            ServerMemoryTool(memoryRepository)
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

                // 记录助手文本 + thinking（终态事件，App 拉回放）
                if (response.content.isNotBlank() || response.toolCalls.isNotEmpty()) {
                    store.append(NewEvent(
                        sessionId = sessionId, kind = EventKind.ASSISTANT_TEXT, taskId = taskId,
                        text = response.content, reasoning = response.reasoning ?: "",
                        thinkingBlocksJson = response.thinkingBlocksJson ?: "", signature = response.signature ?: ""
                    ))
                }

                // 把助手消息回填上下文（thinking/signature 原样保留， Anthropic 工具循环必需）
                messages += AgentMessage.AssistantMessage(
                    content = response.content,
                    toolCalls = response.toolCalls,
                    reasoning = response.reasoning ?: "",
                    signature = response.signature ?: "",
                    thinkingBlocksJson = response.thinkingBlocksJson ?: "",
                    inputTokens = response.inputTokens
                )

                if (response.toolCalls.isEmpty()) {
                    // 任务正常完成：写回会话上下文，下一轮提问可在云端续接多轮历史
                    store.updateSessionContext(sessionId, messages)
                    store.append(NewEvent(sessionId, EventKind.TASK_COMPLETED, taskId, text = response.content))
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

                val result = tool.executeWithContext(call.arguments, agentContext)
                val resultText = when (result) {
                    is ToolResult.Success -> result.data.toString()
                    is ToolResult.Error -> result.message
                    is ToolResult.Partial -> result.message + "\n" + result.data.toString()
                }
                store.append(NewEvent(
                    sessionId, EventKind.TOOL_CALL_FINISHED, taskId, text = resultText,
                    toolName = call.name, toolArgs = compactArgs(call), toolCallId = call.id,
                    isError = result is ToolResult.Error
                ))
                messages += AgentMessage.ToolResultMessage(
                    id = call.id, toolName = call.name, result = resultText,
                    modelResult = resultText
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
