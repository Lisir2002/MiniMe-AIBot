package com.minime.agent.server

import com.minime.agent.server.modules.ServerAskUserTool
import com.minime.agent.server.modules.ServerGoalStatusTool
import com.minime.agent.server.modules.ServerMessageParentTool
import com.minime.agent.server.modules.ServerPlanModeTool
import com.minime.agent.server.modules.ServerTaskTool
import com.minime.agent.server.modules.ServerWebFetchTool
import com.minime.agent.server.modules.ServerWebSearchTool
import com.minime.code.feature.agent.domain.tool.ToolResult
import com.minime.code.feature.agent.domain.tool.toTransportString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 本轮补齐的 7 个云端工具，输出必须与 App 卡片契约对齐：
 * transport JSON 外层 status=success、data 结构含 App parse 函数读取的字段。
 */
class ServerExtendedToolsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun dataOf(result: ToolResult): JsonObject {
        val t = (result as ToolResult.Success).toTransportString()
        val outer = json.parseToJsonElement(t).jsonObject
        assertEquals("success", outer["status"]?.jsonPrimitive?.content)
        return outer["data"]!!.jsonObject
    }

    @Test
    fun websearch_missingQuery_errors() = runBlocking {
        val r = ServerWebSearchTool().execute(emptyMap())
        assertTrue(r is ToolResult.Error)
    }

    @Test
    fun websearch_noBackend_stillReturnsCardSchema() = runBlocking {
        // 未配置 MINIME_SEARCH_URL 时返回空但结构合法的搜索卡（results 数组 + search_id）
        val r = ServerWebSearchTool().execute(mapOf("query" to json.parseToJsonElement("\"test\"")))
        val data = dataOf(r)
        assertTrue(data["results"] is kotlinx.serialization.json.JsonArray)
        assertTrue(data.containsKey("search_id"))
    }

    @Test
    fun webfetch_missingUrl_errors() = runBlocking {
        assertTrue(ServerWebFetchTool().execute(emptyMap()) is ToolResult.Error)
    }

    @Test
    fun task_dataMatchesSubAgentCardSchema() = runBlocking {
        val tasks = json.parseToJsonElement(
            "[{\"description\":\"写代码\",\"agent\":\"coder\",\"tier\":\"balanced\"}]"
        )
        val r = ServerTaskTool().execute(mapOf("tasks" to tasks))
        val data = dataOf(r)
        assertEquals("1", data["count"]?.jsonPrimitive?.content)
        val sub = data["subagents"]!!.jsonArray[0].jsonObject
        assertTrue(sub.containsKey("id") && sub.containsKey("description") && sub.containsKey("agent"))
        assertTrue(sub["state"]?.jsonPrimitive?.content == "running")
        assertEquals(sub["id"]?.jsonPrimitive?.content, data["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun askUserQuestion_dataCarriesQuestions() = runBlocking {
        val qs = json.parseToJsonElement(
            "[{\"question\":\"选哪个?\",\"header\":\"方案\",\"options\":[{\"label\":\"A\",\"description\":\"方案A\"},{\"label\":\"B\"}]}]"
        )
        val r = ServerAskUserTool().execute(mapOf("questions" to qs))
        val data = dataOf(r)
        val q = data["questions"]!!.jsonArray[0].jsonObject
        assertEquals("选哪个?", q["question"]?.jsonPrimitive?.content)
        assertEquals("方案", q["header"]?.jsonPrimitive?.content)
        assertEquals(2, (q["options"] as kotlinx.serialization.json.JsonArray).size)
        assertTrue(data["summary"]?.jsonPrimitive?.content?.contains("A") == true)
    }

    private fun textDataOf(result: ToolResult): String {
        val t = (result as ToolResult.Success).toTransportString()
        val outer = json.parseToJsonElement(t).jsonObject
        assertEquals("success", outer["status"]?.jsonPrimitive?.content)
        return outer["data"]!!.jsonPrimitive.content
    }

    @Test
    fun planModeAndGoalStatus_returnText() = runBlocking {
        val plan = textDataOf(ServerPlanModeTool().execute(mapOf(
            "action" to json.parseToJsonElement("\"enter\""),
            "reason" to json.parseToJsonElement("\"先规划\"")
        )))
        assertTrue(plan.contains("计划模式"), plan)
        val goal = textDataOf(ServerGoalStatusTool().execute(mapOf(
            "action" to json.parseToJsonElement("\"blocked\""),
            "reason" to json.parseToJsonElement("\"缺依赖\"")
        )))
        assertTrue(goal.contains("受阻"), goal)
    }

    @Test
    fun messageParent_dataMatchesSchema() = runBlocking {
        val r = ServerMessageParentTool().execute(mapOf("message" to json.parseToJsonElement("\"进度同步\"")))
        val data = dataOf(r)
        assertEquals("sent", data["state"]?.jsonPrimitive?.content)
        assertTrue(data.containsKey("message"))
    }
}
