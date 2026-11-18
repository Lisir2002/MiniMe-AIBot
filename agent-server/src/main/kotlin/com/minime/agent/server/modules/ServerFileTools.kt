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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 服务端文件工具：writeFile / editFile / readFile / list。
 *
 * 关键：Success.data 的结构与 App 本地同名工具**逐字段对齐**，这样落库成 transport JSON 后，
 * App 的 parseEditDiff / 通用文件卡渲染即可识别，代码不再裸写正文。
 * - editFile / writeFile 产出 path / added_lines / removed_lines / hunks[{start_line,added,removed,diff}]；
 * - readFile / list 产出 content 主文本字段（App formatToolData 优先取 content）。
 *
 * 路径解析复用 [LocalFileAccessProvider] 的 workspace 映射语义（~/workspace == /workspace）。
 */
class ServerFileTools(private val workspacePath: String = "/workspace") {

    private fun resolve(path: String): File {
        val p = path.trim()
        return when {
            p == "~" || p == "/workspace" || p == "~/workspace" -> File(workspacePath)
            p.startsWith("~/workspace/") -> File(workspacePath, p.removePrefix("~/workspace/"))
            p.startsWith("/workspace/") -> File(workspacePath, p.removePrefix("/workspace/"))
            p.startsWith("~/") -> File(System.getProperty("user.home"), p.removePrefix("~/"))
            else -> File(p)
        }
    }

    private fun displayPath(path: String): String = path.trim()

