package com.minime.code.feature.agent.domain.workflow

import com.minime.code.feature.agent.data.local.dao.ChatSessionDao
import com.minime.code.feature.agent.data.local.entity.ChatSessionEntity
import com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行目标解析：多会话可各绑定不同云服务器，解析结果必须按会话隔离、互不串台。
 */
class CloudTargetResolverTest {

    private val dao = mockk<ChatSessionDao>(relaxed = true)
    private val settings = mockk<CloudAgentSettingsRepository>(relaxed = true)
    private val resolver = CloudTargetResolver(dao, settings)

    private fun session(id: String, connectionId: String?) = ChatSessionEntity(
        id = id, title = "t", createdAt = 0L, updatedAt = 0L, cloudConnectionId = connectionId
    )

    @Test
    fun boundSession_resolvesItsOwnServer() = runTest {
        coEvery { dao.getById("s1") } returns session("s1", "conn-A")
        coEvery { settings.serverUrlFor("conn-A") } returns "http://10.0.0.1:8080"
        coEvery { settings.tokenFor("conn-A") } returns "token-A"

        val target = resolver.resolve("s1")!!

        assertEquals("conn-A", target.connectionId)
        assertEquals("http://10.0.0.1:8080", target.baseUrl)
        assertEquals("token-A", target.token)
    }

    @Test
    fun differentSessions_resolveToDifferentServers() = runTest {
        coEvery { dao.getById("s1") } returns session("s1", "conn-A")
        coEvery { dao.getById("s2") } returns session("s2", "conn-B")
        coEvery { settings.serverUrlFor("conn-A") } returns "http://10.0.0.1:8080"
        coEvery { settings.serverUrlFor("conn-B") } returns "http://10.0.0.2:8080"
        coEvery { settings.tokenFor(any()) } returns "t"

        val a = resolver.resolve("s1")!!
        val b = resolver.resolve("s2")!!

        assertEquals("http://10.0.0.1:8080", a.baseUrl)
        assertEquals("http://10.0.0.2:8080", b.baseUrl)
        assertTrue(resolver.isCloudSession("s1"))
        assertTrue(resolver.isCloudSession("s2"))
    }

    @Test
    fun unboundSession_withGlobalEnabled_fallsBackToGlobalServer() = runTest {
        coEvery { dao.getById("s3") } returns session("s3", null)
        coEvery { settings.isEnabled() } returns true
        coEvery { settings.serverUrl() } returns "http://legacy:8080"
        coEvery { settings.token() } returns "legacy-token"

        val target = resolver.resolve("s3")!!

        assertNull(target.connectionId)
        assertEquals("http://legacy:8080", target.baseUrl)
    }

    @Test
    fun unboundSession_withoutGlobal_isLocal() = runTest {
        coEvery { dao.getById("s4") } returns session("s4", null)
        coEvery { settings.isEnabled() } returns false

        assertNull(resolver.resolve("s4"))
        assertEquals(false, resolver.isCloudSession("s4"))
    }

    @Test
    fun clearSessionMapping_usesOldBoundServer() = runTest {
        // 清理必须针对"切换前"的旧目标：此时会话仍绑定 conn-A。
        coEvery { dao.getById("s1") } returns session("s1", "conn-A")
        coEvery { settings.serverUrlFor("conn-A") } returns "http://10.0.0.1:8080"

        resolver.clearSessionMapping("s1")

        coVerify { settings.clearCloudSessionId("s1", "http://10.0.0.1:8080") }
    }
}
