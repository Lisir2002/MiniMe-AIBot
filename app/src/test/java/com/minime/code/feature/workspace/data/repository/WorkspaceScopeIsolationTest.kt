package com.minime.code.feature.workspace.data.repository

import com.minime.code.feature.agent.domain.container.ContainerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区「按容器作用域隔离」的纯逻辑单测。
 *
 * 对应真机关系模型：容器 → 工作区 → 工作目录(1:1) → 对话。
 * 本地每个容器拥有独立的工作区物理目录与选中态 key，切容器即整组替换；
 * 内置 Alpine 保持旧路径/旧 key，保证存量用户升级不丢工作区与会话定位。
 */
class WorkspaceScopeIsolationTest {

    @Test
    fun builtin_keeps_legacy_paths_and_key() {
        // 内置容器必须用旧目录名与空后缀 key，存量 workspacePath 才不会失效。
        assertEquals("projects", WorkspaceRepository.projectsDirNameFor(ContainerProfile.BUILTIN_ID))
        assertEquals("", WorkspaceRepository.currentNameKeySuffixFor(ContainerProfile.BUILTIN_ID))
        // 空白 id 也回退内置路径（防御）。
        assertEquals("projects", WorkspaceRepository.projectsDirNameFor(""))
        assertEquals("", WorkspaceRepository.currentNameKeySuffixFor(""))
    }

    @Test
    fun custom_containers_get_separate_dirs_and_keys() {
        val dirA = WorkspaceRepository.projectsDirNameFor("profile_ubuntu")
        val dirB = WorkspaceRepository.projectsDirNameFor("profile_debian")
        assertEquals("projects_local_profile_ubuntu", dirA)
        assertEquals("projects_local_profile_debian", dirB)
        assertNotEquals(dirA, dirB)

        // 选中态 key 同样按容器分开，切容器不会串改对方记住的工作区。
        assertEquals("_local_profile_ubuntu", WorkspaceRepository.currentNameKeySuffixFor("profile_ubuntu"))
        assertEquals("_local_profile_debian", WorkspaceRepository.currentNameKeySuffixFor("profile_debian"))
    }

    @Test
    fun custom_dir_sanitizes_unsafe_chars() {
        // profileId 中的路径分隔符/空格等危险字符被剔除，避免目录穿越；安全字符保留。
        val dir = WorkspaceRepository.projectsDirNameFor("abc/../x y:z-1_2")
        assertEquals("projects_local_abcxyz-1_2", dir)
        assertTrue(dir.startsWith("projects_local_"))
        assertTrue(dir.none { it == '/' || it == ' ' || it == ':' || it == '.' })
    }

    @Test
    fun builtin_never_collides_with_custom() {
        // 内置目录名不能与任何自定义目录名相同，否则存量工作区会被自定义容器看到。
        assertNotEquals(
            WorkspaceRepository.projectsDirNameFor(ContainerProfile.BUILTIN_ID),
            WorkspaceRepository.projectsDirNameFor("some_custom")
        )
    }
}
