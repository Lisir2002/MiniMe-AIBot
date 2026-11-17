package com.minime.agent.server.modules

import com.minime.code.feature.agent.domain.skill.Skill
import com.minime.code.feature.agent.domain.skill.SkillDirectoryScanner

/**
 * 云端 skill 仓库（完全独立，与 App 端各存各的）。
 *
 * 两级目录，与 App 约定一致：
 * - 全局：`~/.minimecode/skills`（容器内 /root/.minimecode/skills）；
 * - 项目：`~/workspace/.minimecode/skills`（容器内 /workspace/.minimecode/skills）。
 *
 * 同名项目级覆盖全局；扫描与解析 100% 复用 :agent-core 的 [SkillDirectoryScanner] / SkillParser。
 */
class ServerSkillRepository(
    private val fileAccess: LocalFileAccessProvider,
    /** 全局技能根目录（容器路径）；生产默认 ~/.minimecode/skills。 */
    private val globalRoot: String = "~/.minimecode/skills",
    /** 项目技能根目录（容器路径）；生产默认 ~/workspace/.minimecode/skills。 */
    private val projectRoot: String = "~/workspace/.minimecode/skills"
) {

    /** 全部技能，项目级覆盖全局，按名称排序。 */
    fun listSkills(): List<Skill> {
        val byName = LinkedHashMap<String, Skill>()
        SkillDirectoryScanner.scan(fileAccess, globalRoot).forEach { byName[it.name.lowercase()] = it }
        SkillDirectoryScanner.scan(fileAccess, projectRoot).forEach { byName[it.name.lowercase()] = it }
        return byName.values.sortedBy { it.name.lowercase() }
    }

    /** 读取指定 skill 的完整指令正文（项目优先）。 */
    fun loadInstructions(name: String): String? =
        listSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions
}
