package com.minime.agent.server.modules

import com.minime.code.feature.agent.domain.model.AgentImage
import com.minime.code.feature.agent.domain.tool.AgentTool
import com.minime.code.feature.agent.domain.tool.ParameterType
import com.minime.code.feature.agent.domain.tool.ToolCapability
import com.minime.code.feature.agent.domain.tool.ToolParameter
import com.minime.code.feature.agent.domain.tool.ToolPermissionPolicy
import com.minime.code.feature.agent.domain.tool.ToolResult
import com.minime.code.feature.agent.domain.tool.stringArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端镜像工具集：补齐 App 本地 ToolRegistry 中存在、但服务端此前缺失的 7 个工具，
 * 使云端会话可用的工具集与本地完全对齐（sendFile/viewImage/generateImage/Shizuku/terminal/manageMcp/browser）。
 *
 * 每个工具的 Success.data 结构与 App 同名工具**逐字段对齐**，保证落库成 transport JSON 后
 * App 的富卡片 parse 函数能识别。平台绑定能力（WebView 交互、Shizuku 提权、Android 分享面板）
 * 在服务端以等价语义实现或明确降级，但都返回结构合法的结果，绝不返回裸文本让卡片失效。
 */

/** 与 ServerFileTools.resolve 一致的路径映射（~/workspace ↔ workspaceRoot）。 */
private fun mirrorResolve(path: String, workspaceRoot: String): File {
    val p = path.trim()
    return when {
        p == "~" || p == "/workspace" || p == "~/workspace" -> File(workspaceRoot)
        p.startsWith("~/workspace/") -> File(workspaceRoot, p.removePrefix("~/workspace/"))
        p.startsWith("/workspace/") -> File(workspaceRoot, p.removePrefix("/workspace/"))
        p.startsWith("~/") -> File(System.getProperty("user.home"), p.removePrefix("~/"))
        else -> File(p)
    }
}

private fun guessMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    "txt", "md" -> "text/plain"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}

/** sendFile：服务端把容器内文件打包成 App SendFileCard 需要的 files[] 结构；local_path 即容器内绝对路径。 */
class ServerSendFileTool(
    private val workspaceRoot: String,
    /** 单文件大小上限 MB，来自云端设置 sendfile_max_size_mb（对齐 App 默认 100）。 */
    private val maxSizeMb: Int = 100
) : AgentTool() {
    override val name = "sendFile"
    override val description = "把工作区文件发送给用户（在聊天区生成可下载/可查看的文件卡片）。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val parameters = mapOf(
        "paths" to ToolParameter("paths", ParameterType.ARRAY, "要发送的文件路径数组（容器路径）", true, itemsSchema = mapOf("type" to "string")),
        "names" to ToolParameter("names", ParameterType.ARRAY, "可选：显示名数组，与 paths 一一对应", false, itemsSchema = mapOf("type" to "string"))
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        // 兼容文本形态调用：paths 可能是 JsonArray，也可能是被包成字符串的数组/单值。
        val paths = args.stringArray("paths")
        if (paths.isEmpty()) return ToolResult.Error("缺少 paths", "MISSING_PATHS")
        val names = args.stringArray("names")
        // 原子校验（对齐 App）：文件必须存在且不超过设置上限，任一失败整体失败。
        val maxBytes = maxSizeMb.coerceAtLeast(1) * 1024L * 1024L
        val failures = mutableListOf<String>()
        val resolved = paths.map { path -> mirrorResolve(path, workspaceRoot) }
        resolved.forEachIndexed { index, file ->
            when {
                !file.isFile -> failures.add("文件不存在：${paths[index]}")
                file.length() > maxBytes -> failures.add("文件超过 ${maxSizeMb}MB 限制：${paths[index]}")
            }
        }
        if (failures.isNotEmpty()) return ToolResult.Error(failures.joinToString("；"), "INVALID_FILE")
        val files = buildJsonArray {
            resolved.forEachIndexed { index, file ->
                val path = paths[index]
                val rawName = file.name.ifBlank { path }
                val displayName = names.getOrNull(index)?.takeIf { it.isNotBlank() } ?: rawName
                val mime = guessMimeType(rawName)
                add(buildJsonObject {
                    put("path", JsonPrimitive(path.trim()))
                    put("local_path", JsonPrimitive(file.absolutePath))
                    put("name", JsonPrimitive(displayName))
                    put("mime_type", JsonPrimitive(mime))
                    put("size_bytes", JsonPrimitive(file.length()))
                    put("is_image", JsonPrimitive(mime.startsWith("image/")))
                })
            }
        }
        if (files.isEmpty()) return ToolResult.Error("没有可发送的文件（路径均不存在）", "NO_FILES")
        return ToolResult.Success(buildJsonObject {
            put("content", JsonPrimitive("已发送 ${files.size} 个文件（云端工作区）。"))
            put("files", files)
            put("file_count", JsonPrimitive(files.size))
        })
    }
}

