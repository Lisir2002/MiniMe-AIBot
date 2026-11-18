package com.minime.agent.server

import com.minime.agent.server.modules.LocalFileAccessProvider
import com.minime.agent.server.modules.ServerMemoryRepository
import com.minime.agent.server.modules.ServerPromptBuilder
import com.minime.agent.server.modules.ServerPromptFragmentCatalog
import com.minime.agent.server.modules.ServerPromptFragmentSource
import com.minime.agent.server.modules.ServerSkillRepository
import com.minime.code.feature.agent.domain.model.AgentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 云端提示词引擎测试：19 模板 classpath 加载、四级覆盖、.no-builtin、变量注入、规则文件。
 */
class ServerPromptEngineTest {
    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "minime-prompt-test-$name-${System.nanoTime()}").also { it.mkdirs() }

    private fun catalog(home: File, ws: File) = ServerPromptFragmentCatalog(home.absolutePath, ws.absolutePath)

    private fun builder(home: File, ws: File, cat: ServerPromptFragmentCatalog = catalog(home, ws)): ServerPromptBuilder {
        val provider = LocalFileAccessProvider(home.absolutePath, ws.absolutePath)
        val skills = ServerSkillRepository(provider, "~/.minimecode/skills", "~/workspace/.minimecode/skills")
        val mem = ServerMemoryRepository(home.absolutePath, ws.absolutePath)
        return ServerPromptBuilder(skills, mem, cat, home.absolutePath)
    }

    @Test
    fun `19个模板从classpath加载并编号排序`() {
        val home = tempDir("home"); val ws = tempDir("ws")
        val cat = catalog(home, ws)
        val list = cat.list()
        // 顶层 10 个编号片段全部来自 BUILTIN（classpath）
        assertEquals(10, list.size)
        assertEquals(ServerPromptFragmentSource.BUILTIN, list.first().source)
        assertEquals(0, list.first().number) // 00-identity.md 排首位
        assertEquals("identity", list.first().title)
        // renderStatic 应拼接出身份段与运行期段
        val static = cat.renderStatic()
        assertTrue(static.contains("## 身份"))
        assertTrue(static.contains("## 可用技能")) // 99-runtime-context 片段正文首段
    }

    @Test
    fun `全局覆盖同编号覆盖内置`() {
        val home = tempDir("home"); val ws = tempDir("ws")
        val cat = catalog(home, ws)
        val dir = File(home, ".minimecode/prompts.custom").also { it.mkdirs() }
        File(dir, "00-identity.md").writeText("<!-- 自定义身份 -->\n## 身份\n我是云端自定义助手")
        val f = cat.fragment(0)!!
        assertEquals(ServerPromptFragmentSource.GLOBAL, f.source)
        assertTrue(cat.renderStatic().contains("我是云端自定义助手"))
        assertFalse(cat.renderStatic().contains("你是 MiniMe-Code 的 AI 编程助手"))
    }

    @Test
    fun `项目级覆盖优先于全局`() {
        val home = tempDir("home"); val ws = tempDir("ws")
        val cat = catalog(home, ws)
        File(File(home, ".minimecode/prompts.custom").also { it.mkdirs() }, "00-identity.md").writeText("全局版")
        File(File(ws, ".minimecode/prompts.custom").also { it.mkdirs() }, "00-identity.md").writeText("项目版")
        assertEquals(ServerPromptFragmentSource.PROJECT, cat.fragment(0)!!.source)
        assertTrue(cat.renderStatic().contains("项目版"))
        assertFalse(cat.renderStatic().contains("全局版"))
    }

    @Test
    fun `no-builtin时只输出自定义片段`() {
        val home = tempDir("home"); val ws = tempDir("ws")
        val cat = catalog(home, ws)
        cat.setBuiltinDisabled(true)
        File(File(home, ".minimecode/prompts.custom").also { it.mkdirs() }, "05-custom.md").writeText("自定义规则正文")
        assertTrue(cat.isBuiltinDisabled())
        val static = cat.renderStatic()
        assertTrue(static.contains("自定义规则正文"))
        assertFalse(static.contains("你是 MiniMe-Code"))
    }

    @Test
    fun `规则文件AGENTS优先并注入变量`() {
        val home = tempDir("home"); val ws = tempDir("ws")
        File(ws, "AGENTS.md").writeText("工作区规则：用中文提交")
        val b = builder(home, ws)
        val ctx = AgentContext(null, null, ws.absolutePath, null)
        val prompt = b.build(ctx)
        assertTrue(prompt.contains("工作区规则：用中文提交"))
        assertFalse(prompt.contains("{{MINIMECODE_"))
    }

    @Test
    fun `fallbackBaseline含关键工具约定`() {
        val home = tempDir("home"); val ws = tempDir("ws")
        val b = builder(home, ws)
        val fb = b.fallbackBaseline()
        assertTrue(fb.contains("sendFile"))
        assertTrue(fb.contains("structured function-calling"))
    }
}
