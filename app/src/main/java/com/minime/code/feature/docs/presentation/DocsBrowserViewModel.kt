package com.minime.code.feature.docs.presentation

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.lifecycle.ViewModel
import androidx.webkit.WebViewAssetLoader
import com.google.gson.JsonParser
import com.minime.code.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.inject.Inject

/** 文档目录里的一个条目。 */
data class DocItem(val title: String, val id: String)

/** 文档目录里的一个分组。 */
data class DocGroup(val title: String, val items: List<DocItem>)

/**
 * 内置使用手册的后端。
 *
 * 文档以 markdown 存放在 `assets/docs/`，[manifest] 描述目录结构（分组 → 条目 id）。
 * 渲染复用编辑器预览页的 `markdown/preview.html` 模板与 marked.js/DOMPurify/highlight.js，
 * 通过 [assetLoader] 从 assets 提供这些 JS/CSS，全程离线，不依赖网络。
 *
 * markdown 里的站内链接在生成资源时已被重写为 `appdoc://<id>`，[docIdFromUrl] 负责还原，
 * 供 WebView 拦截后在本浏览器内切换文档；外链则交给系统浏览器。
 */
@HiltViewModel
class DocsBrowserViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        private const val ASSET_BASE = "https://$ASSET_HOST/assets/markdown/"
        private const val DOCS_DIR = "docs/"
        private const val SCHEME = "appdoc"
        private const val DEFAULT_DOC = "index"

        const val BG_DARK = "#0d1117"
        const val BG_LIGHT = "#ffffff"

        /** 判断 URL 是否为本浏览器内的文档跳转链接。 */
        fun isDocUrl(url: Uri): Boolean = url.scheme == SCHEME
    }

    private val template: String by lazy {
        context.assets.open("markdown/preview.html").bufferedReader().use { it.readText() }
    }

    /** 目录：分组 + 每个分组的条目；失败时给出空目录，不崩溃。 */
    val groups: List<DocGroup> by lazy { loadManifest() }

    /** 打开的文档 id，默认首页。 */
    val startDocId: String = DEFAULT_DOC

    /** 供 WebView 加载渲染所需 JS/CSS 的资产加载器。 */
    val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    /**
     * 从 `appdoc://<id>` 里取出文档 id；非文档链接返回 null。
     *
     * id 形如 `guide/quick-start`，在 URL 里被拆成 host=`guide`、path=`/quick-start`，
     * 需要拼回来；`index` 这类单段 id 则只有 host。锚点（fragment）不参与 id。
     */
    fun docIdFromUrl(url: Uri): String? {
        if (url.scheme != SCHEME) return null
        val id = (url.host.orEmpty() + url.path.orEmpty()).trim('/')
        return id.ifBlank { null }
    }

    /** 读取指定文档的 markdown 原文；不存在时返回带提示的降级文本。 */
    suspend fun loadMarkdown(id: String): String = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("$DOCS_DIR$id.md").bufferedReader().use { it.readText() }
        }.getOrDefault(
            context.getString(R.string.docs_load_failed)
        )
    }

    /** 把 markdown 注入预览模板生成 HTML；[dark] 决定明/暗主题。 */
    suspend fun buildHtml(markdown: String, dark: Boolean): String = withContext(Dispatchers.IO) {
        val b64 = Base64.encodeToString(markdown.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        template
            .replace("__ASSET_BASE__", ASSET_BASE)
            .replace("__MD_CSS__", if (dark) "github-markdown-dark.css" else "github-markdown-light.css")
            .replace("__HL_CSS__", if (dark) "highlight-github-dark.min.css" else "highlight-github.min.css")
            .replace("__BG__", if (dark) BG_DARK else BG_LIGHT)
            .replace("__MD_B64__", b64)
    }

    private fun loadManifest(): List<DocGroup> = runCatching {
        val text = context.assets.open("${DOCS_DIR}manifest.json").bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(text).asJsonObject
        root.getAsJsonArray("groups").map { el ->
            val g = el.asJsonObject
            val items = g.getAsJsonArray("items").map { item ->
                val o = item.asJsonObject
                DocItem(
                    title = o.get("title")?.asString.orEmpty(),
                    id = o.get("id")?.asString.orEmpty()
                )
            }
            DocGroup(title = g.get("title")?.asString.orEmpty(), items = items)
        }
    }.getOrDefault(emptyList())
}
