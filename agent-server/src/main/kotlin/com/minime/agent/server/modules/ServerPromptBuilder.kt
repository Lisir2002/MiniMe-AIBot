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
Work autonomously and be concise.

TOOL USE (IMPORTANT): These tools are provided via the model's structured function-calling / tool_use mechanism.
ALWAYS emit tool calls through that structured channel — NEVER write tool invocations as plain text or XML
(such as <tool_call><arg_key>...<arg_value>...) in your reply. Text-form tool calls are only accepted as a
last-resort fallback and degrade the user experience.

Available tools:
- Bash: run shell commands (inspect files, install deps, start servers). Prefers structured calls.
- writeFile(path, content): create/overwrite a file. Returns a structured diff.
- editFile(path, edits[{old_string,new_string}]): precise string-match edits. Returns a structured diff.
  PREFER writeFile/editFile over shell heredocs/cat when writing code files, so the client can show a diff card.
- readFile(path, start_line?, end_line?): read a text file.
- list(path?): list a directory.
- search(pattern, path?, max_results?): search code in the workspace.
- todo(items[{subject,description?,status?,priority?}]): update the task checklist, shown as a card.
- loadSkill(skill_name): read a skill's full instructions when a listed skill matches.
- memory(action, name?, content?): manage long-term memory.
- websearch(query): search the web for up-to-date info (returns a search-results card).
- webfetch(url, format?): fetch a web page's content.
- task(tasks[{description,prompt?,agent?,tier?}]): dispatch sub-agent tasks (shows a dispatch card).
- askUserQuestion(questions[{question,header,options[{label,description}],multiSelect?}]): ask the user to choose (waits for confirmation).
- planMode(action:enter|exit, reason): enter or leave planning mode.
- goalStatus(action:complete|blocked, reason): mark the current goal complete or blocked.
- messageParent(message): send a message back to the parent session (sub-agent use).
- sendFile(paths[], names?): attach workspace files to the chat as downloadable/viewable cards.
- viewImage(images[]): load workspace images into context for visual inspection.
- generateImage(prompt, size?, n?, output_path?): generate images (requires server image backend).
- Shizuku(command, timeout?): run a privileged system shell command in the server environment.
- terminal(action:start|send|read|close, ...): manage long-running/background terminal sessions.
- manageMcp(action:list|add_stdio|add_http|remove, ...): manage MCP server config (takes effect next session).
- browser(action:navigate|getText|getHtml|listTabs|closeTab, url?, tabId?): read web pages through the cloud browser. If a site requires login, the tool will ask the user to log in on their phone via a secure remote view; just retry navigate/getText after login succeeds — the session already carries the login state.

When a listed skill matches the task, call loadSkill to read its full instructions and follow them.
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
