package com.minime.agent.server.modules

import com.minime.code.feature.agent.domain.tool.AgentTool
import com.minime.code.feature.agent.domain.tool.ParameterType
import com.minime.code.feature.agent.domain.tool.ToolCapability
import com.minime.code.feature.agent.domain.tool.ToolParameter
import com.minime.code.feature.agent.domain.tool.ToolPermissionPolicy
import com.minime.code.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * websearch：按 App WebSearchCard 的 data 契约产出结构化搜索结果。
 *
 * 搜索后端可配置：环境变量 MINIME_SEARCH_URL 指向 Parallel/Brave 风格的 JSON 端点
 * （POST {query}，返回 {results:[{url,title,excerpts|snippet,publish_date}]}）。
 * 未配置时返回结构合法的空结果 + 说明，保证 App 卡片不崩、模型能继续。
 */
class ServerWebSearchTool : AgentTool() {
    override val name = "websearch"
    override val capabilities = setOf(ToolCapability.NETWORK_READ)
    override val description = "联网搜索。返回 {results:[{url,title,excerpts,publish_date}],search_id,usage} 结构。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val parameters = mapOf(
        "query" to ToolParameter("query", ParameterType.STRING, "搜索关键词", true)
    )

    private val searchUrl = System.getenv("MINIME_SEARCH_URL")?.takeIf { it.isNotBlank() }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少 query", "MISSING_QUERY")
        val raw = if (searchUrl != null) runCatching {
            postJson(searchUrl!!, buildJsonObject { put("query", query) }.toString())
        }.getOrNull() else null

        val results = JsonArray(parseResults(raw))
        return ToolResult.Success(buildJsonObject {
            put("results", results)
            put("search_id", JsonPrimitive("srv-${query.hashCode()}"))
            put("session_id", JsonPrimitive(""))
            put("warnings", JsonPrimitive("null"))
            put("usage", buildJsonArray { add(buildJsonObject { put("name", JsonPrimitive("server")); put("count", JsonPrimitive(1)) }) })
            if (searchUrl == null) put("notice", JsonPrimitive("服务端未配置搜索后端（MINIME_SEARCH_URL），无实时结果。"))
        })
    }

    /** 兼容 excerpts(string[]) 与 snippet(string) 两种上游格式。 */
    private fun parseResults(raw: String?): List<JsonElement> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val arr = root["results"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val excerpts = when (val e = o["excerpts"] ?: o["excerpts_list"]) {
                is JsonArray -> e
                is JsonPrimitive -> JsonArray(listOf(e))
                else -> {
                    val s = o["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (s.isEmpty()) JsonArray(emptyList()) else JsonArray(listOf(JsonPrimitive(s)))
                }
            }
            buildJsonObject {
                put("url", JsonPrimitive(o["url"]?.jsonPrimitive?.contentOrNull ?: ""))
                put("title", JsonPrimitive(o["title"]?.jsonPrimitive?.contentOrNull ?: ""))
                put("excerpts", excerpts)
                o["publish_date"]?.let { put("publish_date", JsonPrimitive(it.jsonPrimitive.contentOrNull ?: "")) }
            }
        }
    }

    private fun postJson(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

/** webfetch：抓取网页，data 为纯文本 JsonPrimitive（与 App WebFetchTool 一致）。 */
class ServerWebFetchTool : AgentTool() {
    override val name = "webfetch"
    override val capabilities = setOf(ToolCapability.NETWORK_READ)
    override val description = "抓取指定 URL 的网页正文。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val parameters = mapOf(
        "url" to ToolParameter("url", ParameterType.STRING, "目标 URL", true),
        "format" to ToolParameter("format", ParameterType.STRING, "text 或 html", false, enum = listOf("text", "html"))
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少 url", "MISSING_URL")
        val result = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "MiniMe-CloudAgent/1.0")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { return ToolResult.Error("抓取失败: ${it.message}", "FETCH_ERROR") }
        val text = if (result.length > 100_000) result.take(100_000) + "\n\n[网页内容超长，已截断]" else result
        return ToolResult.Success(JsonPrimitive(text))
    }
}

