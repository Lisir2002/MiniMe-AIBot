package com.minime.agent.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun Application.configureServer(store: EventStore, runner: AgentRunner, token: String) {
    val log = LoggerFactory.getLogger("AgentServer")
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("请求处理失败: ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "内部错误"))
        }
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "minime-agent-server"))
        }

        // 可选的 Bearer token 鉴权（第一版：环境变量 MINIME_TOKEN 为空则不校验）
        fun authorized(c: ApplicationCall): Boolean {
            if (token.isBlank()) return true
            val header = c.request.headers["Authorization"] ?: return false
            return header == "Bearer $token"
        }

        post("/sessions") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val req = call.receive<CreateSessionRequest>()
            val id = store.createSession(req.title)
            call.respond(CreateSessionResponse(id))
        }

        post("/sessions/{id}/messages") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val sessionId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing session id")); return@post
            }
            if (!store.sessionExists(sessionId)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("session not found")); return@post
            }
            val req = call.receive<SubmitMessageRequest>()
            val taskId = store.createTask(sessionId, req.provider)
            runner.submit(sessionId, taskId, req.content, req.provider)
            call.respond(HttpStatusCode.Accepted, SubmitMessageResponse(taskId))
        }

        get("/sessions/{id}/events") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@get }
            val sessionId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing session id")); return@get
            }
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val events = store.eventsAfter(sessionId, after)
            val nextSeq = events.lastOrNull()?.seq ?: store.latestSeq(sessionId)
            // 直接读任务真实状态（历史事件里永久保留 pending_decision，不能用它反推，否则会卡在 waiting_approval）
            val status = store.latestTaskStatus(sessionId) ?: "idle"
            // 挂起的审批项：App 断线/删后台/熄屏期间云端可能已挂起等审批，重连后据此恢复审批卡片
            val pending = store.pendingDecisionsFor(sessionId)
            call.respond(EventsResponse(events, nextSeq, status, pending))
        }

        post("/decisions/{id}") {
            if (!authorized(call)) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")); return@post }
            val decisionId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing decision id")); return@post
            }
            val req = call.receive<DecisionRequest>()
            val ok = store.resolvePending(decisionId, req.approve)
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("decision not found or already resolved")); return@post
            }
            call.respond(OkResponse())
        }
    }
}
