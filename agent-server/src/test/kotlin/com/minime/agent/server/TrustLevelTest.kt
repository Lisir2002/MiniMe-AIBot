package com.minime.agent.server

import com.minime.code.feature.agent.domain.model.TrustLevel
import com.minime.code.feature.agent.domain.tool.ToolPermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端信任档位裁决单测：验证 [TrustPolicy.needsApproval] 的三档语义与 App 端一致，
 * 以及 wireName 持久化解析对旧库/异常值的兜底。
 */
class TrustLevelTest {

    @Test
    fun `完全信任档 所有工具静默放行`() {
        assertFalse(TrustPolicy.needsApproval(TrustLevel.FULL, ToolPermissionPolicy.ASK))
        assertFalse(TrustPolicy.needsApproval(TrustLevel.FULL, ToolPermissionPolicy.AUTO_APPROVE))
    }

    @Test
    fun `严格审批档 所有工具都需审批`() {
        assertTrue(TrustPolicy.needsApproval(TrustLevel.STRICT, ToolPermissionPolicy.ASK))
        assertTrue(TrustPolicy.needsApproval(TrustLevel.STRICT, ToolPermissionPolicy.AUTO_APPROVE))
    }

    @Test
    fun `基本信任档 沿用工具自身策略`() {
        assertTrue(TrustPolicy.needsApproval(TrustLevel.BASIC, ToolPermissionPolicy.ASK))        // Bash
        assertFalse(TrustPolicy.needsApproval(TrustLevel.BASIC, ToolPermissionPolicy.AUTO_APPROVE)) // loadSkill/memory
    }

    @Test
    fun `wireName 解析兼容别名与旧值`() {
        assertEquals(TrustLevel.FULL, TrustLevel.fromWire("full"))
        assertEquals(TrustLevel.STRICT, TrustLevel.fromWire("STRICT"))
        assertEquals(TrustLevel.BASIC, TrustLevel.fromWire("basic"))
        assertEquals(TrustLevel.BASIC, TrustLevel.fromWire(null))
        assertEquals(TrustLevel.BASIC, TrustLevel.fromWire("unknown-legacy-value"))
    }

    @Test
    fun `wireName 往返一致`() {
        TrustLevel.entries.forEach { assertEquals(it, TrustLevel.fromWire(it.wireName)) }
    }
}
