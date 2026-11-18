package com.minime.code.feature.agent.domain.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 工具参数读取的兼容层。
 *
 * 背景：结构化 function-calling 下，数组参数（paths/names/images/args...）是标准 [JsonArray]；
 * 但文本形态工具调用（讯飞 spark、部分兼容端点）经 ToolCallTextParser 还原后，参数常被包成
 * [JsonPrimitive] 字符串，内容可能是：
 * - 合法 JSON 数组：`["/workspace/a.txt", "/workspace/b.txt"]`
 * - 不带引号的方括号：`[/workspace/a.txt, /workspace/b.txt]`（模型自由格式）
 * - 单个路径：`/workspace/a.txt`
 * - 逗号分隔：`a.txt, b.txt`
 *
 * 若工具直接 `args["paths"] as? JsonArray` 会取到 null，误报「缺少 paths」——明明卡片指令里
 * 已经显示了参数。这里统一归一化，App 本地链路与云端 agent-server 共用，消除双份实现。
 */

/** 从参数表中稳健读取字符串数组，兼容上述所有形态；返回空列表表示确实 lacked。 */
fun Map<String, JsonElement>.stringArray(key: String): List<String> =
    this[key]?.asStringList().orEmpty()

/** 从单个 [JsonElement] 归一化为字符串列表。 */
fun JsonElement.asStringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { it.asPrimitiveContent() }.map { it.trim() }.filter { it.isNotBlank() }
    is JsonPrimitive -> content.asDelimitedList()
    else -> emptyList()
}

private fun JsonElement.asPrimitiveContent(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    else -> null
}

/** 把字符串形态的数组/单值解析为列表。 */
private fun String.asDelimitedList(): List<String> {
    val raw = trim()
    if (raw.isEmpty()) return emptyList()
    // 去外层方括号（可能是不带引号的自由格式，不能依赖 Json 解析）
    val inner = if (raw.startsWith("[") && raw.endsWith("]")) {
        raw.substring(1, raw.length - 1)
    } else raw
    return inner.split(',')
        .map { it.trim().trim('"', '\'') }
        .filter { it.isNotBlank() }
}

/**
 * 尝试把字符串升级为 JsonArray：内容本身是合法 JSON 数组时返回之，否则返回 null。
 * 供 ToolCallTextParser 在还原文本调用时使用——让合法的 `["a","b"]` 从源头就是数组，
 * 同时保留自由格式字符串交由 [asStringList] 兜底。
 */
fun String.tryParseJsonArray(): JsonArray? = runCatching {
    val el = Json.parseToJsonElement(trim())
    el as? JsonArray
}.getOrNull()
