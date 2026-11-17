package com.minime.agent.server.modules

import com.minime.code.feature.agent.domain.tool.AgentTool
import com.minime.code.feature.agent.domain.tool.ParameterType
import com.minime.code.feature.agent.domain.tool.ToolCapability
import com.minime.code.feature.agent.domain.tool.ToolParameter
import com.minime.code.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 服务端 loadSkill 工具：AI 判断系统提示「可用技能」清单中某个技能适用时，调用它加载 SKILL.md 正文。
 * 与 App 端 LoadSkillTool 参数/命名一致，数据来自服务端的 [ServerSkillRepository]（完全独立）。
 */
class ServerLoadSkillTool(
    private val skillRepository: ServerSkillRepository
) : AgentTool() {
    override val name = "loadSkill"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "加载指定技能的完整指令正文。当系统提示「可用技能」清单中的技能适用于当前任务时使用。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "skill_name" to ToolParameter(
            name = "skill_name",
            type = ParameterType.STRING,
            description = "要加载的技能名称（与系统提示「可用技能」清单中的名称一致）。",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val skillName = args["skill_name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (skillName.isNullOrEmpty()) {
            return ToolResult.Error("缺少必需参数：skill_name", "MISSING_SKILL_NAME")
        }
        val instructions = skillRepository.loadInstructions(skillName)
            ?: return ToolResult.Error(
                "未找到技能「$skillName」。可用技能：${
                    skillRepository.listSkills().joinToString(", ") { it.name }.ifEmpty { "（无）" }
                }",
                "SKILL_NOT_FOUND"
            )
        return ToolResult.Success(JsonPrimitive(instructions))
    }
}
