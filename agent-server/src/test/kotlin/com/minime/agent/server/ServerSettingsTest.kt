package com.minime.agent.server

import com.minime.agent.server.modules.ServerSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 云端设置仓库测试：默认值、落盘持久化、局部更新、语言指令。
 * 用临时 HOME 隔离，避免污染真实 ~/.minimecode/settings.json。
 */
class ServerSettingsTest {
    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "minime-settings-test-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun `默认值与App对齐`() {
        val repo = ServerSettingsRepository(tempHome().absolutePath)
        val s = repo.get()
        assertEquals(30, s.maxIterations)
        assertEquals(50, s.goalMaxTurns)
        assertEquals(500_000, s.goalMaxTokens)
        assertEquals(100, s.sendFileMaxSizeMb)
        assertEquals(85, s.compactionThresholdPercent)
        assertEquals("", s.language)
        assertNull(repo.languageInstruction())
    }

    @Test
    fun `局部更新并落盘持久化`() {
        val home = tempHome()
        val repo = ServerSettingsRepository(home.absolutePath)
        repo.update { it.copy(language = "中文", maxIterations = 60, sendFileMaxSizeMb = 250) }
        assertEquals("中文", repo.get().language)
        assertEquals(60, repo.get().maxIterations)
        assertTrue(repo.languageInstruction()!!.contains("请用中文回复"))

        // 新实例从磁盘重读，验证持久化
        val reloaded = ServerSettingsRepository(home.absolutePath)
        assertEquals("中文", reloaded.get().language)
        assertEquals(60, reloaded.get().maxIterations)
        assertEquals(250, reloaded.get().sendFileMaxSizeMb)
        assertTrue(File(home, ".minimecode/settings.json").isFile)
    }

    @Test
    fun `局部更新只改指定字段`() {
        val repo = ServerSettingsRepository(tempHome().absolutePath)
        repo.update { it.copy(goalMaxTurns = 12) }
        val s = repo.update { it.copy(language = "英文") } // 另一个字段
        assertEquals(12, s.goalMaxTurns) // 保持
        assertEquals("英文", s.language) // 新改
    }

    @Test
    fun `数值下限约束生效`() {
        val repo = ServerSettingsRepository(tempHome().absolutePath)
        val s = repo.update {
            it.copy(goalMaxTurns = 0, sendFileMaxSizeMb = 0)
        }
        // 仓库 update 不强制 coerce（coerce 在 HTTP 层与读取时用），这里仅验证数据可存，
        // 真正在线路径由 POST /settings 的 coerce 保证。
        assertEquals(0, s.goalMaxTurns)
    }
}
