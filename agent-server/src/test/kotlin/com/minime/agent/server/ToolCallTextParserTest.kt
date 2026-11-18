package com.minime.agent.server

import com.minime.code.feature.agent.domain.provider.ToolCallTextParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 协议无关文本工具调用解析：覆盖截图中的讯飞 XML 格式、function_call JSON、markdown json 块。
 * 这是修复「工具调用/代码被写在正文」的第一层——先把文本调用还原成标准工具调用。
 */
class ToolCallTextParserTest {
    private val parser = ToolCallTextParser

    @Test
    fun parse_xfyunArgKeyValueXML() {
        // 截图真实形态：<tool_call><arg_key>command</arg_key><arg_value>...</arg_value></tool_call>
        val text = """
            环境确认：8080 被 agent 服务占用，我把站点用独立静态服务器托管。现在开始写主页。
            <tool_call><arg_key>command</arg_key><arg_value>mkdir -p /workspace/site &amp;&amp; cat &gt; /workspace/site/index.html &lt;&lt;'EOF'&gt; body EOF</arg_value></tool_call>
        """.trimIndent()
        val r = parser.parse(text)
        assertTrue(r.hasCalls, "应解析出工具调用")
        assertEquals("Bash", r.toolCalls.first().name)
        assertEquals(
            "mkdir -p /workspace/site && cat > /workspace/site/index.html <<'EOF'> body EOF",
            r.toolCalls.first().arguments["command"]?.toString()?.trim('"')
        )
        assertTrue(!r.cleanText.contains("<tool_call>"), "工具调用 XML 应从正文剥离")
        assertTrue(r.cleanText.contains("环境确认"), "自然语言正文应保留")
    }

    @Test
    fun parse_xmlWithNameAttribute() {
        val text = """分析完了，开始写文件。
            <tool_call name="writeFile"><arg_key>path</arg_key><arg_value>/workspace/a.txt</arg_value><arg_key>content</arg_key><arg_value>hello</arg_value></tool_call>
        """.trimIndent()
        val r = parser.parse(text)
        assertEquals("writeFile", r.toolCalls.first().name)
        assertEquals("/workspace/a.txt", r.toolCalls.first().arguments["path"]?.toString()?.trim('"'))
        assertEquals("hello", r.toolCalls.first().arguments["content"]?.toString()?.trim('"'))
    }

    @Test
    fun parse_functionCallWithJsonBody() {
        val text = """<function_call name="search">{"pattern":"TODO","path":"/workspace"}</function_call>"""
        val r = parser.parse(text)
        assertEquals("search", r.toolCalls.first().name)
        assertEquals("TODO", r.toolCalls.first().arguments["pattern"]?.toString()?.trim('"'))
    }

    @Test
    fun parse_markdownJsonBlock() {
        val text = """按计划执行：
            ```json
            {"tool":"websearch","arguments":{"query":"玻璃质感 设计"}}
            ```
        """.trimIndent()
        val r = parser.parse(text)
        assertEquals("websearch", r.toolCalls.first().name)
        assertEquals("玻璃质感 设计", r.toolCalls.first().arguments["query"]?.toString()?.trim('"'))
        assertTrue(!r.cleanText.contains("```json"), "json 块应被剥离")
    }

    @Test
    fun parse_plainText_noCalls() {
        val text = "这是一段普通回答，包含 <div>html</div> 示例但没有工具调用。"
        val r = parser.parse(text)
        assertTrue(!r.hasCalls, "普通文本不应被误判")
        assertEquals(text, r.cleanText)
    }
}
