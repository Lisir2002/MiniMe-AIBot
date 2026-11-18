package com.minime.agent.server.modules

import java.io.File

/**
 * 云端提示词片段来源，优先级从高到低：PROJECT > GLOBAL > LOCAL > BUILTIN。
 * 与 App 端 PromptFragmentSource 一一对应，保证同一套用户编辑在云端语义一致。
 */
enum class ServerPromptFragmentSource { PROJECT, GLOBAL, LOCAL, BUILTIN }

/** 一个编号最终生效的片段（云端版，字段对齐 App 的 PromptFragment）。 */
data class ServerPromptFragment(
    val number: Int,
    val title: String,
    val source: ServerPromptFragmentSource,
    val content: String,
    val file: File?,
    val description: String
) {
    val body: String get() = content.replace(LEADING_COMMENT, "")
    val editable: Boolean
        get() = source == ServerPromptFragmentSource.PROJECT || source == ServerPromptFragmentSource.GLOBAL

    companion object {
        private val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        fun composeContent(description: String, body: String): String =
            if (description.isBlank()) body else "<!-- ${description.trim().replace("-->", "-- >")} -->\n$body"
    }
}

/**
 * 服务端提示词片段四级解析（纯文件系统逻辑，模板从 fat jar classpath 读取）。
 *
 * - BUILTIN：classpath `/prompts/<NN>-*.md`（随 fat jar 发布，容器无需额外挂载）；
 * - LOCAL：`~/.minimecode/prompts`（内置释放副本，云端默认与 BUILTIN 同源，保留层级以兼容手工放置）；
 * - GLOBAL：`~/.minimecode/prompts.custom`（容器全局覆盖）；
 * - PROJECT：`<workspaceRoot>/.minimecode/prompts.custom`（工作区覆盖，优先级最高）。
 *
 * 编号即身份（文件名前两位数字），决定注入顺序；同编号只生效最高优先级层。
 */
