package com.minime.code.feature.agent.domain.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端版本是否支持"工具审批三档信任"的判定。
 * 信任档位随 App rc13 引入；旧版服务端不认识 trustLevel，Bash 固定每次审批，
 * UI 需据 [supportsTrustLevel] 提示用户升级云端服务。
 */
class CloudTargetDiscoveryTest {

    @Test
    fun older_rc_is_not_supported() {
        assertFalse(supportsTrustLevel("1.0.0-rc10"))
        assertFalse(supportsTrustLevel("1.0.0-rc12"))
    }

    @Test
    fun threshold_and_newer_are_supported() {
        assertTrue(supportsTrustLevel("1.0.0-rc13"))
        assertTrue(supportsTrustLevel("1.0.0-rc14"))
        assertTrue(supportsTrustLevel("1.0.0"))
        assertTrue(supportsTrustLevel("1.1.0-rc1"))
    }

    @Test
    fun rc_number_compares_numerically() {
        // rc9 必须判为旧，不能被字典序当作比 rc13 新
        assertFalse(supportsTrustLevel("1.0.0-rc9"))
        assertTrue(supportsTrustLevel("1.0.0-rc20"))
    }

    @Test
    fun unknown_or_dev_version_is_not_supported() {
        assertFalse(supportsTrustLevel("unknown"))
        assertFalse(supportsTrustLevel("dev"))
        assertFalse(supportsTrustLevel(""))
    }

    @Test
    fun availableTarget_exposes_support_flag() {
        val old = CloudTargetDiscovery.AvailableTarget("c1", "测试", "1.2.3.4", "http://1.2.3.4:8080", "1.0.0-rc10")
        val new = CloudTargetDiscovery.AvailableTarget("c2", "生产", "5.6.7.8", "http://5.6.7.8:8080", "1.0.0-rc13")
        assertFalse(old.trustLevelSupported)
        assertTrue(new.trustLevelSupported)
    }
}