/** viewImage：读取容器内图片，base64 编码后经 images 字段透传给 App 渲染；与 App "加载到上下文"分支同构。 */
class ServerViewImageTool(private val workspaceRoot: String) : AgentTool() {
    override val name = "viewImage"
    override val description = "查看当前执行环境中的图片（读取并附加到上下文，供模型识别）。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val parameters = mapOf(
        "images" to ToolParameter("images", ParameterType.ARRAY, "图片路径数组（容器路径）", true, itemsSchema = mapOf("type" to "string"))
    )
    private val maxBytes = 8 * 1024 * 1024

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val paths = args.stringArray("images")
        if (paths.isEmpty()) return ToolResult.Error("缺少 images", "MISSING_IMAGES")
        val encoded = mutableListOf<AgentImage>()
        val okPaths = mutableListOf<String>()
        for (path in paths) {
            val file = mirrorResolve(path, workspaceRoot)
            if (!file.isFile || file.length() > maxBytes) continue
            val mime = guessMimeType(file.name)
            if (!mime.startsWith("image/")) continue
            val b64 = Base64.getEncoder().encodeToString(file.readBytes())
            encoded.add(AgentImage(mimeType = mime, base64Data = b64, path = path.trim()))
            okPaths.add(path.trim())
        }
        if (encoded.isEmpty()) return ToolResult.Error("未找到可读取的图片（文件不存在/过大/非图片）", "NO_IMAGES")
        return ToolResult.Success(
            data = buildJsonObject {
                put("status", JsonPrimitive("loaded_to_context"))
                put("image_count", JsonPrimitive(encoded.size))
                put("content", JsonPrimitive("已附加 ${encoded.size} 张图片到上下文。"))
                put("paths", JsonArray(okPaths.map { JsonPrimitive(it) }))
            },
            images = encoded
        )
    }
}

/**
 * generateImage：调用可配置的图像生成 API（环境变量 MINIME_IMAGE_API_URL，OpenAI images 风格
 * POST {prompt,size,n} → {data:[{b64_json|url}]}）。未配置时返回结构合法的空结果 + 说明，
 * 保证 App 卡片不崩、模型能继续。
 */
class ServerGenerateImageTool(private val workspaceRoot: String) : AgentTool() {
    override val name = "generateImage"
    override val description = "根据文本描述生成图片。需服务端配置图像生成后端（MINIME_IMAGE_API_URL）。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.NETWORK_WRITE, ToolCapability.WRITE_WORKSPACE)
    override val parameters = mapOf(
        "prompt" to ToolParameter("prompt", ParameterType.STRING, "图片描述", true),
        "size" to ToolParameter("size", ParameterType.STRING, "尺寸，如 1024x1024", false),
        "n" to ToolParameter("n", ParameterType.INTEGER, "生成张数", false),
        "output_path" to ToolParameter("output_path", ParameterType.STRING, "可选：保存为工作区文件路径", false)
    )

    private val apiUrl = System.getenv("MINIME_IMAGE_API_URL")?.takeIf { it.isNotBlank() }
    private val apiKey = System.getenv("MINIME_IMAGE_API_KEY")?.takeIf { it.isNotBlank() }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少 prompt", "MISSING_PROMPT")
        val n = args["n"]?.jsonPrimitive?.intOrNull ?: 1
        val images = mutableListOf<AgentImage>()
        val filesList = mutableListOf<JsonElement>()
        var notice: String? = null

