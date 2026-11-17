package com.minime.agent.server.modules

import com.minime.code.feature.agent.domain.memory.MemoryEdit
import com.minime.code.feature.agent.domain.memory.MemoryEditResult
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 服务端 memory 工具：参数/命名与 App 端 MemoryTool 一致，数据来自 [ServerMemoryRepository]（完全独立）。
 * 一期沿用 AUTO_APPROVE（写入仅影响容器内文件，隔离且可回滚）。
 */
class ServerMemoryTool(
    private val memoryRepository: ServerMemoryRepository
) : AgentTool() {
    override val name = "memory"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
        when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "read", "list" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }

    override val description =
        "管理 AI 的长期记忆（读取/保存/局部编辑/删除/列表）。发现新的用户偏好、项目约定或架构决策时主动记录。"

    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf("type" to "string", "description" to "要被替换的原文，需与当前正文精确匹配。"),
            "new_string" to mapOf("type" to "string", "description" to "替换后的内容，传空串表示删除。"),
            "replace_all" to mapOf("type" to "boolean", "description" to "是否替换全部匹配项，默认 false。")
        ),
        "required" to listOf("old_string", "new_string")
    )

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action", type = ParameterType.STRING,
            description = "操作：read/save/edit/delete/list",
            enum = listOf("read", "save", "edit", "delete", "list"), required = true
        ),
        "name" to ToolParameter(name = "name", type = ParameterType.STRING,
            description = "记忆短名称（作为文件名）。list 可省略。", required = false),
        "description" to ToolParameter(name = "description", type = ParameterType.STRING,
            description = "一句话摘要（save 必填）。", required = false),
        "content" to ToolParameter(name = "content", type = ParameterType.STRING,
            description = "记忆正文，Markdown 格式（save 必填）。", required = false),
        "edits" to ToolParameter(name = "edits", type = ParameterType.ARRAY,
            description = "edit 操作的编辑列表，按顺序生效。元素：{old_string,new_string,replace_all?}。",
            required = false, itemsSchema = editItemSchema),
        "scope" to ToolParameter(name = "scope", type = ParameterType.STRING,
            description = "作用域：project=当前项目；global=跨项目通用。默认 project。",
            enum = listOf("project", "global"), required = false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数：action", "MISSING_ACTION")
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "project"

        return try {
            when (action) {
                "list" -> {
                    val list = memoryRepository.promptList(scope)
                    if (list.isEmpty()) ToolResult.Success(JsonPrimitive("当前没有任何记忆。"))
                    else ToolResult.Success(JsonPrimitive("当前记忆列表：\n" +
                        list.joinToString("\n") { "- ${it.first} (${scope}): ${it.second}" }))
                }
                "read" -> {
                    if (name.isNullOrEmpty()) return ToolResult.Error("read 缺少 name", "MISSING_NAME")
                    val content = memoryRepository.read(name, scope)
                        ?: return ToolResult.Error("未找到记忆「$name」", "MEMORY_NOT_FOUND")
                    ToolResult.Success(JsonPrimitive(content))
                }
                "save" -> {
                    if (name.isNullOrEmpty()) return ToolResult.Error("save 缺少 name", "MISSING_NAME")
                    val description = args["description"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: return ToolResult.Error("save 缺少 description", "MISSING_DESCRIPTION")
                    val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: return ToolResult.Error("save 缺少 content", "MISSING_CONTENT")
                    if (memoryRepository.save(name, description, content, scope))
                        ToolResult.Success(JsonPrimitive("已保存 $scope 作用域的记忆「$name」。"))
                    else ToolResult.Error("保存记忆失败。", "SAVE_FAILED")
                }
                "edit" -> {
                    if (name.isNullOrEmpty()) return ToolResult.Error("edit 缺少 name", "MISSING_NAME")
                    val edits = parseEdits(args) ?: return ToolResult.Error("edit 缺少 edits", "MISSING_EDITS")
                    when (val result = memoryRepository.edit(name, edits, scope)) {
                        is MemoryEditResult.Success -> ToolResult.Success(JsonPrimitive("已编辑记忆「$name」。"))
                        is MemoryEditResult.NotFound -> ToolResult.Error("未找到记忆「${result.name}」。", "MEMORY_NOT_FOUND")
                        is MemoryEditResult.Error -> ToolResult.Error(result.message, result.code)
                    }
                }
                "delete" -> {
                    if (name.isNullOrEmpty()) return ToolResult.Error("delete 缺少 name", "MISSING_NAME")
                    if (memoryRepository.delete(name, scope))
                        ToolResult.Success(JsonPrimitive("已删除 $scope 作用域的记忆「$name」。"))
                    else ToolResult.Error("删除失败，记忆「$name」可能不存在。", "DELETE_FAILED")
                }
                else -> ToolResult.Error("不支持的操作：$action", "UNSUPPORTED_ACTION")
            }
        } catch (e: Exception) {
            ToolResult.Error("记忆操作失败：${e.message}")
        }
    }

    private fun parseEdits(args: Map<String, JsonElement>): List<MemoryEdit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            MemoryEdit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }
}
