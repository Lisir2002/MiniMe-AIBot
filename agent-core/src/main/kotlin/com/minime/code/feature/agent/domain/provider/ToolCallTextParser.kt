package com.minime.code.feature.agent.domain.provider

import com.minime.code.feature.agent.domain.tool.tryParseJsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 协议无关的「文本形态工具调用」兜底解析器。
 *
 * 背景：无论 Anthropic / OpenAI / Gemini 还是各类兼容端点（讯飞 maas、智谱、Kimi 等），
 * 大部分模型会返回结构化的 tool_use / function_call（由各 Adapter 解析）。但仍有相当一部分
 * 模型/端点会把工具调用「吐成文本」——典型如截图中的讯飞 spark：
 *
 * ```
 * <tool_call><arg_key>command</arg_key><arg_value>mkdir -p ...</arg_value></tool_call>
 * ```
 *
 * 这类文本会被当成 ASSISTANT_TEXT 落库，表现为「工具调用和代码被直接写在正文里」，无法接入
 * 任何卡片。本解析器在 provider 返回后兜底扫描正文，把识别出的工具调用转成标准 [ToolCall]，
 * 并从正文中剥离对应片段，从而让后续工具执行、结果卡片渲染走与标准调用完全相同的链路。
 *
 * 支持形态（可同时出现）：
 * 1. `<tool_call>...<arg_key>k</arg_key><arg_value>v</arg_value>...</tool_call>`，
 *    可带 `name`/`tool` 属性或 `<name>` 子元素；缺 name 时按 arg_key 推断（command→Bash）。
 * 2. `<function_call name="X">{"arg":"v"}</function_call>` / `<invoke name="X">...</invoke>`。
 * 3. Markdown ```json 块里的 `{"tool":"X","arguments":{...}}`（单对象或数组）。
 *
 * 原则：宁可漏解析也不误伤正文。只有明确命中标记才转换，解析失败一律保留原文。
 */
object ToolCallTextParser {

    data class Result(
        val cleanText: String,
        val toolCalls: List<ParsedCall>
    ) {
        val hasCalls: Boolean get() = toolCalls.isNotEmpty()
    }

    data class ParsedCall(
        val name: String,
        val arguments: Map<String, JsonElement>,
        val raw: String
    )

    private val parserJson = Json { ignoreUnknownKeys = true }

    private val ARG_KEY_TO_TOOL: List<Pair<Set<String>, String>> = listOf(
        setOf("command", "cmd") to "Bash",
        setOf("path", "content") to "writeFile",
        setOf("path", "edits") to "editFile",
        setOf("old_string", "new_string") to "editFile",
        setOf("query") to "websearch",
        setOf("url") to "webfetch",
        setOf("pattern") to "search",
        setOf("action", "name", "scope") to "memory",
        setOf("paths") to "sendFile",
        setOf("images") to "viewImage"
    )

    private val XML_CALL = Regex(
        "(?s)<(tool_call|function_call|invoke|tool_use)\\b([^>]*)>(.*?)</\\1>",
        RegexOption.MULTILINE
    )
    private val KV = Regex("(?s)<arg_key>\\s*([^<]+?)\\s*</arg_key>\\s*<arg_value>(.*?)</arg_value>")
    private val PARAM = Regex("(?s)<parameter\\b[^>]*name=[\"']?([^\"'\\s>]+)[\"']?[^>]*>(.*?)</parameter>")
    private val NAME_TAG = Regex("(?s)<(?:name|tool_name)>\\s*([^<]+?)\\s*</(?:name|tool_name)>")
    private val FENCE = Regex("(?s)```(?:json)?\\s*(\\{[\\s\\S]*?\\}|\\[[\\s\\S]*?\\])\\s*```")

    fun parse(text: String): Result {
        if (text.isBlank()) return Result(text, emptyList())
        val calls = ArrayList<ParsedCall>()
        var clean: String = text

        val xmlResult = XML_CALL.replace(clean) { m: MatchResult ->
            val attrs: String = m.groupValues[2]
            val body: String = m.groupValues[3]
            val parsed: ParsedCall? = parseXmlCall(attrs, body, m.value)
            if (parsed != null) {
                calls.add(parsed)
                ""
            } else {
                m.value
            }
        }
        clean = xmlResult

        if (calls.isEmpty()) {
            clean = FENCE.replace(clean) { m: MatchResult ->
                val parsed: List<ParsedCall> = parseJsonCallBlock(m.groupValues[1])
                if (parsed.isNotEmpty()) {
                    calls.addAll(parsed)
                    ""
                } else {
                    m.value
                }
            }
        }

        return Result(clean.trim(), calls)
    }

    private fun parseXmlCall(attrs: String, body: String, raw: String): ParsedCall? {
        var name: String? = extractAttr(attrs, "name")
            ?: extractAttr(attrs, "tool")
            ?: NAME_TAG.find(body)?.groupValues?.get(1)?.trim()

        val args = LinkedHashMap<String, JsonElement>()
        var foundKv = false
        for (m: MatchResult in KV.findAll(body)) {
            foundKv = true
            args[unescape(m.groupValues[1].trim())] = coerceValue(unescape(m.groupValues[2]))
        }
        if (!foundKv) {
            for (m: MatchResult in PARAM.findAll(body)) {
                args[unescape(m.groupValues[1].trim())] = coerceValue(unescape(m.groupValues[2]).trim())
            }
        }
        if (!foundKv && args.isEmpty()) {
            val trimmed = body.trim()
            if (trimmed.startsWith("{")) {
                val obj: JsonObject? = runCatching { parserJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                obj?.forEach { (k: String, v: JsonElement) -> args[k] = v }
            }
        }
        if (args.isEmpty()) return null
        if (name.isNullOrBlank()) name = guessToolName(args.keys)
        if (name.isNullOrBlank()) return null
        return ParsedCall(name, args, raw)
    }

    private fun parseJsonCallBlock(block: String): List<ParsedCall> {
        val el: JsonElement = runCatching { parserJson.parseToJsonElement(block) }.getOrNull() ?: return emptyList()
        val objects: List<JsonObject?> = when (el) {
            is JsonArray -> el.map { it as? JsonObject }
            is JsonObject -> listOf(el)
            else -> emptyList()
        }
        val out = ArrayList<ParsedCall>()
        for (obj: JsonObject? in objects) {
            if (obj == null) continue
            val name: String? = obj["tool"]?.jsonPrimitiveOrNull()?.content
                ?: obj["name"]?.jsonPrimitiveOrNull()?.content
                ?: obj["tool_name"]?.jsonPrimitiveOrNull()?.content
            val argsObj: JsonObject? = obj["arguments"] as? JsonObject
                ?: obj["parameters"] as? JsonObject
                ?: obj["input"] as? JsonObject
            if (!name.isNullOrBlank() && argsObj != null) out.add(ParsedCall(name, argsObj, block))
        }
        return out
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun extractAttr(attrs: String, key: String): String? {
        val re = Regex("\\b" + Regex.escape(key) + "\\s*=\\s*[\"']([^\"']+)[\"']")
        return re.find(attrs)?.groupValues?.get(1)?.trim()
    }

    private fun guessToolName(keys: Collection<String>): String? {
        val keySet = keys.toSet()
        // command / cmd 是同义键，任一出现即判为 Bash；其余组合要求关键键齐全，避免误判。
        if (keySet.contains("command") || keySet.contains("cmd")) return "Bash"
        for (entry: Pair<Set<String>, String> in ARG_KEY_TO_TOOL) {
            if (entry.first.all { it in keySet }) return entry.second
        }
        return null
    }

    /**
     * 把文本参数值还原为合适的 [JsonElement]：
     * - 合法 JSON 数组（如 `["/workspace/a.txt"]`）升级为 [JsonArray]，使 tools 的 `as? JsonArray` 直接可用；
     * - 其余（含自由格式 `[a, b]`、普通字符串）保留为 [JsonPrimitive]，由工具的 stringArray 兜底。
     * 宁可保守也误伤正文：仅当整段能被解析为 JSON 数组时才升级。
     */
    private fun coerceValue(raw: String): JsonElement {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.tryParseJsonArray()?.let { return it }
        }
        return JsonPrimitive(raw)
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
