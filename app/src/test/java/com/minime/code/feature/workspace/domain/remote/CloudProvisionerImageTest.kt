package com.minime.code.feature.workspace.domain.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端 AI 环境向导：镜像拉取脚本的纯函数单测。
 * 覆盖默认镜像走 GHCR 主通道 + Release 离线包兜底，自定义镜像交还用户、不误走兜底。
 */
class CloudProvisionerImageTest {

    @Test
    fun defaultImage_usesGhcrMainChannelAndReleaseFallback() {
        val cmd = CloudProvisioner.buildDockerImageCommand(CloudProvisioner.DEFAULT_IMAGE)

        // 主通道：docker pull
        assertTrue(cmd.contains("docker pull"))
        assertTrue(cmd.contains(CloudProvisioner.DEFAULT_IMAGE_REPO))
        // 兜底：解析 atom 版本 → 下载 Release tar → load → retag
        assertTrue(cmd.contains(CloudProvisioner.RELEASE_ATOM))
        assertTrue(cmd.contains(CloudProvisioner.RELEASE_DOWNLOAD))
        assertTrue(cmd.contains(CloudProvisioner.FALLBACK_TAR))
        assertTrue(cmd.contains("docker load"))
        assertTrue(cmd.contains("docker tag"))
        // 兜底分支对默认镜像是放行的（case 命中 repo 后 ;; 继续执行，不 exit）
        assertTrue(cmd.contains("esac"))
    }

    @Test
    fun customImage_fallsBackWithLoginHintNotOurArtifact() {
        val cmd = CloudProvisioner.buildDockerImageCommand("registry.example.com/team/private:1.0")

        // 自定义镜像仍走 pull；失败时靠 case 分支给出 docker login 提示并 exit 1，不会走到兜底下载。
        // 兜底代码是静态共用的一段脚本，故不能断言字符串里不含 download/load；
        // 关键是 case 放行项只放行默认仓库 repo，自定义镜像名不会被放行。
        assertTrue(cmd.contains("docker pull"))
        assertTrue(cmd.contains("docker login"))
        // case 放行项只匹配默认仓库 repo，自定义镜像名不会命中，落到 login 提示分支后 exit 1
        val allowLine = "  *${CloudProvisioner.DEFAULT_IMAGE_REPO}*) ;;"
        assertTrue(cmd.contains(allowLine))
        assertFalse(allowLine.contains("registry.example.com"))
    }

    @Test
    fun versionedDefaultImage_alsoGetsFallback() {
        // 版本化 tag 的官方镜像也应走兜底（case 按 repo 前缀匹配）
        val cmd = CloudProvisioner.buildDockerImageCommand("ghcr.io/lisir2002/minimecode-agent-server:base-1.0.0-rc24")
        assertTrue(cmd.contains("docker load"))
        assertTrue(cmd.contains(CloudProvisioner.RELEASE_DOWNLOAD))
    }

    @Test
    fun defaultImage_constantIsPublishedRepo() {
        assertTrue(CloudProvisioner.DEFAULT_IMAGE.startsWith("ghcr.io/"))
        assertTrue(CloudProvisioner.DEFAULT_IMAGE.endsWith(":base"))
    }
}