class ServerPromptFragmentCatalog(
    private val homePath: String = System.getenv("HOME") ?: "/root",
    private val workspacePath: String = "/workspace"
) {
    private fun globalDir(): File = File(homePath, ".minimecode/$CUSTOM_DIR")
    private val localDir: File get() = File(homePath, ".minimecode/$LOCAL_DIR")
    private fun projectDir(): File = File(workspacePath, ".minimecode/$CUSTOM_DIR")

    /** 有工作区写项目层，否则写全局层。 */
    fun writableDir(): File = projectDir()

    fun list(): List<ServerPromptFragment> {
        val builtin = builtinFiles()
        val project = numberedFragments(projectDir())
        val global = numberedFragments(globalDir())
        val numbers = LinkedHashSet<Int>().apply {
            addAll(builtin.keys); addAll(project.keys); addAll(global.keys)
        }
        val all = numbers.sorted().mapNotNull { resolve(it, builtin, project, global) }
        return if (isBuiltinDisabled()) {
            all.filter { it.source == ServerPromptFragmentSource.PROJECT || it.source == ServerPromptFragmentSource.GLOBAL }
        } else all
    }

    fun fragment(number: Int): ServerPromptFragment? = resolve(
        number, builtinFiles(), numberedFragments(projectDir()), numberedFragments(globalDir())
    )

    /** 静态基线正文：所有生效片段去前导注释后按编号拼接。 */
    fun renderStatic(): String = list()
        .mapNotNull { it.body.trim().takeIf { text -> text.isNotEmpty() } }
        .joinToString("\n\n")

    /** 仅项目/全局自定义片段正文（.no-builtin 模式）。 */
    fun renderCustomOnly(): String = list()
        .filter { it.source == ServerPromptFragmentSource.PROJECT || it.source == ServerPromptFragmentSource.GLOBAL }
        .mapNotNull { it.body.trim().takeIf { text -> text.isNotEmpty() } }
        .joinToString("\n\n")

    fun isBuiltinDisabled(): Boolean = File(globalDir(), DISABLE_BUILTIN_FILE).isFile

    /**
     * 解析单个具名片段（顶层 `<NN>-*.md` 或 agent/ 叶子），供变量块/子代理片段复用。
     * 优先级：项目 custom 数字覆盖 → 项目 custom 同名 → 全局 custom → local 副本 → classpath builtin。
     */
    fun resolvePrompt(name: String): String {
        parseNumber(name)?.let { number -> numberedFragments(projectDir())[number]?.let { return readText(it) ?: "" } }
        parseNumber(name)?.let { number -> numberedFragments(globalDir())[number]?.let { return readText(it) ?: "" } }
        readFileOrNull(File(projectDir(), name))?.let { return it }
        readFileOrNull(File(globalDir(), name))?.let { return it }
        readFileOrNull(File(localDir, name))?.let { return it }
        return readClasspath("$ASSET_DIR/$name").orEmpty()
    }

    /** 保存某编号覆盖。有工作区写项目层。 */
    fun saveOverride(number: Int, title: String, content: String, target: ServerPromptFragmentSource? = null): Boolean {
        val dir = when (target) {
            ServerPromptFragmentSource.GLOBAL -> globalDir()
            else -> projectDir()
        }
        return try {
            if (!dir.exists()) dir.mkdirs()
            deleteOverridesFor(number, dir)
            File(dir, "%02d-%s.md".format(number, sanitizeTitle(title))).writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 删除某编号覆盖（仅当生效层可写）。 */
    fun deleteOverride(number: Int): Boolean {
        val file = fragment(number)?.takeIf { it.editable }?.file ?: return false
        return file.delete()
    }

    /** 清空项目层与全局层的全部编号片段，回到内置默认。 */
    fun deleteAllCustom(): Int {
        var deleted = 0
        listOf(projectDir(), globalDir()).distinctBy { it.absolutePath }.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.isFile && parseNumber(file.name) != null && file.delete()) deleted++
            }
        }
        return deleted
    }

    fun setBuiltinDisabled(disabled: Boolean): Boolean = try {
        val marker = File(globalDir(), DISABLE_BUILTIN_FILE)
        if (disabled) {
            globalDir().mkdirs(); marker.writeText("")
        } else if (marker.isFile) marker.delete()
        true
    } catch (e: Exception) {
        false
    }

    private fun resolve(
        number: Int,
        builtin: Map<Int, String>,
        project: Map<Int, File>,
        global: Map<Int, File>
    ): ServerPromptFragment? {
        project[number]?.let { return readFragment(it, number, ServerPromptFragmentSource.PROJECT) }
        global[number]?.let { return readFragment(it, number, ServerPromptFragmentSource.GLOBAL) }
        builtin[number]?.let { name ->
            File(localDir, name).takeIf { it.isFile }?.let { return readFragment(it, number, ServerPromptFragmentSource.LOCAL) }
            readClasspath("$ASSET_DIR/$name")?.let { content ->
                return ServerPromptFragment(number, titleOf(name), ServerPromptFragmentSource.BUILTIN, content, null, extractDescription(content))
            }
        }
        return null
    }

    private fun readFragment(file: File, number: Int, source: ServerPromptFragmentSource): ServerPromptFragment? =
        readText(file)?.let { ServerPromptFragment(number, titleOf(file.name), source, it, file, extractDescription(it)) }

    /** classpath `/prompts` 顶层带编号 md：编号 → 文件名。 */
    private fun builtinFiles(): Map<Int, String> {
        val names = classpathListing(ASSET_DIR).filter { it.endsWith(".md") }.sorted()
        val byNumber = LinkedHashMap<Int, String>()
        names.forEach { parseNumber(it)?.let { n -> byNumber.putIfAbsent(n, it) } }
        return byNumber
    }

    private fun numberedFragments(dir: File): Map<Int, File> {
        val files = dir.listFiles() ?: return emptyMap()
        val byNumber = LinkedHashMap<Int, File>()
        files.filter { it.isFile }.sortedBy { it.name }.forEach { file ->
            parseNumber(file.name)?.let { byNumber.putIfAbsent(it, file) }
        }
        return byNumber
    }

    private fun deleteOverridesFor(number: Int, dir: File) {
        dir.listFiles { f -> f.isFile && parseNumber(f.name) == number }?.forEach { it.delete() }
    }

    private fun readClasspath(path: String): String? = runCatching {
        javaClass.classLoader.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

    /** 列出 classpath 目录下直接子文件名（jar 内目录条目）。 */
    private fun classpathListing(dir: String): List<String> = runCatching {
        val res = javaClass.classLoader.getResource(dir) ?: return emptyList()
        when (res.protocol) {
            "jar" -> {
                val jarPath = res.path.substringBefore("!")
                java.util.jar.JarFile(jarPath).use { jar ->
                    val prefix = "$dir/"
                    jar.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith(prefix) && it != prefix }
                        .map { it.removePrefix(prefix) }
                        .filter { it.isNotEmpty() && !it.contains('/') }
                        .toList()
                }
            }
            else -> File(res.toURI()).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
        }
    }.getOrDefault(emptyList())

    private fun readText(file: File): String? = runCatching { file.readText() }.getOrNull()
    private fun readFileOrNull(file: File): String? = file.takeIf { it.isFile }?.let { readText(it) }
    private fun parseNumber(fileName: String): Int? = NUMBERED.matchEntire(fileName)?.groupValues?.get(1)?.toInt()
    private fun titleOf(fileName: String): String {
        val base = fileName.removeSuffix(".md")
        return base.substringAfter('-', missingDelimiterValue = base)
    }
    private fun extractDescription(content: String): String =
        DESCRIPTION.find(content)?.groupValues?.get(1)?.trim().orEmpty()
    private fun sanitizeTitle(title: String): String =
        title.trim().replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "-").take(40).ifBlank { "fragment" }

    companion object {
        const val DISABLE_BUILTIN_FILE = ".no-builtin"
        private const val ASSET_DIR = "prompts"
        private const val CUSTOM_DIR = "prompts.custom"
        private const val LOCAL_DIR = "prompts"
        private val NUMBERED = Regex("""^(\d{2})-(.+)\.md$""")
        private val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        private val DESCRIPTION = Regex("^\\s*<!--\\s*(.*?)\\s*-->", RegexOption.DOT_MATCHES_ALL)
    }
}
