package com.minime.agent.server

import com.minime.agent.server.modules.LocalFileAccessProvider
import com.minime.agent.server.modules.ServerMemoryRepository
import com.minime.agent.server.modules.ServerPromptBuilder
import com.minime.agent.server.modules.ServerSkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 云端 skill/memory/prompt 模块的单元测试：用临时目录隔离，验证与 App 对齐的两级目录语义、
 * 解析/编辑/合并逻辑，以及 system prompt 的清单注入格式。
 */
class ServerModulesTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "minime-server-test-$name-${System.nanoTime()}").also { it.mkdirs() }

    private fun writeSkill(root: File, dir: String, md: String) {
        File(root, "$dir/SKILL.md").apply { parentFile.mkdirs(); writeText(md) }
    }

    @Test
    fun `skill 两级目录合并 项目级覆盖全局`() {
        val home = tempDir("home"); val ws = tempDir("ws")
        val provider = LocalFileAccessProvider(home.absolutePath, ws.absolutePath)
        writeSkill(File(home, ".minimecode/skills"), "review", "---\nname: review\ndescription: code review global\n---\nreview body")
        writeSkill(File(home, ".minimecode/skills"), "deploy", "---\nname: deploy\ndescription: deploy app\n---\ndeploy body")
        writeSkill(File(ws, ".minimecode/skills"), "review", "---\nname: review\ndescription: code review project\n---\nproject review body")

        val repo = ServerSkillRepository(provider, "~/.minimecode/skills", "~/workspace/.minimecode/skills")
        val skills = repo.listSkills().associateBy { it.name }
        assertEquals(2, skills.size)
        assertEquals("code review project", skills["review"]!!.description) // 项目覆盖全局
        assertTrue(repo.loadInstructions("review")!!.contains("project review body"))
        assertEquals("deploy app", skills["deploy"]!!.description)
    }

    @Test
    fun `memory 保存读取编辑删除 全链路`() {
        val home = tempDir("home"); val ws = tempDir("ws")
        val repo = ServerMemoryRepository(home.absolutePath, ws.absolutePath)

        assertTrue(repo.save("prefs", "user preferences", "likes dark mode", "global"))
        assertEquals("likes dark mode", repo.read("prefs", "global"))
        assertEquals(1, repo.promptList("global").size)
        assertEquals("user preferences", repo.promptList("global")[0].second)

        // edit：精确替换
        val edits = listOf(com.minime.code.feature.agent.domain.memory.MemoryEdit("dark mode", "light mode", false))
        assertTrue(repo.edit("prefs", edits, "global") is com.minime.code.feature.agent.domain.memory.MemoryEditResult.Success)
        assertEquals("likes light mode", repo.read("prefs", "global"))

        // 不存在时 NotFound
        assertTrue(repo.read("missing", "global") == null)
        assertTrue(repo.delete("prefs", "global"))
        assertNull(repo.read("prefs", "global"))
    }

    @Test
    fun `memory 项目级覆盖全局同名`() {
        val home = tempDir("home"); val ws = tempDir("ws")
        val repo = ServerMemoryRepository(home.absolutePath, ws.absolutePath)
        repo.save("conventions", "global desc", "global body", "global")
        repo.save("conventions", "project desc", "project body", "project")
        assertEquals("project desc", repo.promptList("project").first { it.first == "conventions" }.second)
        assertEquals("global desc", repo.promptList("global").first { it.first == "conventions" }.second)
    }

    @Test
    fun `prompt 用19模板注入 skill 与 memory 清单 格式对齐App`() {
        val home = tempDir("home"); val ws = tempDir("ws")
        val provider = LocalFileAccessProvider(home.absolutePath, ws.absolutePath)
        writeSkill(File(home, ".minimecode/skills"), "review", "---\nname: review\ndescription: code review\n---\nbody")
        val skills = ServerSkillRepository(provider, "~/.minimecode/skills", "~/workspace/.minimecode/skills")
        val mem = ServerMemoryRepository(home.absolutePath, ws.absolutePath)
        mem.save("prefs", "user prefs", "body", "global")

        val catalog = com.minime.agent.server.modules.ServerPromptFragmentCatalog(
            homePath = home.absolutePath, workspacePath = ws.absolutePath
        )
        val prompt = ServerPromptBuilder(skills, mem, catalog, home.absolutePath).build()
        // 19 模板的 99-runtime-context 片段带这些变量，注入后应含技能/记忆清单。
        assertTrue(prompt.contains("## 可用技能"))
        assertTrue(prompt.contains("- review: code review"))
        assertTrue(prompt.contains("### 全局记忆"))
        assertTrue(prompt.contains("- prefs: user prefs"))
        // 变量占位符应被全部展开，不应残留。
        assertFalse(prompt.contains("{{MINIMECODE_"))
    }
}
