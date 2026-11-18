package com.minime.agent.server.modules

import com.minime.code.feature.agent.domain.tool.AgentTool
import com.minime.code.feature.agent.domain.tool.ParameterType
import com.minime.code.feature.agent.domain.tool.ToolCapability
import com.minime.code.feature.agent.domain.tool.ToolParameter
import com.minime.code.feature.agent.domain.tool.ToolPermissionPolicy
import com.minime.code.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * firewall：容器出口防火墙管理。enable=收紧（白名单外默认拒绝），disable=开放。
 * 无 NET_ADMIN 能力时管理器返回明确降级提示，不假装生效。
 */
class ServerFirewallTool(private val manager: FirewallManager) : AgentTool() {
    override val name = "firewall"
    override val description = "管理容器的出站防火墙：查看状态、开放/关闭、增删白名单与黑名单。收紧后仅白名单目标可出站。出于安全，SSH/数据库/管理面板等高危端口禁止操作。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_CONTAINER_ENV, ToolCapability.NETWORK_WRITE)
    override val parameters = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING,
            "status / enable / disable / reset / add / remove", true,
            enum = listOf("status", "enable", "disable", "reset", "add", "remove")
        ),
        "list" to ToolParameter("list", ParameterType.STRING, "add/remove 的目标列表：allow 或 deny", false, enum = listOf("allow", "deny")),
        "host" to ToolParameter("host", ParameterType.STRING, "域名/IP/CIDR，空表示不限", false),
        "port" to ToolParameter("port", ParameterType.INTEGER, "端口，0 表示不限；80/8888/22/3304/3306/3389 等高危端口禁止操作", false),
        "protocol" to ToolParameter("protocol", ParameterType.STRING, "tcp/udp/any，默认 tcp", false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "status"
        return when (action) {
            "status" -> ToolResult.Success(stateToJson(manager.status()))
            "enable" -> ToolResult.Success(stateToJson(manager.enable()))
            "disable" -> ToolResult.Success(stateToJson(manager.disable()))
            "reset" -> ToolResult.Success(stateToJson(manager.reset()))
            "add", "remove" -> {
                val list = args["list"]?.jsonPrimitive?.contentOrNull ?: "allow"
                if (list !in setOf("allow", "deny")) {
                    return ToolResult.Error("list 只能是 allow 或 deny", "BAD_LIST")
                }
                val rule = FirewallManager.Rule(
                    host = args["host"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    port = args["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    protocol = args["protocol"]?.jsonPrimitive?.contentOrNull ?: "tcp"
                )
                // 高危端口保护：工具层显式校验并返回结构化错误，管理器层亦有同样防线（双保险）。
                manager.validateRule(rule)?.let { reason ->
                    return ToolResult.Error(reason, "PROTECTED_PORT")
                }
                val state = if (action == "add") manager.addRule(list, rule) else manager.removeRule(list, rule)
                ToolResult.Success(stateToJson(state))
            }
            else -> ToolResult.Error("不支持的 action: $action", "UNKNOWN_ACTION")
        }
    }

    private fun stateToJson(s: FirewallManager.State) = buildJsonObject {
        put("enabled", kotlinx.serialization.json.JsonPrimitive(s.enabled))
        put("kernelApplied", kotlinx.serialization.json.JsonPrimitive(s.kernelApplied))
        put("note", kotlinx.serialization.json.JsonPrimitive(s.note))
        put("allow", buildJsonArray { s.allow.forEach { add(ruleToJson(it)) } })
        put("deny", buildJsonArray { s.deny.forEach { add(ruleToJson(it)) } })
        put("protectedPorts", buildJsonArray {
            FirewallManager.PROTECTED_PORTS.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        })
    }

    private fun ruleToJson(r: FirewallManager.Rule) = buildJsonObject {
        put("host", kotlinx.serialization.json.JsonPrimitive(r.host))
        put("port", kotlinx.serialization.json.JsonPrimitive(r.port))
        put("protocol", kotlinx.serialization.json.JsonPrimitive(r.protocol))
    }
}

/**
 * port：容器冷门端口池管理。分配/归还/查询端口，供容器内服务监听使用。
 * 空闲端口优先复用，进程退出自动归还，分配与防火墙白名单同步。
 */
class ServerPortTool(private val manager: PortPoolManager) : AgentTool() {
    override val name = "port"
    override val description = "管理容器冷门端口池：分配端口供服务监听（空闲端口优先复用）、归还、按 owner 批量归还、查询统计。端口会与防火墙放行同步，进程退出自动归还。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_CONTAINER_ENV, ToolCapability.NETWORK_WRITE)
    override val parameters = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING, "acquire / release / release_owner / bind / list / stats", true,
            enum = listOf("acquire", "release", "release_owner", "bind", "list", "stats")
        ),
        "port" to ToolParameter("port", ParameterType.INTEGER, "目标端口：acquire 时可选指定，release/bind 时必填", false),
        "owner" to ToolParameter("owner", ParameterType.STRING, "端口归属（进程 tab id / 任务名），进程退出按此归还", false),
        "note" to ToolParameter("note", ParameterType.STRING, "端口用途备注", false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
        val owner = args["owner"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val port = args["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        return when (action) {
            "acquire" -> {
                val r = manager.acquire(
                    owner = owner,
                    note = args["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    preferredPort = port ?: 0
                )
                if (r == null) {
                    ToolResult.Error("无可用冷门端口（指定了高危/占用端口或端口池耗尽）", "NO_PORT_AVAILABLE")
                } else ToolResult.Success(buildJsonObject {
                    put("port", kotlinx.serialization.json.JsonPrimitive(r.port))
                    put("owner", kotlinx.serialization.json.JsonPrimitive(r.owner))
                    put("inUse", kotlinx.serialization.json.JsonPrimitive(r.inUse))
                    put("useCount", kotlinx.serialization.json.JsonPrimitive(r.useCount))
                })
            }
            "release" -> {
                if (port == null) return ToolResult.Error("缺少 port", "MISSING_PORT")
                if (!manager.release(port)) ToolResult.Error("端口未被占用或不存在: $port", "PORT_NOT_IN_USE")
                else ToolResult.Success(buildJsonObject {
                    put("released", kotlinx.serialization.json.JsonPrimitive(port))
                })
            }
            "release_owner" -> {
                if (owner.isBlank()) return ToolResult.Error("缺少 owner", "MISSING_OWNER")
                val freed = manager.releaseByOwner(owner)
                ToolResult.Success(buildJsonObject {
                    put("owner", kotlinx.serialization.json.JsonPrimitive(owner))
                    put("released", buildJsonArray { freed.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                })
            }
            "bind" -> {
                if (port == null || owner.isBlank()) return ToolResult.Error("bind 需要 port 与 owner", "MISSING_ARGS")
                if (!manager.bind(port, owner)) ToolResult.Error("端口未被占用或不存在: $port", "PORT_NOT_IN_USE")
                else ToolResult.Success(buildJsonObject {
                    put("port", kotlinx.serialization.json.JsonPrimitive(port))
                    put("owner", kotlinx.serialization.json.JsonPrimitive(owner))
                })
            }
            "stats" -> {
                val s = manager.stats()
                ToolResult.Success(buildJsonObject {
                    s.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                })
            }
            "list" -> {
                val arr = buildJsonArray {
                    manager.list().records.sortedBy { it.port }.forEach { r ->
                        add(buildJsonObject {
                            put("port", kotlinx.serialization.json.JsonPrimitive(r.port))
                            put("inUse", kotlinx.serialization.json.JsonPrimitive(r.inUse))
                            put("owner", kotlinx.serialization.json.JsonPrimitive(r.owner))
                            put("note", kotlinx.serialization.json.JsonPrimitive(r.note))
                            put("useCount", kotlinx.serialization.json.JsonPrimitive(r.useCount))
                            put("acquiredAt", kotlinx.serialization.json.JsonPrimitive(r.acquiredAt))
                            put("releasedAt", kotlinx.serialization.json.JsonPrimitive(r.releasedAt))
                        })
                    }
                }
                ToolResult.Success(buildJsonObject {
                    put("ports", arr)
                    put("excluded", buildJsonArray {
                        manager.excludedPorts().sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                })
            }
            else -> ToolResult.Error("不支持的 action: $action", "UNKNOWN_ACTION")
        }
    }
}

/**
 * process：容器进程生命周期管理。查看进程、手动终止、设置/取消持久化、调整 TTL、查看守护状态。
 * 所有工具启动的后台进程默认带 TTL（编译类 2h、服务类 6h、普通 30min），到期自动回收释放内存；
 * 标记 persistent 的进程永不自动回收。
 */
class ServerProcessTool(private val manager: ProcessLifecycleManager) : AgentTool() {
    override val name = "process"
    override val description = "管理容器进程生命周期：列出后台进程及内存/TTL，手动终止，设置或取消持久化，调整 TTL，查看守护状态。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS, ToolCapability.MODIFY_CONTAINER_ENV)
    override val parameters = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING, "list / kill / persist / release / ttl / status / sweep", true,
            enum = listOf("list", "kill", "persist", "release", "ttl", "status", "sweep")
        ),
        "id" to ToolParameter("id", ParameterType.STRING, "目标进程 id（terminal 标签或进程 id）", false),
        "ttl_minutes" to ToolParameter("ttl_minutes", ParameterType.INTEGER, "ttl 动作的目标存活分钟数", false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()
        return when (action) {
            "list" -> {
                val arr = buildJsonArray {
                    manager.list().forEach { r ->
                        add(buildJsonObject {
                            put("id", kotlinx.serialization.json.JsonPrimitive(r.id))
                            put("pid", kotlinx.serialization.json.JsonPrimitive(r.pid))
                            put("kind", kotlinx.serialization.json.JsonPrimitive(r.kind))
                            put("label", kotlinx.serialization.json.JsonPrimitive(r.label))
                            put("command", kotlinx.serialization.json.JsonPrimitive(r.command.take(200)))
                            put("ttl_minutes", kotlinx.serialization.json.JsonPrimitive(r.ttlMs / 60000))
                            put("idle_minutes", kotlinx.serialization.json.JsonPrimitive((System.currentTimeMillis() - r.lastHeartbeat) / 60000))
                            put("persistent", kotlinx.serialization.json.JsonPrimitive(r.persistent))
                            put("alive", kotlinx.serialization.json.JsonPrimitive(r.process?.isAlive ?: true))
                        })
                    }
                }
                ToolResult.Success(buildJsonObject {
                    put("count", kotlinx.serialization.json.JsonPrimitive(arr.size))
                    put("processes", arr)
                })
            }
            "kill" -> {
                if (id == null) return ToolResult.Error("缺少 id", "MISSING_ID")
                when (val outcome = manager.kill(id)) {
                    is ProcessLifecycleManager.KillOutcome.Killed -> ToolResult.Success(buildJsonObject {
                        put("killed", kotlinx.serialization.json.JsonPrimitive(true))
                        put("freed_kb", kotlinx.serialization.json.JsonPrimitive(outcome.freedKb))
                    })
                    is ProcessLifecycleManager.KillOutcome.AlreadyExited -> ToolResult.Success(buildJsonObject {
                        put("killed", kotlinx.serialization.json.JsonPrimitive(false))
                        put("note", kotlinx.serialization.json.JsonPrimitive("进程已退出"))
                    })
                    is ProcessLifecycleManager.KillOutcome.NotFound -> ToolResult.Error("进程不存在: $id", "NOT_FOUND")
                }
            }
            "persist", "release" -> {
                if (id == null) return ToolResult.Error("缺少 id", "MISSING_ID")
                val ok = manager.setPersistent(id, action == "persist")
                if (!ok) ToolResult.Error("进程不存在: $id", "NOT_FOUND")
                else ToolResult.Success(buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(id))
                    put("persistent", kotlinx.serialization.json.JsonPrimitive(action == "persist"))
                })
            }
            "ttl" -> {
                if (id == null) return ToolResult.Error("缺少 id", "MISSING_ID")
                val minutes = args["ttl_minutes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: return ToolResult.Error("缺少 ttl_minutes", "MISSING_TTL")
                val ok = manager.setTtl(id, minutes * 60_000L)
                if (!ok) ToolResult.Error("进程不存在: $id", "NOT_FOUND")
                else ToolResult.Success(buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(id))
                    put("ttl_minutes", kotlinx.serialization.json.JsonPrimitive(minutes))
                })
            }
            "status", "sweep" -> {
                val reaped = if (action == "sweep") manager.sweep() else emptyList()
                ToolResult.Success(buildJsonObject {
                    put("managed", kotlinx.serialization.json.JsonPrimitive(manager.list().size))
                    put("reaped", kotlinx.serialization.json.JsonPrimitive(reaped.size))
                    put("details", buildJsonArray { reaped.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                })
            }
            else -> ToolResult.Error("不支持的 action: $action", "UNKNOWN_ACTION")
        }
    }
}
