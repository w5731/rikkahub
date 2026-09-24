package me.rerere.rikkahub.ui.components.richtext

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.MarkdownRemoteImageRepository
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.isRemoteHttpUrl
import org.koin.compose.koinInject

/**
 * 远端 Markdown 图片在气泡里的三态：
 * - [Saved]   本地有 PNG 副本 → 显示本地图（也叫「正常」状态）；
 * - [Loading] 正在请求 URL（流式输出最后一条新消息出现的瞬间，或用户在全屏点了刷新）→ 显示
 *             rikkahub 原生占位图 [R.drawable.placeholder]，加载完成自动落盘并切到 [Saved]；
 * - [Missing] 本地没有副本、且不允许自动联网（历史会话、流式结束等）→ 显示 rikkahub 原生占位图。
 *             **除非用户在全屏点刷新，绝不会自动转为 [Loading]。**
 */
private enum class RemoteImageState { Saved, Loading, Missing }

@Composable
fun MarkdownRemoteImage(
    src: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    missingLabel: String? = null,
    missingDetail: String? = null,
    allowNetwork: Boolean = true,
) {
    if (!src.isRemoteHttpUrl()) {
        ZoomableAsyncImage(
            model = src,
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            alpha = alpha,
        )
        return
    }

    val repo = koinInject<MarkdownRemoteImageRepository>()
    val context = LocalContext.current
    val imageLoader = remember(context) { context.imageLoader }
    val scope = rememberCoroutineScope()
    val sessionUrls = LocalSessionMarkdownRemoteImageUrls.current
    val export = LocalExportContext.current
    val allowAutoFetch = LocalMarkdownRemoteImageAutoFetch.current
    val chatImageNavigation = LocalChatImageNavigation.current
    val requestSpecResolver = LocalMarkdownRemoteImageRequestSpecResolver.current

    var localUri by remember(src) { mutableStateOf<Uri?>(null) }
    var localVersion by remember(src) { mutableStateOf(0L) }
    var cacheInvalidate by remember(src) { mutableStateOf(0) }
    var remoteLoadFailed by remember(src) { mutableStateOf(false) }
    var bubbleRefreshing by remember(src) { mutableStateOf(false) }
    var loadingRequestUrl by remember(src) { mutableStateOf<String?>(null) }
    var persistRequestUrl by remember(src) { mutableStateOf<String?>(null) }

    val updatedSrc by rememberUpdatedState(src)

    LaunchedEffect(src) {
        repo.imageUpdates.collect { updatedUrl ->
            if (updatedUrl != src) return@collect
            val row = repo.getBySourceUrl(src) ?: return@collect
            val file = repo.resolveAbsoluteFile(row)
            if (file.isFile) {
                localUri = file.toUri()
                localVersion = row.createdAt
                remoteLoadFailed = false
                cacheInvalidate++
            }
        }
    }

    LaunchedEffect(src, cacheInvalidate) {
        // 重要：刷新成功后会触发 cacheInvalidate++，此时已存在的 localUri 不能在 DB 重查完成前被
        // 提前置空，否则会有几十毫秒的「破碎/占位」闪烁。先并行重置标志位、保留旧 localUri，DB 返回后再原子替换。
        // 仅在没有进行中的网络请求/落盘任务时才重置错误状态。
        if (loadingRequestUrl == null && persistRequestUrl == null) {
            remoteLoadFailed = false
        }
        val row = repo.getBySourceUrl(src)
        localUri = if (row != null) {
            val file = repo.resolveAbsoluteFile(row)
            if (file.isFile) {
                localVersion = row.createdAt
                file.toUri()
            } else {
                localVersion = 0L
                null
            }
        } else {
            localVersion = 0L
            null
        }
    }

    val state: RemoteImageState = when {
        bubbleRefreshing -> RemoteImageState.Loading
        loadingRequestUrl == src -> RemoteImageState.Loading
        persistRequestUrl == src -> RemoteImageState.Loading
        localUri != null -> RemoteImageState.Saved
        allowNetwork && allowAutoFetch && !remoteLoadFailed -> RemoteImageState.Loading
        else -> RemoteImageState.Missing
    }

    var previewSnapshot by remember(src) { mutableStateOf<ImagePreviewSnapshot?>(null) }
    val openPreview = {
        // 当前图片立即打开；会话兄弟图在后台补齐，避免点击后等待数据库与磁盘批量查询。
        previewSnapshot = buildPreviewSnapshot(
            src = src,
            localUri = localUri,
            isMissing = state == RemoteImageState.Missing,
            sessionUrls = emptyList(),
            sessionPreviewModels = null,
        )
        if (localUri != null && sessionUrls.size > 1) {
            scope.launch(Dispatchers.IO) {
                val freshPreviewModels = repo.previewModelsForUrls(sessionUrls)
                val expanded = buildPreviewSnapshot(
                    src = src,
                    localUri = localUri,
                    isMissing = false,
                    sessionUrls = sessionUrls,
                    sessionPreviewModels = freshPreviewModels,
                )
                withContext(Dispatchers.Main) {
                    if (previewSnapshot != null) previewSnapshot = expanded
                }
            }
        }
    }

    Box(modifier = modifier.clickable { openPreview.invoke() }) {
        when (state) {
            RemoteImageState.Saved -> {
                val savedRequest = remember(localUri, localVersion, cacheInvalidate, export) {
                    ImageRequest.Builder(context)
                        .data(localUri)
                        .crossfade(false)
                        .allowHardware(!export)
                        .memoryCacheKey("${localUri}_${localVersion}_$cacheInvalidate")
                        .diskCacheKey("${localUri}_${localVersion}_$cacheInvalidate")
                        .build()
                }
                AsyncImage(
                    model = savedRequest,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    alignment = alignment,
                    alpha = alpha,
                )
            }

            RemoteImageState.Loading -> {
                if (bubbleRefreshing) {
                    RemoteImagePlaceholder(
                        contentDescription = stringResource(R.string.markdown_remote_image_loading_cd),
                        modifier = Modifier
                            .fillMaxSize()
                            .shimmer(isLoading = true),
                        contentScale = contentScale,
                        alignment = alignment,
                        alpha = alpha,
                    )
                    return@Box
                }
                LaunchedEffect(src) {
                    if (loadingRequestUrl == null && src.isRemoteHttpUrl()) {
                        loadingRequestUrl = src
                    }
                }
                // 网络请求使用 resolver 给出的 URL（艾罗拉会附加 token），缓存身份仍是 src
                val requestUrl = requestSpecResolver(src).requestUrl
                val loadingRequest = remember(src, requestUrl, cacheInvalidate, export) {
                    ImageRequest.Builder(context)
                        .data(requestUrl)
                        .crossfade(false)
                        .allowHardware(!export)
                        .apply {
                            if (cacheInvalidate != 0) {
                                memoryCacheKey("${src}_$cacheInvalidate")
                                diskCacheKey("${src}_$cacheInvalidate")
                            }
                        }
                        .build()
                }
                SubcomposeAsyncImage(
                    model = loadingRequest,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    alignment = alignment,
                    alpha = alpha,
                    loading = {
                        val placeholder = if (LocalDarkMode.current) {
                            R.drawable.placeholder_dark
                        } else {
                            R.drawable.placeholder
                        }
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(placeholder),
                                contentDescription = stringResource(R.string.markdown_remote_image_loading_cd),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .shimmer(isLoading = true),
                                contentScale = contentScale,
                                alignment = alignment,
                                alpha = alpha,
                            )
                        }
                    },
                    error = {
                        val placeholder = if (LocalDarkMode.current) {
                            R.drawable.placeholder_dark
                        } else {
                            R.drawable.placeholder
                        }
                        Image(
                            painter = painterResource(placeholder),
                            contentDescription = stringResource(R.string.markdown_remote_image_broken_cd),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = contentScale,
                            alignment = alignment,
                            alpha = alpha,
                        )
                    },
                    onSuccess = { successState: AsyncImagePainter.State.Success ->
                        loadingRequestUrl = null
                        // 只要本地还没有副本且当前没有落盘任务就尝试持久化。
                        if (localUri == null && persistRequestUrl == null &&
                            updatedSrc.isRemoteHttpUrl()
                        ) {
                            persistRequestUrl = updatedSrc
                            scope.launch(Dispatchers.IO) {
                                val persistedUri = runCatching {
                                    val drawable = successState.result.image
                                        .asDrawable(context.resources)
                                    val bitmap = drawable.toBitmap()
                                    repo.persistDecodedBitmap(updatedSrc, bitmap)
                                }.getOrNull()
                                withContext(Dispatchers.Main) {
                                    if (persistedUri != null && persistRequestUrl == updatedSrc) {
                                        localUri = persistedUri
                                        remoteLoadFailed = false
                                    } else if (persistRequestUrl == updatedSrc) {
                                        remoteLoadFailed = true
                                    }
                                    if (persistRequestUrl == updatedSrc) {
                                        persistRequestUrl = null
                                    }
                                }
                            }
                        }
                    },
                    onError = {
                        if (localUri == null) {
                            remoteLoadFailed = true
                            loadingRequestUrl = null
                        }
                    },
                )
            }

            RemoteImageState.Missing -> {
                if (missingLabel != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = missingLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        missingDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    val placeholder = if (LocalDarkMode.current) {
                        R.drawable.placeholder_dark
                    } else {
                        R.drawable.placeholder
                    }
                    Image(
                        painter = painterResource(placeholder),
                        contentDescription = stringResource(R.string.markdown_remote_image_broken_cd),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = contentScale,
                        alignment = alignment,
                        alpha = alpha,
                    )
                }
            }
        }
    }

    val snapshot = previewSnapshot
    if (snapshot != null) {
        ImagePreviewDialog(
            images = snapshot.images,
            sourceUrls = snapshot.sourceUrls,
            initialPage = snapshot.initialPage,
            missingIndices = snapshot.missingIndices,
            showRefreshButton = allowNetwork,
            chatImageNavigation = chatImageNavigation,
            refreshingSourceUrls = if (bubbleRefreshing) setOf(updatedSrc) else emptySet(),
            onRefreshConfirmed = { page ->
                val url = snapshot.sourceUrls.getOrNull(page) ?: updatedSrc
                val spec = requestSpecResolver(url)
                bubbleRefreshing = true
                try {
                    val refreshedUri = withContext(Dispatchers.IO) {
                        repo.refreshFromNetwork(
                            imageLoader = imageLoader,
                            sourceUrl = url,
                            requestUrl = spec.requestUrl,
                            appendNocache = spec.nocacheOnRefresh,
                            timeoutMs = spec.timeoutMs,
                        )
                    }
                    if (url == updatedSrc) {
                        localUri = refreshedUri
                        localVersion = System.currentTimeMillis()
                        remoteLoadFailed = false
                        cacheInvalidate++
                    }
                    refreshedUri.toString()
                } finally {
                    bubbleRefreshing = false
                }
            },
            onDismissRequest = { previewSnapshot = null },
        )
    }
}

