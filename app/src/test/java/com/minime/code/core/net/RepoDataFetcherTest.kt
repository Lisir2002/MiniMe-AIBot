package com.minime.code.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定运行时数据（供应商预设 / 模型元数据 / 容器镜像目录）的拉取源归属。
 * 历史遗留常量 jieapi/minimecode 在 GitHub 上不存在，曾导致三处网络拉取恒失败；
 * 本测试防止品牌重构的残留回归。
 */
class RepoDataFetcherTest {

    @Test
    fun defaultRepoPointsToOurProject() {
        assertEquals("Lisir2002", RepoDataFetcher.DEFAULT_OWNER)
        assertEquals("MiniMe-AIBot", RepoDataFetcher.DEFAULT_REPO)
    }

    @Test
    fun candidateUrlsUseOwnerRepoBranchAndFallbackToRaw() {
        val urls = RepoDataFetcher.candidateUrls("Lisir2002", "MiniMe-AIBot", "data", "data/models.json")
        assertEquals(4, urls.size)
        assertTrue(urls[0].startsWith("https://fastly.jsdelivr.net/gh/Lisir2002/MiniMe-AIBot@data/"))
        assertTrue(urls[1].startsWith("https://cdn.jsdelivr.net/gh/Lisir2002/MiniMe-AIBot@data/"))
        assertTrue(urls[2].startsWith("https://gcore.jsdelivr.net/gh/Lisir2002/MiniMe-AIBot@data/"))
        assertEquals(
            "https://raw.githubusercontent.com/Lisir2002/MiniMe-AIBot/data/data/models.json",
            urls[3]
        )
    }

    @Test
    fun allThreeRuntimeDataSourcesResolveToOurRepo() {
        // 容器目录与模型元数据走 data 分支，供应商预设走默认 main 分支
        val container = RepoDataFetcher.candidateUrls(
            RepoDataFetcher.DEFAULT_OWNER, RepoDataFetcher.DEFAULT_REPO,
            "data", "data/container-images.json"
        )
        val models = RepoDataFetcher.candidateUrls(
            RepoDataFetcher.DEFAULT_OWNER, RepoDataFetcher.DEFAULT_REPO,
            "data", "data/models.json"
        )
        val providers = RepoDataFetcher.candidateUrls(
            RepoDataFetcher.DEFAULT_OWNER, RepoDataFetcher.DEFAULT_REPO,
            RepoDataFetcher.DEFAULT_BRANCH, "data/providers.json"
        )
        val rawUrl = { list: List<String> -> list.last() }
        assertEquals(
            "https://raw.githubusercontent.com/Lisir2002/MiniMe-AIBot/data/data/container-images.json",
            rawUrl(container)
        )
        assertEquals(
            "https://raw.githubusercontent.com/Lisir2002/MiniMe-AIBot/data/data/models.json",
            rawUrl(models)
        )
        assertEquals(
            "https://raw.githubusercontent.com/Lisir2002/MiniMe-AIBot/main/data/providers.json",
            rawUrl(providers)
        )
    }
}