        if (apiUrl == null) {
            notice = "服务端未配置图像生成后端（MINIME_IMAGE_API_URL），本次未生成图片。"
        } else {
            val body = buildJsonObject {
                put("prompt", JsonPrimitive(prompt))
                args["size"]?.jsonPrimitive?.contentOrNull?.let { put("size", JsonPrimitive(it)) }
                put("n", JsonPrimitive(n))
            }.toString()
            val raw = runCatching {
                val conn = URL(apiUrl!!).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.connectTimeout = 10_000; conn.readTimeout = 120_000
                conn.setRequestProperty("Content-Type", "application/json")
                apiKey?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()
            val dataArr = raw?.let {
                runCatching { Json.parseToJsonElement(it).jsonObject["data"] as? JsonArray }.getOrNull()
            } ?: JsonArray(emptyList())
            dataArr.forEachIndexed { idx, el ->
                val o = el as? JsonObject ?: return@forEachIndexed
                val b64 = o["b64_json"]?.jsonPrimitive?.contentOrNull
                val url = o["url"]?.jsonPrimitive?.contentOrNull
                val bytes = when {
                    b64 != null -> runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
                    url != null -> runCatching { URL(url).readBytes() }.getOrNull()
                    else -> null
                } ?: return@forEachIndexed
                val name = "generated-${System.currentTimeMillis()}-$idx.png"
                val outPath = args["output_path"]?.jsonPrimitive?.contentOrNull
                val savedPath = if (outPath != null) {
                    val f = mirrorResolve(outPath, workspaceRoot)
                    f.parentFile?.mkdirs(); runCatching { f.writeBytes(bytes) }; f.absolutePath
                } else name
                images.add(AgentImage(mimeType = "image/png", base64Data = Base64.getEncoder().encodeToString(bytes), path = savedPath))
                filesList.add(buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("path", JsonPrimitive(savedPath))
                    put("mime_type", JsonPrimitive("image/png"))
                    put("size_bytes", JsonPrimitive(bytes.size))
                })
            }
            if (images.isEmpty()) notice = "图像生成后端未返回可用图片，请检查配置与额度。"
        }

        return ToolResult.Success(
            data = buildJsonObject {
                put("status", JsonPrimitive(if (images.isEmpty()) "failed" else "generated"))
                put("content", JsonPrimitive(notice ?: "已生成 ${images.size} 张图片。"))
                put("image_count", JsonPrimitive(images.size))
                put("failed_count", JsonPrimitive(if (notice != null) n else 0))
                put("model", JsonPrimitive("server-image-api"))
                put("requested_count", JsonPrimitive(n))
                put("files", JsonArray(filesList))
                notice?.let { put("notice", JsonPrimitive(it)) }
            },
            images = images
        )
    }
}

/**
 * Shizuku：App 端用于在宿主 Android 上提权执行 pm/am/cmd；服务端进程本身即运行在目标环境
 * （容器/服务器），等价实现为直接在服务进程本地执行 shell。产出与 App 一致的纯文本 data。
 */
