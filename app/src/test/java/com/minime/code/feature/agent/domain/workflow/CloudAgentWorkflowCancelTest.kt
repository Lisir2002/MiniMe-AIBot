package com.minime.code.feature.agent.domain.workflow

import com.minime.code.feature.agent.data.remote.cloudagent.CloudAgentApi
import com.minime.code.feature.agent.data.remote.cloudagent.OkResp
import com.minime.code.feature.settings.data.repository.CloudAgentSettingsRepository
import com.minime.code.feature.settings.data.repository.DefaultModelSettingsRepository
import com.minime.code.feature.settings.data.repository.TrustLevelRepository
import com.minime.code.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端「停止」的核心：cancelBySession 必须按 session 解析目标与持久化 taskId，
 * 精确向服务端发取消；无任务时不发请求；网络失败也要清本地映射且不抛异常。
 */
class CloudAgentWorkflowCancelTest {

    private val api = mockk<CloudAgentApi>()
    private val settings = mockk<CloudAgentSettingsRepository>(relaxed = true)
    private val providerRepository = mockk<AIProviderRepository>(relaxed = true)
    private val defaultModelSettings = mockk<DefaultModelSettingsRepository>(relaxed = true)
    private val trustLevelRepository = mockk<TrustLevelRepository>(relaxed = true)
    private val targetResolver = mockk<CloudTargetResolver>(relaxed = true)

    private val baseUrl = "http://example.com:8080"
    private val appSid = "app-session-1"

    private fun workflow(): CloudAgentWorkflow = CloudAgentWorkflow(
        api, settings, providerRepository, defaultModelSettings, trustLevelRepository, targetResolver
    )

    private fun target() = CloudTargetResolver.CloudTarget(
        connectionId = "conn-1", baseUrl = baseUrl, token = "tok"
    )

    @Test
    fun cancelBySession_sendsCancelWithPersistedTaskId() = runTest {
        coEvery { targetResolver.resolve(appSid) } returns target()
        coEvery { settings.getRunningTaskId(appSid, baseUrl) } returns "task-abc"
        coEvery { api.cancelTask(any(), any(), "task-abc") } returns OkResp(ok = true)

        val result = workflow().cancelBySession(appSid)

        assertTrue(result)
        coVerify { api.cancelTask(baseUrl, "Bearer tok", "task-abc") }
        // 取消后清掉映射，避免下次停止命中已结束任务
        coVerify { settings.clearRunningTaskId(appSid, baseUrl) }
    }

    @Test
    fun cancelBySession_noTaskId_doesNotCallApi() = runTest {
        coEvery { targetResolver.resolve(appSid) } returns target()
        coEvery { settings.getRunningTaskId(appSid, baseUrl) } returns null

        val result = workflow().cancelBySession(appSid)

        assertFalse(result)
        coVerify(exactly = 0) { api.cancelTask(any(), any(), any()) }
    }

    @Test
    fun cancelBySession_offline_returnsFalseAndClearsMapping() = runTest {
        coEvery { targetResolver.resolve(appSid) } returns target()
        coEvery { settings.getRunningTaskId(appSid, baseUrl) } returns "task-xyz"
        coEvery { api.cancelTask(any(), any(), any()) } throws RuntimeException("network down")

        val result = workflow().cancelBySession(appSid) // 不得抛异常

        assertFalse(result)
        coVerify { settings.clearRunningTaskId(appSid, baseUrl) }
    }

    @Test
    fun cancelBySession_noTarget_returnsFalse() = runTest {
        coEvery { targetResolver.resolve(appSid) } returns null

        assertFalse(workflow().cancelBySession(appSid))
        coVerify(exactly = 0) { api.cancelTask(any(), any(), any()) }
    }
}
