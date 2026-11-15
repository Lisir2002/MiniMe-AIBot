package com.minime.code.feature.docs.presentation

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.ContextThemeWrapper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.minime.code.R
import com.minime.code.core.theme.Spacing
import com.minime.code.core.theme.semanticColors
import com.minime.code.core.ui.MiniMeIcons
import com.minime.code.core.ui.isExpandedWidth

/**
 * 内置使用手册浏览器。
 *
 * 目录来自 assets/docs/manifest.json，正文为本地 markdown，经 marked.js 离线渲染，无需网络。
 * 文档间的站内链接以 [DocsBrowserViewModel] 的 `appdoc://` scheme 表达，点击时压入导航栈，
 * 顶部返回键逐层回退；外链用系统浏览器打开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsBrowserScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocsBrowserViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val groups = viewModel.groups
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 平板横屏等大屏：左侧目录常驻 + 右侧正文并排；手机窄屏：目录与正文单栏切换。
    val expanded = isExpandedWidth()

    // 导航栈：栈顶为当前文档；窄屏时用 showIndex 在「目录 / 正文」间切换。
    val stack = remember { mutableStateListOf(viewModel.startDocId) }
    var showIndex by remember { mutableStateOf(!expanded) }
    val currentId = stack.last()

    fun titleOf(id: String): String? =
        groups.firstNotNullOfOrNull { g -> g.items.firstOrNull { it.id == id }?.title }

    fun openDoc(id: String) {
        if (id != currentId) stack.add(id)
        showIndex = false
    }

    fun goBack() {
        when {
            // 栈内还有上一层文档：先回上一层
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            // 窄屏正在看正文且没有上一层：回目录
            !expanded && !showIndex -> showIndex = true
            else -> onExit()
        }
    }

    BackHandler { goBack() }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                Text(
                    text = if (expanded || !showIndex) {
                        titleOf(currentId) ?: stringResource(R.string.docs_title)
                    } else {
                        stringResource(R.string.docs_title)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            navigationIcon = {
                IconButton(onClick = { goBack() }) {
                    Icon(MiniMeIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                // 大屏左侧目录常驻，不需要目录按钮
                if (!expanded && !showIndex) {
                    IconButton(onClick = { showIndex = true }) {
                        Icon(MiniMeIcons.Menu, contentDescription = stringResource(R.string.docs_index))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface
            )
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (expanded) {
            // 大屏：左侧目录常驻，右侧正文并排
            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    DocsIndex(
                        groups = groups,
                        onOpen = { openDoc(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DocPage(
                    id = currentId,
                    isDark = isDark,
                    viewModel = viewModel,
                    onOpenDoc = { openDoc(it) },
                    onOpenExternal = { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        } else {
            AnimatedContent(targetState = showIndex, label = "docs-index-content") { indexMode ->
                if (indexMode) {
                    DocsIndex(
                        groups = groups,
                        onOpen = { openDoc(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    DocPage(
                        id = currentId,
                        isDark = isDark,
                        viewModel = viewModel,
                        onOpenDoc = { openDoc(it) },
                        onOpenExternal = { url ->
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun DocsIndex(
    groups: List<DocGroup>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.docs_load_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Spacing.lg, vertical = Spacing.lg
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items(groups, key = { it.title }) { group ->
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = Spacing.sm)
            )
            group.items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpen(item.id) }
                        .padding(horizontal = Spacing.md, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = MiniMeIcons.FileText,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.semanticColors.subtleText
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = MiniMeIcons.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.semanticColors.subtleText
                    )
                }
            }
        }
    }
}

@Composable
private fun DocPage(
    id: String,
    isDark: Boolean,
    viewModel: DocsBrowserViewModel,
    onOpenDoc: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bgColor = if (isDark) Color.parseColor(DocsBrowserViewModel.BG_DARK) else Color.WHITE
    var markdown by remember { mutableStateOf("") }
    LaunchedEffect(id) { markdown = viewModel.loadMarkdown(id) }

    val onOpenDocUpdated = rememberUpdatedState(onOpenDoc)
    val onOpenExternalUpdated = rememberUpdatedState(onOpenExternal)

    val webView = remember {
        val themedContext = ContextThemeWrapper(
            context,
            if (isDark) R.style.Theme_MiniMeCode_WebView_Dark else R.style.Theme_MiniMeCode_WebView
        )
        WebView(themedContext).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            setBackgroundColor(bgColor)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = viewModel.assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    // 页内锚点：交给 WebView 自身滚动
                    if (url.fragment != null && url.path == view.url?.let { Uri.parse(it).path } && !DocsBrowserViewModel.isDocUrl(url)) {
                        return false
                    }
                    viewModel.docIdFromUrl(url)?.let { docId ->
                        onOpenDocUpdated.value(docId)
                        return true
                    }
                    // 其它（外链）交给系统浏览器
                    onOpenExternalUpdated.value(url.toString())
                    return true
                }
            }
        }
    }

    DisposableEffect(webView) { onDispose { webView.destroy() } }

    LaunchedEffect(isDark) {
        (webView.context as? ContextThemeWrapper)?.setTheme(
            if (isDark) R.style.Theme_MiniMeCode_WebView_Dark else R.style.Theme_MiniMeCode_WebView
        )
        webView.setBackgroundColor(bgColor)
    }

    LaunchedEffect(markdown, isDark) {
        if (markdown.isNotEmpty()) {
            val html = viewModel.buildHtml(markdown, isDark)
            webView.loadDataWithBaseURL(
                "https://${DocsBrowserViewModel.ASSET_HOST}/",
                html, "text/html", "utf-8", null
            )
        }
    }

    AndroidView(factory = { webView }, modifier = modifier.fillMaxSize())
}