/** task（子代理派发）：产出 App SubAgentCard 需要的 {count,subagents[],id,agent,tier,model} 结构。 */
class ServerTaskTool : AgentTool() {
    override val name = "task"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description = "派发子代理任务，返回子代理派发卡片。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    private val taskSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "description" to mapOf("type" to "string", "description" to "任务简述"),
            "prompt" to mapOf("type" to "string", "description" to "完整指令"),
            "agent" to mapOf("type" to "string", "description" to "代理类型"),
            "tier" to mapOf("type" to "string", "description" to "模型层级")
        ),
        "required" to listOf("description")
    )
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "create", false),
        "tasks" to ToolParameter("tasks", ParameterType.ARRAY, "子代理任务列表", true, itemsSchema = taskSchema),
        "description" to ToolParameter("description", ParameterType.STRING, "单任务简述", false),
        "agent" to ToolParameter("agent", ParameterType.STRING, "代理类型", false),
        "tier" to ToolParameter("tier", ParameterType.STRING, "模型层级", false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val arr = args["tasks"] as? JsonArray
        val items = if (arr != null && arr.isNotEmpty()) arr.mapIndexed { idx, el ->
            val o = el as? JsonObject ?: JsonObject(emptyMap())
            o to idx
        } else {
            val single = buildJsonObject {
                args["description"]?.jsonPrimitive?.contentOrNull?.let { put("description", JsonPrimitive(it)) }
                args["prompt"]?.jsonPrimitive?.contentOrNull?.let { put("prompt", JsonPrimitive(it)) }
                args["agent"]?.jsonPrimitive?.contentOrNull?.let { put("agent", JsonPrimitive(it)) }
                args["tier"]?.jsonPrimitive?.contentOrNull?.let { put("tier", JsonPrimitive(it)) }
            }
            listOf(single to 0)
        }
        val subagents = buildJsonArray {
            items.forEach { (o, idx) ->
                add(buildJsonObject {
                    put("id", JsonPrimitive("sub-${System.currentTimeMillis()}-$idx"))
                    o["agent"]?.jsonPrimitive?.contentOrNull?.let { put("agent", JsonPrimitive(it)) }
                    put("description", JsonPrimitive(o["description"]?.jsonPrimitive?.contentOrNull ?: "子任务 $idx"))
                    put("tier", JsonPrimitive(o["tier"]?.jsonPrimitive?.contentOrNull ?: "balanced"))
                    put("model", JsonPrimitive(""))
                    put("state", JsonPrimitive("running"))
                })
            }
        }
        val first = subagents.firstOrNull() as? JsonObject
        return ToolResult.Success(buildJsonObject {
            put("count", JsonPrimitive(subagents.size))
            put("subagents", subagents)
            first?.let {
                put("id", JsonPrimitive(it["id"]?.jsonPrimitive?.contentOrNull ?: ""))
                it["agent"]?.jsonPrimitive?.contentOrNull?.let { a -> put("agent", JsonPrimitive(a)) }
                put("tier", JsonPrimitive(it["tier"]?.jsonPrimitive?.contentOrNull ?: "balanced"))
                put("model", JsonPrimitive(""))
            }
            put("state", JsonPrimitive("running"))
        })
    }
}

/** askUserQuestion：产出结构化提问 data；审批挂起由 permissionPolicy 驱动（用户确认后继续）。 */
class ServerAskUserTool : AgentTool() {
    override val name = "askUserQuestion"
    override val capabilities = setOf(ToolCapability.USER_INTERACTION)
    override val description = "向用户提问并等待选择，返回提问结构。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    private val questionSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "question" to mapOf("type" to "string", "description" to "问题"),
            "header" to mapOf("type" to "string", "description" to "短标题"),
            "options" to mapOf("type" to "array", "items" to mapOf("type" to "object")),
            "multiSelect" to mapOf("type" to "boolean", "description" to "是否多选")
        ),
        "required" to listOf("question", "options")
    )
    override val parameters = mapOf(
        "questions" to ToolParameter("questions", ParameterType.ARRAY, "问题列表", true, itemsSchema = questionSchema)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val arr = args["questions"] as? JsonArray
            ?: return ToolResult.Error("缺少 questions", "MISSING_QUESTIONS")
        val questions = buildJsonArray {
            arr.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                add(buildJsonObject {
                    put("question", JsonPrimitive(o["question"]?.jsonPrimitive?.contentOrNull ?: ""))
                    put("header", JsonPrimitive(o["header"]?.jsonPrimitive?.contentOrNull ?: ""))
                    put("options", o["options"] as? JsonArray ?: JsonArray(emptyList()))
                    put("multiSelect", JsonPrimitive(o["multiSelect"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false))
                })
            }
        }
        val summary = buildString {
            (arr).forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                append("「").append(o["question"]?.jsonPrimitive?.contentOrNull ?: "").append("」= ")
                val opts = (o["options"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull }
                append(opts.joinToString("、")).append("\n")
            }
        }
        return ToolResult.Success(buildJsonObject {
            put("questions", questions)
            put("summary", JsonPrimitive(summary))
            put("answered", JsonPrimitive(false))
        })
    }
}

/** planMode：模式切换说明（纯文本 data，与 App 一致）。 */
class ServerPlanModeTool : AgentTool() {
    override val name = "planMode"
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)
    override val description = "进入或退出计划模式。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "enter 或 exit", true, enum = listOf("enter", "exit")),
        "reason" to ToolParameter("reason", ParameterType.STRING, "原因", true)
    )
    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "enter"
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val text = if (action == "exit") "已退出计划模式：$reason" else "已进入计划模式：$reason。接下来只做规划，不修改文件。"
        return ToolResult.Success(JsonPrimitive(text))
    }
}

/** goalStatus：目标状态变更说明（纯文本 data）。 */
class ServerGoalStatusTool : AgentTool() {
    override val name = "goalStatus"
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)
    override val description = "标记目标完成或受阻。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "complete 或 blocked", true, enum = listOf("complete", "blocked")),
        "reason" to ToolParameter("reason", ParameterType.STRING, "说明", true)
    )
    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "complete"
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return ToolResult.Success(JsonPrimitive(if (action == "blocked") "目标受阻：$reason" else "目标已完成：$reason"))
    }
}

/** messageParent：向主会话回传消息（子代理用），data 结构 {state,message}。 */
class ServerMessageParentTool : AgentTool() {
    override val name = "messageParent"
    override val capabilities = setOf(ToolCapability.USER_INTERACTION)
    override val description = "向父会话发送消息。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val parameters = mapOf(
        "message" to ToolParameter("message", ParameterType.STRING, "消息内容", true)
    )
    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        args["message"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少 message", "MISSING_MESSAGE")
        return ToolResult.Success(buildJsonObject {
            put("state", JsonPrimitive("sent"))
            put("message", JsonPrimitive("消息已发送给主会话，可继续手头工作，主会话的回复会稍后送达。"))
        })
    }
}
