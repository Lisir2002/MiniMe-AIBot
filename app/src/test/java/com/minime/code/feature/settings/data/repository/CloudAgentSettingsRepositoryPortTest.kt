package com.minime.code.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 端口校验/解析纯函数测试（companion object，无需 Android Context）。 */
class CloudAgentSettingsRepositoryPortTest {

    @Test
    fun validatePort_acceptsValidRange() {
        assertEquals(8080, CloudAgentSettingsRepository.validatePort("8080"))
        assertEquals(18080, CloudAgentSettingsRepository.validatePort(" 18080 "))
        assertEquals(1, CloudAgentSettingsRepository.validatePort("1"))
        assertEquals(65535, CloudAgentSettingsRepository.validatePort("65535"))
    }

    @Test
    fun validatePort_rejectsInvalid() {
        assertNull(CloudAgentSettingsRepository.validatePort("0"))
        assertNull(CloudAgentSettingsRepository.validatePort("65536"))
        assertNull(CloudAgentSettingsRepository.validatePort("abc"))
        assertNull(CloudAgentSettingsRepository.validatePort(""))
        assertNull(CloudAgentSettingsRepository.validatePort("80.5"))
    }

    @Test
    fun portFromUrl_parsesExplicitAndDefaults() {
        assertEquals(18080, CloudAgentSettingsRepository.portFromUrl("http://1.2.3.4:18080"))
        assertEquals(8080, CloudAgentSettingsRepository.portFromUrl("http://1.2.3.4"))
        assertEquals(8080, CloudAgentSettingsRepository.portFromUrl(""))
    }

    @Test
    fun serverUrl_buildsFromHostAndPort() {
        assertEquals("http://1.2.3.4:18080", CloudAgentSettingsRepository.serverUrl("1.2.3.4", 18080))
    }
}