    val writeFile = object : AgentTool() {
        override val name = "writeFile"
        override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)
        override val description = "写入文件（可创建新文件）。返回结构化的写入结果与 diff。"
        override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
        override val parameters = mapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "目标文件路径（可用 /workspace/... 或 ~/...）", true),
            "content" to ToolParameter("content", ParameterType.STRING, "要写入的完整文件内容", true),
            "overwrite" to ToolParameter("overwrite", ParameterType.BOOLEAN, "是否覆盖已存在文件，默认 true", false)
        )

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return ToolResult.Error("缺少 path", "MISSING_PATH")
            val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true
            val file = resolve(path)
            return try {
                val existed = file.exists()
                val old = if (existed) file.readText(Charsets.UTF_8) else ""
                if (existed && !overwrite) return ToolResult.Error("文件已存在且 overwrite=false: $path", "ALREADY_EXISTS")
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
                val diff = ServerLineDiff.toUnified(old, content)
                val added = diff.lines().count { it.startsWith("+") }
                val removed = diff.lines().count { it.startsWith("-") }
                val hunks = if (diff.isEmpty()) JsonArray(emptyList()) else JsonArray(listOf(
                    JsonObject(mapOf(
                        "start_line" to JsonPrimitive(1),
                        "added" to JsonPrimitive(added),
                        "removed" to JsonPrimitive(removed),
                        "diff" to JsonPrimitive(diff)
                    ))
                ))
                ToolResult.Success(JsonObject(mapOf(
                    "path" to JsonPrimitive(displayPath(path)),
                    "created" to JsonPrimitive(!existed),
                    "bytes_written" to JsonPrimitive(content.toByteArray(Charsets.UTF_8).size),
                    "lines_written" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(added),
                    "removed_lines" to JsonPrimitive(removed),
                    "hunks" to hunks
                )))
            } catch (e: Exception) {
                ToolResult.Error("写入失败: ${e.message}", "WRITE_ERROR")
            }
        }
    }

    val editFile = object : AgentTool() {
        override val name = "editFile"
        override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)
        override val description = "通过精确字符串匹配编辑文件（替换/删除/插入），支持多处编辑。返回 diff。"
        override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
        private val editItemSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "old_string" to mapOf("type" to "string", "description" to "要被替换的原文，需精确匹配"),
                "new_string" to mapOf("type" to "string", "description" to "替换后的内容，空串表示删除"),
                "replace_all" to mapOf("type" to "boolean", "description" to "是否替换全部匹配")
            ),
            "required" to listOf("old_string", "new_string")
        )
        override val parameters = mapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "目标文件路径", true),
            "edits" to ToolParameter("edits", ParameterType.ARRAY, "编辑列表，按顺序生效", true, itemsSchema = editItemSchema)
        )

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return ToolResult.Error("缺少 path", "MISSING_PATH")
            val editsArr = args["edits"] as? JsonArray
                ?: return ToolResult.Error("缺少 edits 数组", "MISSING_EDITS")
            val file = resolve(path)
            return try {
                if (!file.exists()) return ToolResult.Error("文件不存在: $path", "NOT_FOUND")
                var content = file.readText(Charsets.UTF_8)
                val hunks = ArrayList<JsonObject>()
                var totalReplacements = 0
                var totalAdded = 0
                var totalRemoved = 0

                editsArr.forEachIndexed { idx, el ->
                    val o = el as? JsonObject ?: return ToolResult.Error("edits[$idx] 不是对象", "BAD_EDIT")
                    val oldS = o["old_string"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.Error("edits[$idx] 缺少 old_string", "BAD_EDIT")
                    val newS = o["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
                    val replaceAll = o["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
                    val occurrences = countOccurrences(content, oldS)
                    if (occurrences == 0) return ToolResult.Error("edits[$idx] 的 old_string 未匹配", "NO_MATCH")
                    if (occurrences > 1 && !replaceAll) return ToolResult.Error("edits[$idx] 匹配 $occurrences 处，需 replace_all 或更长上下文", "MULTIPLE_MATCHES")

                    val startLine = content.substring(0, content.indexOf(oldS)).count { it == '\n' } + 1
                    val diff = ServerLineDiff.toUnified(oldS, newS)
                    val added = diff.lines().count { it.startsWith("+") }
                    val removed = diff.lines().count { it.startsWith("-") }
                    hunks.add(JsonObject(mapOf(
                        "start_line" to JsonPrimitive(startLine),
                        "added" to JsonPrimitive(added),
                        "removed" to JsonPrimitive(removed),
                        "diff" to JsonPrimitive(diff)
                    )))
                    content = if (replaceAll) content.replace(oldS, newS) else content.replaceFirst(oldS, newS)
                    totalReplacements += if (replaceAll) occurrences else 1
                    totalAdded += added
                    totalRemoved += removed
                }
                file.writeText(content, Charsets.UTF_8)
                ToolResult.Success(JsonObject(mapOf(
                    "status" to JsonPrimitive("edited"),
                    "path" to JsonPrimitive(displayPath(path)),
                    "edits_count" to JsonPrimitive(editsArr.size),
                    "replacements" to JsonPrimitive(totalReplacements),
                    "total_lines" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(totalAdded),
                    "removed_lines" to JsonPrimitive(totalRemoved),
                    "hunks" to JsonArray(hunks)
                )))
            } catch (e: Exception) {
                ToolResult.Error("编辑失败: ${e.message}", "EDIT_ERROR")
            }
        }

        private fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var i = 0
            while (true) {
                val idx = haystack.indexOf(needle, i)
                if (idx < 0) break
                count++
                i = idx + needle.length
            }
            return count
        }
    }

    val readFile = object : AgentTool() {
        override val name = "readFile"
        override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
        override val description = "读取文本文件内容（可指定行范围）。"
        override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
        override val parameters = mapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "文件路径", true),
            "start_line" to ToolParameter("start_line", ParameterType.INTEGER, "起始行（1 基，可选）", false),
            "end_line" to ToolParameter("end_line", ParameterType.INTEGER, "结束行（含，可选）", false)
        )
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return ToolResult.Error("缺少 path", "MISSING_PATH")
            val file = resolve(path)
            if (!file.exists()) return ToolResult.Error("文件不存在: $path", "NOT_FOUND")
            return try {
                val all = file.readLines(Charsets.UTF_8)
                val start = (args["start_line"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
                val end = (args["end_line"]?.jsonPrimitive?.intOrNull ?: all.size).coerceAtMost(all.size)
                val slice = if (start <= end) all.subList(start - 1, end) else emptyList()
                val truncated = end < all.size
                ToolResult.Success(JsonObject(mapOf(
                    "content" to JsonPrimitive(slice.joinToString("\n")),
                    "total_lines" to JsonPrimitive(all.size),
                    "start_line" to JsonPrimitive(start),
                    "end_line" to JsonPrimitive(end),
                    "read_lines" to JsonPrimitive(slice.size),
                    "truncated" to JsonPrimitive(truncated)
                )))
            } catch (e: Exception) {
                ToolResult.Error("读取失败: ${e.message}", "READ_ERROR")
            }
        }
    }

    val list = object : AgentTool() {
        override val name = "list"
        override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
        override val description = "列出目录内容。"
        override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
        override val parameters = mapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "目录路径，默认 /workspace", false)
        )
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim() ?: "/workspace"
            val dir = resolve(path)
            if (!dir.isDirectory) return ToolResult.Error("不是目录或不存在: $path", "NOT_DIR")
            return try {
                val entries = (dir.listFiles() ?: emptyArray())
                    .sortedBy { it.name }
                    .map { f -> "${if (f.isDirectory) "d" else "-"} ${f.name}${if (f.isDirectory) "/" else ""}" }
                ToolResult.Success(JsonObject(mapOf(
                    "content" to JsonPrimitive(entries.joinToString("\n")),
                    "entries" to JsonPrimitive(entries.size),
                    "truncated" to JsonPrimitive(false)
                )))
            } catch (e: Exception) {
                ToolResult.Error("列目录失败: ${e.message}", "LIST_ERROR")
            }
        }
    }
}

/** 简化版 LCS 行级 diff（与 App 端 LineDiff.toUnified 输出格式一致）。 */
object ServerLineDiff {
    fun toUnified(oldText: String, newText: String): String {
        val a = oldText.split("\n")
        val b = newText.split("\n")
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<String>(n + m)
        var i = 0; var j = 0
        while (i < n && j < m) when {
            a[i] == b[j] -> { out.add(" ${a[i]}"); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { out.add("-${a[i]}"); i++ }
            else -> { out.add("+${b[j]}"); j++ }
        }
        while (i < n) { out.add("-${a[i]}"); i++ }
        while (j < m) { out.add("+${b[j]}"); j++ }
        return out.joinToString("\n")
    }
}
