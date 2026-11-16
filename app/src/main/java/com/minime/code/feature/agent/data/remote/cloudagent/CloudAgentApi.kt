package com.minime.code.feature.agent.data.remote.cloudagent

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端 agent-server 的 HTTP 客户端。服务器地址动态传入（用户设置里配置），
 * 因此用 OkHttp 手搓请求而非 Retrofit 静态 baseUrl。DTO 用 Gson 序列化，
 * 字段与 agent-server/Models.kt 一一对应。
 */
@Singleton
class CloudAgentApi @Inject constructor() {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // 轮询等待，不设读上限
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Suppress("UNCHECKED_CAST")
    suspend fun health(baseUrl: String): Map<String, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/health").get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            gson.fromJson(body, Map::class.java) as? Map<String, String> ?: emptyMap()
        }
    }

    suspend fun createSession(baseUrl: String, auth: String, req: CreateSessionReq): CreateSessionResp =
        post(baseUrl, auth, "/sessions", req, CreateSessionResp::class.java)

    suspend fun submitMessage(baseUrl: String, auth: String, sessionId: String, req: SubmitMessageReq): SubmitMessageResp =
        post(baseUrl, auth, "/sessions/$sessionId/messages", req, SubmitMessageResp::class.java)

    suspend fun events(baseUrl: String, auth: String, sessionId: String, after: Long): EventsResp =
        get(baseUrl, auth, "/sessions/$sessionId/events?after=$after", EventsResp::class.java)

    suspend fun decide(baseUrl: String, auth: String, decisionId: String, req: DecisionReq): OkResp =
        post(baseUrl, auth, "/decisions/$decisionId", req, OkResp::class.java)

    private suspend fun <T> post(baseUrl: String, auth: String, path: String, body: Any, type: Class<T>): T =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl$path")
                .header("Authorization", auth)
                .post(gson.toJson(body).toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw CloudAgentHttpException(resp.code, text)
                gson.fromJson(text, type)
            }
        }

    private suspend fun <T> get(baseUrl: String, auth: String, path: String, type: Class<T>): T =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$baseUrl$path").header("Authorization", auth).get().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw CloudAgentHttpException(resp.code, text)
                gson.fromJson(text, type)
            }
        }
}

class CloudAgentHttpException(val code: Int, val body: String) : Exception("云端服务 HTTP $code: ${body.take(200)}")

data class CreateSessionReq(val title: String = "新会话")
data class CreateSessionResp(val sessionId: String)

data class SubmitMessageReq(val content: String, val provider: ProviderConfigDto)

data class ProviderConfigDto(
    val type: String = "anthropic",
    val apiKey: String,
    val baseUrl: String,
    val useFullUrl: Boolean = false,
    val model: String = "claude-3-5-sonnet-20241022"
)

data class SubmitMessageResp(val taskId: String, val accepted: Boolean = true)

data class EventsResp(val events: List<StoredEventDto>, val nextSeq: Long, val taskStatus: String = "running")

data class StoredEventDto(
    val seq: Long,
    val sessionId: String,
    val kind: String,
    val taskId: String = "",
    val text: String = "",
    val toolName: String = "",
    val toolArgs: String = "",
    val toolCallId: String = "",
    val isError: Boolean = false,
    val reasoning: String = "",
    val thinkingBlocksJson: String = "",
    val signature: String = "",
    val timestamp: Long = 0L
)

data class DecisionReq(val approve: Boolean, val remember: Boolean = false)
data class OkResp(val ok: Boolean = true)
