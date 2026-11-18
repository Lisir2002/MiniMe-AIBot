package com.minime.agent.server.modules

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 云端设置仓库：把影响 agent 行为的用户偏好持久化到 `~/.minimecode/settings.json`，
 * 与 App 端 GeneralSettingsRepository 的关键项语义对齐，让"设置"随云端会话生效，而非仅停留在本地。
 *
 * 优先级：环境变量覆盖 > settings.json 用户值 > 默认值。
 * 环境变量命名 `MINIME_SET_<KEY>`（如 MINIME_SET_MAX_ITERATIONS），便于容器部署时无需改文件即可调参，
 * 与现有 MINIME_TOKEN / MINIME_WORKSPACE 风格一致。
 *
 * 读写用 synchronized 保护，避免并发 agent loop 与 HTTP 修改互相踩踏；每次 set 即时落盘。
 */
class ServerSettingsRepository(
    private val homePath: String = System.getenv("HOME") ?: "/root"
) {
    @Serializable
    data class Settings(
        /** agent 单任务最大迭代轮数（工具循环），默认 30。 */
        val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        /** goal 自主循环最大续跑轮数，默认 50（对齐 App）。 */
        val goalMaxTurns: Int = 50,
        /** goal 自主循环 token 预算，默认 500000（对齐 App）。 */
        val goalMaxTokens: Int = 500_000,
        /** sendFile 单文件大小上限 MB，默认 100（对齐 App）。 */
        val sendFileMaxSizeMb: Int = 100,
        /** 上下文自动压缩阈值百分比，默认 85（对齐 App）。 */
        val compactionThresholdPercent: Int = 85,
        /** 回复语言提示：非空时注入 system prompt（如 "中文"）。 */
        val language: String = ""
    ) {
        companion object {
            const val DEFAULT_MAX_ITERATIONS = 30
        }
    }

    private val file: File get() = File(homePath, ".minimecode/settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()

    @Volatile
    private var cached: Settings = load()

    private fun load(): Settings {
        val base = runCatching {
            if (file.isFile) json.decodeFromString<Settings>(file.readText()) else Settings()
        }.getOrDefault(Settings())
        return applyEnvOverrides(base)
    }

    /** 环境变量覆盖，仅当变量存在且为合法数值时生效。 */
    private fun applyEnvOverrides(base: Settings): Settings = base.copy(
        maxIterations = envInt("MINIME_SET_MAX_ITERATIONS")?.coerceIn(1, 500) ?: base.maxIterations,
        goalMaxTurns = envInt("MINIME_SET_GOAL_MAX_TURNS")?.coerceAtLeast(1) ?: base.goalMaxTurns,
        goalMaxTokens = envInt("MINIME_SET_GOAL_MAX_TOKENS")?.coerceAtLeast(1000) ?: base.goalMaxTokens,
        sendFileMaxSizeMb = envInt("MINIME_SET_SENDFILE_MAX_SIZE_MB")?.coerceAtLeast(1) ?: base.sendFileMaxSizeMb,
        compactionThresholdPercent = envInt("MINIME_SET_COMPACTION_THRESHOLD_PERCENT")?.coerceIn(1, 100)
            ?: base.compactionThresholdPercent,
        language = System.getenv("MINIME_SET_LANGUAGE")?.takeIf { it.isNotBlank() } ?: base.language
    )

    private fun envInt(name: String): Int? = System.getenv(name)?.trim()?.toIntOrNull()

    fun get(): Settings = synchronized(lock) { cached }

    /** 局部更新并落盘。env 覆盖的字段即使写入文件，读取时仍以 env 为准。 */
    fun update(transform: (Settings) -> Settings): Settings = synchronized(lock) {
        val next = transform(cached)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(next))
        }
        cached = applyEnvOverrides(next)
        cached
    }

    /** 生成注入 system prompt 的语言要求行；未配置语言时返回 null。 */
    fun languageInstruction(): String? = get().language.takeIf { it.isNotBlank() }
        ?.let { "请用${it}回复用户，除非用户明确要求使用其它语言。" }

    companion object {
        const val SETTINGS_FILE = ".minimecode/settings.json"
    }
}
