package com.minime.code.feature.agent.domain.workflow

import com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager.Inspection.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 部署前容器检测的核心决策不变量：
 *  - 无容器 → INSTALL（安装最新版）
 *  - 有容器但读不到版本（未运行/服务未就绪）→ UPGRADE
 *  - 有容器且版本与最新一致 → UP_TO_DATE（跳过重装）
 *  - 有容器但版本落后 → UPGRADE
 *  - 取不到最新版本 → UNKNOWN（由调用方决定是否强制部署）
 */
class CloudAgentServerManagerTest {

    @Test
    fun noContainer_installsLatest() {
        val r = decideInspection("EMPTY", null, "1.0.0-rc11")
        assertEquals(Action.INSTALL, r.action)
        assertFalse(r.containerExists)
        assertFalse(r.running)
        assertNull(r.currentVersion)
        assertEquals("1.0.0-rc11", r.latestVersion)
    }

    @Test
    fun blankContainer_installsLatest() {
        assertEquals(Action.INSTALL, decideInspection("  ", null, "1.0.0-rc11").action)
        assertEquals(Action.INSTALL, decideInspection(null, null, "1.0.0-rc11").action)
    }

    @Test
    fun containerButNoVersion_upgrades() {
        val r = decideInspection("minime-agent-server", null, "1.0.0-rc11")
        assertEquals(Action.UPGRADE, r.action)
        assertTrue(r.containerExists)
        assertFalse(r.running)
    }

    @Test
    fun containerAtLatestVersion_skips() {
        val r = decideInspection("minime-agent-server", "1.0.0-rc11", "1.0.0-rc11")
        assertEquals(Action.UP_TO_DATE, r.action)
        assertTrue(r.isUpToDate)
        assertTrue(r.running)
    }

    @Test
    fun versionWithVPrefix_isStillLatest() {
        // 服务端可能返回带 v 前缀的版本，比较时归一化，避免误判为旧版而重复重装。
        val r = decideInspection("minime-agent-server", "v1.0.0-rc11", "1.0.0-rc11")
        assertEquals(Action.UP_TO_DATE, r.action)
    }

    @Test
    fun olderVersion_upgrades() {
        val r = decideInspection("minime-agent-server", "1.0.0-rc9", "1.0.0-rc11")
        assertEquals(Action.UPGRADE, r.action)
        assertFalse(r.isUpToDate)
        assertEquals("1.0.0-rc9", r.currentVersion)
    }

    @Test
    fun latestVersionUnknown_isUnknown_evenWithContainer() {
        val r = decideInspection("minime-agent-server", "1.0.0-rc11", null)
        assertEquals(Action.UNKNOWN, r.action)
        assertNull(r.latestVersion)
        // 取不到最新版本时不能判 UP_TO_DATE，交由调用方强制部署兜底。
        assertFalse(r.isUpToDate)
    }

    @Test
    fun unknownWithoutContainer_isStillUnknown() {
        // 连最新版本都取不到时，即使没容器也不该盲目 INSTALL（版本号未知）。
        assertEquals(Action.UNKNOWN, decideInspection("EMPTY", null, null).action)
    }
}
