package com.minime.agent.server.modules

import com.minime.code.feature.agent.domain.tool.AgentTool
import com.minime.code.feature.agent.domain.tool.ParameterType
import com.minime.code.feature.agent.domain.tool.ToolCapability
import com.minime.code.feature.agent.domain.tool.ToolParameter
import com.minime.code.feature.agent.domain.tool.ToolPermissionPolicy
import com.minime.code.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 服务端代码搜索工具：在工作区内按关键字递归搜索，产出 {content, matches, ...} 结构。
 * App 端 formatToolData 取 content 作结果正文；matches 供后续可能的搜索卡扩展。
 */
class ServerSearchTool(private val workspacePath: String = "/workspace") : AgentTool() {
    override val name = "search"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val description = "在工作区文件中搜索关键字/正则，返回匹配的文件与行。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val parameters = mapOf(
        "pattern" to ToolParameter("pattern", ParameterType.STRING, "要搜索的关键字或正则", true),
        "path" to ToolParameter("path", ParameterType.STRING, "搜索根目录，默认 /workspace", false),
        "max_results" to ToolParameter("max_results", ParameterType.INTEGER, "最多返回条数，默认 100", false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少 pattern", "MISSING_PATTERN")
        val rootPath = args["path"]?.jsonPrimitive?.contentOrNull?.trim() ?: "/workspace"
        val maxResults = args["max_results"]?.jsonPrimitive?.let {
            runCatching { it.content.toInt() }.getOrNull()
        } ?: 100
        val root = File(resolvePath(rootPath))
        if (!root.exists()) return ToolResult.Error("目录不存在: $rootPath", "NOT_FOUND")

        val regex = runCatching { Regex(pattern) }.getOrNull()
            ?: return ToolResult.Error("无效的正则: $pattern", "BAD_REGEX")
        val matches = ArrayList<JsonObject>()
        val sb = StringBuilder()
        val exts = setOf("kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "json", "xml", "md", "gradle", "sh", "txt", "yaml", "yml", "html", "css")
        var filesScanned = 0
        try {
            root.walkTopDown().forEach { f ->
                if (!f.isFile || f.length() > 2_000_000) return@forEach
                if (f.extension.lowercase() !in exts) return@forEach
                filesScanned++
                f.readLines(Charsets.UTF_8).forEachIndexed { idx, line ->
                    if (matches.size >= maxResults) return@forEachIndexed
                    if (regex.containsMatchIn(line)) {
                        val rel = f.absolutePath.removePrefix(File(workspacePath).absolutePath).trimStart('/')
                        val display = "/workspace/$rel"
                        matches.add(JsonObject(mapOf(
                            "path" to JsonPrimitive(display),
                            "line" to JsonPrimitive(idx + 1),
                            "content" to JsonPrimitive(line.take(500))
                        )))
                        sb.append("$display:${idx + 1}: ${line.trim().take(300)}\n")
                    }
                }
            }
        } catch (e: Exception) {
            return ToolResult.Error("搜索失败: ${e.message}", "SEARCH_ERROR")
        }
        val content = sb.toString().ifEmpty { "未找到匹配: $pattern" }
        return ToolResult.Success(JsonObject(mapOf(
            "content" to JsonPrimitive(content),
            "matches" to JsonPrimitive(matches.size),
            "results" to JsonArray(matches),
            "truncated" to JsonPrimitive(matches.size >= maxResults),
            "backend" to JsonPrimitive("grep")
        )))
    }

    private fun resolvePath(path: String): String = when {
        path == "/" || path == "/workspace" || path == "~" || path == "~/workspace" -> workspacePath
        path.startsWith("/workspace/") -> workspacePath + path.removePrefix("/workspace")
        path.startsWith("~/workspace/") -> workspacePath + path.removePrefix("~/workspace")
        else -> path
    }
}

/**
 * 服务端待办工具（无状态回显）：云端没有 App 的会话状态机，无法维护跨轮 todo，
 * 但可以按本轮传入的 items 原样产出 App TodoCard 需要的结构，保证卡片能正确渲染。
 */
class ServerTodoTool : AgentTool() {
    override val name = "todo"
    override val capabilities = setOf(ToolCapability.MODIFY_TODO_STATE)
    override val description = "记录/更新任务待办清单，返回待办卡片。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    private val itemSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "subject" to mapOf("type" to "string", "description" to "待办主题"),
            "description" to mapOf("type" to "string", "description" to "补充说明"),
            "status" to mapOf("type" to "string", "enum" to listOf("pending", "in_progress", "completed"), "description" to "状态"),
            "priority" to mapOf("type" to "string", "description" to "优先级")
        ),
        "required" to listOf("subject")
    )
    override val parameters = mapOf(
        "items" to ToolParameter("items", ParameterType.ARRAY, "待办项列表", true, itemsSchema = itemSchema)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val arr = args["items"] as? JsonArray
            ?: return ToolResult.Error("缺少 items", "MISSING_ITEMS")
        val items = ArrayList<JsonObject>()
        arr.forEachIndexed { idx, el ->
            val o = el as? JsonObject ?: return@forEachIndexed
            val subject = o["subject"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
            items.add(JsonObject(mapOf(
                "id" to JsonPrimitive(o["id"]?.jsonPrimitive?.contentOrNull ?: "todo-$idx"),
                "subject" to JsonPrimitive(subject),
                "description" to JsonPrimitive(o["description"]?.jsonPrimitive?.contentOrNull ?: ""),
                "status" to JsonPrimitive((o["status"]?.jsonPrimitive?.contentOrNull ?: "pending").lowercase()),
                "priority" to JsonPrimitive(o["priority"]?.jsonPrimitive?.contentOrNull ?: ""),
                "order" to JsonPrimitive(idx)
            )))
        }
        val completed = items.count { it["status"]?.jsonPrimitive?.contentOrNull == "completed" }
        return ToolResult.Success(JsonObject(mapOf(
            "total" to JsonPrimitive(items.size),
            "completed" to JsonPrimitive(completed),
            "items" to JsonArray(items)
        )))
    }
}