class ServerShizukuTool(private val workspaceRoot: String) : AgentTool() {
    override val name = "Shizuku"
    override val description = "在服务进程所在环境执行系统 shell 命令（服务端等价 Shizuku 提权执行）。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)
    override val parameters = mapOf(
        "command" to ToolParameter("command", ParameterType.STRING, "要执行的 shell 命令", true),
        "timeout" to ToolParameter("timeout", ParameterType.INTEGER, "超时秒数", false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少 command", "MISSING_COMMAND")
        val timeoutSec = (args["timeout"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 300)
        return try {
            val pb = ProcessBuilder("/bin/bash", "-lc", command)
            val wd = File(workspaceRoot); if (wd.isDirectory) pb.directory(wd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader(Charsets.UTF_8).use { readWithTimeout(it, timeoutSec * 1000L) }
            val alive = proc.isAlive
            if (alive) { proc.destroyForcibly(); proc.waitFor() }
            val exit = if (alive) null else proc.exitValue()
            val text = if (exit == null || exit == 0) output else "exit $exit\n$output"
            ToolResult.Success(JsonPrimitive(text.take(20_000)))
        } catch (e: Exception) {
            ToolResult.Error("执行失败: ${e.message}", "SHIZUKU_ERROR")
        }
    }

    /** 简易带超时读取：另起线程读满，超时返回已读部分并中断。 */
    private fun readWithTimeout(reader: java.io.BufferedReader, timeoutMs: Long): String {
        val result = StringBuilder()
        val t = Thread {
            try { var line: String?; while (reader.readLine().also { line = it } != null) { result.append(line).append('\n') } } catch (_: Exception) {}
        }
        t.isDaemon = true; t.start(); t.join(timeoutMs)
        if (t.isAlive) t.interrupt()
        return result.toString()
    }
}

/** 服务端终端会话：tabId → 后台进程 + 输出缓冲，实现 start/send/read/close 的等价语义。 */
class ServerTerminalTool(
    private val workspaceRoot: String,
    /** 进程生命周期管理器：登记后台进程，使其在过期后被自动回收（持久化例外）。 */
    private val lifecycle: ProcessLifecycleManager? = null,
    /** 端口池：start 时把命令中已分配的端口绑定到 tab id，进程退出后自动归还。 */
    private val portPool: com.minime.agent.server.modules.PortPoolManager? = null
) : AgentTool() {
    override val name = "terminal"
    override val description = "在服务端环境启动/管理后台终端会话，适合耗时任务与常驻服务。支持 start/send/read/close。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "start/send/read/close", true, enum = listOf("start", "send", "read", "close")),
        "command" to ToolParameter("command", ParameterType.STRING, "start 必填：启动命令", false),
        "title" to ToolParameter("title", ParameterType.STRING, "标签显示名", false),
        "tab_id" to ToolParameter("tab_id", ParameterType.STRING, "目标标签 id", false),
        "tab_ids" to ToolParameter("tab_ids", ParameterType.ARRAY, "close 批量关闭的 id 数组", false, itemsSchema = mapOf("type" to "string")),
        "filter" to ToolParameter("filter", ParameterType.STRING, "read 按正则表达式筛选输出行", false),
        "input" to ToolParameter("input", ParameterType.STRING, "send 要发送的输入", false)
    )

    private data class Session(val process: Process, val output: StringBuilder, val title: String, val writer: java.io.OutputStream)
    private val sessions = ConcurrentHashMap<String, Session>()
    private var seq = 0

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "read"
        return when (action) {
            "start" -> {
                val command = args["command"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return ToolResult.Error("start 缺少 command", "MISSING_COMMAND")
                val id = "term-${++seq}"
                return try {
                    val pb = ProcessBuilder("/bin/bash", "-lc", command)
                    val wd = File(workspaceRoot); if (wd.isDirectory) pb.directory(wd)
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    val buf = StringBuilder()
                    val reader = Thread {
                        try { var line: String?; while (proc.inputStream.bufferedReader(Charsets.UTF_8).readLine().also { line = it } != null) { synchronized(buf) { buf.append(line).append('\n') } } } catch (_: Exception) {}
                    }
                    reader.isDaemon = true; reader.start()
                    sessions[id] = Session(proc, buf, args["title"]?.jsonPrimitive?.contentOrNull ?: id, proc.outputStream)
                    // 登记到生命周期管理器：按命令分类 TTL（编译长、服务更长、普通短），到期自动回收。
                    lifecycle?.register(
                        id = id, pid = proc.pid(), command = command,
                        label = args["title"]?.jsonPrimitive?.contentOrNull ?: id,
                        kind = ProcessLifecycleManager.KIND_TERMINAL, process = proc
                    )
                    // 把命令里出现的、已分配的端口绑定到该终端进程；进程退出/回收时按 owner 自动归还。
                    val boundPorts = portPool?.bindPortsInCommand(command, id).orEmpty()
                    Thread.sleep(300)
                    ToolResult.Success(buildJsonObject {
                        put("tab_id", JsonPrimitive(id))
                        put("running", JsonPrimitive(proc.isAlive))
                        put("output", JsonPrimitive(snapshot(buf)))
                        if (boundPorts.isNotEmpty()) {
                            put("bound_ports", JsonArray(boundPorts.map { JsonPrimitive(it) }))
                            put("port_note", JsonPrimitive("这些端口已绑定到本进程，进程退出后自动归还端口池。"))
                        }
                    })
                } catch (e: Exception) {
                    ToolResult.Error("启动失败: ${e.message}", "TERM_START_ERROR")
                }
            }
            "read" -> {
                val id = args["tab_id"]?.jsonPrimitive?.contentOrNull
                if (id == null) {
                    val tabs = buildJsonArray { sessions.forEach { (k, v) -> add(buildJsonObject { put("tab_id", JsonPrimitive(k)); put("title", JsonPrimitive(v.title)); put("running", JsonPrimitive(v.process.isAlive)) }) } }
                    return ToolResult.Success(buildJsonObject { put("tabs", tabs); put("content", JsonPrimitive("当前 ${sessions.size} 个终端标签。")) })
                }
                val s = sessions[id] ?: return ToolResult.Error("终端标签不存在: $id", "TERM_NOT_FOUND")
                lifecycle?.heartbeat(id) // 正在读取视为活跃，续期避免误杀
                var text = snapshot(s.output)
                lifecycle?.noteOutput(id, text.length.toLong())
                args["filter"]?.jsonPrimitive?.contentOrNull?.let { pattern ->
                    text = runCatching { Regex(pattern).findAll(text).joinToString("\n") { it.value } }.getOrDefault(text)
                }
                ToolResult.Success(buildJsonObject { put("tab_id", JsonPrimitive(id)); put("running", JsonPrimitive(s.process.isAlive)); put("output", JsonPrimitive(text)) })
            }
            "send" -> {
                val id = args["tab_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("send 缺少 tab_id", "MISSING_TAB")
                val s = sessions[id] ?: return ToolResult.Error("终端标签不存在: $id", "TERM_NOT_FOUND")
                val input = args["input"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("send 缺少 input", "MISSING_INPUT")
                try { s.writer.write((input + "\n").toByteArray()); s.writer.flush() } catch (e: Exception) { return ToolResult.Error("发送失败: ${e.message}", "TERM_SEND_ERROR") }
                lifecycle?.heartbeat(id)
                Thread.sleep(200)
                ToolResult.Success(buildJsonObject { put("tab_id", JsonPrimitive(id)); put("running", JsonPrimitive(s.process.isAlive)); put("output", JsonPrimitive(snapshot(s.output))) })
            }
            "close" -> {
                val ids = mutableSetOf<String>()
                args["tab_id"]?.jsonPrimitive?.contentOrNull?.let { ids.add(it) }
                (args["tab_ids"] as? JsonArray)?.forEach { ids.add(it.jsonPrimitive.contentOrNull ?: return@forEach) }
                ids.forEach { id ->
                    sessions.remove(id)?.let { s -> s.process.destroyForcibly() }
                    lifecycle?.unregister(id)
                }
                ToolResult.Success(JsonPrimitive("已关闭终端标签 ${ids.joinToString(", ")}。"))
            }
            else -> ToolResult.Error("不支持的 action: $action", "UNKNOWN_ACTION")
        }
    }

    private fun snapshot(buf: StringBuilder) = synchronized(buf) { buf.toString().take(20_000) }
}

/** manageMcp：服务端在 ~/.minimecode/agent/mcp.json 维护 MCP 配置，list/add_stdio/add_http/remove。 */
class ServerManageMcpTool : AgentTool() {
    override val name = "manageMcp"
    override val description = "管理服务端 MCP 服务器配置（list/add_stdio/add_http/remove）。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_AGENT_CONFIG)
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "list/add_stdio/add_http/remove", true, enum = listOf("list", "add_stdio", "add_http", "remove")),
        "server_name" to ToolParameter("server_name", ParameterType.STRING, "服务器名称", false),
        "command" to ToolParameter("command", ParameterType.STRING, "stdio 执行命令", false),
        "args" to ToolParameter("args", ParameterType.ARRAY, "命令参数", false, itemsSchema = mapOf("type" to "string")),
        "url" to ToolParameter("url", ParameterType.STRING, "HTTP 服务地址", false)
    )

    private val configFile: File
        get() = File(System.getProperty("user.home"), ".minimecode/agent/mcp.json")

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
        val servers = load()
        return when (action) {
            "list" -> ToolResult.Success(JsonPrimitive(Json.encodeToString(servers).take(20_000)))
            "add_stdio", "add_http" -> {
                val name = args["server_name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return ToolResult.Error("缺少 server_name", "MISSING_NAME")
                if (!name.matches(Regex("[A-Za-z0-9_-]+"))) return ToolResult.Error("server_name 只能含字母数字下划线连字符", "BAD_NAME")
                val obj = buildJsonObject {
                    if (action == "add_stdio") {
                        put("command", JsonPrimitive(args["command"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                        (args["args"] as? JsonArray)?.let { put("args", it) }
                    } else {
                        put("url", JsonPrimitive(args["url"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                    }
                }
                val mutable = servers.toMutableMap(); mutable[name] = obj; save(mutable)
                ToolResult.Success(JsonPrimitive("已添加 MCP server：$name（下一次会话生效）"))
            }
            "remove" -> {
                val name = args["server_name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return ToolResult.Error("缺少 server_name", "MISSING_NAME")
                val mutable = servers.toMutableMap()
                if (mutable.remove(name) == null) return ToolResult.Error("MCP server 不存在: $name", "NOT_FOUND")
                save(mutable)
                ToolResult.Success(JsonPrimitive("已移除 MCP server：$name"))
            }
            else -> ToolResult.Error("不支持的 action: $action", "UNKNOWN_ACTION")
        }
    }

    private fun load(): Map<String, JsonElement> {
        val f = configFile; if (!f.isFile) return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(f.readText()).jsonObject
            root["servers"]?.jsonObject?.entries?.associate { it.key to it.value } ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun save(servers: Map<String, JsonElement>) {
        configFile.parentFile?.mkdirs()
        val root = buildJsonObject { put("servers", JsonObject(servers)) }
        configFile.writeText(Json.encodeToString(root))
    }
}

/**
 * browser：通过 [BrowserDriver] 访问网页（navigate/getText/getHtml/listTabs/closeTab）。
 * 底层驱动由构造参数注入——远程 Playwright 微服务（带真实登录态）或本地只读降级，
 * 工具本身不关心差异。当驱动报告命中登录墙时，返回 [BrowserLoginSignal] 特殊结果，
 * 由 AgentRunner 识别并发 LOGIN_REQUIRED 事件挂起，等用户在手机登录后续跑。
 * data 结构与 App envelope 对齐 {ok,action,tabId,url,title,detail,error}。
 *
 * @param profileId 登录态隔离键（按用户×执行目标），同键共享一个浏览器 profile。
 */
class ServerBrowserTool(
    private val driver: BrowserDriver,
    private val profileId: String = "default"
) : AgentTool() {
    override val name = "browser"
    override val description = "在服务端访问网页（navigate/getText/getHtml/closeTab/listTabs）。遇到需要登录的站点会请求用户在手机登录。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE)
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "navigate/getText/getHtml/listTabs/closeTab", true),
        "url" to ToolParameter("url", ParameterType.STRING, "navigate 的目标 URL", false),
        "tabId" to ToolParameter("tabId", ParameterType.STRING, "标签 id", false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "listTabs"
        return when (action) {
            "listTabs" -> {
                val arr = buildJsonArray { driver.listTabs().forEach { add(buildJsonObject { put("tabId", JsonPrimitive(it.tabId)); put("url", JsonPrimitive(it.url)); put("title", JsonPrimitive(it.title)) }) } }
                ok(action, buildJsonObject { put("tabs", arr); put("count", JsonPrimitive(arr.size)) })
            }
            "navigate", "getText", "getHtml" -> {
                val tabId = args["tabId"]?.jsonPrimitive?.contentOrNull
                val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim()
                if (url == null && tabId == null) return ToolResult.Error("缺少 url", "MISSING_URL")
                val nav = try {
                    if (url != null) driver.navigate(url, tabId, profileId)
                    else {
                        // 无 url 的 getText/getHtml：从已存标签重取 html，构造一个无登录墙的 nav
                        val html = driver.getHtml(tabId!!)
                        BrowserNavigation(tabId, "", "", html, false)
                    }
                } catch (e: BrowserUnavailableException) {
                    return ToolResult.Error("浏览器不可用: ${e.message}", "BROWSER_UNAVAILABLE")
                }
                // 命中登录墙：产出挂起信号，AgentRunner 接管（不返回普通结果给模型）
                if (nav.needsLogin) {
                    return BrowserLoginSignal.create(newLoginId(), nav.url, "访问 ${nav.url} 需要登录")
                }
                val detail = when (action) {
                    "getText" -> buildJsonObject {
                        put("text", JsonPrimitive(nav.html
                            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(100_000)))
                    }
                    else -> buildJsonObject { put("html", JsonPrimitive(nav.html)) }
                }
                ok(action, detail, nav.tabId, nav.url, nav.title)
            }
            "closeTab" -> {
                val tabId = args["tabId"]?.jsonPrimitive?.contentOrNull
                val closed = if (tabId != null) driver.closeTab(tabId) else false
                ok(action, buildJsonObject { put("closed", JsonPrimitive(closed)) }, tabId)
            }
            else -> ok(action, buildJsonObject {
                put("supported", JsonPrimitive(false))
                put("notice", JsonPrimitive("action=$action 不支持；可用 navigate/getText/getHtml/listTabs/closeTab。"))
            })
        }
    }

    private fun ok(
        action: String, detail: JsonObject, tabId: String? = null,
        url: String? = null, title: String? = null
    ): ToolResult = ToolResult.Success(buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("action", JsonPrimitive(action))
        put("tabId", JsonPrimitive(tabId ?: "tab-1"))
        put("url", JsonPrimitive(url ?: ""))
        put("title", JsonPrimitive(title ?: ""))
        put("detail", detail)
        put("error", JsonNull)
    })
}
