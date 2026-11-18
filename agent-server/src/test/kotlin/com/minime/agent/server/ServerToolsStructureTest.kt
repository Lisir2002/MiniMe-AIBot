package com.minime.agent.server

import com.minime.agent.server.modules.ServerFileTools
import com.minime.agent.server.modules.ServerLineDiff
import com.minime.agent.server.modules.ServerTodoTool
import com.minime.code.feature.agent.domain.tool.ToolResult
import com.minime.code.feature.agent.domain.tool.toTransportString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 服务端工具输出必须与 App 卡片 parse 的 gold standard 逐字段对齐：
 * - editFile/writeFile 的 data 含 path / added_lines / removed_lines / hunks[{diff,start_line}]；
 * - transport JSON 外层含 status 判别式，落库后 App 才认得出是工具结果而非正文；
 * - todo / search 的 data 结构对齐对应卡片。
 */
class ServerToolsStructureTest {
    private lateinit var dir: File
    private val json = Json { ignoreUnknownKeys = true }
    private val fileTools by lazy { ServerFileTools(dir.absolutePath) }

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("server-tools-test").toFile()
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    private fun dataOf(result: ToolResult): JsonObject {
        val t = (result as ToolResult.Success).toTransportString()
        val outer = json.parseToJsonElement(t).jsonObject
        assertEquals("success", outer["status"]?.jsonPrimitive?.content)
        return outer["data"]!!.jsonObject
    }

    @Test
    fun writeFile_dataMatchesDiffCardSchema() = runBlocking {
        val r = fileTools.writeFile.execute(mapOf(
            "path" to json.parseToJsonElement("\"/workspace/a.txt\""),
            "content" to json.parseToJsonElement("\"line1\\nline2\\n\"")
        ))
        val data = dataOf(r)
        assertEquals("/workspace/a.txt", data["path"]?.jsonPrimitive?.content)
        assertTrue(data.containsKey("hunks"))
        assertTrue(data.containsKey("added_lines"))
        assertTrue(data.containsKey("removed_lines"))
        assertTrue(data["created"]?.jsonPrimitive?.content == "true")
        val hunk = data["hunks"]!!.jsonArray[0].jsonObject
        assertTrue(hunk.containsKey("diff") && hunk.containsKey("start_line"))
    }

    @Test
    fun editFile_producesDiffHunks() = runBlocking {
        // 先写一个文件
        val pathEl = json.parseToJsonElement("\"/workspace/edit.txt\"")
        fileTools.writeFile.execute(mapOf("path" to pathEl, "content" to json.parseToJsonElement("\"hello world\\nsecond\\n\"")))
        val edits = json.parseToJsonElement("[{\"old_string\":\"world\",\"new_string\":\"MiniMe\"}]")
        val r = fileTools.editFile.execute(mapOf("path" to pathEl, "edits" to edits))
        val data = dataOf(r)
        assertEquals("edited", data["status"]?.jsonPrimitive?.content)
        assertEquals("1", data["replacements"]?.jsonPrimitive?.content)
        val hunk = data["hunks"]!!.jsonArray[0].jsonObject
        val diff = hunk["diff"]!!.jsonPrimitive.content
        assertTrue(diff.contains("-world") && diff.contains("+MiniMe"), "diff 应含增删行: $diff")
        assertTrue(hunk["start_line"]!!.jsonPrimitive.content.toInt() >= 1)
    }

    @Test
    fun todo_dataMatchesTodoCardSchema() = runBlocking {
        val todo = ServerTodoTool()
        val items = json.parseToJsonElement("[{\"subject\":\"写主页\",\"status\":\"in_progress\"},{\"subject\":\"部署\",\"status\":\"pending\"}]")
        val r = todo.execute(mapOf("items" to items))
        val data = dataOf(r)
        assertEquals("2", data["total"]?.jsonPrimitive?.content)
        assertEquals("0", data["completed"]?.jsonPrimitive?.content)
        val first = data["items"]!!.jsonArray[0].jsonObject
        assertTrue(first.containsKey("subject") && first.containsKey("status") && first.containsKey("id"))
    }

    @Test
    fun lineDiff_basic() {
        val d = ServerLineDiff.toUnified("a\nb\nc", "a\nx\nc")
        assertTrue(d.contains("-b") && d.contains("+x"))
    }
}
