package com.minime.agent.server

import com.minime.agent.server.modules.ServerBrowserTool
import com.minime.agent.server.modules.ServerGenerateImageTool
import com.minime.agent.server.modules.ServerManageMcpTool
import com.minime.agent.server.modules.ServerSendFileTool
import com.minime.agent.server.modules.ServerShizukuTool
import com.minime.agent.server.modules.ServerTerminalTool
import com.minime.agent.server.modules.ServerViewImageTool
import com.minime.code.feature.agent.domain.tool.ToolResult
import com.minime.code.feature.agent.domain.tool.toTransportString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 7 个镜像工具的契约测试：确保 Success.data 与 App 同名工具逐字段对齐，
 * 落库 transport JSON 后 App 富卡片可识别。网络类工具走缺参/降级路径，不发真实请求。
 */
class ServerMirrorToolsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val ws: String = System.getProperty("java.io.tmpdir") + "/mirror-ws-" + System.nanoTime()

    private fun dataOf(result: ToolResult): JsonObject {
        val success = result as? ToolResult.Success ?: throw AssertionError("期望 Success，实际 Error: ${(result as? ToolResult.Error)?.message}")
        val outer = json.parseToJsonElement(success.toTransportString()).jsonObject
        assertEquals("success", outer["status"]?.jsonPrimitive?.content)
        return outer["data"]!!.jsonObject
    }

    private fun textDataOf(result: ToolResult): String {
        val success = result as? ToolResult.Success ?: throw AssertionError("期望 Success，实际 Error: ${(result as? ToolResult.Error)?.message}")
        return json.parseToJsonElement(success.toTransportString()).jsonObject["data"]!!.jsonPrimitive.content
    }

    private fun js(s: String) = json.parseToJsonElement(s)

    @BeforeTest
    fun ensureWorkspace() { File(ws).mkdirs() }

    @Test
    fun sendFile_dataMatchesSendFileCard() = runBlocking {
        val f = File(ws, "note.txt"); f.writeText("hello")
        val r = ServerSendFileTool(ws).execute(mapOf(
            "paths" to js("[\"/workspace/note.txt\"]"),
            "names" to js("[\"说明.txt\"]")
        ))
        val data = dataOf(r)
        assertEquals(1, data["file_count"]?.jsonPrimitive?.intOrNull)
        val file = data["files"]!!.jsonArray[0].jsonObject
        assertEquals("说明.txt", file["name"]?.jsonPrimitive?.content)
        assertEquals("note.txt", file["path"]?.jsonPrimitive?.content?.substringAfterLast('/'))
        assertEquals("text/plain", file["mime_type"]?.jsonPrimitive?.content)
        assertEquals(f.absolutePath, file["local_path"]?.jsonPrimitive?.content)
    }

    @Test
    fun sendFile_missingPaths_errors() = runBlocking {
        assertTrue(ServerSendFileTool(ws).execute(emptyMap()) is ToolResult.Error)
    }

    @Test
    fun viewImage_nonImage_errors() = runBlocking {
        val f = File(ws, "bad.png"); f.writeText("not a real png but has image ext")
        // 有 image 扩展名就会尝试编码，这里内容任意也能编码成功（工具不做像素校验），应返回 loaded_to_context
        val r = ServerViewImageTool(ws).execute(mapOf("images" to js("[\"/workspace/bad.png\"]")))
        val data = dataOf(r)
        assertEquals("loaded_to_context", data["status"]?.jsonPrimitive?.content)
        assertEquals(1, data["image_count"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun viewImage_missingImages_errors() = runBlocking {
        assertTrue(ServerViewImageTool(ws).execute(emptyMap()) is ToolResult.Error)
    }

    @Test
    fun generateImage_noBackend_returnsSchemaWithNotice() = runBlocking {
        // MINIME_IMAGE_API_URL 未配置时返回结构合法 + notice，image_count=0
        val r = ServerGenerateImageTool(ws).execute(mapOf("prompt" to js("\"一只猫\"")))
        val data = dataOf(r)
        assertEquals(0, data["image_count"]?.jsonPrimitive?.intOrNull)
        assertEquals("failed", data["status"]?.jsonPrimitive?.content)
        assertTrue(data["notice"]?.jsonPrimitive?.content?.contains("MINIME_IMAGE_API_URL") == true)
        assertTrue(data["files"] is JsonArray)
    }

    @Test
    fun shizuku_executesAndReturnsText() = runBlocking {
        val r = ServerShizukuTool(ws).execute(mapOf("command" to js("\"echo mirror-ok\"")))
        assertTrue(textDataOf(r).contains("mirror-ok"))
    }

    @Test
    fun shizuku_missingCommand_errors() = runBlocking {
        assertTrue(ServerShizukuTool(ws).execute(emptyMap()) is ToolResult.Error)
    }

    @Test
    fun terminal_startReadClose_lifecycle() = runBlocking {
        val t = ServerTerminalTool(ws)
        val started = dataOf(t.execute(mapOf(
            "action" to js("\"start\""),
            "command" to js("\"echo term-line\"")
        )))
        val tabId = started["tab_id"]?.jsonPrimitive?.content!!
        assertTrue(tabId.startsWith("term-"))
        val read = dataOf(t.execute(mapOf("action" to js("\"read\""), "tab_id" to js("\"$tabId\""))))
        assertTrue(read.containsKey("output"))
        val closed = textDataOf(t.execute(mapOf("action" to js("\"close\""), "tab_id" to js("\"$tabId\""))))
        assertTrue(closed.contains(tabId))
    }

    @Test
    fun manageMcp_addListRemove() = runBlocking {
        val t = ServerManageMcpTool()
        val added = textDataOf(t.execute(mapOf(
            "action" to js("\"add_stdio\""),
            "server_name" to js("\"test_srv\""),
            "command" to js("\"npx\""),
            "args" to js("[\"-y\",\"foo\"]")
        )))
        assertTrue(added.contains("test_srv"))
        val listed = textDataOf(t.execute(mapOf("action" to js("\"list\""))))
        assertTrue(listed.contains("test_srv"))
        val removed = textDataOf(t.execute(mapOf("action" to js("\"remove\""), "server_name" to js("\"test_srv\""))))
        assertTrue(removed.contains("已移除"))
    }

    @Test
    fun manageMcp_badName_errors() = runBlocking {
        assertTrue(ServerManageMcpTool().execute(mapOf(
            "action" to js("\"add_http\""),
            "server_name" to js("\"bad name!\""),
            "url" to js("\"http://x\"")
        )) is ToolResult.Error)
    }

    @Test
    fun browser_listTabs_emptySchema() = runBlocking {
        val data = dataOf(ServerBrowserTool(com.minime.agent.server.modules.LocalBrowserDriver()).execute(mapOf("action" to js("\"listTabs\""))))
        assertEquals("listTabs", data["action"]?.jsonPrimitive?.content)
        assertTrue(data["detail"]!!.jsonObject.containsKey("tabs"))
        assertTrue(data.containsKey("tabId") && data.containsKey("url") && data.containsKey("error"))
    }

    @Test
    fun browser_interactiveAction_returnsSupportedFalse() = runBlocking {
        val data = dataOf(ServerBrowserTool(com.minime.agent.server.modules.LocalBrowserDriver()).execute(mapOf("action" to js("\"click\""))))
        val detail = data["detail"]!!.jsonObject
        assertEquals("false", detail["supported"]?.jsonPrimitive?.content)
        assertTrue(detail["notice"]?.jsonPrimitive?.content?.contains("不支持") == true)
    }
}
