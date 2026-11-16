package com.minime.agent.server

import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import com.minime.code.feature.agent.data.remote.anthropic.AnthropicApi
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
    private val scope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(AgentRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 当前在跑的任务 job，便于取消（第一版不实现取消，占位）。 */
    private val running = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun submit(sessionId: String, taskId: String, userText: String, providerConfig: ProviderConfigDto) {
        store.setTaskStatus(taskId, TaskStatus.QUEUED)
        val job = scope.launch(Dispatchers.IO) {
            try {
                runLoop(sessionId, taskId, userText, providerConfig)
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

    private suspend fun runLoop(sessionId: String, taskId: String, userText: String, cfg: ProviderConfigDto) {
        store.setTaskStatus(taskId, TaskStatus.RUNNING)
        store.append(NewEvent(sessionId, EventKind.USER_MESSAGE, taskId, text = userText))

        val provider = buildProvider(cfg)
        provider.logSessionId = sessionId

        // 第一版：每个任务一条用户消息起步（历史回放留待 P4 App 接入时补齐）。
        val messages = mutableListOf<AgentMessage>(AgentMessage.UserMessage(content = userText))
        val tools: List<AgentTool> = listOf(BashTool(workspaceRoot))

        val maxIterations = 30
        var iteration = 0
        while (iteration < maxIterations) {
            // checkpoint：每轮保存对话与轮次，服务重启后可从这里恢复（P5 续跑用）。
            store.saveCheckpoint(taskId, TaskCheckpoint(messagesJson = json.encodeToString(messages), iteration = iteration))

            val response = provider.complete(
                systemPrompt = SYSTEM_PROMPT,
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
                store.append(NewEvent(sessionId, EventKind.TASK_COMPLETED, taskId, text = response.content))
                store.setTaskStatus(taskId, TaskStatus.COMPLETED)
                return
            }

            // 逐个执行工具（第一版串行，审批挂起更简单）
            for (call in response.toolCalls) {
                val tool = tools.firstOrNull { it.name == call.name }
                if (tool == null) {
                    val err = "未知工具: ${call.name}"
                    store.append(NewEvent(sessionId, EventKind.TOOL_CALL_FINISHED, taskId, text = err, toolName = call.name, toolCallId = call.id, isError = true))
                    messages += AgentMessage.ToolResultMessage(toolName = call.name, result = err)
                    continue
                }

                store.append(NewEvent(
                    sessionId, EventKind.TOOL_CALL_STARTED, taskId,
                    toolName = call.name, toolArgs = compactArgs(call), toolCallId = call.id
                ))

                // 高风险工具（ASK）→ 写 pending_decision 并挂起，等 App 回传
                if (tool.permissionPolicy == ToolPermissionPolicy.ASK) {
                    val decisionId = call.id
                    store.append(NewEvent(
                        sessionId, EventKind.PENDING_DECISION, taskId,
                        text = "请求执行 ${call.name}", toolName = call.name,
                        toolArgs = compactArgs(call), toolCallId = decisionId
                    ))
                    store.setTaskStatus(taskId, TaskStatus.WAITING_APPROVAL)
                    val deferred = store.registerPending(decisionId)
                    val approved = deferred.await()
                    store.setTaskStatus(taskId, TaskStatus.RUNNING)
                    if (!approved) {
                        val rejected = "用户拒绝了 ${call.name} 调用"
                        store.append(NewEvent(sessionId, EventKind.TOOL_CALL_FINISHED, taskId, text = rejected, toolName = call.name, toolCallId = call.id, isError = true))
                        messages += AgentMessage.ToolResultMessage(toolName = call.name, result = rejected)
                        continue
                    }
                }

                val result = tool.execute(call.arguments)
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
                    toolName = call.name, result = resultText,
                    modelResult = resultText
                )
            }
            iteration++
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

    companion object {
        const val SYSTEM_PROMPT = "You are a helpful AI coding assistant running in a cloud workspace. " +
            "Use the Bash tool to inspect and modify the project. Be concise and precise."
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
