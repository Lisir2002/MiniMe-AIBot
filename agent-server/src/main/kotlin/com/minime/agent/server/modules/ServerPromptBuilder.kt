package com.minime.agent.server.modules

import com.minime.code.feature.agent.domain.model.AgentContext
import com.minime.code.feature.agent.domain.model.GoalStatus
import java.io.File

/**
 * 云端 system prompt 组装器：与 App 端 SystemPromptProvider 行为对齐。
 *
 * 组成：
 * 1. 静态基线 = 19 个编号模板经四级覆盖（PROJECT/GLOBAL/LOCAL/BUILTIN）拼装；
 * 2. 运行期变量 {{MINIMECODE_*}} 就地注入：技能清单、记忆清单、子代理清单、
 *    项目规则（AGENTS.md/CLAUDE.md）、工作区、运行环境、日期、目标块；
 * 3. `.no-builtin` 标记生效时只输出自定义片段，动态内容仍经变量取回。
 *
 * 模板随 agent-server fat jar 的 classpath `/prompts` 发布（19 个 md），
 * 用户在容器 `~/.minimecode/prompts.custom` 或 `<workspace>/.minimecode/prompts.custom`
 * 放置同名 `<NN>-*.md` 即可覆盖，编辑下一轮立即生效。
 */
class ServerPromptBuilder(
    private val skillRepository: ServerSkillRepository,
    private val memoryRepository: ServerMemoryRepository,
    private val catalog: ServerPromptFragmentCatalog = ServerPromptFragmentCatalog(),
    private val homePath: String = System.getenv("HOME") ?: "/root"
) {
    /** 兼容旧调用：无上下文时用最小默认 AgentContext。 */
    fun build(): String = build(
        AgentContext(currentFile = null, selectedCode = null, projectRoot = "/workspace", language = null)
    )

    fun build(ctx: AgentContext): String {
        // 1. 取基线（正常四级拼装 / .no-builtin 仅自定义）
        val rawStatic = if (catalog.isBuiltinDisabled()) catalog.renderCustomOnly() else catalog.renderStatic()

        // 2. 取各 Source 快照
        val skills = skillsList()
        val subAgents = subAgentList()
        val memGlobal = memoryList("global")
        val memProject = memoryList("project")
        val rules = projectRules(ctx.projectRoot)
        val workspace = if (ctx.projectRoot.isNotBlank()) "~/workspace" else "（未选择工作区）"
        val environment = "云端容器（agent-server）"
        val date = currentDate()
        val goal = renderGoalBlock(ctx)

        // 3. 变量就地展开
        val rendered = renderVariables(
            rawStatic, skills, memGlobal, memProject, subAgents,
            rules?.global, rules?.project, workspace, environment, date, goal = goal
        )
        return collapseBlankLines(rendered)
    }

    /** 供 banBuiltin 且无模板时的兜底：一段精简但完整的云端基线，确保工具清单不丢。 */
    fun fallbackBaseline(): String = FALLBACK_BASELINE

    private fun skillsList(): String? {
        val skills = runCatching { skillRepository.listSkills() }.getOrNull().orEmpty()
        if (skills.isEmpty()) return null
        return skills.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "（无描述）" }}" }
    }

    /** 云端子代理清单：一期容器未内置子代理定义仓库，留空由变量折叠为空行。 */
    private fun subAgentList(): String? = null

    private fun memoryList(scope: String): String? {
        val list = runCatching { memoryRepository.list(scope) }.getOrNull().orEmpty()
        if (list.isEmpty()) return null
        return list.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "无" }}" }
    }

    private data class Rules(val global: String?, val project: String?)

    /** 全局（~/.minimecode）与工作区规则：各自 AGENTS.md 优先、CLAUDE.md 兜底，超长截断。 */
    private fun projectRules(projectRoot: String): Rules? {
        val global = resolveRuleFile(File(homePath, ".minimecode"))
        val project = projectRoot.takeIf { it.isNotBlank() }?.let { resolveRuleFile(File(it)) }
        if (global == null && project == null) return null
        return Rules(
            global = global?.let { readRule(it) },
            project = project?.let { readRule(it) }
        )
    }

    private fun resolveRuleFile(dir: File): File? {
        val agents = File(dir, AGENTS_FILE)
        if (agents.isFile && agents.canRead()) return agents
        val claude = File(dir, CLAUDE_FILE)
        if (claude.isFile && claude.canRead()) return claude
        return null
    }

    private fun readRule(file: File): String? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return if (text.length > MAX_RULE_CHARS) {
            (text.take(MAX_RULE_CHARS) + "\n…（规则过长，已截断）").trim()
        } else text.trim()
    }

    private fun renderGoalBlock(ctx: AgentContext): String? {
        if (ctx.goalStatus != GoalStatus.ACTIVE) return null
        val goal = ctx.goal?.takeIf { it.isNotBlank() } ?: return null
        val template = catalog.resolvePrompt(GOAL_FILE)
        if (template.isBlank()) return goal
        return template.replace(GOAL_TEXT_PLACEHOLDER, goal).trim()
    }

    /** 与 App 端 SystemPromptProvider.renderVariables 同名同序，保证模板变量语义一致。 */
    private fun renderVariables(
        text: String,
        skills: String?,
        memoryGlobal: String?,
        memoryProject: String?,
        subAgents: String?,
        projectRulesGlobal: String?,
        projectRulesProject: String?,
        workspace: String,
        environment: String,
        date: String,
        subAgentName: String = "",
        goal: String? = null
    ): String {
        var out = text
        out = out.replace(SKILLS_VAR, skills.orEmpty())
        out = out.replace(MEMORY_GLOBAL_VAR, memoryGlobal.orEmpty())
        out = out.replace(MEMORY_PROJECT_VAR, memoryProject.orEmpty())
        out = out.replace(SUBAGENTS_VAR, subAgents.orEmpty())
        out = out.replace(PROJECT_RULES_GLOBAL_VAR, projectRulesGlobal.orEmpty())
        out = out.replace(PROJECT_RULES_PROJECT_VAR, projectRulesProject.orEmpty())
        out = out.replace(PROJECT_RULES_VAR, projectRulesProject.orEmpty())
        out = out.replace(WORKSPACE_VAR, workspace)
        out = out.replace(ENVIRONMENT_VAR, environment)
        out = out.replace(DATE_VAR, date)
        out = out.replace(SUBAGENT_NAME_VAR, subAgentName)
        out = out.replace(GOAL_VAR, goal.orEmpty())
        return out
    }

    private fun currentDate(): String =
        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    private fun collapseBlankLines(text: String): String =
        text.trim().replace(Regex("\\n{3,}"), "\n\n")

    private companion object {
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val MAX_RULE_CHARS = 32_000
        const val GOAL_FILE = "agent/goal.md"
        const val GOAL_TEXT_PLACEHOLDER = "{{GOAL_TEXT}}"
        const val SKILLS_VAR = "{{MINIMECODE_SKILLS}}"
        const val MEMORY_GLOBAL_VAR = "{{MINIMECODE_MEMORY_GLOBAL}}"
        const val MEMORY_PROJECT_VAR = "{{MINIMECODE_MEMORY_PROJECT}}"
        const val SUBAGENTS_VAR = "{{MINIMECODE_SUBAGENTS}}"
        const val PROJECT_RULES_VAR = "{{MINIMECODE_PROJECT_RULES}}"
        const val PROJECT_RULES_GLOBAL_VAR = "{{MINIMECODE_PROJECT_RULES_GLOBAL}}"
        const val PROJECT_RULES_PROJECT_VAR = "{{MINIMECODE_PROJECT_RULES_PROJECT}}"
        const val WORKSPACE_VAR = "{{MINIMECODE_WORKSPACE}}"
        const val ENVIRONMENT_VAR = "{{MINIMECODE_ENVIRONMENT}}"
        const val DATE_VAR = "{{MINIMECODE_DATE}}"
        const val GOAL_VAR = "{{MINIMECODE_GOAL}}"
        const val SUBAGENT_NAME_VAR = "{{MINIMECODE_SUBAGENT_NAME}}"

        /** 模板缺失时的兜底基线（内容取自原一期极简版，保证核心工具约定不丢）。 */
        val FALLBACK_BASELINE = """
You are MiniMe, an AI coding assistant running in a cloud workspace. Work autonomously and be concise.
Always emit tool calls through structured function-calling — never as plain text or XML.
Available tools: Bash, writeFile, editFile, readFile, list, search, todo, loadSkill, memory, websearch,
webfetch, task, askUserQuestion, planMode, goalStatus, messageParent, sendFile(paths[], names?),
viewImage(images[]), generateImage, Shizuku, terminal, manageMcp, browser.
firewall(action:status|enable|disable|reset|add|remove, ...): manage outbound firewall (whitelist outside default-denied).
process(action:list|kill|persist|release|ttl|status|sweep, id?): manage process lifecycles; background processes auto-reclaim on TTL expiry unless marked persistent.
port(action:acquire|release|release_owner|bind|list|stats, port?, owner?, note?): allocate a uncommon local port for services to listen on; free ports are reused first (LRU), high-risk/common ports are forbidden, firewall allow is synced, and ports bound to a process owner are auto-released when that process exits. Use acquire then start a terminal service with that port in its command to bind automatically.
When a skill matches the task, call loadSkill. Save durable facts with memory.
""".trimIndent()
    }
}
