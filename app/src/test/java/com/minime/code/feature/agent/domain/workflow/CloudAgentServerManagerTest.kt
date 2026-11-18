package com.minime.code.feature.agent.domain.workflow

import com.minime.code.feature.agent.domain.workflow.CloudAgentServerManager.Inspection.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun versionedImageTag_derivedFromReleaseTag() {
        // 镜像 tag 必须随版本变化，否则升级时本地同名 tag 命中会跳过 pull，
        // 导致镜像（含 iptables 等系统能力）永远停在旧版。
        assertEquals(
            "ghcr.io/lisir2002/minimecode-agent-server:base-1.0.0-rc19",
            CloudAgentServerManager.baseImageFor("v1.0.0-rc19")
        )
        assertEquals(
            "ghcr.io/lisir2002/minimecode-agent-server:base-1.0.0-rc19",
            CloudAgentServerManager.baseImageFor("1.0.0-rc19")
        )
        // 不同版本必须产生不同 tag
        assertNotEquals(
            CloudAgentServerManager.baseImageFor("v1.0.0-rc18"),
            CloudAgentServerManager.baseImageFor("v1.0.0-rc19")
        )
    }

    @Test
    fun atomFeed_parsesLatestTag() {
        // 模拟 releases.atom：按文档顺序第一条即最新，且含 prerelease
        val atom = """<?xml version="1.0"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry><title>Release v1.0.0-rc19</title>
            <link rel="alternate" href="https://github.com/Lisir2002/MiniMe-AIBot/releases/tag/v1.0.0-rc19"/>
          </entry>
          <entry><title>Release v1.0.0-rc18</title>
            <link rel="alternate" href="https://github.com/Lisir2002/MiniMe-AIBot/releases/tag/v1.0.0-rc18"/>
          </entry>
        </feed>""".trimIndent()
        assertEquals("v1.0.0-rc19", CloudAgentServerManager.parseLatestTagFromAtom(atom))
        // 没有 tag 链接时返回 null
        assertNull(CloudAgentServerManager.parseLatestTagFromAtom("<feed></feed>"))
    }

    @Test
    fun rateLimitMessage_isActionable() {
        // 403 限流：给出配额原因 + 关闭 VPN/稍后重试建议，不再是误导的「检查网络」
        val limited = CloudAgentServerManager.rateLimitMessage(
            403, null, null, "API rate limit exceeded for 1.2.3.4."
        )
        assertTrue(limited.contains("60 次/小时"))
        assertTrue(limited.contains("VPN"))
        assertTrue(limited.contains("重试"))
        // 429 即使 body 无关键字也识别为限流
        assertTrue(CloudAgentServerManager.rateLimitMessage(429, "30", null, "").contains("30秒后"))
        // 非限流的 403：给出通用拒绝提示
        val blocked = CloudAgentServerManager.rateLimitMessage(403, null, null, "Forbidden by policy")
        assertTrue(blocked.contains("拒绝访问"))
    }
}
