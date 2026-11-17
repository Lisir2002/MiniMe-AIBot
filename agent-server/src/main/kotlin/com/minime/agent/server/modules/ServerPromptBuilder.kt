package com.minime.agent.server.modules

/**
 * 服务端 system prompt 组装（一期极简版）。
 *
 * 基线说明 + 可用技能清单 + 记忆清单，格式与 App 端 SystemPromptProvider 的
 * ActiveSkillsSource / MemoryListSource 对齐（`- name: description`），保证 AI 行为一致。
 * 清单每轮调用时现取（一期不做 KV 缓存优化，先求正确）。
 *
 * 完整 prompt 引擎（19 个模板 / 四级覆盖 / 变量注入）留二期。
 */
class ServerPromptBuilder(
    private val skillRepository: ServerSkillRepository,
    private val memoryRepository: ServerMemoryRepository
) {
    companion object {
        private const val BASELINE = """You are MiniMe, an AI coding assistant running in a cloud workspace.
You have a Bash tool to inspect and modify the project, plus tools to load skills and manage long-term memory.
Work autonomously and be concise. When a listed skill matches the task, call loadSkill to read its full instructions and follow them.
When you learn durable facts (user preferences, project conventions, architecture decisions), save them with the memory tool."""
    }

    fun build(): String = buildString {
        append(BASELINE)
        appendLine()

        val skills = skillRepository.listSkills()
        if (skills.isNotEmpty()) {
            appendLine()
            appendLine("## Available skills (call loadSkill to read full instructions)")
            skills.forEach { appendLine("- ${it.name}: ${it.description.ifBlank { "（无描述）" }}") }
        }

        val globalMem = memoryRepository.promptList("global")
        val projectMem = memoryRepository.promptList("project")
        if (globalMem.isNotEmpty()) {
            appendLine()
            appendLine("## Long-term memory (global) — call memory read to load full content")
            globalMem.forEach { appendLine("- ${it.first}: ${it.second}") }
        }
        if (projectMem.isNotEmpty()) {
            appendLine()
            appendLine("## Project memory — call memory read to load full content")
            projectMem.forEach { appendLine("- ${it.first}: ${it.second}") }
        }
    }
}
