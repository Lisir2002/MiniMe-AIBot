package com.minime.agent.server

/**
 * agent-server 版本信息。
 *
 * 版本号在构建期由 GITHUB_REF_NAME（发布 tag）写入 agent-server-version.properties；
 * 本地开发未注入时为 "dev"。App 通过 /version 读取，用于「当前/期望版本」对比与升级提示。
 */
object ServerVersion {
    /** 服务端协议版本：事件/决策 API 的形状版本。API 有破坏性变更时 +1，供 App 判断兼容性。 */
    const val PROTOCOL = 1

    val version: String by lazy {
        runCatching {
            ServerVersion::class.java.classLoader
                .getResourceAsStream("agent-server-version.properties")
                ?.use { stream ->
                    val props = java.util.Properties()
                    props.load(stream)
                    props.getProperty("version")?.takeIf { it.isNotBlank() }
                }
        }.getOrNull() ?: "dev"
    }
}
