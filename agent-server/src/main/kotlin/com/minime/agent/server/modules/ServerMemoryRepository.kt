package com.minime.agent.server.modules

import com.minime.code.feature.agent.domain.memory.Memory
import com.minime.code.feature.agent.domain.memory.MemoryEdit
import com.minime.code.feature.agent.domain.memory.MemoryEditResult
import com.minime.code.feature.agent.domain.memory.MemoryParser
import com.minime.code.feature.agent.domain.memory.MemoryScope
import com.minime.code.feature.agent.domain.memory.MemorySource
import java.io.File

/**
 * 云端 memory 仓库（完全独立，与 App 端各存各的）。
 *
 * 两级目录，与 App 约定一致：
 * - 全局：`~/.minimecode/memory`（容器内 /root/.minimecode/memory）；
 * - 项目：`~/workspace/.minimecode/memory`（容器内 /workspace/.minimecode/memory）。
 *
 * 解析/格式化复用 :agent-core 的 [MemoryParser]；读写用本地 java.io.File（容器内真实路径）。
 * 提供 read/save/edit/delete/list 五个动作，与 App 端 MemoryTool 对齐。
 */
class ServerMemoryRepository(
    /** home 真实路径：生产取 HOME（容器内 /root）；单测可注入临时目录。 */
    private val homePath: String = System.getenv("HOME") ?: "/root",
    /** workspace 真实路径：生产 /workspace；单测可注入临时目录。 */
    private val workspacePath: String = "/workspace"
) {

    private val globalRoot: File by lazy { ensureDir(homePath, ".minimecode/memory") }
    private val projectRoot: File by lazy { ensureDir(workspacePath, ".minimecode/memory") }

    private fun ensureDir(base: String, sub: String): File = File(base, sub).also { it.mkdirs() }

    private fun rootFor(scope: String): File =
        if (scope.equals("project", ignoreCase = true)) projectRoot else globalRoot

    /**
     * list：按作用域返回记忆（与 App 的 global/project 两组独立列表语义一致）。
     * memory 不做"项目覆盖全局"——两者是独立视图，分别注入不同的 prompt 变量；
     * 覆盖合并只适用于 skill。
     */
    fun list(scope: String = "project"): List<Memory> {
        val (root, memScope) = if (scope.equals("global", ignoreCase = true)) {
            globalRoot to MemoryScope.GLOBAL
        } else {
            projectRoot to MemoryScope.PROJECT
        }
        return scan(root, memScope).sortedBy { it.name.lowercase() }
    }

    private fun scan(root: File, scope: MemoryScope): List<Memory> {
        if (!root.isDirectory) return emptyList()
        return (root.listFiles { f -> f.isFile && f.extension == "md" } ?: emptyArray())
            .mapNotNull { MemoryParser.parse(it, scope) }
    }

    /** read：读取指定记忆正文（项目优先）。 */
    fun read(name: String, scope: String = "project"): String? {
        val inScope = list(scope).firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (inScope != null) return inScope.content
        // 项目作用域读不到时兜底全局
        return list("global").firstOrNull { it.name.equals(name, ignoreCase = true) }?.content
    }

    /** save：创建或覆盖一条记忆。 */
    fun save(name: String, description: String, content: String, scope: String = "project"): Boolean = try {
        val root = rootFor(scope)
        root.mkdirs()
        val file = MemorySource.resolveMemoryFile(root, name)
        file.writeText(MemoryParser.format(MemorySource.sanitizeName(name), description, content))
        true
    } catch (e: Exception) {
        false
    }

    /** edit：对已有记忆正文做精确局部编辑，复用 :agent-core 的默认编辑算法。 */
    fun edit(name: String, edits: List<MemoryEdit>, scope: String = "project"): MemoryEditResult {
        val memory = list(scope).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return MemoryEditResult.NotFound(name)
        val file = memory.file ?: return MemoryEditResult.NotFound(name)
        if (edits.isEmpty()) return MemoryEditResult.Error("EMPTY_EDITS", "edits 不能为空")
        var content = memory.content
        edits.forEachIndexed { i, e ->
            if (e.oldString.isEmpty()) return MemoryEditResult.Error("EMPTY_OLD_STRING", "第 ${i + 1} 个编辑 old_string 为空")
            if (e.oldString == e.newString) return MemoryEditResult.Error("NO_OP", "第 ${i + 1} 个编辑无变化")
            val occurrences = content.split(e.oldString).size - 1
            if (occurrences == 0) return MemoryEditResult.Error("NO_MATCH", "第 ${i + 1} 个编辑未找到 old_string")
            if (occurrences > 1 && !e.replaceAll) return MemoryEditResult.Error("MULTIPLE_MATCHES", "第 ${i + 1} 个编辑匹配多处")
            content = if (e.replaceAll) content.replace(e.oldString, e.newString) else content.replaceFirst(e.oldString, e.newString)
        }
        return try {
            file.writeText(MemoryParser.format(memory.name, memory.description, content))
            MemoryEditResult.Success
        } catch (e: Exception) {
            MemoryEditResult.Error("EDIT_WRITE_FAILED", "写入失败: ${e.message}")
        }
    }

    /** delete：删除一条记忆。 */
    fun delete(name: String, scope: String = "project"): Boolean {
        val file = MemorySource.resolveMemoryFile(rootFor(scope), name)
        return if (file.exists()) file.delete() else false
    }

    /** 供 prompt 拼装的 name:description 清单。 */
    fun promptList(scope: String): List<Pair<String, String>> =
        list(scope).map { it.name to it.description }
}
