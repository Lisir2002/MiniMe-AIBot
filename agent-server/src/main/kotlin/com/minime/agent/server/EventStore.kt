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
                    created_at INTEGER NOT NULL
                )
            """.trimIndent())
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

    fun registerPending(decisionId: String): kotlinx.coroutines.CompletableDeferred<Boolean> {
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingDecisions[decisionId] = d
        return d
    }

    fun resolvePending(decisionId: String, approve: Boolean): Boolean {
        val d = pendingDecisions.remove(decisionId) ?: return false
        if (!d.isCompleted) d.complete(approve)
        return true
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
