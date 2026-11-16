package com.minime.agent.server

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SQLite 持久化：会话、任务、事件（append-only 带 seq）、待审批决策、checkpoint。
 *
 * 第一版用单连接 + synchronized 串行化写，读写量小（单用户场景）足够。
 * 事件 seq 用 sqlite 自增主键，天然全局递增，App 按 after=seq 增量拉取。
 */
class EventStore(dbPath: String) : AutoCloseable {

    private val conn: Connection
    private val json = Json { ignoreUnknownKeys = true }

    /** decisionId -> 等待审批的 CompletableDeferred，由 AgentRunner 注册、API 层 resolve。 */
    private val pendingDecisions = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    init {
        File(dbPath).parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    context_json TEXT NOT NULL DEFAULT '[]',
                    created_at INTEGER NOT NULL
                )
            """.trimIndent())
            // 兼容已有库：老版本 sessions 表没有 context_json 列
            runCatching {
                val cols = st.executeQuery("PRAGMA table_info(sessions)").use { rs ->
                    val names = mutableListOf<String>()
                    while (rs.next()) names += rs.getString("name")
                    names
                }
                if (!cols.contains("context_json")) st.executeUpdate("ALTER TABLE sessions ADD COLUMN context_json TEXT NOT NULL DEFAULT '[]'")
            }
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS events (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    task_id TEXT NOT NULL DEFAULT '',
                    text TEXT NOT NULL DEFAULT '',
                    tool_name TEXT NOT NULL DEFAULT '',
                    tool_args TEXT NOT NULL DEFAULT '',
                    tool_call_id TEXT NOT NULL DEFAULT '',
                    is_error INTEGER NOT NULL DEFAULT 0,
                    reasoning TEXT NOT NULL DEFAULT '',
                    thinking_blocks_json TEXT NOT NULL DEFAULT '',
                    signature TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL
                )
            """.trimIndent())
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_session_seq ON events(session_id, seq)")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    provider_json TEXT NOT NULL DEFAULT '',
                    checkpoint_json TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            // 待审批决策持久化：仅靠内存 pendingDecisions，进程重启或 App 重连后无法判断
            // 一个 pending_decision 事件是否已处理。落库后 App 可按 status=pending 重建审批卡片。
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS decisions (
                    decision_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    task_id TEXT NOT NULL DEFAULT '',
                    tool_name TEXT NOT NULL DEFAULT '',
                    args_preview TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_decisions_session_status ON decisions(session_id, status)")
        }
    }

    @Synchronized
    fun createSession(title: String): String {
        val id = UUID.randomUUID().toString()
        conn.prepareStatement("INSERT INTO sessions(id,title,created_at) VALUES(?,?,?)").use { ps ->
            ps.setString(1, id); ps.setString(2, title); ps.setLong(3, System.currentTimeMillis()); ps.executeUpdate()
        }
        return id
    }

    @Synchronized
    fun sessionExists(sessionId: String): Boolean {
        conn.prepareStatement("SELECT 1 FROM sessions WHERE id=?").use { ps ->
            ps.setString(1, sessionId); return ps.executeQuery().use { it.next() }
        }
    }

    @Synchronized
    fun createTask(sessionId: String, provider: ProviderConfigDto): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        conn.prepareStatement("INSERT INTO tasks(id,session_id,status,provider_json,created_at,updated_at) VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, id); ps.setString(2, sessionId); ps.setString(3, TaskStatus.QUEUED)
            ps.setString(4, json.encodeToString(provider)); ps.setLong(5, now); ps.setLong(6, now); ps.executeUpdate()
        }
        return id
    }

    @Synchronized
    fun setTaskStatus(taskId: String, status: String) {
        conn.prepareStatement("UPDATE tasks SET status=?, updated_at=? WHERE id=?").use { ps ->
            ps.setString(1, status); ps.setLong(2, System.currentTimeMillis()); ps.setString(3, taskId); ps.executeUpdate()
        }
        // 任务进入终态时清理该任务残留的 pending 决策，避免 App 重连后显示「幽灵审批卡片」
        if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED) {
            conn.prepareStatement("UPDATE decisions SET status='cancelled', updated_at=? WHERE task_id=? AND status='pending'").use { ps ->
                ps.setLong(1, System.currentTimeMillis()); ps.setString(2, taskId); ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun getTaskStatus(taskId: String): String? {
        conn.prepareStatement("SELECT status FROM tasks WHERE id=?").use { ps ->
            ps.setString(1, taskId); ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    /** 取某会话最新创建的任务状态（App 拉取时展示用，避免用历史事件反推状态导致卡在 waiting_approval）。 */
    @Synchronized
    fun latestTaskStatus(sessionId: String): String? {
        conn.prepareStatement("SELECT status FROM tasks WHERE session_id=? ORDER BY created_at DESC, rowid DESC LIMIT 1").use { ps ->
            ps.setString(1, sessionId); ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    @Synchronized
    fun getTaskProvider(taskId: String): ProviderConfigDto? {
        var result: ProviderConfigDto? = null
        conn.prepareStatement("SELECT provider_json FROM tasks WHERE id=?").use { ps ->
            ps.setString(1, taskId); ps.executeQuery().use { rs ->
                if (rs.next()) result = runCatching { json.decodeFromString<ProviderConfigDto>(rs.getString(1)) }.getOrNull()
            }
        }
        return result
    }

    @Synchronized
    fun saveCheckpoint(taskId: String, checkpoint: TaskCheckpoint) {
        conn.prepareStatement("UPDATE tasks SET checkpoint_json=?, updated_at=? WHERE id=?").use { ps ->
            ps.setString(1, json.encodeToString(checkpoint)); ps.setLong(2, System.currentTimeMillis()); ps.setString(3, taskId); ps.executeUpdate()
        }
    }

    @Synchronized
    fun getCheckpoint(taskId: String): TaskCheckpoint? {
        conn.prepareStatement("SELECT checkpoint_json FROM tasks WHERE id=?").use { ps ->
            ps.setString(1, taskId); ps.executeQuery().use { rs ->
                if (rs.next()) { val s = rs.getString(1); if (!s.isNullOrBlank()) return runCatching { json.decodeFromString<TaskCheckpoint>(s) }.getOrNull() }
                return null
            }
        }
    }

    /** 读取会话累积的对话上下文（多轮历史），供云端续接新提问。 */
    @Synchronized
    fun getSessionContext(sessionId: String): List<com.minime.code.feature.agent.domain.model.AgentMessage> {
        conn.prepareStatement("SELECT context_json FROM sessions WHERE id=?").use { ps ->
            ps.setString(1, sessionId); ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val s = rs.getString(1)
                    if (!s.isNullOrBlank() && s != "[]") {
                        return runCatching {
                            json.decodeFromString<List<com.minime.code.feature.agent.domain.model.AgentMessage>>(s)
                        }.getOrDefault(emptyList())
                    }
                }
                return emptyList()
            }
        }
    }

    /** 任务正常完成后写回会话上下文，作为下一轮提问的历史。 */
    @Synchronized
    fun updateSessionContext(sessionId: String, messages: List<com.minime.code.feature.agent.domain.model.AgentMessage>) {
        conn.prepareStatement("UPDATE sessions SET context_json=? WHERE id=?").use { ps ->
            ps.setString(1, json.encodeToString(messages)); ps.setString(2, sessionId); ps.executeUpdate()
        }
    }

    /** 扫描服务重启后需要续跑的任务（非终态）。 */
    @Synchronized
    fun unfinishedTasks(): List<TaskInfo> {
        val out = mutableListOf<TaskInfo>()
        conn.prepareStatement("SELECT id,session_id,provider_json FROM tasks WHERE status IN (?,?,?)").use { ps ->
            ps.setString(1, TaskStatus.QUEUED)
            ps.setString(2, TaskStatus.RUNNING)
            ps.setString(3, TaskStatus.WAITING_APPROVAL)
            ps.executeQuery().use { rs ->
                while (rs.next()) out += TaskInfo(rs.getString("id"), rs.getString("session_id"), rs.getString("provider_json"))
            }
        }
        return out
    }

    /** 追加一条事件，返回其 seq。 */
    @Synchronized
    fun append(event: NewEvent): Long {
        conn.prepareStatement("""
            INSERT INTO events(session_id,kind,task_id,text,tool_name,tool_args,tool_call_id,is_error,reasoning,thinking_blocks_json,signature,timestamp)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent(), Statement.RETURN_GENERATED_KEYS).use { ps ->
            ps.setString(1, event.sessionId); ps.setString(2, event.kind); ps.setString(3, event.taskId)
            ps.setString(4, event.text); ps.setString(5, event.toolName); ps.setString(6, event.toolArgs)
            ps.setString(7, event.toolCallId); ps.setInt(8, if (event.isError) 1 else 0)
            ps.setString(9, event.reasoning); ps.setString(10, event.thinkingBlocksJson); ps.setString(11, event.signature)
            ps.setLong(12, System.currentTimeMillis())
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) return rs.getLong(1) }
        }
        return -1L
    }

    /** 拉取某会话 after 之后的所有事件，按 seq 升序。 */
    @Synchronized
    fun eventsAfter(sessionId: String, after: Long): List<StoredEvent> {
        val out = mutableListOf<StoredEvent>()
        conn.prepareStatement("SELECT * FROM events WHERE session_id=? AND seq>? ORDER BY seq ASC").use { ps ->
            ps.setString(1, sessionId); ps.setLong(2, after)
            ps.executeQuery().use { rs ->
                while (rs.next()) out += StoredEvent(
                    seq = rs.getLong("seq"), sessionId = rs.getString("session_id"), kind = rs.getString("kind"),
                    taskId = rs.getString("task_id"), text = rs.getString("text"), toolName = rs.getString("tool_name"),
                    toolArgs = rs.getString("tool_args"), toolCallId = rs.getString("tool_call_id"),
                    isError = rs.getInt("is_error") != 0, reasoning = rs.getString("reasoning"),
                    thinkingBlocksJson = rs.getString("thinking_blocks_json"), signature = rs.getString("signature"),
                    timestamp = rs.getLong("timestamp")
                )
            }
        }
        return out
    }

    @Synchronized
    fun latestSeq(sessionId: String): Long {
        conn.prepareStatement("SELECT COALESCE(MAX(seq),0) FROM events WHERE session_id=?").use { ps ->
            ps.setString(1, sessionId); ps.executeQuery().use { rs -> if (rs.next()) return rs.getLong(1) }
        }
        return 0L
    }

    /** 注册一个待审批决策：内存放 deferred 供 AgentRunner await，同时落库供 App 重连恢复。 */
    @Synchronized
    fun registerPending(
        decisionId: String,
        sessionId: String,
        taskId: String,
        toolName: String,
        argsPreview: String
    ): kotlinx.coroutines.CompletableDeferred<Boolean> {
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingDecisions[decisionId] = d
        val now = System.currentTimeMillis()
        conn.prepareStatement("""
            INSERT OR REPLACE INTO decisions(decision_id,session_id,task_id,tool_name,args_preview,status,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?)
        """.trimIndent()).use { ps ->
            ps.setString(1, decisionId); ps.setString(2, sessionId); ps.setString(3, taskId)
            ps.setString(4, toolName); ps.setString(5, argsPreview)
            ps.setString(6, "pending"); ps.setLong(7, now); ps.setLong(8, now)
            ps.executeUpdate()
        }
        return d
    }

    /**
     * 决策完成：把库状态从 pending 改成终态，并唤醒内存 deferred（若在本进程内）。
     * 以数据库状态为准——进程重启后内存 deferred 已丢失，但用户仍须能对恢复出来的审批卡片做决策。
     * 仅当该决策确实处于 pending 时才算成功（重复提交返回 false）。
     */
    @Synchronized
    fun resolvePending(decisionId: String, approve: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val changed = conn.prepareStatement("UPDATE decisions SET status=?, updated_at=? WHERE decision_id=? AND status='pending'").use { ps ->
            ps.setString(1, if (approve) "approved" else "rejected")
            ps.setLong(2, now); ps.setString(3, decisionId)
            ps.executeUpdate() > 0
        }
        val d = pendingDecisions.remove(decisionId)
        if (d != null && !d.isCompleted) d.complete(approve)
        return changed
    }

    /** 该会话仍挂起的审批项（App 断线重连后重建审批卡片用）。 */
    @Synchronized
    fun pendingDecisionsFor(sessionId: String): List<PendingDecisionDto> {
        val out = mutableListOf<PendingDecisionDto>()
        conn.prepareStatement("""
            SELECT decision_id,task_id,tool_name,args_preview,session_id
            FROM decisions WHERE session_id=? AND status='pending' ORDER BY created_at ASC
        """.trimIndent()).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) out += PendingDecisionDto(
                    decisionId = rs.getString("decision_id"),
                    taskId = rs.getString("task_id"),
                    toolName = rs.getString("tool_name"),
                    argsPreview = rs.getString("args_preview"),
                    sessionId = rs.getString("session_id")
                )
            }
        }
        return out
    }

    override fun close() { conn.close() }
}

/** 追加事件用的精简入参。 */
data class NewEvent(
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
    val signature: String = ""
)

/** 任务断点：已积累的对话消息（JSON）+ 迭代轮次。 */
@kotlinx.serialization.Serializable
data class TaskCheckpoint(
    val messagesJson: String = "[]",
    val iteration: Int = 0
)

/** 待续跑任务的精简信息。 */
data class TaskInfo(val taskId: String, val sessionId: String, val providerJson: String)