@Composable
private fun RemoteImagePlaceholder(
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    alignment: Alignment,
    alpha: Float,
) {
    val placeholder = if (LocalDarkMode.current) {
        R.drawable.placeholder_dark
    } else {
        R.drawable.placeholder
    }
    Image(
        painter = painterResource(placeholder),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        alpha = alpha,
    )
}

private data class ImagePreviewSnapshot(
    val images: List<String>,
    val sourceUrls: List<String>,
    val initialPage: Int,
    val missingIndices: Set<Int>,
)

/**
 * 点击气泡时根据当前状态构造预览快照：
 * - 当前为 [RemoteImageState.Saved]：左右滑动会贯穿同会话内**所有**已保存的兄弟图（按 sessionUrls 顺序），
 *   并把当前气泡的 [localUri] 强制覆盖到自己槽位（避免顶层缓存陈旧导致黑屏）。
 * - 当前为 [RemoteImageState.Loading]：单页预览，使用 http URL，Coil 加载时显示原生 placeholder。
 * - 当前为 [RemoteImageState.Missing]：单页预览，**不发起网络请求**，直接由 dialog 渲染破碎图标，
 *   用户可点刷新进入请求。
 */
private fun buildPreviewSnapshot(
    src: String,
    localUri: Uri?,
    isMissing: Boolean,
    sessionUrls: List<String>,
    sessionPreviewModels: List<String>?,
): ImagePreviewSnapshot {
    val ownLocal = localUri?.toString()
    if (ownLocal != null) {
        if (sessionUrls.isNotEmpty() &&
            sessionPreviewModels != null &&
            sessionPreviewModels.size == sessionUrls.size
        ) {
            val savedFiles = mutableListOf<String>()
            val savedSourceUrls = mutableListOf<String>()
            for (i in sessionUrls.indices) {
                val m = sessionPreviewModels[i]
                val effective = if (sessionUrls[i] == src) ownLocal else m
                if (!effective.isRemoteHttpUrl()) {
                    savedFiles.add(effective)
                    savedSourceUrls.add(sessionUrls[i])
                }
            }
            if (savedFiles.isNotEmpty()) {
                val pos = savedSourceUrls.indexOf(src).coerceAtLeast(0)
                return ImagePreviewSnapshot(
                    images = savedFiles,
                    sourceUrls = savedSourceUrls,
                    initialPage = pos.coerceIn(0, savedFiles.lastIndex),
                    missingIndices = emptySet(),
                )
            }
        }
        return ImagePreviewSnapshot(
            images = listOf(ownLocal),
            sourceUrls = listOf(src),
            initialPage = 0,
            missingIndices = emptySet(),
        )
    }

    return if (isMissing) {
        ImagePreviewSnapshot(
            images = listOf(""),
            sourceUrls = listOf(src),
            initialPage = 0,
            missingIndices = setOf(0),
        )
    } else {
        ImagePreviewSnapshot(
            images = listOf(src),
            sourceUrls = listOf(src),
            initialPage = 0,
            missingIndices = emptySet(),
        )
    }
}
